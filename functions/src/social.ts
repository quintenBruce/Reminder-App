import { randomUUID } from 'node:crypto';
import { getFirestore } from 'firebase-admin/firestore';
import { HttpsError, onCall } from 'firebase-functions/v2/https';
import { authorize, consume, identifier, limits, profileInput } from './limits';

const options = { region: process.env.FUNCTIONS_REGION || 'us-south1', maxInstances: 2 };
const user = (uid: string) => getFirestore().collection('users').doc(uid);
export const accountProfile = onCall(options, async request => {
  const uid = await authorize(request);
  return { profile: (await user(uid).get()).get('profile') ?? null };
});
export const saveProfile = onCall(options, async request => {
  const uid = await authorize(request), profile = profileInput(request.data?.displayName, request.data?.username);
  await getFirestore().runTransaction(async tx => {
    const ref = getFirestore().collection('usernames').doc(profile.username);
    const [account, reserved] = await Promise.all([tx.get(user(uid)), tx.get(ref)]);
    if (account.get('profile')) {
      if (account.get('profile.username') === profile.username && account.get('profile.displayName') === profile.displayName) return;
      throw new HttpsError('failed-precondition', 'Profile is already set up.');
    }
    if (reserved.exists) throw new HttpsError('already-exists', 'That username is taken. Try another.');
    tx.create(ref, { uid });
    tx.set(user(uid), { profile }, { merge: true });
  });
  return { profile };
});
export const listFriends = onCall(options, async request => {
  const uid = await authorize(request);
  const friends = await user(uid).collection('friends').limit(limits.friends).get();
  return { friends: friends.docs.map(doc => ({ uid: doc.id, ...doc.data() })) };
});
export const createFriendInvite = onCall(options, async request => {
  const uid = await authorize(request); await consume(uid, 'invite');
  const token = randomUUID();
  await getFirestore().runTransaction(async tx => {
    const account = await tx.get(user(uid));
    if (!account.get('profile')) throw new HttpsError('failed-precondition', 'Complete your profile first.');
    const old = account.get('inviteToken');
    if (typeof old === 'string') tx.delete(getFirestore().collection('friendInvites').doc(old));
    tx.create(getFirestore().collection('friendInvites').doc(token), { uid, profile: account.get('profile'), expiresAt: Date.now() + 7 * 86_400_000 });
    tx.set(user(uid), { inviteToken: token }, { merge: true });
  });
  const project = process.env.GCLOUD_PROJECT || process.env.GOOGLE_CLOUD_PROJECT;
  return { token, url: `https://${project}.web.app/invite/${token}` };
});
export const friendInvite = onCall(options, async request => {
  const uid = await authorize(request); await consume(uid, 'invite');
  const token = identifier(request.data?.token), accept = request.data?.accept === true;
  return getFirestore().runTransaction(async tx => {
    const ref = getFirestore().collection('friendInvites').doc(token), invite = await tx.get(ref);
    if (!invite.exists || invite.get('expiresAt') <= Date.now()) throw new HttpsError('not-found', 'This invitation expired or was revoked. Ask for a new link.');
    const sender = invite.get('uid') as string;
    if (sender === uid) throw new HttpsError('invalid-argument', 'Send this link to a friend.');
    if (invite.get('acceptedBy') === uid) return { profile: invite.get('profile'), accepted: true };
    if (invite.get('acceptedBy')) throw new HttpsError('failed-precondition', 'This invitation has already been used.');
    if (!accept) return { profile: invite.get('profile'), accepted: false };
    const [account, senderAccount, mine, theirs] = await Promise.all([tx.get(user(uid)), tx.get(user(sender)),
      tx.get(user(uid).collection('friends')), tx.get(user(sender).collection('friends'))]);
    if (!account.get('profile')) throw new HttpsError('failed-precondition', 'Complete your profile first.');
    const exists = mine.docs.some(doc => doc.id === sender);
    if (!exists && (mine.size >= limits.friends || theirs.size >= limits.friends)) throw new HttpsError('resource-exhausted', 'Friend limit reached.');
    tx.set(user(uid).collection('friends').doc(sender), senderAccount.get('profile'));
    tx.set(user(sender).collection('friends').doc(uid), account.get('profile'));
    tx.update(ref, { acceptedBy: uid });
    return { profile: invite.get('profile'), accepted: true };
  });
});
export const removeFriend = onCall(options, async request => {
  const uid = await authorize(request), friend = identifier(request.data?.uid);
  const batch = getFirestore().batch();
  batch.delete(user(uid).collection('friends').doc(friend)); batch.delete(user(friend).collection('friends').doc(uid));
  await batch.commit(); return { removed: true };
});
export const revokeFriendInvite = onCall(options, async request => {
  const uid = await authorize(request);
  await getFirestore().runTransaction(async tx => {
    const account = await tx.get(user(uid));
    const token = account.get('inviteToken');
    if (typeof token === 'string') tx.delete(getFirestore().collection('friendInvites').doc(token));
    tx.set(user(uid), { inviteToken: null }, { merge: true });
  });
  return { revoked: true };
});
