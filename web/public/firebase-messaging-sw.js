/* A data-only FCM push is shown here so the same worker works in Firefox for Android. */
self.addEventListener('push', event => {
  let message = {};
  try { message = event.data ? event.data.json() : {}; } catch { /* Ignore malformed payload. */ }
  const data = message.data || message;
  const title = data.title || 'Remind';
  const body = data.body || 'You have a reminder.';
  const reminderId = data.reminderId || '';
  const occurrenceId = data.occurrenceId || reminderId;
  event.waitUntil(self.registration.showNotification(title, {
    body,
    icon: '/icon.svg',
    badge: '/icon.svg',
    tag: occurrenceId,
    data: { reminderId, occurrenceId, url: data.url || '/' },
    actions: reminderId === 'test' ? [] : [
      { action: 'done', title: 'Done' },
      { action: 'snooze', title: 'Snooze 10 min' },
    ],
  }));
});

self.addEventListener('notificationclick', event => {
  event.notification.close();
  const data = event.notification.data || {};
  const url = new URL(data.url || '/', self.location.origin);
  if (event.action && data.reminderId) {
    url.searchParams.set('action', event.action);
    url.searchParams.set('reminder', data.reminderId);
    url.searchParams.set('occurrenceId', data.occurrenceId || '');
    url.searchParams.set('requestId', `${data.occurrenceId || data.reminderId}_${event.action}`);
  }
  event.waitUntil((async () => {
    const windows = await self.clients.matchAll({ type: 'window', includeUncontrolled: true });
    const existing = windows.find(client => client.url.startsWith(self.location.origin));
    if (existing) {
      await existing.navigate(url.href);
      await existing.focus();
    } else {
      await self.clients.openWindow(url.href);
    }
  })());
});
