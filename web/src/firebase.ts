import { initializeApp } from 'firebase/app';
import { getAuth, GoogleAuthProvider, signInWithRedirect, signOut } from 'firebase/auth';
import { getFirestore } from 'firebase/firestore';
import { getFunctions, httpsCallable } from 'firebase/functions';
import { deleteToken, getMessaging, getToken, isSupported, onMessage } from 'firebase/messaging';
import { initializeAppCheck, ReCaptchaEnterpriseProvider } from 'firebase/app-check';
import { registerPushWithRecovery, registrationNeedsRefresh, type SavedPushRegistration } from './pushRegistration';

const config = {
  apiKey: import.meta.env.VITE_FIREBASE_API_KEY,
  authDomain: import.meta.env.VITE_FIREBASE_AUTH_DOMAIN,
  projectId: import.meta.env.VITE_FIREBASE_PROJECT_ID,
  appId: import.meta.env.VITE_FIREBASE_APP_ID,
  messagingSenderId: import.meta.env.VITE_FIREBASE_MESSAGING_SENDER_ID,
};

export const app = initializeApp(config);
if (import.meta.env.VITE_RECAPTCHA_ENTERPRISE_KEY) {
  initializeAppCheck(app, {
    provider: new ReCaptchaEnterpriseProvider(import.meta.env.VITE_RECAPTCHA_ENTERPRISE_KEY),
    isTokenAutoRefreshEnabled: true,
  });
}

export const auth = getAuth(app);
export const database = getFirestore(app);
export const functions = getFunctions(app, import.meta.env.VITE_FIREBASE_FUNCTIONS_REGION || 'us-south1');
let stopForegroundMessages: (() => void) | null = null;

export async function signIn() {
  const provider = new GoogleAuthProvider();
  provider.setCustomParameters({ prompt: 'select_account' });
  await signInWithRedirect(auth, provider);
}
export async function signOutUser() {
  stopForegroundMessages?.();
  stopForegroundMessages = null;
  const saved = localStorage.getItem('remind-push-registration');
  if (saved && auth.currentUser) {
    let registration: { uid: string; token: string } | null = null;
    try { registration = JSON.parse(saved) as { uid: string; token: string }; }
    catch { localStorage.removeItem('remind-push-registration'); }
    if (registration?.uid === auth.currentUser.uid && registration.token) {
      await callable<{ token: string }, { unregistered: boolean }>('unregisterDevice')({ token: registration.token });
      localStorage.removeItem('remind-push-registration');
      try { if (await isSupported()) await deleteToken(getMessaging(app)); } catch { /* Server registration is already removed. */ }
    }
  }
  await signOut(auth);
}

export function callable<TInput, TOutput>(name: string, timeout?: number) {
  return async (data: TInput): Promise<TOutput> => {
    const fn = httpsCallable<TInput, TOutput>(functions, name, timeout ? { timeout } : undefined);
    const response = await fn(data);
    return response.data;
  };
}

export async function enablePush(onForeground: (message: string) => void, test = true) {
  if (!('serviceWorker' in navigator) || !('Notification' in window)) {
    throw new Error('Push is unavailable in this browser. In-app reminders still work.');
  }
  // Request permission before the first await so Firefox sees the user's tap.
  const permission = Notification.permission === 'granted' ? 'granted' : await Notification.requestPermission();
  if (permission !== 'granted') throw new Error('Notification permission was not granted.');
  if (!(await isSupported())) throw new Error('Push is unavailable in this browser. In-app reminders still work.');
  const registration = await navigator.serviceWorker.register('/firebase-messaging-sw.js');
  const messaging = getMessaging(app);
  const uid = auth.currentUser?.uid;
  if (!uid) throw new Error('Sign in before enabling notifications.');
  const registerDevice = callable<{ token: string; test: boolean }, { registered: boolean }>('registerDevice');
  const token = await registerPushWithRecovery({
    getToken: () => getToken(messaging, {
      vapidKey: import.meta.env.VITE_FIREBASE_VAPID_KEY,
      serviceWorkerRegistration: registration,
    }),
    register: token => {
      if (auth.currentUser?.uid !== uid) throw new Error('Account changed while enabling notifications.');
      return registerDevice({ token, test });
    },
    deleteToken: () => deleteToken(messaging),
    renewSubscription: async () => {
      const subscription = await registration.pushManager.getSubscription();
      return subscription ? subscription.unsubscribe() : true;
    },
  });
  if (auth.currentUser?.uid !== uid) throw new Error('Account changed while enabling notifications.');
  let oldRegistration: SavedPushRegistration = null;
  try { oldRegistration = JSON.parse(localStorage.getItem('remind-push-registration') || 'null') as SavedPushRegistration; }
  catch { /* Replace malformed local state below. */ }
  localStorage.setItem('remind-push-registration', JSON.stringify({ uid, token, checkedAt: Date.now() }));
  if (oldRegistration?.uid === uid && oldRegistration.token && oldRegistration.token !== token) {
    await callable<{ token: string }, { unregistered: boolean }>('unregisterDevice')({ token: oldRegistration.token }).catch(() => undefined);
  }
  stopForegroundMessages?.();
  stopForegroundMessages = onMessage(messaging, payload => onForeground(payload.data?.title || 'Reminder received'));
}

export function needsPushRegistration(uid: string) {
  if (!('Notification' in window) || Notification.permission === 'denied') return false;
  try {
    const saved = JSON.parse(localStorage.getItem('remind-push-registration') || 'null') as SavedPushRegistration;
    return registrationNeedsRefresh(saved, uid);
  } catch { return true; }
}

export async function closeReminderNotifications(reminderId: string) {
  if (!('serviceWorker' in navigator)) return;
  const registration = await navigator.serviceWorker.getRegistration('/');
  if (!registration) return;
  const notifications = await registration.getNotifications();
  notifications.filter(notification => notification.data?.reminderId === reminderId).forEach(notification => notification.close());
}
