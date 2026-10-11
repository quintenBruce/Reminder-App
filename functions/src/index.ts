import { createHash, randomUUID } from 'node:crypto';
import { initializeApp } from 'firebase-admin/app';
import { getFirestore, FieldValue } from 'firebase-admin/firestore';
import { getMessaging } from 'firebase-admin/messaging';
import { HttpsError, onCall, type CallableRequest } from 'firebase-functions/v2/https';
import { onSchedule } from 'firebase-functions/v2/scheduler';
import { defineSecret } from 'firebase-functions/params';
import OpenAI, { toFile } from 'openai';
import { nextOccurrence, previewOccurrences, validateNudge, validateRule, type ReminderRecord, type ScheduleRule } from '../../shared/src/schedule';
import { afterDelivery, applyReminderAction } from '../../shared/src/reminderState';

initializeApp();
const db = getFirestore();
const openAiKey = defineSecret('OPENAI_API_KEY');
const region = process.env.FUNCTIONS_REGION || 'us-south1';
const model = process.env.OPENAI_MODEL || 'gpt-6.1-sol';

type Action = 'create' | 'edit' | 'cancel' | 'done' | 'dismiss' | 'snooze' | 'list' | 'clarify';
type ModelDecision = {
  action: Action; targetId: string | null; title: string | null; notes: string | null;
  kind: ScheduleRule['kind'] | null; at: string | null; startAt: string | null;
  everyMinutes: number | null; timeZone: string | null; hour: number | null;
  minute: number | null; daysOfWeek: number[]; dayOfMonth: number | null;
  month: number | null; endAt: string | null; repeatUntilDoneMinutes: number | null;
  snoozeMinutes: number | null; clearRepeatUntilDone: boolean; message: string;
};
type Proposal = {
  id: string; conversationId: string; action: Action; targetId: string | null; baseVersion: number | null;
  originalWording: string;
  title: string | null; notes: string | null; rule: ScheduleRule | null;
  repeatUntilDoneMinutes: number | null; snoozeMinutes: number | null;
  readback: string; preview: number[]; createdAt: number; expiresAt: number;
  committedResult?: unknown;
};

const decisionSchema = {
  type: 'object', additionalProperties: false,
  properties: {
    action: { type: 'string', enum: ['create', 'edit', 'cancel', 'done', 'dismiss', 'snooze', 'list', 'clarify'] },
    targetId: { type: ['string', 'null'] }, title: { type: ['string', 'null'] }, notes: { type: ['string', 'null'] },
    kind: { type: ['string', 'null'], enum: ['once', 'interval', 'daily', 'weekly', 'monthly', 'yearly', null] },
    at: { type: ['string', 'null'] }, startAt: { type: ['string', 'null'] },
    everyMinutes: { type: ['integer', 'null'] }, timeZone: { type: ['string', 'null'] },
    hour: { type: ['integer', 'null'] }, minute: { type: ['integer', 'null'] },
    daysOfWeek: { type: 'array', items: { type: 'integer' } }, dayOfMonth: { type: ['integer', 'null'] },
    month: { type: ['integer', 'null'] }, endAt: { type: ['string', 'null'] },
    repeatUntilDoneMinutes: { type: ['integer', 'null'] }, snoozeMinutes: { type: ['integer', 'null'] },
    clearRepeatUntilDone: { type: 'boolean' }, message: { type: 'string' },
  },
  required: ['action', 'targetId', 'title', 'notes', 'kind', 'at', 'startAt', 'everyMinutes', 'timeZone',
    'hour', 'minute', 'daysOfWeek', 'dayOfMonth', 'month', 'endAt', 'repeatUntilDoneMinutes', 'snoozeMinutes', 'clearRepeatUntilDone', 'message'],
} as const;

function requireGoogleUser(request: CallableRequest): string {
  const uid = request.auth?.uid;
  const provider = request.auth?.token.firebase?.sign_in_provider;
  const verified = request.auth?.token.email_verified;
  if (!uid || provider !== 'google.com' || verified !== true) {
    throw new HttpsError('permission-denied', 'Sign in with a verified Google account.');
  }
  return uid;
}

