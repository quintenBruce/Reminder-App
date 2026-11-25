import { Temporal } from '@js-temporal/polyfill';
import type { ScheduleRule } from '../../shared/src/schedule';

type DayPart = 'morning' | 'afternoon' | 'evening' | 'night';
const defaults: Record<DayPart, string> = {
  morning: '09:00', afternoon: '13:00', evening: '18:00', night: '20:00',
};

/** Rules are supplied most recently confirmed first, one per saved reminder. */
export function preferredTimes(rules: ScheduleRule[], zone: string): Record<DayPart, string> {
  const counts: Record<DayPart, Map<string, number>> = {
    morning: new Map(), afternoon: new Map(), evening: new Map(), night: new Map(),
  };
  for (const rule of rules) {
    if (rule.kind === 'interval') continue;
    const time = rule.kind === 'once'
      ? Temporal.Instant.from(rule.at).toZonedDateTimeISO(zone)
      : rule;
    const { hour, minute } = time;
    if (hour < 5) continue;
    const part = hour < 12 ? 'morning' : hour < 17 ? 'afternoon' : hour < 20 ? 'evening' : 'night';
    const clock = `${String(hour).padStart(2, '0')}:${String(minute).padStart(2, '0')}`;
    counts[part].set(clock, (counts[part].get(clock) ?? 0) + 1);
  }
  const preferences = { ...defaults };
  for (const part of Object.keys(defaults) as DayPart[]) {
    const best = [...counts[part]].sort((a, b) => b[1] - a[1])[0];
    if (best && best[1] >= 2) preferences[part] = best[0];
  }
  return preferences;
}
