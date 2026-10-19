import { Temporal } from '@js-temporal/polyfill';

export type ScheduleRule =
  | { kind: 'once'; at: string }
  | { kind: 'interval'; startAt: string; everyMinutes: number; endAt?: string }
  | {
      kind: 'daily' | 'weekly' | 'monthly' | 'monthly_weekday' | 'monthly_weekly' | 'yearly';
      startAt: string;
      timeZone: string;
      hour: number;
      minute: number;
      daysOfWeek?: number[];
      dayOfMonth?: number;
      weekOfMonth?: number;
      startDayOfMonth?: number;
      month?: number;
      endAt?: string;
    };

export interface ReminderRecord {
  id: string;
  title: string;
  notes: string;
  originalWording: string;
  rule: ScheduleRule;
  repeatUntilDoneMinutes: number | null;
  status: 'active' | 'completed' | 'canceled';
  nextDueAt: number | null;
  currentScheduledAt: number | null;
  version: number;
  createdAt: number;
  updatedAt: number;
  lastSentAt?: number;
  lastScheduledAt?: number;
  lastAcknowledgedScheduledAt?: number;
  deletionRequestedAt?: number;
  failedAttempts?: number;
  dismissedAt?: number;
  leaseUntil?: number;
}

const minuteMs = 60_000;
const MAX_YEARS = 20;

export function parseInstant(value: string): number {
  const ms = Date.parse(value);
  if (!Number.isFinite(ms) || !/Z$|[+-]\d\d:\d\d$/.test(value)) {
    throw new Error('Use an ISO date and time with an offset.');
  }
  return ms;
}

export function validateRule(rule: ScheduleRule): void {
  if (rule.kind === 'once') {
    parseInstant(rule.at);
    return;
  }
  const start = parseInstant(rule.startAt);
  if ('endAt' in rule && rule.endAt && parseInstant(rule.endAt) < start) {
    throw new Error('End time must follow start time.');
  }
  if (rule.kind === 'interval') {
    if (!Number.isInteger(rule.everyMinutes) || rule.everyMinutes < 5 || rule.everyMinutes > 525_600) {
      throw new Error('Repeat interval must be between 5 minutes and one year.');
    }
    return;
  }
  Temporal.Now.zonedDateTimeISO(rule.timeZone);
  if (!Number.isInteger(rule.hour) || rule.hour < 0 || rule.hour > 23 ||
      !Number.isInteger(rule.minute) || rule.minute < 0 || rule.minute > 59) {
    throw new Error('Invalid local time.');
  }
  if (['weekly', 'monthly_weekday', 'monthly_weekly'].includes(rule.kind) &&
      (!rule.daysOfWeek?.length || rule.daysOfWeek.some(d => !Number.isInteger(d) || d < 1 || d > 7))) {
    throw new Error('Weekdays must be numbered 1 to 7.');
  }
  if (rule.kind === 'monthly_weekday' &&
      (rule.daysOfWeek?.length !== 1 || !Number.isInteger(rule.weekOfMonth) ||
        ![1, 2, 3, 4, 5, -1].includes(rule.weekOfMonth!))) {
    throw new Error('Monthly weekday reminders need one weekday and a week number from 1 to 5, or -1 for last.');
  }
  if (rule.kind === 'monthly_weekly' &&
      (!Number.isInteger(rule.startDayOfMonth) || (rule.startDayOfMonth ?? 0) < 1 || (rule.startDayOfMonth ?? 0) > 31)) {
    throw new Error('Invalid monthly start day.');
  }
  if ((rule.kind === 'monthly' || rule.kind === 'yearly') &&
      (!Number.isInteger(rule.dayOfMonth) || (rule.dayOfMonth ?? 0) < 1 || (rule.dayOfMonth ?? 0) > 31)) {
    throw new Error('Invalid day of month.');
  }
  if (rule.kind === 'yearly' && (!Number.isInteger(rule.month) || (rule.month ?? 0) < 1 || (rule.month ?? 0) > 12)) {
    throw new Error('Invalid month.');
  }
}

export function nextOccurrence(rule: ScheduleRule, afterMs: number): number | null {
  validateRule(rule);
  if (rule.kind === 'once') {
    const at = parseInstant(rule.at);
    return at > afterMs ? at : null;
  }
  const start = parseInstant(rule.startAt);
  const end = rule.endAt ? parseInstant(rule.endAt) : Infinity;
  if (rule.kind === 'interval') {
    const step = rule.everyMinutes * minuteMs;
    const n = Math.max(0, Math.floor((afterMs - start) / step) + 1);
    const candidate = start + n * step;
    return candidate <= end ? candidate : null;
  }
  const from = Math.max(start, afterMs + 1);
  const localStart = Temporal.Instant.fromEpochMilliseconds(from).toZonedDateTimeISO(rule.timeZone).toPlainDate();
  for (let i = 0; i < 366 * MAX_YEARS; i++) {
    const date = localStart.add({ days: i });
    if (rule.kind === 'weekly' && !rule.daysOfWeek?.includes(date.dayOfWeek)) continue;
    if (rule.kind === 'monthly_weekday') {
      if (date.dayOfWeek !== rule.daysOfWeek![0]) continue;
      const week = Math.floor((date.day - 1) / 7) + 1;
      if (rule.weekOfMonth === -1 ? date.day + 7 <= date.daysInMonth : week !== rule.weekOfMonth) continue;
    }
    if (rule.kind === 'monthly_weekly' &&
        (date.day < rule.startDayOfMonth! || !rule.daysOfWeek?.includes(date.dayOfWeek))) continue;
    if (rule.kind === 'monthly' && date.day !== Math.min(rule.dayOfMonth!, date.daysInMonth)) continue;
    if (rule.kind === 'yearly' && (date.month !== rule.month || date.day !== Math.min(rule.dayOfMonth!, date.daysInMonth))) continue;
    const candidate = Temporal.ZonedDateTime.from({
      timeZone: rule.timeZone,
      year: date.year, month: date.month, day: date.day,
      hour: rule.hour, minute: rule.minute,
    }, { disambiguation: 'compatible' }).epochMilliseconds;
    if (candidate > afterMs && candidate >= start && candidate <= end) return candidate;
    if (candidate > end) return null;
  }
  return null;
}

export function previewOccurrences(rule: ScheduleRule, afterMs: number, count = 5): number[] {
  const dates: number[] = [];
  let cursor = afterMs;
  for (let i = 0; i < count; i++) {
    const next = nextOccurrence(rule, cursor);
    if (next === null) break;
    dates.push(next);
    cursor = next;
  }
  return dates;
}

export function validateNudge(minutes: number | null): void {
  if (minutes !== null && (!Number.isInteger(minutes) || minutes < 5 || minutes > 525_600)) {
    throw new Error('Repeat-until-done interval must be between 5 minutes and one year.');
  }
}
