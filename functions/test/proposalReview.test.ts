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
      recurrence: 'Every 120 minutes', endsAt: Date.parse('2026-07-02T22:00:00Z') });
    expect(readback).toContain('1:00 PM CDT');
    expect(readback).toContain('Ends Jul 2, 2026');
    expect(readback).not.toMatch(/follow up|confirm\?/i);
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
