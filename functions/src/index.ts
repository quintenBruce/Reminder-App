import { createHash, randomUUID } from 'node:crypto';
import { initializeApp } from 'firebase-admin/app';
import { getFirestore, FieldValue } from 'firebase-admin/firestore';
import { getMessaging } from 'firebase-admin/messaging';
import { HttpsError, onCall, type CallableRequest } from 'firebase-functions/v2/https';
import { onSchedule } from 'firebase-functions/v2/scheduler';
import { defineSecret } from 'firebase-functions/params';
import { info, warn } from 'firebase-functions/logger';
import OpenAI, { toFile } from 'openai';
import { nextOccurrence, previewOccurrences, validateNudge, validateRule, type ReminderRecord, type ScheduleRule } from '../../shared/src/schedule';
import { afterDelivery, applyReminderAction, canPersistDelivery, hasCurrentAlert } from '../../shared/src/reminderState';
import { deliveryDiagnostic } from './deliveryDiagnostics';
import { errorFields, observe } from './observability';
import { purgeLinkedConversations, purgeRelatedRecords, type RelatedRecordKind } from './reminderDeletion';
import { reminderPushData } from './notificationPayload';
import { canConfirmDraft, canDiscardDraft, purgeConversationProposals, purgeLegacyConversations } from './conversationCleanup';

initializeApp();
const db = getFirestore();
const openAiKey = defineSecret('OPENAI_API_KEY');
const region = process.env.FUNCTIONS_REGION || 'us-south1';
const model = process.env.OPENAI_MODEL || 'gpt-6.1-sol';

type Action = 'create' | 'edit' | 'cancel' | 'delete' | 'done' | 'dismiss' | 'snooze' | 'list' | 'clarify';
type ModelDecision = {
  action: Action; targetId: string | null; title: string | null; notes: string | null;
  kind: ScheduleRule['kind'] | null; at: string | null; startAt: string | null;
  everyMinutes: number | null; timeZone: string | null; hour: number | null;
  minute: number | null; daysOfWeek: number[]; dayOfMonth: number | null;
  weekOfMonth: number | null; startDayOfMonth: number | null;
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
    action: { type: 'string', enum: ['create', 'edit', 'cancel', 'delete', 'done', 'snooze', 'list', 'clarify'] },
    targetId: { type: ['string', 'null'] }, title: { type: ['string', 'null'] }, notes: { type: ['string', 'null'] },
    kind: { type: ['string', 'null'], enum: ['once', 'interval', 'daily', 'weekly', 'monthly', 'monthly_weekday', 'monthly_weekly', 'yearly', null] },
    at: { type: ['string', 'null'] }, startAt: { type: ['string', 'null'] },
    everyMinutes: { type: ['integer', 'null'] }, timeZone: { type: ['string', 'null'] },
    hour: { type: ['integer', 'null'] }, minute: { type: ['integer', 'null'] },
    daysOfWeek: { type: 'array', items: { type: 'integer' } }, dayOfMonth: { type: ['integer', 'null'] },
    weekOfMonth: { type: ['integer', 'null'] }, startDayOfMonth: { type: ['integer', 'null'] },
    month: { type: ['integer', 'null'] }, endAt: { type: ['string', 'null'] },
    repeatUntilDoneMinutes: { type: ['integer', 'null'] }, snoozeMinutes: { type: ['integer', 'null'] },
    clearRepeatUntilDone: { type: 'boolean' }, message: { type: 'string' },
  },
  required: ['action', 'targetId', 'title', 'notes', 'kind', 'at', 'startAt', 'everyMinutes', 'timeZone',
    'hour', 'minute', 'daysOfWeek', 'dayOfMonth', 'weekOfMonth', 'startDayOfMonth', 'month', 'endAt', 'repeatUntilDoneMinutes', 'snoozeMinutes', 'clearRepeatUntilDone', 'message'],
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
function conversationRef(uid: string, conversationId: string) { return root(uid).collection('conversations').doc(conversationId); }
async function deleteConversationTree(uid: string, conversationId: string) {
  await purgeConversationProposals(uid, conversationId,
    async (accountId, id, limit) => (await root(accountId).collection('proposals')
      .where('conversationId', '==', id).limit(limit).get()).docs.map(doc => doc.ref),
    async refs => {
      const batch = db.batch();
      refs.forEach(ref => batch.delete(ref));
      await batch.commit();
    });
  await db.recursiveDelete(conversationRef(uid, conversationId));
}
async function claimDraftForDeletion(uid: string, conversationId: string): Promise<boolean> {
  const ref = conversationRef(uid, conversationId);
  return db.runTransaction(async tx => {
    const snap = await tx.get(ref);
    if (!canDiscardDraft(snap.get('state'), snap.exists)) return false;
    if (snap.exists && snap.get('state') === 'draft') tx.update(ref, { state: 'deleting', updatedAt: Date.now() });
    return true;
  });
}
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
      ...(['weekly', 'monthly_weekday', 'monthly_weekly'].includes(d.kind) ? { daysOfWeek: d.daysOfWeek } : {}),
      ...(d.kind === 'monthly' || d.kind === 'yearly' ? { dayOfMonth: d.dayOfMonth ?? 0 } : {}),
      ...(d.kind === 'monthly_weekday' ? { weekOfMonth: d.weekOfMonth ?? 0 } : {}),
      ...(d.kind === 'monthly_weekly' ? { startDayOfMonth: d.startDayOfMonth ?? 0 } : {}),
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
      : rule.kind === 'monthly_weekday' ? `monthly on the ${rule.weekOfMonth === -1 ? 'last' : `${rule.weekOfMonth}${['st', 'nd', 'rd'][rule.weekOfMonth! - 1] ?? 'th'}`} ${weekdays[rule.daysOfWeek![0] - 1]}`
        : rule.kind === 'monthly_weekly' ? `every ${rule.daysOfWeek!.map(day => weekdays[day - 1]).join(', ')} on or after day ${rule.startDayOfMonth} of each month`
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

