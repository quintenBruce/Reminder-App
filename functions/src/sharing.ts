import { randomUUID } from 'node:crypto';
import { isDeepStrictEqual } from 'node:util';
import { getFirestore } from 'firebase-admin/firestore';
import { HttpsError, onCall } from 'firebase-functions/v2/https';
import { nextOccurrence } from '../../shared/src/schedule';
import type { Proposal } from './index';
import { authorize, identifier, limits } from './limits';
import { canConfirmDraft } from './conversationCleanup';

const user = (uid: string) => getFirestore().collection('users').doc(uid);
const shared = (id: string) => getFirestore().collection('sharedReminders').doc(id);
const mirror = (uid: string, id: string) => user(uid).collection('reminders').doc(id);

// These per-account documents are delivery views, never independent editable copies.
// All shared content/membership writes go through the canonical record transaction.
export async function commitShared(uid: string, p: Proposal, priority: boolean) {
  const db = getFirestore(), id = p.targetId || randomUUID(), now = Date.now();
  return db.runTransaction(async tx => {
    const proposalRef = user(uid).collection('proposals').doc(p.id);
    const conversation = user(uid).collection('conversations').doc(p.conversationId);
    const [fresh, canonical, conv, account] = await Promise.all([tx.get(proposalRef), tx.get(shared(id)), tx.get(conversation), tx.get(user(uid))]);
    if (fresh.get('committedResult')) return fresh.get('committedResult');
    if (!fresh.exists || now > fresh.get('expiresAt') || !canConfirmDraft(conv.get('state')))
      throw new HttpsError('failed-precondition', 'Proposal expired or abandoned. Ask again.');
    if (!account.get('profile')) throw new HttpsError('failed-precondition', 'Complete your profile first.');
    const old = canonical.data();
    if (p.targetId && (!old || !old.participantIds.includes(uid) || old.version !== p.sharedVersion))
      throw new HttpsError('failed-precondition', 'Shared reminder changed. Ask again.');
    const ids: string[] = old?.participantIds ?? [...new Set([uid, ...(p.participantIds || [])])];
    if (ids.length < (old ? 1 : 2) || ids.length > limits.participants) throw new HttpsError('invalid-argument', 'Choose 1–9 friends.');
    const profiles = await Promise.all(ids.map(member => tx.get(user(member))));
    const views = await Promise.all(ids.map(member => tx.get(mirror(member, id))));
    if (!old) {
      const friendships = await Promise.all(ids.filter(member => member !== uid).map(member => tx.get(user(uid).collection('friends').doc(member))));
      if (friendships.some(doc => !doc.exists)) throw new HttpsError('permission-denied', 'Recipients must still be your friends.');
    }
    const version = (old?.version || 0) + 1;
    const canceled = p.action === 'cancel';
    if (!canceled && (!p.rule || !p.title)) throw new HttpsError('invalid-argument', 'Incomplete reminder.');
    const preserveDelivery = !!old && !canceled && isDeepStrictEqual(old.rule, p.rule) && old.repeatUntilDoneMinutes === p.repeatUntilDoneMinutes;
    if (!canceled && !preserveDelivery) {
      const activating = ids.filter((_, i) => views[i].get('status') !== 'active');
      const active = await Promise.all(activating.map(member => tx.get(user(member).collection('reminders').where('status', '==', 'active'))));
      if (active.some(result => result.size >= limits.active)) throw new HttpsError('resource-exhausted', 'A participant has reached the active reminder limit.');
    }
    const due = canceled || preserveDelivery ? null : nextOccurrence(p.rule!, now);
    if (!canceled && !preserveDelivery && due === null) throw new HttpsError('failed-precondition', 'The proposed time has passed.');
    const names = profiles.map(profile => profile.get('profile.displayName') || 'Friend');
    const content = { title: p.title || old?.title, notes: p.notes || '', rule: p.rule || old?.rule,
      repeatUntilDoneMinutes: p.repeatUntilDoneMinutes, priority,
      participantIds: ids, participantNames: names, version, updatedAt: now,
      status: canceled ? 'canceled' : 'active', createdAt: old?.createdAt || now, createdBy: old?.createdBy || uid };
    tx.set(shared(id), content);
    ids.forEach((member, i) => {
      const override = views[i].get('priorityOverride') ?? null;
      tx.set(mirror(member, id), { ...(preserveDelivery ? views[i].data() : {}), ...content, id, sharedId: id, sharedVersion: version,
        version: Number(views[i].get('version') || 0) + 1, priorityOverride: override,
        priority: override ?? priority, sharedPriority: priority,
        ...(preserveDelivery ? { status: views[i].get('status') } : { nextDueAt: due, currentScheduledAt: null }) });
      tx.create(user(member).collection('events').doc(), { reminderId: id, action: p.action, actorName: account.get('profile.displayName'), at: now });
    });
    const result = { reminderId: id, action: p.action, at: now };
    tx.update(proposalRef, { committedResult: result });
    tx.set(conversation, { schemaVersion: 2, state: 'linked', reminderId: id, linkedAt: now, updatedAt: now }, { merge: true });
    return result;
  });
}

export async function removeShared(uid: string, id: string, leave: boolean, expectedVersion?: number) {
  return getFirestore().runTransaction(async tx => {
    const doc = await tx.get(shared(id));
    if (!doc.exists) throw new HttpsError('not-found', 'Shared reminder not found.');
    const data = doc.data()!, ids: string[] = data.participantIds;
    if (!ids.includes(uid)) throw new HttpsError('not-found', 'You are no longer a participant.');
    const own = await tx.get(mirror(uid, id));
    if (expectedVersion != null && own.get('version') !== expectedVersion) throw new HttpsError('failed-precondition', 'Reminder changed. Refresh first.');
    const remaining = leave ? ids.filter(member => member !== uid) : [];
    const views = await Promise.all(remaining.map(member => tx.get(mirror(member, id))));
    if (remaining.length) {
      const names = remaining.map(member => data.participantNames[ids.indexOf(member)]);
      tx.update(doc.ref, { participantIds: remaining, participantNames: names, version: data.version + 1, updatedAt: Date.now() });
      remaining.forEach((member, i) => tx.update(views[i].ref, { participantIds: remaining, participantNames: names,
        sharedVersion: data.version + 1, version: Number(views[i].get('version')) + 1 }));
    } else tx.delete(doc.ref);
    ids.filter(member => !remaining.includes(member)).forEach(member => tx.delete(mirror(member, id)));
    ids.forEach(member => tx.create(user(member).collection('events').doc(), { reminderId: id, action: leave ? 'participant left' : 'deleted for everyone', at: Date.now() }));
    return { reminderId: id, deleted: !leave, removed: ids.length - remaining.length };
  });
}
export const leaveSharedReminder = onCall({ region: process.env.FUNCTIONS_REGION || 'us-south1', maxInstances: 2 }, async request =>
  removeShared(await authorize(request), identifier(request.data?.reminderId), true));

export const setPriorityOverride = onCall({ region: process.env.FUNCTIONS_REGION || 'us-south1', maxInstances: 2 }, async request => {
  const uid = await authorize(request), id = identifier(request.data?.reminderId), value = request.data?.priority;
  if (value !== null && typeof value !== 'boolean') throw new HttpsError('invalid-argument', 'Invalid priority.');
  await getFirestore().runTransaction(async tx => {
    const view = await tx.get(mirror(uid, id));
    if (!view.exists) throw new HttpsError('not-found', 'Reminder not found.');
    tx.update(view.ref, { priorityOverride: value, priority: value ?? view.get('sharedPriority') ?? false });
  });
  return { saved: true };
});
