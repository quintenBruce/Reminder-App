import { describe, expect, it } from 'vitest';
import { deliveryDiagnostic } from '../src/deliveryDiagnostics';

describe('reminder delivery diagnostics', () => {
  it('distinguishes no registered devices', () => {
    expect(deliveryDiagnostic(0)).toEqual({
      reason: 'no_devices', deviceCount: 0, acceptedCount: 0, rejectedCount: 0, failureCodes: [],
    });
  });

  it('keeps Firebase error codes when all devices reject a push', () => {
    expect(deliveryDiagnostic(2, [
      { success: false, error: { code: 'messaging/registration-token-not-registered' } },
      { success: false, error: { code: 'messaging/registration-token-not-registered' } },
    ])).toEqual({
      reason: 'all_rejected', deviceCount: 2, acceptedCount: 0, rejectedCount: 2,
      failureCodes: ['messaging/registration-token-not-registered'],
    });
  });

  it('counts partial acceptance as sent', () => {
    expect(deliveryDiagnostic(2, [
      { success: true }, { success: false, error: { code: 'messaging/invalid-registration-token' } },
    ])).toMatchObject({ reason: 'accepted', deviceCount: 2, acceptedCount: 1, rejectedCount: 1 });
  });

  it('records a send exception without storing device tokens', () => {
    expect(deliveryDiagnostic(1, undefined, 'messaging/server-unavailable')).toEqual({
      reason: 'send_error', deviceCount: 1, acceptedCount: 0, rejectedCount: 1,
      failureCodes: ['messaging/server-unavailable'],
    });
  });
});
