export const relatedRecordKinds = ['occurrences', 'actions', 'events', 'proposalsByTarget', 'proposalsByResult'] as const;
export type RelatedRecordKind = typeof relatedRecordKinds[number];

export async function purgeRelatedRecords<T>(
  uid: string,
  reminderId: string,
  loadPage: (uid: string, reminderId: string, kind: RelatedRecordKind, limit: number) => Promise<T[]>,
  deletePage: (records: T[]) => Promise<void>,
): Promise<number> {
  let removed = 0;
  for (const kind of relatedRecordKinds) {
    for (;;) {
      const page = await loadPage(uid, reminderId, kind, 200);
      if (!page.length) break;
      await deletePage(page);
      removed += page.length;
    }
  }
  return removed;
}

export async function purgeLinkedConversations<T>(
  uid: string,
  reminderId: string,
  loadPage: (uid: string, reminderId: string, limit: number) => Promise<T[]>,
  deleteTree: (conversation: T) => Promise<void>,
): Promise<number> {
  let removed = 0;
  for (;;) {
    const page = await loadPage(uid, reminderId, 100);
    if (!page.length) return removed;
    for (const conversation of page) {
      await deleteTree(conversation);
      removed++;
    }
  }
}
