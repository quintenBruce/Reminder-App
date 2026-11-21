import type { ScheduleRule } from '../../shared/src/schedule';

const weekdays = ['Monday', 'Tuesday', 'Wednesday', 'Thursday', 'Friday', 'Saturday', 'Sunday'];
const ordinals = ['first', 'second', 'third', 'fourth', 'fifth'];
const months = ['January', 'February', 'March', 'April', 'May', 'June',
  'July', 'August', 'September', 'October', 'November', 'December'];

function cadence(rule: ScheduleRule): string {
  switch (rule.kind) {
    case 'once': return 'Once';
    case 'interval': return `Every ${rule.everyMinutes} minutes`;
    case 'daily': return 'Daily';
    case 'weekly': return `Every ${rule.daysOfWeek!.map(day => weekdays[day - 1]).join(', ')}`;
    case 'monthly': return `Monthly on day ${rule.dayOfMonth} (last day in shorter months)`;
    case 'monthly_weekday': {
      const week = rule.weekOfMonth === -1 ? 'last' : ordinals[rule.weekOfMonth! - 1];
      return `Monthly on the ${week} ${weekdays[rule.daysOfWeek![0] - 1]}`;
    }
    case 'monthly_weekly':
      return `Every ${rule.daysOfWeek!.map(day => weekdays[day - 1]).join(', ')} from day ${rule.startDayOfMonth} each month`;
    case 'yearly': return `Yearly on ${months[rule.month! - 1]} ${rule.dayOfMonth}`;
  }
}

const centralTime = new Intl.DateTimeFormat('en-US', {
  timeZone: 'America/Chicago', month: 'short', day: 'numeric', year: 'numeric',
  hour: 'numeric', minute: '2-digit', hour12: true, timeZoneName: 'short',
});

export function buildProposalReview(input: {
  action: string; title: string | null; notes: string | null; rule: ScheduleRule | null;
  recipients: string[]; preview: number[]; snoozeMinutes: number | null;
}) {
  const { action, title, notes, rule, recipients, preview, snoozeMinutes } = input;
  const actionDetail = action === 'delete' ? 'Permanently delete this reminder and its records. This cannot be undone.'
    : action === 'snooze' ? `Remind me again in ${snoozeMinutes} minutes.`
      : action === 'cancel' ? 'Stop future alerts for this reminder.'
        : action === 'done' ? 'Mark this reminder as done.' : null;
  const recurrence = rule ? cadence(rule) : null;
  const endsAt = rule && 'endAt' in rule && rule.endAt ? Date.parse(rule.endAt) : null;
  const review = { title, notes, recurrence, endsAt, recipients, actionDetail };
  // Older clients still display the text reply. Android's review card uses the structured fields.
  const readback = [
    title, actionDetail, recurrence, notes || null,
    recipients.length ? `Shared with ${recipients.join(', ')}` : null,
    ...preview.map(time => centralTime.format(time)),
    endsAt === null ? null : `Ends ${centralTime.format(endsAt)}`,
  ].filter(Boolean).join('\n');
  return { review, readback };
}
