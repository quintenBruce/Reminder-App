import { nextOccurrence, type ReminderRecord } from './schedule';

export type OccurrenceAction = 'done' | 'dismiss' | 'snooze';

export function reminderSection(reminder: ReminderRecord): 'upcoming' | 'history' {
  return reminder.status === 'active' && reminder.nextDueAt != null ? 'upcoming' : 'history';
}

export function currentAlertId(reminder: ReminderRecord, now: number): string | undefined {
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
  const update: Partial<ReminderRecord> = { updatedAt: now, version: reminder.version + 1 };
  if (action === 'done') {
    const deliveredAlert = occurrenceAt !== null && occurrenceAt === reminder.lastScheduledAt && reminder.currentScheduledAt === null;
    if (!deliveredAlert) {
      const target = reminder.currentScheduledAt ?? reminder.nextDueAt ?? reminder.lastScheduledAt;
      if (target != null) update.nextDueAt = nextOccurrence(reminder.rule, target);
    }
    update.currentScheduledAt = null;
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
      : nextOccurrence(reminder.rule, Math.max(scheduledAt, finishedAt)))
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
