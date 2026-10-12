import { describe, expect, it } from 'vitest';
import { nextOccurrence, previewOccurrences, type ReminderRecord } from '../src/schedule';
import { afterDelivery, applyReminderAction, currentAlertId, reminderSection } from '../src/reminderState';

const ms = (value: string) => Date.parse(value);
const iso = (value: number | null) => value === null ? null : new Date(value).toISOString();

function reminder(partial: Partial<ReminderRecord> = {}): ReminderRecord {
  return {
    id: 'r1', title: 'Take vitamins', notes: '', originalWording: '',
    rule: { kind: 'once', at: '2026-10-11T14:00:00-05:00' },
    repeatUntilDoneMinutes: null, status: 'active',
    nextDueAt: ms('2026-10-11T14:00:00-05:00'), currentScheduledAt: null,
    version: 1, createdAt: 0, updatedAt: 0, ...partial,
  };
}

describe('deterministic recurrence', () => {
  it('schedules an elapsed interval from its anchor, including after a late dispatcher', () => {
    const rule = { kind: 'interval' as const, startAt: '2026-10-11T09:00:00-05:00', everyMinutes: 5 };
    expect(iso(nextOccurrence(rule, ms('2026-10-11T09:11:00-05:00')))).toBe('2026-10-11T14:15:00.000Z');
    expect(previewOccurrences(rule, ms('2026-10-11T08:59:00-05:00'), 3).map(iso)).toEqual([
      '2026-10-11T14:00:00.000Z', '2026-10-11T14:05:00.000Z', '2026-10-11T14:10:00.000Z',
    ]);
  });

  it('keeps a daily 9 AM reminder at local 9 AM across fall DST', () => {
    const rule = { kind: 'daily' as const, startAt: '2026-10-31T09:00:00-05:00',
      timeZone: 'America/Chicago', hour: 9, minute: 0 };
    expect(previewOccurrences(rule, ms('2026-10-31T08:00:00-05:00'), 3).map(iso)).toEqual([
      '2026-10-31T14:00:00.000Z', '2026-11-01T15:00:00.000Z', '2026-11-02T15:00:00.000Z',
    ]);
  });

  it('moves a skipped spring 2:30 AM to a valid local time and fires once on fall repeat', () => {
    const spring = { kind: 'daily' as const, startAt: '2026-03-07T02:30:00-06:00',
      timeZone: 'America/Chicago', hour: 2, minute: 30 };
    expect(iso(nextOccurrence(spring, ms('2026-03-08T00:00:00-06:00')))).toBe('2026-03-08T08:30:00.000Z');
    const fall = { ...spring, startAt: '2026-10-31T01:30:00-05:00', hour: 1, minute: 30 };
    expect(iso(nextOccurrence(fall, ms('2026-11-01T00:00:00-05:00')))).toBe('2026-11-01T06:30:00.000Z');
    expect(iso(nextOccurrence(fall, ms('2026-11-01T01:30:00-05:00')))).toBe('2026-11-02T07:30:00.000Z');
  });

  it('clamps monthly day 31 to shorter months and respects an end date', () => {
    const rule = { kind: 'monthly' as const, startAt: '2026-01-01T00:00:00-06:00',
      timeZone: 'America/Chicago', hour: 9, minute: 0, dayOfMonth: 31,
      endAt: '2026-03-31T23:59:00-05:00' };
    expect(previewOccurrences(rule, ms('2026-01-01T00:00:00-06:00')).map(iso)).toEqual([
      '2026-01-31T15:00:00.000Z', '2026-02-28T15:00:00.000Z', '2026-03-31T14:00:00.000Z',
    ]);
  });

  it('rejects intervals shorter than five minutes', () => {
    expect(() => nextOccurrence({ kind: 'interval', startAt: '2026-01-01T00:00:00Z', everyMinutes: 4 }, 0)).toThrow();
  });
});

describe('reminder actions and delivery', () => {
  it('Dismiss leaves repeat-until-done nudges scheduled; Done stops them', () => {
    const scheduled = ms('2026-10-11T14:00:00-05:00');
    const now = scheduled + 60_000;
    const r = reminder({ repeatUntilDoneMinutes: 5, currentScheduledAt: scheduled, nextDueAt: now + 5 * 60_000 });
    expect(applyReminderAction(r, 'dismiss', now, null).nextDueAt).toBeUndefined();
    expect(applyReminderAction(r, 'done', now, null)).toMatchObject({ nextDueAt: null, status: 'completed' });
  });

  it('Snooze delays this alert and keeps its original occurrence ID', () => {
    const scheduled = ms('2026-10-11T14:00:00-05:00');
    const r = reminder({ nextDueAt: null, lastScheduledAt: scheduled, lastSentAt: scheduled });
    const result = applyReminderAction(r, 'snooze', scheduled + 60_000, 10, `r1_${scheduled}`);
    expect(result.currentScheduledAt).toBe(scheduled);
    expect(result.nextDueAt).toBe(scheduled + 11 * 60_000);
  });

  it('Done on a delivered notification does not skip the next base occurrence', () => {
    const prior = ms('2026-10-11T09:00:00-05:00');
    const next = ms('2026-10-12T09:00:00-05:00');
    const r = reminder({ rule: { kind: 'daily', startAt: '2026-10-11T09:00:00-05:00',
      timeZone: 'America/Chicago', hour: 9, minute: 0 }, lastScheduledAt: prior,
      lastSentAt: prior, nextDueAt: next });
    const result = applyReminderAction(r, 'done', prior + 60_000, null, `r1_${prior}`);
    expect(result.nextDueAt).toBeUndefined();
    expect(result.status).toBeUndefined();
  });

  it('Upcoming actions target a recent alert but not an old recurrence', () => {
    const prior = ms('2026-10-11T09:00:00-05:00');
    const next = ms('2026-10-12T09:00:00-05:00');
    const r = reminder({ lastScheduledAt: prior, lastSentAt: prior, nextDueAt: next });
    expect(currentAlertId(r, prior + 60_000)).toBe(`r1_${prior}`);
    expect(currentAlertId(r, prior + 25 * 60 * 60_000)).toBeUndefined();
    const done = applyReminderAction(r, 'done', prior + 60_000, null, currentAlertId(r, prior + 60_000));
    expect(done.nextDueAt).toBeUndefined();
  });

  it('backs off failed push attempts and keeps the original due instant', () => {
    const due = ms('2026-10-11T14:00:00-05:00');
    const result = afterDelivery(reminder({ failedAttempts: 2 }), due, due + 60_000, false);
    expect(result.update.nextDueAt).toBe(due + 5 * 60_000);
    expect(result.update.currentScheduledAt).toBe(due);
    expect(result.occurrenceStatus).toBe('retry');
  });

  it('moves a sent final occurrence out of Upcoming without breaking its actions', () => {
    const due = ms('2026-10-11T14:00:00-05:00');
    const sent = afterDelivery(reminder(), due, due + 1000, true);
    expect(sent.update.nextDueAt).toBeNull();
    const delivered = reminder(sent.update);
    expect(reminderSection(delivered)).toBe('history');
    expect(applyReminderAction(delivered, 'done', due + 2000, null, `r1_${due}`)).toMatchObject({ status: 'completed' });
    expect(applyReminderAction(delivered, 'snooze', due + 2000, 10, `r1_${due}`)).toMatchObject({ nextDueAt: due + 602000 });
  });
});
