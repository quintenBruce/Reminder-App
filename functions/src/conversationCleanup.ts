export function canConfirmDraft(state: unknown): boolean {
  return state === 'draft';
}

export function canDiscardDraft(state: unknown, exists: boolean): boolean {
  return !exists || state === 'draft' || state === 'deleting';
}

export async function purgeLegacyConversations<T>(
  documents: T[],
  schemaVersion: (document: T) => Promise<number | undefined>,
  deleteTree: (document: T) => Promise<void>,
): Promise<number> {
  let deleted = 0;
  for (const document of documents) {
    if (await schemaVersion(document) === 2) continue;
    await deleteTree(document);
    deleted++;
  }
  return deleted;
}

export async function purgeConversationProposals<T>(
  uid: string,
  conversationId: string,
  loadPage: (uid: string, conversationId: string, limit: number) => Promise<T[]>,
  deletePage: (proposals: T[]) => Promise<void>,
): Promise<number> {
  let deleted = 0;
  for (;;) {
    const page = await loadPage(uid, conversationId, 200);
    if (!page.length) return deleted;
    await deletePage(page);
    deleted += page.length;
  }
}