function root(uid: string) { return db.collection('users').doc(uid); }
function textValue(value: unknown, max = 1000): string {
  if (typeof value !== 'string' || !value.trim() || value.length > max) {
    throw new HttpsError('invalid-argument', 'Text is empty or too long.');
  }
  return value.trim();
}
function stringValue(value: unknown, max = 100): string {
  return typeof value === 'string' && value.length <= max ? value : '';
}
function record(data: FirebaseFirestore.DocumentData, id: string): ReminderRecord {
  return { ...data, id } as ReminderRecord;
}
function checkedRule(d: ModelDecision, current?: ReminderRecord, pending?: Proposal | null): ScheduleRule {
  if (!d.kind) {
    if (current) return current.rule;
    if (pending?.rule) return pending.rule;
    throw new HttpsError('invalid-argument', 'A schedule is required.');
  }
  let rule: ScheduleRule;
  if (d.kind === 'once') {
    rule = { kind: 'once', at: textValue(d.at, 50) };
  } else if (d.kind === 'interval') {
    rule = { kind: 'interval', startAt: textValue(d.startAt, 50), everyMinutes: d.everyMinutes ?? 0,
      ...(d.endAt ? { endAt: d.endAt } : {}) };
  } else {
    rule = { kind: d.kind, startAt: textValue(d.startAt, 50), timeZone: textValue(d.timeZone, 80),
      hour: d.hour ?? -1, minute: d.minute ?? -1,
      ...(d.kind === 'weekly' ? { daysOfWeek: d.daysOfWeek } : {}),
      ...(d.kind === 'monthly' || d.kind === 'yearly' ? { dayOfMonth: d.dayOfMonth ?? 0 } : {}),
      ...(d.kind === 'yearly' ? { month: d.month ?? 0 } : {}),
      ...(d.endAt ? { endAt: d.endAt } : {}) };
  }
  try { validateRule(rule); } catch (error) {
    throw new HttpsError('invalid-argument', error instanceof Error ? error.message : 'Invalid schedule.');
  }
  return rule;
}

function summarize(rule: ScheduleRule, dates: number[], deviceZone: string): string {
  const first = dates[0] ? new Date(dates[0]).toLocaleString('en-US', { timeZone: 'timeZone' in rule ? rule.timeZone : deviceZone, dateStyle: 'medium', timeStyle: 'short' }) : 'no future date';
  if (rule.kind === 'once') return `once, ${first}`;
  const ending = rule.endAt ? `, ending ${new Date(rule.endAt).toLocaleString('en-US', { timeZone: 'timeZone' in rule ? rule.timeZone : deviceZone, dateStyle: 'medium', timeStyle: 'short' })}` : '';
  if (rule.kind === 'interval') return `every ${rule.everyMinutes} minutes, starting ${first}${ending}`;
  const localTime = `${String(rule.hour).padStart(2, '0')}:${String(rule.minute).padStart(2, '0')}`;
  const weekdays = ['Monday', 'Tuesday', 'Wednesday', 'Thursday', 'Friday', 'Saturday', 'Sunday'];
  const frequency = rule.kind === 'weekly' ? `weekly on ${rule.daysOfWeek!.map(day => weekdays[day - 1]).join(', ')}`
    : rule.kind === 'monthly' ? `monthly on day ${rule.dayOfMonth} (or the last day of shorter months)`
      : rule.kind === 'yearly' ? `yearly on month ${rule.month}, day ${rule.dayOfMonth} (or the last day of shorter months)` : 'daily';
  return `${frequency} at ${localTime} in ${rule.timeZone}, starting ${first}${ending}`;
}

function openai() { return new OpenAI({ apiKey: openAiKey.value() }); }

async function enforceQuota(uid: string, kind: 'transcribe' | 'interpret') {
  const day = new Date().toISOString().slice(0, 10);
  const ref = root(uid).collection('quotas').doc(`${day}_${kind}`);
  const globalRef = db.collection('config').doc(`quota_${day}_${kind}`);
  await db.runTransaction(async tx => {
    const [snap, globalSnap] = await Promise.all([tx.get(ref), tx.get(globalRef)]);
    const count = snap.exists ? Number(snap.get('count') || 0) : 0;
    const globalCount = globalSnap.exists ? Number(globalSnap.get('count') || 0) : 0;
    if (count >= 100) throw new HttpsError('resource-exhausted', 'Daily voice and interpretation limit reached.');
    if (globalCount >= 30) throw new HttpsError('resource-exhausted', 'The app-wide daily AI limit was reached. Try again tomorrow.');
    tx.set(ref, { count: count + 1, updatedAt: Date.now() });
    tx.set(globalRef, { count: globalCount + 1, updatedAt: Date.now() });
  });
}

