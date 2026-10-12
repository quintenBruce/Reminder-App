import { readFileSync } from 'node:fs';
import { runInNewContext } from 'node:vm';
import { describe, expect, it } from 'vitest';

function notificationFor(data: Record<string, string>) {
  const handlers: Record<string, (event: { data: { json: () => unknown }; waitUntil: (work: Promise<unknown>) => void }) => void> = {};
  let shown: { title: string; options: Record<string, unknown> } | null = null;
  const worker = {
    addEventListener: (name: string, handler: typeof handlers[string]) => { handlers[name] = handler; },
    registration: { showNotification: async (title: string, options: Record<string, unknown>) => { shown = { title, options }; } },
  };
  const source = readFileSync(new URL('../public/firebase-messaging-sw.js', import.meta.url), 'utf8');
  runInNewContext(source, { self: worker, URL });
  handlers.push({ data: { json: () => ({ data }) }, waitUntil: () => undefined });
  return shown as { title: string; options: Record<string, unknown> } | null;
}

describe('Firefox push worker', () => {
  it('shows a title-only reminder without generic filler', () => {
    const shown = notificationFor({ title: 'Take vitamins', reminderId: 'r1' });
    expect(shown?.title).toBe('Take vitamins');
    expect(shown?.options).not.toHaveProperty('body');
  });

  it('shows a distinct reminder description and the test message', () => {
    expect(notificationFor({ title: 'Take vitamins', body: 'With breakfast', reminderId: 'r1' })?.options.body).toBe('With breakfast');
    expect(notificationFor({ title: 'Remind is ready', body: 'Notifications are connected.', reminderId: 'test' })?.options.body).toBe('Notifications are connected.');
  });
});
