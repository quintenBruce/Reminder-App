import { describe, expect, it } from 'vitest';
import { buildProposalReview } from '../src/proposalReview';

const base = {
  title: 'Water plants', notes: 'Use the small jug', recipients: ['Sam (@sam)'],
  rule: null, preview: [], snoozeMinutes: null,
};

describe('buildProposalReview', () => {
  it.each(['create', 'edit'])('gives %s a structured schedule and a Central Time readback', action => {
    const { review, readback } = buildProposalReview({
      ...base, action, rule: { kind: 'interval', everyMinutes: 120, startAt: '2026-07-02T18:00:00Z',
        endAt: '2026-07-02T22:00:00Z' }, preview: [Date.parse('2026-07-02T18:00:00Z')],
    });
    expect(review).toMatchObject({ title: base.title, notes: base.notes, recipients: base.recipients,
      recurrence: 'Every 120 minutes', startsAt: Date.parse('2026-07-02T18:00:00Z'),
      endsAt: Date.parse('2026-07-02T22:00:00Z'), followUpMinutes: null });
    expect(readback).toContain('1:00 PM CDT');
    expect(readback).toContain('Ends Jul 2, 2026');
    expect(readback).not.toMatch(/follow up|confirm\?/i);
  });

  it('shows a single time for a private one-time reminder and omits blank notes', () => {
    const when = Date.parse('2026-11-24T01:00:00Z');
    const { review, readback } = buildProposalReview({ ...base, action: 'create', notes: '  ', recipients: [],
      rule: { kind: 'once', at: '2026-11-24T01:00:00Z' }, preview: [when] });
    expect(review).toMatchObject({ whenAt: when, recurrence: null, startsAt: null, endsAt: null, notes: null, recipients: [] });
    expect(readback.match(/7:00 PM CST/g)).toHaveLength(1);
    expect(readback).not.toMatch(/Notes:|Shared with|until Done/);
  });

  it('includes Central recurrence time, sharing details and a requested follow-up', () => {
    const rule = { kind: 'weekly' as const, daysOfWeek: [1, 4], hour: 19, minute: 0,
      timeZone: 'America/Chicago', startAt: '2026-11-23T06:00:00Z' };
    const first = Date.parse('2026-11-24T01:00:00Z');
    const result = buildProposalReview({ ...base, action: 'create', rule, preview: [first], repeatUntilDoneMinutes: 5 });
    expect(result.review).toMatchObject({ startsAt: first, repeatTime: '7:00 PM Central', followUpMinutes: 5 });
    expect(result.readback).toContain('Done and snooze apply individually');
    expect(result.readback).toContain('Every 5 minutes until Done');
    const nonCentral = buildProposalReview({ ...base, action: 'create', rule: { ...rule, timeZone: 'America/New_York' } });
    expect(nonCentral.review).toMatchObject({ repeatTime: null, scheduleZone: 'America/New_York' });
  });

  it.each([
    ['delete', 'cannot be undone'], ['snooze', '15 minutes'],
    ['cancel', 'Stop future alerts'], ['done', 'Mark this reminder as done'],
  ])('preserves the %s action details', (action, detail) => {
    const result = buildProposalReview({ ...base, action, snoozeMinutes: 15 });
    expect(result.review.actionDetail).toContain(detail);
    expect(result.readback).toContain(detail);
  });
});