export const transcribeAudio = onCall({ region, secrets: [openAiKey], maxInstances: 2, memory: '512MiB', enforceAppCheck: true }, async request => {
  const uid = requireGoogleUser(request);
  const { audioBase64, mimeType } = request.data ?? {};
  if (typeof audioBase64 !== 'string' || audioBase64.length > 7_000_000 ||
      typeof mimeType !== 'string' || !['audio/webm', 'audio/mp4', 'audio/mpeg', 'audio/wav'].includes(mimeType)) {
    throw new HttpsError('invalid-argument', 'Unsupported or oversized recording.');
  }
  const bytes = Buffer.from(audioBase64, 'base64');
  if (!bytes.length || bytes.length > 5_000_000) throw new HttpsError('invalid-argument', 'Recording exceeds 5 MB.');
  await enforceQuota(uid, 'transcribe');
  const extension = mimeType.split('/')[1];
  const file = await toFile(bytes, `recording.${extension}`, { type: mimeType });
  const result = await openai().audio.transcriptions.create({ model: 'gpt-transcribe', file });
  return { transcript: textValue(result.text, 10_000) };
});

export const proposeAction = onCall({ region, secrets: [openAiKey], maxInstances: 2, enforceAppCheck: true }, async request => {
  const uid = requireGoogleUser(request);
  const utterance = textValue(request.data?.text, 4000);
  const conversationId = stringValue(request.data?.conversationId, 100) || randomUUID();
  const zone = stringValue(request.data?.timeZone, 80) || 'America/Chicago';
  const pendingProposalId = stringValue(request.data?.pendingProposalId, 100);
  try { Intl.DateTimeFormat('en', { timeZone: zone }); } catch { throw new HttpsError('invalid-argument', 'Invalid time zone.'); }
  const remindersRef = root(uid).collection('reminders');
  const [candidatesSnap, turnsSnap, pendingSnap] = await Promise.all([
    remindersRef.where('status', '==', 'active').limit(40).get(),
    root(uid).collection('conversations').doc(conversationId).collection('turns').orderBy('createdAt', 'desc').limit(8).get(),
    pendingProposalId ? root(uid).collection('proposals').doc(pendingProposalId).get() : Promise.resolve(null),
  ]);
  const pending = pendingSnap?.exists && pendingSnap.get('conversationId') === conversationId && pendingSnap.get('expiresAt') > Date.now()
    ? pendingSnap.data() as Proposal : null;
  const candidates = candidatesSnap.docs.map(doc => record(doc.data(), doc.id));
  const history = turnsSnap.docs.reverse().map(doc => ({ user: doc.get('user'), assistant: doc.get('assistant') }));
  await enforceQuota(uid, 'interpret');
  const response = await openai().responses.create({
    model, store: false, reasoning: { effort: 'low' },
    max_output_tokens: 2000,
    text: { format: { type: 'json_schema', name: 'reminder_decision', strict: true, schema: decisionSchema } },
    instructions: `You interpret personal reminder requests. Current instant: ${new Date().toISOString()}. Device IANA zone: ${zone}. Resolve references only to candidate IDs. If ambiguous or a time is missing, clarify. For create/edit return a complete schedule using an ISO offset instant in at/startAt. For a wall-clock recurrence choose an IANA timeZone and local hour/minute. Weekly days use 1=Monday through 7=Sunday. A request like "every 5 minutes until done" is a one-time occurrence with repeatUntilDoneMinutes=5, not a perpetual interval rule. For an edit, provide the entire replacement schedule when changing timing; otherwise kind=null preserves it. Use clearRepeatUntilDone=true only if the user removes those nudges. If the user revises a pending creation, return action=create with full revised fields. Keep responses short. Never invent a target ID.`,
    input: JSON.stringify({ utterance, history, pendingProposal: pending ? { action: pending.action, targetId: pending.targetId, title: pending.title, notes: pending.notes, rule: pending.rule, repeatUntilDoneMinutes: pending.repeatUntilDoneMinutes, snoozeMinutes: pending.snoozeMinutes } : null,
      candidates: candidates.map(r => ({ id: r.id, title: r.title, rule: r.rule, repeatUntilDoneMinutes: r.repeatUntilDoneMinutes, nextDueAt: r.nextDueAt })) }),
  });
  if (!response.output_text) throw new HttpsError('internal', 'No interpretation returned.');
  const d = JSON.parse(response.output_text) as ModelDecision;
  const candidate = candidates.find(r => r.id === d.targetId);
  if (['edit', 'cancel', 'done', 'dismiss', 'snooze'].includes(d.action) && !candidate) {
    d.action = 'clarify'; d.message = 'Which reminder do you mean?';
  }
  const now = Date.now();
  const turns = root(uid).collection('conversations').doc(conversationId).collection('turns');
  if (d.action === 'list' || d.action === 'clarify') {
    const reply = d.action === 'list' ? (candidates.length ? candidates.slice(0, 8).map(r => r.title).join('; ') : 'You have no active reminders.') : textValue(d.message, 500);
    await turns.add({ user: utterance, assistant: reply, createdAt: now });
    return { conversationId, reply, proposal: null };
  }
  let rule: ScheduleRule | null = null;
  let preview: number[] = [];
  const nudgeMinutes = d.clearRepeatUntilDone ? null : (d.repeatUntilDoneMinutes ?? candidate?.repeatUntilDoneMinutes ?? pending?.repeatUntilDoneMinutes ?? null);
  if (d.action === 'create' || d.action === 'edit') {
    rule = checkedRule(d, candidate, pending);
    validateNudge(nudgeMinutes);
    preview = previewOccurrences(rule, now);
    if (!preview.length) throw new HttpsError('invalid-argument', 'This schedule has no future occurrence.');
  }
  if (d.action === 'snooze' && (!Number.isInteger(d.snoozeMinutes) || (d.snoozeMinutes ?? 0) < 5 || (d.snoozeMinutes ?? 0) > 525_600)) {
    throw new HttpsError('invalid-argument', 'Snooze must be at least five minutes.');
  }
  const title = d.action === 'create' ? textValue(d.title || pending?.title, 200) : (d.title ? textValue(d.title, 200) : candidate?.title || null);
  const notes = d.notes === null ? candidate?.notes ?? pending?.notes ?? '' : stringValue(d.notes, 1000);
  const readback = d.action === 'create' || d.action === 'edit'
    ? `${d.action === 'create' ? 'Create' : 'Change'} “${title}”${notes ? ` (${notes})` : ''}: ${summarize(rule!, preview, zone)}${nudgeMinutes ? `; repeat every ${nudgeMinutes} minutes until Done` : ''}. Confirm?`
    : `${d.action === 'snooze' ? `Snooze “${candidate!.title}” for ${d.snoozeMinutes} minutes` : `${d.action[0].toUpperCase()}${d.action.slice(1)} “${candidate!.title}”`}. Confirm?`;
  const proposal: Proposal = {
    id: randomUUID(), conversationId, action: d.action, targetId: candidate?.id ?? null, baseVersion: candidate?.version ?? null,
    originalWording: utterance,
    title, notes, rule, repeatUntilDoneMinutes: nudgeMinutes,
    snoozeMinutes: d.snoozeMinutes, readback, preview, createdAt: now, expiresAt: now + 10 * 60_000,
  };
  await Promise.all([
    root(uid).collection('proposals').doc(proposal.id).set(proposal),
    turns.add({ user: utterance, assistant: readback, proposalId: proposal.id, createdAt: now }),
  ]);
  return { conversationId, reply: readback, proposal };
});

