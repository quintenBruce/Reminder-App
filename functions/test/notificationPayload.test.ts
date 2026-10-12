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
});
