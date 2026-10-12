import { describe, expect, it } from 'vitest';
import { PUSH_REFRESH_MS, registrationNeedsRefresh } from '../src/pushRegistration';

describe('push registration refresh', () => {
  const now = 1_000_000;
  it('refreshes a missing or different account registration', () => {
    expect(registrationNeedsRefresh(null, 'me', now)).toBe(true);
    expect(registrationNeedsRefresh({ uid: 'other', token: 'token', checkedAt: now }, 'me', now)).toBe(true);
  });
  it('refreshes old registrations and legacy records without a timestamp', () => {
    expect(registrationNeedsRefresh({ uid: 'me', token: 'token' }, 'me', now)).toBe(true);
    expect(registrationNeedsRefresh({ uid: 'me', token: 'token', checkedAt: now - PUSH_REFRESH_MS }, 'me', now)).toBe(true);
    expect(registrationNeedsRefresh({ uid: 'me', token: 'token', checkedAt: now + 1000 }, 'me', now)).toBe(true);
  });
  it('keeps a recently verified registration', () => {
    expect(registrationNeedsRefresh({ uid: 'me', token: 'token', checkedAt: now - 1000 }, 'me', now)).toBe(false);
  });
});