async function applyAction(uid: string, reminderId: string, action: 'done' | 'dismiss' | 'snooze', snoozeMinutes: number | null, requestId: string, expectedVersion?: number | null, occurrenceId?: string) {
  const ref = root(uid).collection('reminders').doc(reminderId);
  const idempotency = root(uid).collection('actions').doc(requestId);
  const now = Date.now();
  return db.runTransaction(async tx => {
    const [prior, snap] = await Promise.all([tx.get(idempotency), tx.get(ref)]);
    if (prior.exists) return prior.data()?.result;
    if (!snap.exists) throw new HttpsError('not-found', 'Reminder not found.');
    const r = record(snap.data()!, snap.id);
    if (r.status !== 'active') throw new HttpsError('failed-precondition', 'Reminder is canceled.');
    if (expectedVersion != null && r.version !== expectedVersion) throw new HttpsError('failed-precondition', 'Reminder changed. Please ask again.');
    let update: Partial<ReminderRecord>;
    try { update = applyReminderAction(r, action, now, snoozeMinutes, occurrenceId); }
    catch (error) { throw new HttpsError('failed-precondition', error instanceof Error ? error.message : 'Invalid occurrence action.'); }
    tx.update(ref, action === 'done' ? { ...update, dismissedAt: FieldValue.delete() } : update);
    const result = { reminderId, action, at: now };
    tx.create(idempotency, { result, createdAt: now });
    const eventId = randomUUID();
    tx.create(root(uid).collection('events').doc(eventId), { reminderId, action, at: now });
    return result;
  });
}

