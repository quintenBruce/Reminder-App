import { beforeEach, describe, expect, it, vi } from 'vitest';
const fake = vi.hoisted(() => {
  const data = new Map<string, any>();
  let tail = Promise.resolve();
  const ref = (path: string): any => ({ path, id: path.split('/').at(-1), collection: (s: string) => ref(`${path}/${s}`),
    doc: (s = `event${data.size}`) => ref(`${path}/${s}`), where: (key: string, _op: string, value: unknown) => ({ path, filter: { key, value } }) });
  const snap = (path: string) => ({ ref: ref(path), id: path.split('/').at(-1), exists: data.has(path), data: () => structuredClone(data.get(path)),
    get: (key: string) => key.split('.').reduce((v, k) => v?.[k], data.get(path)) });
  const db = { collection: (s: string) => ref(s), runTransaction: (fn: any) => {
    const result = tail.then(async () => {
      const writes: (() => void)[] = [];
      const tx = { get: async (r: any) => {
        if (r.path.split('/').length % 2 === 0) return snap(r.path);
        const docs = [...data.keys()].filter(p => p.startsWith(r.path + '/') && p.split('/').length === r.path.split('/').length + 1)
          .filter(p => !r.filter || data.get(p)?.[r.filter.key] === r.filter.value).map(snap);
        return { docs, size: docs.length };
      }, set: (r: any, value: any, options?: any) => writes.push(() => data.set(r.path, options?.merge ? { ...data.get(r.path), ...value } : structuredClone(value))),
      update: (r: any, value: any) => writes.push(() => data.set(r.path, { ...data.get(r.path), ...structuredClone(value) })),
      create: (r: any, value: any) => writes.push(() => data.set(r.path, structuredClone(value))),
      delete: (r: any) => writes.push(() => data.delete(r.path)) };
      const value = await fn(tx); writes.forEach(write => write()); return value;
    });
    tail = result.then(() => undefined, () => undefined); return result;
  } };
  return { data, db };
});
vi.mock('firebase-admin/firestore', () => ({ getFirestore: () => fake.db }));
import { commitShared, removeShared } from '../src/sharing';
import { authorize, consume, profileInput, quotaWindows } from '../src/limits';
import { saveProfile, friendInvite } from '../src/social';
import { matchFriend } from '../src/friendMatching';
import { applyReminderAction } from '../../shared/src/reminderState';
import type { Proposal } from '../src/index';

