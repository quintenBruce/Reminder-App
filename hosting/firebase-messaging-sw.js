// Retirement worker for previous PWA installs. No push handler remains.
self.addEventListener('install', () => self.skipWaiting());
self.addEventListener('activate', event => event.waitUntil((async () => {
  const sub = await self.registration.pushManager.getSubscription();
  if (sub) await sub.unsubscribe();
  await self.registration.unregister();
})()));