export const applyOccurrenceAction = onCall({ region, enforceAppCheck: true }, async request => {
  const uid = requireGoogleUser(request);
  const reminderId = textValue(request.data?.reminderId, 100);
  const action = request.data?.action;
  if (!['done', 'dismiss', 'snooze'].includes(action)) throw new HttpsError('invalid-argument', 'Invalid action.');
  const requestId = textValue(request.data?.requestId, 100);
  return applyAction(uid, reminderId, action, request.data?.snoozeMinutes ?? null, requestId, null, stringValue(request.data?.occurrenceId, 150) || undefined);
});

export const confirmProposal = onCall({ region, enforceAppCheck: true }, async request => {
  const uid = requireGoogleUser(request);
  const proposalId = textValue(request.data?.proposalId, 100);
  const ref = root(uid).collection('proposals').doc(proposalId);
  const snap = await ref.get();
  if (!snap.exists) throw new HttpsError('not-found', 'Proposal not found.');
  const p = snap.data() as Proposal;
  if (p.committedResult) return p.committedResult;
  if (Date.now() > p.expiresAt) throw new HttpsError('failed-precondition', 'Proposal expired. Please ask again.');
  if (['done', 'dismiss', 'snooze'].includes(p.action)) {
    const result = await applyAction(uid, p.targetId!, p.action as 'done' | 'dismiss' | 'snooze', p.snoozeMinutes, proposalId, p.baseVersion);
    await ref.update({ committedResult: result });
    return result;
  }
  const target = p.targetId ? root(uid).collection('reminders').doc(p.targetId) : root(uid).collection('reminders').doc();
  const now = Date.now();
  return db.runTransaction(async tx => {
    const [freshProposal, prior] = await Promise.all([tx.get(ref), p.targetId ? tx.get(target) : Promise.resolve(null)]);
    const current = freshProposal.data() as Proposal;
    if (current.committedResult) return current.committedResult;
    if (now > current.expiresAt) throw new HttpsError('failed-precondition', 'Proposal expired.');
    const existing = prior?.exists ? record(prior.data()!, prior.id) : null;
    if (p.targetId && (!existing || existing.version !== p.baseVersion)) {
      throw new HttpsError('failed-precondition', 'Reminder changed. Please ask again.');
    }
    if (p.action === 'create' || p.action === 'edit') {
      if (!p.rule || !p.title) throw new HttpsError('internal', 'Incomplete proposal.');
      const nextDueAt = nextOccurrence(p.rule, now);
      if (!nextDueAt) throw new HttpsError('failed-precondition', 'The proposed time has passed.');
      const next: ReminderRecord = {
        id: target.id, title: p.title, notes: p.notes ?? '', originalWording: p.originalWording,
        rule: p.rule, repeatUntilDoneMinutes: p.repeatUntilDoneMinutes,
        status: 'active', nextDueAt, currentScheduledAt: null,
        version: (existing?.version ?? 0) + 1, createdAt: existing?.createdAt ?? now, updatedAt: now,
      };
      tx.set(target, next);
    } else if (p.action === 'cancel') {
      tx.update(target, { status: 'canceled', nextDueAt: null, currentScheduledAt: null, leaseUntil: 0, version: existing!.version + 1, updatedAt: now });
    } else throw new HttpsError('invalid-argument', 'Unsupported proposal.');
    const result = { reminderId: target.id, action: p.action, at: now };
    tx.update(ref, { committedResult: result });
    tx.create(root(uid).collection('events').doc(randomUUID()), { reminderId: target.id, action: p.action, at: now });
    return result;
  });
});