function proposal(id = 'p'): Proposal {
  return { id, conversationId: 'c', action: 'create', targetId: null, baseVersion: null, originalWording: 'test',
    title: 'Meet', notes: '', rule: { kind: 'once', at: new Date(Date.now() + 3600000).toISOString() },
    repeatUntilDoneMinutes: null, snoozeMinutes: null, readback: '', preview: [], createdAt: Date.now(), expiresAt: Date.now() + 600000, participantIds: ['b'] };
}
function seed(p: Proposal) {
  fake.data.set('users/a', { profile: { displayName: 'Alice', username: 'alice' } });
  fake.data.set('users/b', { profile: { displayName: 'Bob', username: 'bob' } });
  fake.data.set('users/a/friends/b', { displayName: 'Bob' });
  fake.data.set(`users/a/proposals/${p.id}`, p);
  fake.data.set('users/a/conversations/c', { state: 'draft' });
}
beforeEach(() => fake.data.clear());
describe('shared reminder transactions', () => {
  it('creates a canonical record and independent personal delivery state; confirmation is idempotent', async () => {
    const p = proposal(); seed(p); const result = await commitShared('a', p, true);
    expect(await commitShared('a', p, true)).toEqual(result);
    const id = result.reminderId, a = fake.data.get(`users/a/reminders/${id}`), b = fake.data.get(`users/b/reminders/${id}`);
    expect(fake.data.get(`sharedReminders/${id}`).participantIds).toEqual(['a', 'b']);
    expect(a.priority).toBe(true);
    const done = applyReminderAction(a, 'done', a.nextDueAt, null, `${id}_${a.nextDueAt}`, true);
    Object.assign(a, done); expect(a.status).toBe('completed'); expect(b.status).toBe('active');
  });
  it('refuses recipients removed since proposal and makes no partial writes', async () => {
    const p = proposal(); seed(p); fake.data.delete('users/a/friends/b');
    await expect(commitShared('a', p, false)).rejects.toMatchObject({ code: 'permission-denied' });
    expect([...fake.data.keys()].some(k => k.startsWith('sharedReminders/'))).toBe(false);
  });
  it('checks shared version, preserves personal overrides and updates both views', async () => {
    const p = proposal(); seed(p); const { reminderId: id } = await commitShared('a', p, true);
    fake.data.get(`users/b/reminders/${id}`).priorityOverride = false;
    const edit = { ...proposal('edit'), action: 'edit' as const, targetId: id, sharedId: id, sharedVersion: 1, title: 'Changed' };
    fake.data.set('users/a/proposals/edit', edit); fake.data.set('users/a/conversations/c', { state: 'draft' });
    await commitShared('a', edit, true);
    expect(fake.data.get(`users/b/reminders/${id}`).title).toBe('Changed');
    expect(fake.data.get(`users/b/reminders/${id}`).priority).toBe(false);
    fake.data.set('users/a/proposals/stale', { ...edit, id: 'stale' }); fake.data.set('users/a/conversations/c', { state: 'draft' });
    await expect(commitShared('a', { ...edit, id: 'stale' }, false)).rejects.toMatchObject({ code: 'failed-precondition' });
  });
  it('lets participants leave, rejects outsiders, and deletes for everyone', async () => {
    const p = proposal(); seed(p); const { reminderId: id } = await commitShared('a', p, false);
    await expect(removeShared('outsider', id, false)).rejects.toMatchObject({ code: 'not-found' });
    await removeShared('b', id, true);
    expect(fake.data.has(`users/b/reminders/${id}`)).toBe(false);
    expect(fake.data.get(`sharedReminders/${id}`).participantIds).toEqual(['a']);
    await expect(removeShared('b', id, false)).rejects.toMatchObject({ code: 'not-found' });
    await removeShared('a', id, false); expect(fake.data.has(`users/a/reminders/${id}`)).toBe(false);
  });
  it('keeps personal completion and snooze on content-only edits', async () => {
    const p = proposal(); seed(p); const { reminderId: id } = await commitShared('a', p, false);
    const a = fake.data.get(`users/a/reminders/${id}`), b = fake.data.get(`users/b/reminders/${id}`);
    a.status = 'completed'; a.nextDueAt = null; a.lastAcknowledgedScheduledAt = 123;
    b.nextDueAt = 999; b.currentScheduledAt = 123;
    const edit = { ...p, id: 'edit', action: 'edit' as const, targetId: id, sharedId: id, sharedVersion: 1, title: 'New title' };
    fake.data.set('users/a/proposals/edit', edit); fake.data.set('users/a/conversations/c', { state: 'draft' });
    await commitShared('a', edit, true);
    expect(fake.data.get(`users/a/reminders/${id}`).status).toBe('completed');
    expect(fake.data.get(`users/b/reminders/${id}`).nextDueAt).toBe(999);
    expect(fake.data.get(`users/b/reminders/${id}`).priority).toBe(true);
  });
});
describe('backend limits and identity', () => {
  it('rejects anonymous, unverified, and non-Google callers before consuming quota', async () => {
    for (const auth of [undefined, { uid: 'a', token: { email_verified: false, firebase: { sign_in_provider: 'google.com' } } },
      { uid: 'a', token: { email_verified: true, firebase: { sign_in_provider: 'password' } } }]) {
      await expect(authorize({ auth } as any)).rejects.toMatchObject({ code: 'permission-denied' });
    }
    expect(fake.data.size).toBe(0);
  });
  it('reserves usernames atomically and permits retry of the same profile', async () => {
    const req = (uid: string) => ({ auth: { uid, token: { email_verified: true, firebase: { sign_in_provider: 'google.com' } } }, data: { displayName: 'Alice', username: 'ALICE' } }) as any;
    const result = await Promise.allSettled([saveProfile.run(req('a')), saveProfile.run(req('b'))]);
    expect(result.filter(r => r.status === 'fulfilled')).toHaveLength(1);
    const owner = fake.data.get('usernames/alice').uid;
    await expect(saveProfile.run(req(owner))).resolves.toMatchObject({ profile: { username: 'alice' } });
  });
  it('previews without adding friends, then accepts once and rejects replay by another user', async () => {
    seed(proposal());
    fake.data.set('friendInvites/token', { uid: 'a', profile: { displayName: 'Alice' }, expiresAt: Date.now() + 10000 });
    const req = (uid: string, accept: boolean) => ({ auth: { uid, token: { email_verified: true, firebase: { sign_in_provider: 'google.com' } } }, data: { token: 'token', accept } }) as any;
    await friendInvite.run(req('b', false)); expect(fake.data.has('users/b/friends/a')).toBe(false);
    await friendInvite.run(req('b', true)); expect(fake.data.has('users/b/friends/a')).toBe(true);
    await expect(friendInvite.run(req('b', true))).resolves.toMatchObject({ accepted: true });
    await expect(friendInvite.run(req('c', true))).rejects.toMatchObject({ code: 'failed-precondition' });
    fake.data.get('friendInvites/token').expiresAt = 0;
    await expect(friendInvite.run(req('b', false))).rejects.toMatchObject({ code: 'not-found' });
  });
  it('enforces the combined global AI cap across concurrent accounts', async () => {
    const results = await Promise.allSettled(Array.from({ length: 105 }, (_, i) => consume(`user${i}`, 'ai')));
    expect(results.filter(r => r.status === 'fulfilled')).toHaveLength(100);
    expect(results.filter(r => r.status === 'rejected')).toHaveLength(5);
  });
  it('enforces burst limits without spending daily quota on rejected calls', async () => {
    await Promise.all(Array.from({ length: 5 }, () => consume('a', 'ai')));
    await expect(consume('a', 'ai')).rejects.toMatchObject({ code: 'resource-exhausted' });
    const global = [...fake.data.entries()].find(([key]) => key.includes('ai_global_'))!;
    expect(global[1].count).toBe(5);
  });
  it('resets the daily bucket at UTC midnight', () => {
    expect(quotaWindows('a', 'ai', Date.parse('2026-11-21T23:59:59Z'))[0].resetAt).toBe(Date.parse('2026-11-22T00:00:00Z'));
  });
  it('normalizes usernames and rejects invalid input', () => {
    expect(profileInput(' Alice ', 'Alice_1')).toEqual({ displayName: 'Alice', username: 'alice_1' });
    expect(() => profileInput('Alice', '../alice')).toThrow();
    expect(() => profileInput('', 'alice')).toThrow();
  });
  it('returns ambiguous matches rather than silently choosing a friend', () => {
    const friends = [{ uid: 'a', displayName: 'Alex Green', username: 'alexg' }, { uid: 'b', displayName: 'Alex Blue', username: 'alexb' }];
    expect(matchFriend('Alex', friends)).toHaveLength(2);
    expect(matchFriend('@alexg', friends).map(f => f.uid)).toEqual(['a']);
    expect(matchFriend('Alxe', friends)).toHaveLength(0);
  });
});
