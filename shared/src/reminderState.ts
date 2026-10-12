import { nextOccurrence, type ReminderRecord } from './schedule';

export type OccurrenceAction = 'done' | 'dismiss' | 'snooze';

export function reminderSection(reminder: ReminderRecord): 'upcoming' | 'history' {
  return reminder.status === 'active' && reminder.nextDueAt != null ? 'upcoming' : 'history';
}

export function hasCurrentAlert(reminder: ReminderRecord): boolean {
  return reminder.lastSentAt != null && reminder.lastScheduledAt != null &&
    reminder.lastAcknowledgedScheduledAt !== reminder.lastScheduledAt;
}

export function canPersistDelivery(reminder: ReminderRecord | null, claimedVersion: number, leaseUntil: number): reminder is ReminderRecord {
  return reminder?.status === 'active' && reminder.version === claimedVersion && reminder.leaseUntil === leaseUntil;
}

export function reminderGroup(reminder: ReminderRecord, now: number): {
  alert: { occurrenceId: string; scheduledAt: number; sentAt: number; nextNudgeAt: number | null } | null;
  future: number[];
  retryAt: number | null;
} {
  const alert = hasCurrentAlert(reminder) ? {
    occurrenceId: `${reminder.id}_${reminder.lastScheduledAt!}`,
    scheduledAt: reminder.lastScheduledAt!, sentAt: reminder.lastSentAt!,
    nextNudgeAt: reminder.currentScheduledAt === reminder.lastScheduledAt ? reminder.nextDueAt : null,
  } : null;
  const retryAt = !alert && reminder.currentScheduledAt != null && reminder.nextDueAt != null ? reminder.nextDueAt : null;
  const future: number[] = [];
  if (reminder.nextDueAt != null && !retryAt && !alert?.nextNudgeAt) future.push(reminder.nextDueAt);
  let cursor = future[0] ?? Math.max(now - 1, (alert?.scheduledAt ?? 0));
  while (future.length < 3) {
    const next = nextOccurrence(reminder.rule, cursor);
    if (next == null) break;
    future.push(next);
    cursor = next;
  }
  return { alert, future, retryAt };
}

export function currentAlertId(reminder: ReminderRecord, now: number): string | undefined {
  if (!hasCurrentAlert(reminder)) return undefined;
  const scheduledAt = reminder.currentScheduledAt ??
    (reminder.lastSentAt != null && reminder.lastScheduledAt != null &&
      now >= reminder.lastSentAt && now - reminder.lastSentAt < 24 * 60 * 60_000
      ? reminder.lastScheduledAt : null);
  return scheduledAt == null ? undefined : `${reminder.id}_${scheduledAt}`;
}

export function applyReminderAction(
  reminder: ReminderRecord,
  action: OccurrenceAction,
  now: number,
  snoozeMinutes: number | null,
  occurrenceId?: string,
): Partial<ReminderRecord> {
  const occurrenceAt = occurrenceId?.startsWith(`${reminder.id}_`)
    ? Number(occurrenceId.slice(reminder.id.length + 1)) : null;
  if (occurrenceId && (!Number.isFinite(occurrenceAt) ||
      (occurrenceAt !== reminder.lastScheduledAt && occurrenceAt !== reminder.currentScheduledAt))) {
    throw new Error('That alert is no longer current.');
  }
  if (occurrenceAt != null && reminder.lastAcknowledgedScheduledAt === occurrenceAt && action !== 'dismiss') {
    throw new Error('That alert is already done.');
  }
  const update: Partial<ReminderRecord> = { updatedAt: now, version: reminder.version + 1 };
  if (action === 'done') {
    const deliveredAlert = occurrenceAt !== null && occurrenceAt === reminder.lastScheduledAt && reminder.currentScheduledAt === null;
    if (!deliveredAlert) {
      const target = reminder.currentScheduledAt ?? reminder.nextDueAt ?? reminder.lastScheduledAt;
      if (target != null) update.nextDueAt = nextOccurrence(reminder.rule, target);
    }
    update.currentScheduledAt = null;
    if (occurrenceAt != null || reminder.lastScheduledAt != null) update.lastAcknowledgedScheduledAt = occurrenceAt ?? reminder.lastScheduledAt;
    if (('nextDueAt' in update ? update.nextDueAt : reminder.nextDueAt) === null) update.status = 'completed';
  } else if (action === 'dismiss') {
    update.dismissedAt = now;
  } else {
    if (!Number.isInteger(snoozeMinutes) || (snoozeMinutes ?? 0) < 5 || (snoozeMinutes ?? 0) > 525_600) {
      throw new Error('Snooze must be between five minutes and one year.');
    }
    update.nextDueAt = now + snoozeMinutes! * 60_000;
    update.currentScheduledAt = occurrenceAt ?? reminder.currentScheduledAt ??
      ((reminder.lastSentAt ?? 0) > now - 60 * 60_000
        ? reminder.lastScheduledAt ?? reminder.nextDueAt : reminder.nextDueAt);
  }
  return update;
}

export function afterDelivery(
  reminder: ReminderRecord,
  scheduledAt: number,
  finishedAt: number,
  delivered: boolean,
): { update: Partial<ReminderRecord>; occurrenceStatus: 'sent' | 'retry' | 'missed' } {
  const failures = delivered ? 0 : (reminder.failedAttempts ?? 0) + 1;
  const retryMinutes = Math.min(30, 2 ** Math.min(failures - 1, 5));
  const nextDueAt = delivered
    ? (reminder.repeatUntilDoneMinutes
      ? finishedAt + reminder.repeatUntilDoneMinutes * 60_000
      : nextOccurrence(reminder.rule, reminder.lastSentAt != null && reminder.lastScheduledAt === scheduledAt
        ? scheduledAt : Math.max(scheduledAt, finishedAt)))
    : finishedAt + retryMinutes * 60_000;
  return {
    update: {
      nextDueAt,
      currentScheduledAt: delivered && !reminder.repeatUntilDoneMinutes ? null : scheduledAt,
      ...(delivered ? { lastSentAt: finishedAt, lastScheduledAt: scheduledAt } : {}),
      failedAttempts: failures,
      leaseUntil: 0,
      updatedAt: finishedAt,
      version: reminder.version + 1,
    },
    occurrenceStatus: delivered ? 'sent' : finishedAt - scheduledAt > 6 * 60 * 60_000 ? 'missed' : 'retry',
  };
}