export const registerDevice = onCall({ region, enforceAppCheck: true }, async request => {
  const uid = requireGoogleUser(request);
  const token = textValue(request.data?.token, 4096);
  const id = createHash('sha256').update(token).digest('hex');
  const ownerRef = db.collection('deviceTokens').doc(id);
  await db.runTransaction(async tx => {
    const previous = await tx.get(ownerRef);
    const previousUid = previous.get('uid');
    if (typeof previousUid === 'string' && previousUid !== uid) {
      tx.delete(root(previousUid).collection('devices').doc(id));
    }
    tx.set(ownerRef, { uid, updatedAt: Date.now() });
    tx.set(root(uid).collection('devices').doc(id), { token, updatedAt: Date.now() });
  });
  await getMessaging().send({ token, data: { title: 'Remind is ready', body: 'Notifications are connected.', reminderId: 'test', url: '/' } });
  return { registered: true };
});

export const unregisterDevice = onCall({ region, enforceAppCheck: true }, async request => {
  const uid = requireGoogleUser(request);
  const token = textValue(request.data?.token, 4096);
  const id = createHash('sha256').update(token).digest('hex');
  const ownerRef = db.collection('deviceTokens').doc(id);
  await db.runTransaction(async tx => {
    const owner = await tx.get(ownerRef);
    if (owner.get('uid') === uid) tx.delete(ownerRef);
    tx.delete(root(uid).collection('devices').doc(id));
  });
  return { unregistered: true };
});

export const deleteConversation = onCall({ region, enforceAppCheck: true }, async request => {
  const uid = requireGoogleUser(request);
  const conversationId = textValue(request.data?.conversationId, 100);
  const ref = root(uid).collection('conversations').doc(conversationId);
  while (true) {
    const page = await ref.collection('turns').limit(400).get();
    if (page.empty) break;
    const batch = db.batch();
    page.docs.forEach(doc => batch.delete(doc.ref));
    await batch.commit();
  }
  await ref.delete();
  return { deleted: true };
});

export const dispatchDue = onSchedule({ schedule: '* * * * *', timeZone: 'UTC', region, maxInstances: 1 }, async () => {
  const now = Date.now();
  const due = await db.collectionGroup('reminders').where('status', '==', 'active').where('nextDueAt', '<=', now).orderBy('nextDueAt').limit(50).get();
  for (const doc of due.docs) {
    const uid = doc.ref.parent.parent?.id;
    if (!uid || doc.ref.parent.parent?.parent?.id !== 'users') continue;
    const claimed = await db.runTransaction(async tx => {
      const snap = await tx.get(doc.ref);
      if (!snap.exists) return null;
      const r = record(snap.data()!, snap.id);
      if (r.status !== 'active' || r.nextDueAt === null || r.nextDueAt > now || (r.leaseUntil ?? 0) > now) return null;
      tx.update(doc.ref, { leaseUntil: now + 90_000 });
      return r;
    });
    if (!claimed) continue;
    const scheduledAt = claimed.currentScheduledAt ?? claimed.nextDueAt!;
    const devices = await root(uid).collection('devices').get();
    const tokens = devices.docs.map(d => d.get('token') as string).filter(Boolean);
    let delivered = false;
    if (tokens.length) {
      const results = await getMessaging().sendEachForMulticast({
        tokens: tokens.slice(0, 500),
        data: { title: claimed.title, body: claimed.notes || claimed.title, reminderId: claimed.id,
          url: `/?reminder=${encodeURIComponent(claimed.id)}`, occurrenceId: `${claimed.id}_${scheduledAt}` },
        webpush: { headers: { TTL: '3600' } },
      });
      delivered = results.successCount > 0;
      const invalid = results.responses.map((r, i) => !r.success && /registration-token-not-registered|invalid-registration-token/.test(r.error?.code ?? '') ? devices.docs[i]?.ref : null).filter(Boolean);
      await Promise.all(invalid.map(ref => ref!.delete()));
    }
    const finishAt = Date.now();
    await db.runTransaction(async tx => {
      const snap = await tx.get(doc.ref);
      if (!snap.exists || snap.get('leaseUntil') !== now + 90_000 || snap.get('version') !== claimed.version) return;
      const r = record(snap.data()!, snap.id);
      const transition = afterDelivery(r, scheduledAt, finishAt, delivered);
      tx.update(doc.ref, transition.update);
      tx.set(root(uid).collection('occurrences').doc(`${r.id}_${scheduledAt}`), {
        reminderId: r.id, scheduledAt, lastAttemptAt: finishAt,
        status: transition.occurrenceStatus, attempts: FieldValue.increment(1),
      }, { merge: true });
    });
  }
});