export const transcribeAudio = onCall({ region, secrets: [openAiKey], maxInstances: 2, memory: '512MiB', enforceAppCheck: true }, request => observe('transcribeAudio', request.auth?.uid, async trace => {
  const uid = requireGoogleUser(request);
  const { audioBase64, mimeType } = request.data ?? {};
  const mediaType = typeof mimeType === 'string' ? mimeType.split(';')[0].trim().toLowerCase() : '';
  if (typeof audioBase64 !== 'string' || audioBase64.length > 7_000_000 ||
      typeof mimeType !== 'string' || !['audio/webm', 'audio/ogg', 'audio/mp4', 'audio/mpeg', 'audio/wav'].includes(mediaType)) {
    throw new HttpsError('invalid-argument', 'Unsupported or oversized recording.');
  }
  const bytes = Buffer.from(audioBase64, 'base64');
  if (!bytes.length || bytes.length > 5_000_000) throw new HttpsError('invalid-argument', 'Recording exceeds 5 MB.');
  trace.stage('audio.validated', { mediaType, audioBytes: bytes.length });
  await enforceQuota(uid, 'transcribe');
  trace.stage('quota.accepted');
  const extension = mediaType.split('/')[1];
  const file = await toFile(bytes, `recording.${extension}`, { type: mediaType });
  const aiStartedAt = Date.now();
  const result = await openai().audio.transcriptions.create({ model: 'gpt-transcribe', file });
  trace.stage('transcription.received', { aiDurationMs: Date.now() - aiStartedAt });
  const transcript = typeof result.text === 'string' ? result.text.trim() : '';
  if (!transcript) throw new HttpsError('failed-precondition', 'No speech was detected. Please try again and speak for a few seconds.');
  if (transcript.length > 4000) throw new HttpsError('invalid-argument', 'Speech was too long. Please try a shorter message.');
  trace.stage('transcript.validated', { transcriptLength: transcript.length });
  return { transcript };
}));

