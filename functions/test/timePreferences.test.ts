import { expect, it } from 'vitest';
import { preferredTimes } from '../src/timePreferences';

it('learns frequently confirmed times in the device zone and defaults for sparse history', () => {
  expect(preferredTimes([
    { kind: 'once', at: '2026-11-23T13:30:00Z' },
    { kind: 'once', at: '2026-11-22T13:30:00Z' },
    { kind: 'once', at: '2026-11-21T23:15:00Z' },
    { kind: 'daily', startAt: '2026-11-20T00:00:00Z', timeZone: 'America/Chicago', hour: 21, minute: 15 },
    { kind: 'once', at: '2026-11-20T03:15:00Z' },
    { kind: 'interval', startAt: '2026-11-19T13:30:00Z', everyMinutes: 30 },
  ], 'America/Chicago')).toEqual({
    morning: '07:30', afternoon: '13:00', evening: '18:00', night: '21:15',
  });
});
