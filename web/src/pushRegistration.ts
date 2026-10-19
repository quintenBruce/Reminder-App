export type SavedPushRegistration = { uid?: string; token?: string; checkedAt?: number } | null;

export const PUSH_REFRESH_MS = 30 * 60_000;

export function registrationNeedsRefresh(saved: SavedPushRegistration, uid: string, now = Date.now()): boolean {
  return saved?.uid !== uid || !saved.token || !Number.isFinite(saved.checkedAt) ||
    saved.checkedAt! > now || now - saved.checkedAt! >= PUSH_REFRESH_MS;
}

const UNREGISTERED_TOKEN_CODE = 'messaging/registration-token-not-registered';

function isUnregisteredTokenError(error: unknown): boolean {
  if (typeof error !== 'object' || error === null || !('details' in error)) return false;
  const details = error.details;
  return typeof details === 'object' && details !== null && 'messagingCode' in details &&
    details.messagingCode === UNREGISTERED_TOKEN_CODE;
}

type PushRegistrationActions = {
  getToken: () => Promise<string>;
  register: (token: string) => Promise<unknown>;
  deleteToken: () => Promise<unknown>;
  renewSubscription: () => Promise<boolean>;
};

export async function registerPushWithRecovery(actions: PushRegistrationActions): Promise<string> {
  const token = await actions.getToken();
  if (!token) throw new Error('Firefox did not return a push token.');
  try {
    await actions.register(token);
    return token;
  } catch (error) {
    if (!isUnregisteredTokenError(error)) throw error;
  }

  try {
    await actions.deleteToken();
  } catch {
    if (!await actions.renewSubscription()) {
      throw new Error('Could not renew the browser push subscription. Please try again.');
    }
  }
  const renewedToken = await actions.getToken();
  if (!renewedToken || renewedToken === token) {
    throw new Error('Firebase did not return a new push token. Please try again.');
  }
  await actions.register(renewedToken);
  return renewedToken;
}