export const proposeAction = onCall({ region, secrets: [openAiKey], maxInstances: 2, enforceAppCheck: true }, request => observe('proposeAction', request.auth?.uid, async trace => {
  const uid = requireGoogleUser(request);
  const utterance = textValue(request.data?.text, 4000);
  const conversationId = stringValue(request.data?.conversationId, 100) || randomUUID();
  const zone = stringValue(request.data?.timeZone, 80) || 'America/Chicago';
  const pendingProposalId = stringValue(request.data?.pendingProposalId, 100);
  try { Intl.DateTimeFormat('en', { timeZone: zone }); } catch { throw new HttpsError('invalid-argument', 'Invalid time zone.'); }
  const remindersRef = root(uid).collection('reminders');
  const [candidatesSnap, turnsSnap, pendingSnap] = await Promise.all([
    Promise.all([remindersRef.where('status', '==', 'active').limit(40).get(),
      remindersRef.where('status', 'in', ['completed', 'canceled']).limit(20).get()]),
    conversationRef(uid, conversationId).collection('turns').orderBy('createdAt', 'desc').limit(8).get(),
    pendingProposalId ? root(uid).collection('proposals').doc(pendingProposalId).get() : Promise.resolve(null),
  ]);
  const pending = pendingSnap?.exists && pendingSnap.get('conversationId') === conversationId && pendingSnap.get('expiresAt') > Date.now()
    ? pendingSnap.data() as Proposal : null;
  const candidates = candidatesSnap.flatMap(snapshot => snapshot.docs.map(doc => record(doc.data(), doc.id)))
    .filter(r => !r.deletionRequestedAt);
  const history = turnsSnap.docs.reverse().map(doc => ({ user: doc.get('user'), assistant: doc.get('assistant') }));
  trace.stage('context.loaded', { conversationId, candidateCount: candidates.length, historyCount: history.length, hasPendingProposal: !!pending });
  await enforceQuota(uid, 'interpret');
  trace.stage('quota.accepted');
  const aiStartedAt = Date.now();
  const response = await openai().responses.create({
    model, store: false, reasoning: { effort: 'low' },
    max_output_tokens: 2000,
    text: { format: { type: 'json_schema', name: 'reminder_decision', strict: true, schema: decisionSchema } },
    instructions: `You interpret personal reminder requests. Current instant: ${new Date().toISOString()}. Device IANA zone: ${zone}. Resolve references only to candidate IDs. If ambiguous or a time is missing, clarify. For create/edit return a complete schedule using an ISO offset instant in at/startAt. For a wall-clock recurrence choose an IANA timeZone and local hour/minute. Weekdays use 1=Monday through 7=Sunday. "Every 3rd Sunday of the month" uses kind=monthly_weekday, daysOfWeek=[7], weekOfMonth=3. For the last weekday of a month use weekOfMonth=-1; a fifth weekday occurs only in months that have one. "Starting the 10th of every month, every Friday" uses kind=monthly_weekly, daysOfWeek=[5], startDayOfMonth=10; the cutoff resets each month. Use startAt for the earliest allowed occurrence, not for that repeating monthly cutoff. A request like "every 5 minutes until done" is a one-time occurrence with repeatUntilDoneMinutes=5, not a perpetual interval rule. For an edit, provide the entire replacement schedule when changing timing; otherwise kind=null preserves it. Use clearRepeatUntilDone=true only if the user removes those nudges. If the user revises a pending creation, return action=create with full revised fields. Use delete for delete/remove/erase requests: it permanently erases the reminder and its related records after confirmation. Cancel only stops scheduling and retains history. Never offer dismiss. Keep responses short. Never invent a target ID.`,
    input: JSON.stringify({ utterance, history, pendingProposal: pending ? { action: pending.action, targetId: pending.targetId, title: pending.title, notes: pending.notes, rule: pending.rule, repeatUntilDoneMinutes: pending.repeatUntilDoneMinutes, snoozeMinutes: pending.snoozeMinutes } : null,
      candidates: candidates.map(r => ({ id: r.id, title: r.title, status: r.status, rule: r.rule, repeatUntilDoneMinutes: r.repeatUntilDoneMinutes, nextDueAt: r.nextDueAt })) }),
  });
  trace.stage('interpretation.received', { aiDurationMs: Date.now() - aiStartedAt, outputLength: response.output_text?.length ?? 0 });
  if (!response.output_text) throw new HttpsError('internal', 'No interpretation returned.');
  const d = JSON.parse(response.output_text) as ModelDecision;
  const candidate = candidates.find(r => r.id === d.targetId);
  if (['edit', 'cancel', 'delete', 'done', 'snooze'].includes(d.action) && !candidate) {
    d.action = 'clarify'; d.message = 'Which reminder do you mean?';
  }
  if (candidate?.status !== 'active' && ['edit', 'cancel', 'done', 'snooze'].includes(d.action)) {
    d.action = 'clarify'; d.message = 'That reminder is no longer active. You can delete it from History.';
  }
  if (candidate && ['done', 'snooze'].includes(d.action) && !hasCurrentAlert(candidate)) {
    d.action = 'clarify'; d.message = 'That reminder has no sent alert to act on yet.';
  }
  const now = Date.now();
  const conversation = conversationRef(uid, conversationId);
  const turns = conversation.collection('turns');
  const draftMetadata = { schemaVersion: 2, state: 'draft', updatedAt: now };
  if (d.action === 'list' || d.action === 'clarify') {
    const reply = d.action === 'list' ? (candidates.length ? candidates.slice(0, 8).map(r => r.title).join('; ') : 'You have no active reminders.') : textValue(d.message, 500);
    await Promise.all([conversation.set(draftMetadata, { merge: true }),
      turns.add({ user: utterance, assistant: reply, createdAt: now })]);
    trace.stage('conversation.saved', { conversationId, action: d.action });
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
    : d.action === 'delete' ? `Permanently delete “${candidate!.title}” and its reminder records? This cannot be undone. Confirm?`
      : `${d.action === 'snooze' ? `Snooze “${candidate!.title}” for ${d.snoozeMinutes} minutes` : `${d.action[0].toUpperCase()}${d.action.slice(1)} “${candidate!.title}”`}. Confirm?`;
  const proposal: Proposal = {
    id: randomUUID(), conversationId, action: d.action, targetId: candidate?.id ?? null, baseVersion: candidate?.version ?? null,
    originalWording: utterance,
    title, notes, rule, repeatUntilDoneMinutes: nudgeMinutes,
    snoozeMinutes: d.snoozeMinutes, readback, preview, createdAt: now, expiresAt: now + 10 * 60_000,
  };
  await Promise.all([
    conversation.set(draftMetadata, { merge: true }),
    root(uid).collection('proposals').doc(proposal.id).set(proposal),
    turns.add({ user: utterance, assistant: readback, proposalId: proposal.id, createdAt: now }),
  ]);
  trace.stage('proposal.saved', { conversationId, proposalId: proposal.id, action: d.action, reminderId: candidate?.id ?? null });
  return { conversationId, reply: readback, proposal };
}));

async function applyAction(uid: string, reminderId: string, action: 'done' | 'dismiss' | 'snooze', snoozeMinutes: number | null, requestId: string, expectedVersion?: number | null, occurrenceId?: string, conversationId?: string) {
  const ref = root(uid).collection('reminders').doc(reminderId);
  const idempotency = root(uid).collection('actions').doc(requestId);
  const now = Date.now();
  return db.runTransaction(async tx => {
    const conversation = conversationId ? conversationRef(uid, conversationId) : null;
    const [prior, snap, conversationSnap] = await Promise.all([tx.get(idempotency), tx.get(ref),
      conversation ? tx.get(conversation) : Promise.resolve(null)]);
    if (prior.exists) return prior.data()?.result;
    if (conversation && !canConfirmDraft(conversationSnap?.get('state'))) {
      throw new HttpsError('failed-precondition', 'Conversation was abandoned. Please ask again.');
    }
    if (!snap.exists) throw new HttpsError('not-found', 'Reminder not found.');
    const r = record(snap.data()!, snap.id);
    if (r.status !== 'active') throw new HttpsError('failed-precondition', 'Reminder is canceled.');
    if (expectedVersion != null && r.version !== expectedVersion) throw new HttpsError('failed-precondition', 'Reminder changed. Please ask again.');
    let update: Partial<ReminderRecord>;
    try { update = applyReminderAction(r, action, now, snoozeMinutes, occurrenceId); }
    catch (error) { throw new HttpsError('failed-precondition', error instanceof Error ? error.message : 'Invalid occurrence action.'); }
    tx.update(ref, action === 'done' ? { ...update, dismissedAt: FieldValue.delete() } : update);
    const alertAt = occurrenceId?.startsWith(`${reminderId}_`)
      ? Number(occurrenceId.slice(reminderId.length + 1)) : r.lastScheduledAt;
    if (alertAt != null && Number.isFinite(alertAt) && r.lastSentAt != null && r.lastScheduledAt === alertAt) {
      tx.set(root(uid).collection('occurrences').doc(`${reminderId}_${alertAt}`),
        action === 'done' ? { reminderId, acknowledgedAt: now, acknowledgment: 'done' }
          : action === 'snooze' ? { reminderId, snoozedUntil: update.nextDueAt } : { reminderId, dismissedAt: now },
        { merge: true });
    }
    const result = { reminderId, action, at: now };
    tx.create(idempotency, { result, createdAt: now });
    const eventId = randomUUID();
    tx.create(root(uid).collection('events').doc(eventId), { reminderId, action, at: now });
    if (conversation) tx.set(conversation, { schemaVersion: 2,
      state: 'linked', reminderId, linkedAt: now, updatedAt: now }, { merge: true });
    return result;
  });
}

export const applyOccurrenceAction = onCall({ region, enforceAppCheck: true }, request => observe('applyOccurrenceAction', request.auth?.uid, async trace => {
  const uid = requireGoogleUser(request);
  const reminderId = textValue(request.data?.reminderId, 100);
  const action = request.data?.action;
  if (!['done', 'dismiss', 'snooze'].includes(action)) throw new HttpsError('invalid-argument', 'Invalid action.');
  const requestId = textValue(request.data?.requestId, 100);
  const result = await applyAction(uid, reminderId, action, request.data?.snoozeMinutes ?? null, requestId, null, stringValue(request.data?.occurrenceId, 150) || undefined);
  trace.stage('action.committed', { reminderId, requestId, action });
  return result;
}));

async function deleteReminderData(uid: string, reminderId: string, expectedVersion?: number | null) {
  const owner = root(uid);
  const reminder = owner.collection('reminders').doc(reminderId);
  const stoppedAt = Date.now();
  await db.runTransaction(async tx => {
    const snap = await tx.get(reminder);
    if (!snap.exists) return;
    const current = record(snap.data()!, reminderId);
    if (current.deletionRequestedAt) return;
    if (expectedVersion != null && current.version !== expectedVersion) {
      throw new HttpsError('failed-precondition', 'Reminder changed. Please ask again.');
    }
    tx.update(reminder, { status: 'canceled', deletionRequestedAt: stoppedAt,
      nextDueAt: null, currentScheduledAt: null, leaseUntil: 0,
      version: current.version + 1, updatedAt: stoppedAt });
  });
  const removed = await purgeRelatedRecords(uid, reminderId,
    async (accountId, id, kind: RelatedRecordKind, limit) => {
      const scoped = root(accountId);
      const query = kind === 'occurrences' ? scoped.collection('occurrences').where('reminderId', '==', id)
        : kind === 'actions' ? scoped.collection('actions').where('result.reminderId', '==', id)
          : kind === 'events' ? scoped.collection('events').where('reminderId', '==', id)
            : kind === 'proposalsByTarget' ? scoped.collection('proposals').where('targetId', '==', id)
              : scoped.collection('proposals').where('committedResult.reminderId', '==', id);
      return (await query.limit(limit).get()).docs.map(doc => doc.ref);
    },
    async refs => {
      const batch = db.batch();
      refs.forEach(ref => batch.delete(ref));
      await batch.commit();
    });
  await purgeLinkedConversations(uid, reminderId,
    async (accountId, id, limit) => (await root(accountId).collection('conversations')
      .where('reminderId', '==', id).limit(limit).get()).docs.map(doc => doc.ref),
    async ref => db.recursiveDelete(ref));
  await reminder.delete();
  return { reminderId, deleted: true, removed };
}

export const deleteReminder = onCall({ region, enforceAppCheck: true, timeoutSeconds: 540 }, request => observe('deleteReminder', request.auth?.uid, async trace => {
  const uid = requireGoogleUser(request);
  const reminderId = textValue(request.data?.reminderId, 100);
  const expectedVersion = request.data?.expectedVersion;
  if (expectedVersion != null && (!Number.isInteger(expectedVersion) || expectedVersion < 0)) {
    throw new HttpsError('invalid-argument', 'Invalid reminder version.');
  }
  const proposalId = stringValue(request.data?.proposalId, 100);
  let deletingConversationId: string | null = null;
  if (proposalId) {
    const proposal = await root(uid).collection('proposals').doc(proposalId).get();
    if (proposal.exists) {
      const p = proposal.data() as Proposal;
      if (p.action !== 'delete' || p.targetId !== reminderId || p.baseVersion !== expectedVersion) {
        throw new HttpsError('failed-precondition', 'Deletion proposal changed or expired. Please ask again.');
      }
      if (p.expiresAt < Date.now()) {
        const current = await root(uid).collection('reminders').doc(reminderId).get();
        if (current.exists && !current.get('deletionRequestedAt')) {
          throw new HttpsError('failed-precondition', 'Deletion proposal expired. Please ask again.');
        }
      }
      deletingConversationId = p.conversationId;
    } else {
      const current = await root(uid).collection('reminders').doc(reminderId).get();
      if (current.exists && !current.get('deletionRequestedAt')) {
        throw new HttpsError('not-found', 'Deletion proposal not found. Please ask again.');
      }
    }
  }
  const result = await deleteReminderData(uid, reminderId, expectedVersion);
  if (deletingConversationId) await deleteConversationTree(uid, deletingConversationId);
  trace.stage('reminder.deleted', { reminderId, relatedRecordsRemoved: result.removed });
  return result;
}));

export const confirmProposal = onCall({ region, enforceAppCheck: true, timeoutSeconds: 540 }, request => observe('confirmProposal', request.auth?.uid, async trace => {
  const uid = requireGoogleUser(request);
  const proposalId = textValue(request.data?.proposalId, 100);
  const ref = root(uid).collection('proposals').doc(proposalId);
  const snap = await ref.get();
  if (!snap.exists) throw new HttpsError('not-found', 'Proposal not found.');
  const p = snap.data() as Proposal;
  trace.stage('proposal.loaded', { proposalId, action: p.action, reminderId: p.targetId });
  if (p.committedResult) {
    trace.stage('proposal.alreadyCommitted', { proposalId });
    return p.committedResult;
  }
  if (p.action === 'delete') {
    if (!p.targetId) throw new HttpsError('invalid-argument', 'Missing reminder.');
    if (Date.now() > p.expiresAt) {
      const current = await root(uid).collection('reminders').doc(p.targetId).get();
      if (current.exists && !current.get('deletionRequestedAt')) {
        throw new HttpsError('failed-precondition', 'Proposal expired. Please ask again.');
      }
    }
    const result = await deleteReminderData(uid, p.targetId, p.baseVersion);
    await deleteConversationTree(uid, p.conversationId);
    trace.stage('proposal.deletedReminder', { proposalId, reminderId: p.targetId, relatedRecordsRemoved: result.removed });
    return result;
  }
  if (Date.now() > p.expiresAt) throw new HttpsError('failed-precondition', 'Proposal expired. Please ask again.');
  if (['done', 'dismiss', 'snooze'].includes(p.action)) {
    const result = await applyAction(uid, p.targetId!, p.action as 'done' | 'dismiss' | 'snooze', p.snoozeMinutes, proposalId, p.baseVersion, undefined, p.conversationId);
    await ref.update({ committedResult: result });
    trace.stage('proposal.committed', { proposalId, reminderId: p.targetId, action: p.action });
    return result;
  }
  const target = p.targetId ? root(uid).collection('reminders').doc(p.targetId) : root(uid).collection('reminders').doc();
  const now = Date.now();
  const result = await db.runTransaction(async tx => {
    const conversation = conversationRef(uid, p.conversationId);
    const [freshProposal, prior, conversationSnap] = await Promise.all([tx.get(ref),
      p.targetId ? tx.get(target) : Promise.resolve(null), tx.get(conversation)]);
    const current = freshProposal.data() as Proposal;
    if (current.committedResult) return current.committedResult;
    if (!canConfirmDraft(conversationSnap.get('state'))) {
      throw new HttpsError('failed-precondition', 'Conversation was abandoned. Please ask again.');
    }
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
    tx.set(conversation, { schemaVersion: 2, state: 'linked',
      reminderId: target.id, linkedAt: now, updatedAt: now }, { merge: true });
    tx.create(root(uid).collection('events').doc(randomUUID()), { reminderId: target.id, action: p.action, at: now });
    return result;
  });
  trace.stage('proposal.committed', { proposalId, reminderId: target.id, action: p.action });
  return result;
}));

export const registerDevice = onCall({ region, enforceAppCheck: true }, request => observe('registerDevice', request.auth?.uid, async trace => {
  const uid = requireGoogleUser(request);
  const token = textValue(request.data?.token, 4096);
  const test = request.data?.test === true;
  const id = createHash('sha256').update(token).digest('hex');
  try {
    await getMessaging().send({ token, data: { title: 'Remind is ready', body: 'Notifications are connected.', reminderId: 'test', url: '/' } }, !test);
    trace.stage('push.checkAccepted', { test });
  } catch (error) {
    const code = errorFields(error).errorCode;
    warn('Push registration test was rejected', { uid, code });
    throw new HttpsError('failed-precondition', `Push registration failed (${code}). Please try again.`);
  }
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
  trace.stage('device.registered', { test });
  return { registered: true };
}));

export const unregisterDevice = onCall({ region, enforceAppCheck: true }, request => observe('unregisterDevice', request.auth?.uid, async trace => {
  const uid = requireGoogleUser(request);
  const token = textValue(request.data?.token, 4096);
  const id = createHash('sha256').update(token).digest('hex');
  const ownerRef = db.collection('deviceTokens').doc(id);
  await db.runTransaction(async tx => {
    const owner = await tx.get(ownerRef);
    if (owner.get('uid') === uid) tx.delete(ownerRef);
    tx.delete(root(uid).collection('devices').doc(id));
  });
  trace.stage('device.unregistered');
  return { unregistered: true };
}));

export const deleteConversation = onCall({ region, enforceAppCheck: true }, request => observe('deleteConversation', request.auth?.uid, async trace => {
  const uid = requireGoogleUser(request);
  const conversationId = textValue(request.data?.conversationId, 100);
  if (request.data?.draftOnly === true) {
    if (!await claimDraftForDeletion(uid, conversationId)) {
      trace.stage('linkedConversation.preserved', { conversationId });
      return { deleted: false };
    }
  }
  await deleteConversationTree(uid, conversationId);
  trace.stage('conversation.deleted', { conversationId });
  return { deleted: true };
}));

export const initializeConversations = onCall({ region, enforceAppCheck: true, timeoutSeconds: 540 }, request => observe('initializeConversations', request.auth?.uid, async trace => {
  const uid = requireGoogleUser(request);
  const owner = root(uid);
  if ((await owner.get()).get('conversationSchemaVersion') === 2) return { deleted: 0 };
  const documents = await owner.collection('conversations').listDocuments();
  const deleted = await purgeLegacyConversations(documents,
    async ref => (await ref.get()).get('schemaVersion') as number | undefined,
    async ref => deleteConversationTree(uid, ref.id));
  await owner.set({ conversationSchemaVersion: 2 }, { merge: true });
  trace.stage('legacyConversations.deleted', { count: deleted });
  return { deleted };
}));

export const cleanupDraftConversations = onSchedule({ schedule: '0 3 * * *', timeZone: 'UTC', region, maxInstances: 1 }, async () => {
  const cutoff = Date.now() - 24 * 60 * 60_000;
  let deleted = 0;
  for (;;) {
    const page = await db.collectionGroup('conversations').where('state', 'in', ['draft', 'deleting'])
      .where('updatedAt', '<=', cutoff).orderBy('updatedAt').limit(100).get();
    if (page.empty) break;
    for (const doc of page.docs) {
      if (doc.ref.parent.parent?.parent?.id !== 'users') {
        warn('cleanupDraftConversations.unexpectedPath', { path: doc.ref.path });
        return;
      }
      const uid = doc.ref.parent.parent!.id;
      if (await claimDraftForDeletion(uid, doc.id)) {
        await deleteConversationTree(uid, doc.id);
        deleted++;
      }
    }
  }
  info('cleanupDraftConversations.completed', { operation: 'cleanupDraftConversations', deleted });
});

export const dispatchDue = onSchedule({ schedule: '* * * * *', timeZone: 'UTC', region, maxInstances: 1 }, async () => {
  const operationId = randomUUID();
  const startedAt = Date.now();
  const now = Date.now();
  let due;
  try {
    due = await db.collectionGroup('reminders').where('status', '==', 'active').where('nextDueAt', '<=', now).orderBy('nextDueAt').limit(50).get();
  } catch (reason) {
    warn('dispatchDue.queryFailed', { operation: 'dispatchDue', operationId, durationMs: Date.now() - startedAt, ...errorFields(reason) });
    throw reason;
  }
  if (due.empty) return;
  info('dispatchDue.started', { operation: 'dispatchDue', operationId, dueCount: due.size });
  let claimedCount = 0;
  let skippedCount = 0;
  let acceptedCount = 0;
  let retryCount = 0;
  try {
  for (const doc of due.docs) {
    const uid = doc.ref.parent.parent?.id;
    if (!uid || doc.ref.parent.parent?.parent?.id !== 'users') { skippedCount++; continue; }
    const claimed = await db.runTransaction(async tx => {
      const snap = await tx.get(doc.ref);
      if (!snap.exists) return null;
      const r = record(snap.data()!, snap.id);
      if (r.status !== 'active' || r.nextDueAt === null || r.nextDueAt > now || (r.leaseUntil ?? 0) > now) return null;
      tx.update(doc.ref, { leaseUntil: now + 90_000 });
      return r;
    });
    if (!claimed) { skippedCount++; continue; }
    claimedCount++;
    const scheduledAt = claimed.currentScheduledAt ?? claimed.nextDueAt!;
    const occurrenceId = `${claimed.id}_${scheduledAt}`;
    info('dispatchDue.claimed', { operation: 'dispatchDue', operationId, uid, reminderId: claimed.id, occurrenceId, scheduledAt });
    const devices = await root(uid).collection('devices').get();
    const targets = devices.docs.filter(device => typeof device.get('token') === 'string' && device.get('token'));
    const selected = targets.slice(0, 500);
    let diagnostic = deliveryDiagnostic(selected.length);
    if (selected.length) {
      try {
        const results = await getMessaging().sendEachForMulticast({
          tokens: selected.map(device => device.get('token') as string),
          data: reminderPushData(claimed.title, claimed.notes, claimed.id, `${claimed.id}_${scheduledAt}`),
          webpush: { headers: { TTL: '3600' } },
        });
        diagnostic = deliveryDiagnostic(selected.length, results.responses);
        const invalid = results.responses.flatMap((response, i) =>
          !response.success && /registration-token-not-registered|invalid-registration-token/.test(response.error?.code ?? '')
            ? [selected[i].ref] : []);
        const cleanup = await Promise.allSettled(invalid.map(ref => ref.delete()));
        if (cleanup.some(result => result.status === 'rejected')) {
          warn('Could not remove an invalid push token', { uid, reminderId: claimed.id });
        }
      } catch (error) {
        const code = errorFields(error).errorCode;
        diagnostic = deliveryDiagnostic(selected.length, undefined, code);
      }
    }
    const delivered = diagnostic.acceptedCount > 0;
    const finishAt = Date.now();
    const persisted = await db.runTransaction(async tx => {
      const snap = await tx.get(doc.ref);
      const r = snap.exists ? record(snap.data()!, snap.id) : null;
      if (!canPersistDelivery(r, claimed.version, now + 90_000)) return false;
      const transition = afterDelivery(r, scheduledAt, finishAt, delivered);
      tx.update(doc.ref, transition.update);
      tx.set(root(uid).collection('occurrences').doc(`${r.id}_${scheduledAt}`), {
        reminderId: r.id, scheduledAt, lastAttemptAt: finishAt,
        status: transition.occurrenceStatus, attempts: FieldValue.increment(1), ...diagnostic,
      }, { merge: true });
      return true;
    });
    if (!persisted) {
      warn('dispatchDue.resultNotSaved', { operation: 'dispatchDue', operationId, uid, reminderId: claimed.id, occurrenceId });
      continue;
    }
    if (delivered) acceptedCount++; else retryCount++;
    const details = { operation: 'dispatchDue', operationId, uid, reminderId: claimed.id, occurrenceId, scheduledAt, ...diagnostic };
    if (delivered) info('Reminder push accepted', details);
    else warn('Reminder push not accepted', details);
  }
  info('dispatchDue.completed', { operation: 'dispatchDue', operationId, dueCount: due.size, claimedCount, skippedCount, acceptedCount, retryCount, durationMs: Date.now() - startedAt });
  } catch (reason) {
    warn('dispatchDue.failed', { operation: 'dispatchDue', operationId, dueCount: due.size, claimedCount, skippedCount, acceptedCount, retryCount, durationMs: Date.now() - startedAt, ...errorFields(reason) });
    throw reason;
  }
});
