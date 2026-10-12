export type RequestSource = 'typed' | 'spoken';

export function createConversationSession(
  uid: string,
  storage: Pick<Storage, 'getItem' | 'setItem'>,
  makeId: () => string,
): { id: string; previous: string | null } {
  const key = `remind-conversation-id:${uid}`;
  const previous = storage.getItem(key);
  const id = makeId();
  storage.setItem(key, id);
  return { id, previous };
}

export function shouldSpeakReply(source: RequestSource, enabled: boolean): boolean {
  return enabled && source === 'spoken';
}
