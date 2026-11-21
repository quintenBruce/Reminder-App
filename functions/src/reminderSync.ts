import { onDocumentWritten } from 'firebase-functions/v2/firestore';
import { getFirestore } from 'firebase-admin/firestore';
import { getMessaging } from 'firebase-admin/messaging';

// Synchronize created/edited/deleted reminders even when clients have no active listener.
export const syncReminderDevices = onDocumentWritten({ document: 'users/{uid}/reminders/{reminderId}',
  region: process.env.FUNCTIONS_REGION || 'us-south1', maxInstances: 2, retry: false }, async event => {
  const before = event.data?.before.data(), after = event.data?.after.data();
  // Scheduler bookkeeping does not change user-visible content or require another push.
  const fields = ['version', 'sharedVersion', 'priority', 'priorityOverride'];
  if (before && after && fields.every(key => before[key] === after[key])) return;
  const devices = await getFirestore().collection('users').doc(event.params.uid).collection('devices').get();
  const tokens = devices.docs.filter(doc => doc.get('platform') === 'android').map(doc => doc.get('token') as string).filter(Boolean);
  if (!tokens.length) return;
  const result = await getMessaging().sendEachForMulticast({ tokens: tokens.slice(0, 10),
    data: { sync: 'true', uid: event.params.uid, reminderId: event.params.reminderId }, android: { priority: 'high', ttl: 86_400_000 } });
  if (result.responses.some(r => !r.success && !/registration-token-not-registered|invalid-registration-token/.test(r.error?.code || '')))
    throw new Error('Reminder sync push temporarily failed');
});
