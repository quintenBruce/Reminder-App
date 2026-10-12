import { describe, expect, it } from 'vitest';
import { createConversationSession, shouldSpeakReply } from '../src/conversationFlow';

describe('conversation flow', () => {
  it('starts fresh on each load and after a change without mixing accounts', () => {
    const values = new Map<string, string>();
    const storage = { getItem: (key: string) => values.get(key) ?? null,
      setItem: (key: string, value: string) => { values.set(key, value); } };
    expect(createConversationSession('alice', storage, () => 'first')).toEqual({ id: 'first', previous: null });
    expect(createConversationSession('alice', storage, () => 'second')).toEqual({ id: 'second', previous: 'first' });
    expect(createConversationSession('bob', storage, () => 'third')).toEqual({ id: 'third', previous: null });
  });

  it('speaks only for an enabled spoken request', () => {
    expect(shouldSpeakReply('spoken', true)).toBe(true);
    expect(shouldSpeakReply('spoken', false)).toBe(false);
    expect(shouldSpeakReply('typed', true)).toBe(false);
  });
});
