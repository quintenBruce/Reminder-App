import { describe, expect, it } from 'vitest';
import { reminderPushData } from '../src/notificationPayload';

describe('reminder push text', () => {
  it.each(['', '   ', 'Take vitamins', '  take   VITAMINS  ', 'Take\nvitamins'])
    ('omits an empty or equivalent description: %j', notes => {
      expect(reminderPushData('Take vitamins', notes, 'r1', 'r1_1')).not.toHaveProperty('body');
    });

  it('keeps distinct notes', () => {
    expect(reminderPushData('Take vitamins', 'With breakfast', 'r1', 'r1_1')).toMatchObject({
      title: 'Take vitamins', body: 'With breakfast', reminderId: 'r1', occurrenceId: 'r1_1',
    });
  });

  it('uses a separate alert ID for each repeat-until-Done nudge', () => {
    expect(reminderPushData('Take vitamins', '', 'r1', 'r1_100', 'r1_200')).toMatchObject({
      occurrenceId: 'r1_100', alertId: 'r1_200',
    });
  });
});
