import { getFirestore } from 'firebase-admin/firestore';
import { HttpsError, type CallableRequest } from 'firebase-functions/v2/https';
import { warn } from 'firebase-functions/logger';

export const limits = {
  aiDaily: 100, userAiDaily: 50, aiMinute: 5, requestsMinute: 60,
  invitationsHour: 10, testsHour: 5, devices: 10, friends: 100, participants: 10, active: 200,
};

export function quotaWindows(uid: string, kind: 'request' | 'ai' | 'invite' | 'test', now: number) {
  const minute = Math.floor(now / 60_000), hour = Math.floor(now / 3_600_000), day = Math.floor(now / 86_400_000);
  const key = encodeURIComponent(uid);
  if (kind === 'ai') return [
    { id: `ai_global_${day}`, max: limits.aiDaily, resetAt: (day + 1) * 86_400_000 },
    { id: `ai_${key}_${day}`, max: limits.userAiDaily, resetAt: (day + 1) * 86_400_000 },
    { id: `burst_${key}_${minute}`, max: limits.aiMinute, resetAt: (minute + 1) * 60_000 },
  ];
  return [{ id: `${kind}_${key}_${kind === 'request' ? minute : hour}`,
    max: kind === 'request' ? limits.requestsMinute : kind === 'invite' ? limits.invitationsHour : limits.testsHour,
    resetAt: kind === 'request' ? (minute + 1) * 60_000 : (hour + 1) * 3_600_000 }];
}

export async function consume(uid: string, kind: 'request' | 'ai' | 'invite' | 'test') {
  const db = getFirestore(), windows = quotaWindows(uid, kind, Date.now());
  await db.runTransaction(async tx => {
    const refs = windows.map(w => db.collection('rateLimits').doc(w.id));
    const docs = await Promise.all(refs.map(ref => tx.get(ref)));
    for (let i = 0; i < windows.length; i++) {
      if (Number(docs[i].get('count') || 0) >= windows[i].max) {
        warn('quota.rejected', { uid, kind, resetAt: windows[i].resetAt });
        throw new HttpsError('resource-exhausted', `Usage limit reached. Try after ${new Date(windows[i].resetAt).toISOString()}.`,
          { resetAt: windows[i].resetAt });
      }
    }
    refs.forEach((ref, i) => tx.set(ref, { count: Number(docs[i].get('count') || 0) + 1,
      expiresAt: new Date(windows[i].resetAt + 86_400_000) }));
  });
}

export async function authorize(request: CallableRequest) {
  const uid = request.auth?.uid;
  if (!uid || request.auth?.token.firebase?.sign_in_provider !== 'google.com' || request.auth.token.email_verified !== true)
    throw new HttpsError('permission-denied', 'Sign in with a verified Google account.');
  await consume(uid, 'request');
  return uid;
}

export function identifier(value: unknown): string {
  if (typeof value !== 'string' || !/^[a-zA-Z0-9_-]{1,128}$/.test(value))
    throw new HttpsError('invalid-argument', 'Invalid identifier.');
  return value;
}

export function profileInput(name: unknown, username: unknown) {
  if (typeof name !== 'string' || !name.trim() || name.trim().length > 60 || /[\x00-\x1f]/.test(name))
    throw new HttpsError('invalid-argument', 'Enter a name of 1–60 characters.');
  if (typeof username !== 'string' || !/^[a-zA-Z0-9_]{3,24}$/.test(username))
    throw new HttpsError('invalid-argument', 'Username must be 3–24 letters, numbers, or underscores.');
  return { displayName: name.trim(), username: username.toLowerCase() };
}
