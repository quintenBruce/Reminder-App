import { describe, expect, it, vi } from 'vitest';
import { playHaptic } from '../src/haptics';

describe('optional haptic feedback', () => {
  it('uses short patterns when vibration is available', () => {
    const vibrate = vi.fn(() => true);
    expect(playHaptic('tap', { vibrate })).toBe(true);
    expect(playHaptic('success', { vibrate })).toBe(true);
    expect(vibrate.mock.calls).toEqual([[12], [[18, 35, 18]]]);
  });

  it('leaves the app working when the browser rejects vibration', () => {
    expect(playHaptic('tap', {})).toBe(false);
    expect(playHaptic('tap', { vibrate: () => { throw new Error('blocked'); } })).toBe(false);
  });
});
