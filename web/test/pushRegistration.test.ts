import { describe, expect, it, vi } from 'vitest';
import { PUSH_REFRESH_MS, registerPushWithRecovery, registrationNeedsRefresh } from '../src/pushRegistration';

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

describe('push token recovery', () => {
  const unregistered = { details: { messagingCode: 'messaging/registration-token-not-registered' } };

  it('registers a valid token without refreshing it', async () => {
    const actions = {
      getToken: vi.fn().mockResolvedValue('current'),
      register: vi.fn().mockResolvedValue(undefined),
      deleteToken: vi.fn(),
      renewSubscription: vi.fn(),
    };
    await expect(registerPushWithRecovery(actions)).resolves.toBe('current');
    expect(actions.register).toHaveBeenCalledExactlyOnceWith('current');
    expect(actions.deleteToken).not.toHaveBeenCalled();
    expect(actions.renewSubscription).not.toHaveBeenCalled();
  });

  it('replaces an unregistered token and registers the new one once', async () => {
    const actions = {
      getToken: vi.fn().mockResolvedValueOnce('stale').mockResolvedValueOnce('fresh'),
      register: vi.fn().mockRejectedValueOnce(unregistered).mockResolvedValueOnce(undefined),
      deleteToken: vi.fn().mockResolvedValue(true),
      renewSubscription: vi.fn(),
    };
    await expect(registerPushWithRecovery(actions)).resolves.toBe('fresh');
    expect(actions.register.mock.calls).toEqual([['stale'], ['fresh']]);
    expect(actions.deleteToken).toHaveBeenCalledOnce();
    expect(actions.renewSubscription).not.toHaveBeenCalled();
  });

  it('renews the browser subscription when token deletion fails', async () => {
    const actions = {
      getToken: vi.fn().mockResolvedValueOnce('stale').mockResolvedValueOnce('fresh'),
      register: vi.fn().mockRejectedValueOnce(unregistered).mockResolvedValueOnce(undefined),
      deleteToken: vi.fn().mockRejectedValue(new Error('token-unsubscribe-failed')),
      renewSubscription: vi.fn().mockResolvedValue(true),
    };
    await expect(registerPushWithRecovery(actions)).resolves.toBe('fresh');
    expect(actions.renewSubscription).toHaveBeenCalledOnce();
    expect(actions.register.mock.calls).toEqual([['stale'], ['fresh']]);
  });

  it('does not retry a rejected replacement token', async () => {
    const secondFailure = new Error('replacement rejected');
    const actions = {
      getToken: vi.fn().mockResolvedValueOnce('stale').mockResolvedValueOnce('fresh'),
      register: vi.fn().mockRejectedValueOnce(unregistered).mockRejectedValueOnce(secondFailure),
      deleteToken: vi.fn().mockResolvedValue(true),
      renewSubscription: vi.fn(),
    };
    await expect(registerPushWithRecovery(actions)).rejects.toBe(secondFailure);
    expect(actions.register).toHaveBeenCalledTimes(2);
    expect(actions.getToken).toHaveBeenCalledTimes(2);
  });

  it('leaves other registration failures on the normal retry path', async () => {
    const failure = new Error('network unavailable');
    const actions = {
      getToken: vi.fn().mockResolvedValue('current'),
      register: vi.fn().mockRejectedValue(failure),
      deleteToken: vi.fn(),
      renewSubscription: vi.fn(),
    };
    await expect(registerPushWithRecovery(actions)).rejects.toBe(failure);
    expect(actions.deleteToken).not.toHaveBeenCalled();
    expect(actions.renewSubscription).not.toHaveBeenCalled();
  });
});
