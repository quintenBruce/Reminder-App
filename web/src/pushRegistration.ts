export type SavedPushRegistration = { uid?: string; token?: string; checkedAt?: number } | null;

export const PUSH_REFRESH_MS = 30 * 60_000;

export function registrationNeedsRefresh(saved: SavedPushRegistration, uid: string, now = Date.now()): boolean {
  return saved?.uid !== uid || !saved.token || !Number.isFinite(saved.checkedAt) ||
    saved.checkedAt! > now || now - saved.checkedAt! >= PUSH_REFRESH_MS;
}
