import { initializeApp } from 'firebase/app';
import { getAuth, GoogleAuthProvider, signInWithRedirect, signOut } from 'firebase/auth';
import { getFirestore } from 'firebase/firestore';
import { getFunctions, httpsCallable } from 'firebase/functions';
import { deleteToken, getMessaging, getToken, isSupported, onMessage } from 'firebase/messaging';
import { initializeAppCheck, ReCaptchaV3Provider } from 'firebase/app-check';

const config = {
  apiKey: import.meta.env.VITE_FIREBASE_API_KEY,
  authDomain: import.meta.env.VITE_FIREBASE_AUTH_DOMAIN,
  projectId: import.meta.env.VITE_FIREBASE_PROJECT_ID,
  appId: import.meta.env.VITE_FIREBASE_APP_ID,
  messagingSenderId: import.meta.env.VITE_FIREBASE_MESSAGING_SENDER_ID,
};

export const app = initializeApp(config);
export const auth = getAuth(app);
export const database = getFirestore(app);
export const functions = getFunctions(app, import.meta.env.VITE_FIREBASE_FUNCTIONS_REGION || 'us-south1');

if (import.meta.env.VITE_RECAPTCHA_SITE_KEY) {
  initializeAppCheck(app, {
    provider: new ReCaptchaV3Provider(import.meta.env.VITE_RECAPTCHA_SITE_KEY),
    isTokenAutoRefreshEnabled: true,
  });
}

export async function signIn() {
  const provider = new GoogleAuthProvider();
  provider.setCustomParameters({ prompt: 'select_account' });
  await signInWithRedirect(auth, provider);
}
export async function signOutUser() {
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

export function callable<TInput, TOutput>(name: string) {
  return async (data: TInput): Promise<TOutput> => {
    const fn = httpsCallable<TInput, TOutput>(functions, name);
    const response = await fn(data);
    return response.data;
  };
}

export async function enablePush(onForeground: (message: string) => void) {
  if (!('serviceWorker' in navigator) || !('Notification' in window) || !(await isSupported())) {
    throw new Error('Push is unavailable in this browser. In-app reminders still work.');
  }
  const permission = await Notification.requestPermission();
  if (permission !== 'granted') throw new Error('Notification permission was not granted.');
  const registration = await navigator.serviceWorker.register('/firebase-messaging-sw.js');
  const messaging = getMessaging(app);
  const token = await getToken(messaging, {
    vapidKey: import.meta.env.VITE_FIREBASE_VAPID_KEY,
    serviceWorkerRegistration: registration,
  });
  if (!token) throw new Error('Firefox did not return a push token.');
  localStorage.setItem('remind-push-registration', JSON.stringify({ uid: auth.currentUser?.uid, token }));
  await callable<{ token: string }, { registered: boolean }>('registerDevice')({ token });
  onMessage(messaging, payload => onForeground(payload.data?.title || 'Reminder received'));
}
