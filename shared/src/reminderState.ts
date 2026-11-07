import { nextOccurrence, type ReminderRecord } from './schedule';

export type OccurrenceAction = 'done' | 'dismiss' | 'snooze';
export const DEFAULT_ACK_MINUTES = 30;

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
  allowLocallyDelivered = false,
  snoozeAt?: number,
): Partial<ReminderRecord> {
  const occurrenceAt = occurrenceId?.startsWith(`${reminder.id}_`)
    ? Number(occurrenceId.slice(reminder.id.length + 1)) : null;
  const locallyDue = allowLocallyDelivered && occurrenceAt !== null &&
    occurrenceAt === reminder.nextDueAt && occurrenceAt <= now;
  if (occurrenceId && (!Number.isFinite(occurrenceAt) ||
      (occurrenceAt !== reminder.lastScheduledAt && occurrenceAt !== reminder.currentScheduledAt && !locallyDue))) {
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
      if (target != null) update.nextDueAt = nextOccurrence(reminder.rule, Math.max(target, now));
    }
    update.currentScheduledAt = null;
    if (occurrenceAt != null || reminder.lastScheduledAt != null) update.lastAcknowledgedScheduledAt = occurrenceAt ?? reminder.lastScheduledAt;
    if (('nextDueAt' in update ? update.nextDueAt : reminder.nextDueAt) === null) update.status = 'completed';
  } else if (action === 'dismiss') {
    update.dismissedAt = now;
  } else {
    if (snoozeAt == null && (!Number.isInteger(snoozeMinutes) || (snoozeMinutes ?? 0) < 5 || (snoozeMinutes ?? 0) > 525_600)) {
      throw new Error('Snooze must be between five minutes and one year.');
    }
    if (snoozeAt != null && (!Number.isFinite(snoozeAt) || snoozeAt < now - 60_000 ||
        snoozeAt > now + 366 * 24 * 60 * 60_000)) throw new Error('Invalid snooze time.');
    const scheduledAt = occurrenceAt ?? reminder.currentScheduledAt ?? reminder.lastScheduledAt ?? reminder.nextDueAt;
    const firstRegular = scheduledAt == null ? null : nextOccurrence(reminder.rule, scheduledAt);
    const regular = firstRegular != null && firstRegular <= now
      ? nextOccurrence(reminder.rule, now) : firstRegular;
    const snoozedAt = snoozeAt == null ? now + snoozeMinutes! * 60_000 : Math.max(now + 1_000, snoozeAt);
    update.nextDueAt = regular != null && regular <= snoozedAt ? regular : snoozedAt;
    update.currentScheduledAt = regular != null && regular <= snoozedAt ? null : occurrenceAt ?? reminder.currentScheduledAt ??
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
  const regular = nextOccurrence(reminder.rule, scheduledAt);
  const nextRegular = regular != null && regular <= finishedAt
    ? nextOccurrence(reminder.rule, finishedAt) : regular;
  const nudgeAt = finishedAt + (reminder.repeatUntilDoneMinutes ?? DEFAULT_ACK_MINUTES) * 60_000;
  const nextDueAt = delivered
    ? nextRegular != null && nextRegular <= nudgeAt ? nextRegular : nudgeAt
    : finishedAt + retryMinutes * 60_000;
  const repeatingThisOccurrence = delivered && (nextRegular == null || nudgeAt < nextRegular);
  return {
    update: {
      nextDueAt,
      currentScheduledAt: repeatingThisOccurrence || !delivered ? scheduledAt : null,
      ...(delivered ? { lastSentAt: finishedAt, lastScheduledAt: scheduledAt } : {}),
      failedAttempts: failures,
      leaseUntil: 0,
      updatedAt: finishedAt,
      version: reminder.version + 1,
    },
    occurrenceStatus: delivered ? 'sent' : finishedAt - scheduledAt > 6 * 60 * 60_000 ? 'missed' : 'retry',
  };
}
