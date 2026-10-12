import { describe, expect, it } from 'vitest';
import { canConfirmDraft, canDiscardDraft, purgeConversationProposals, purgeLegacyConversations } from '../src/conversationCleanup';

describe('legacy conversation cleanup', () => {
  it('keeps a linked chat when an old page tries to discard its draft', () => {
    expect(canConfirmDraft('draft')).toBe(true);
    expect(canConfirmDraft('deleting')).toBe(false);
    expect(canDiscardDraft('linked', true)).toBe(false);
    expect(canDiscardDraft('draft', true)).toBe(true);
    expect(canDiscardDraft('deleting', true)).toBe(true);
  });
  it('deletes old and missing-parent trees while retaining new linked sessions', async () => {
    const sessions = [
      { id: 'old', version: undefined },
      { id: 'old-with-parent', version: 1 },
      { id: 'new', version: 2 },
    ];
    const deleted: string[] = [];
    const count = await purgeLegacyConversations(sessions, async session => session.version,
      async session => { deleted.push(session.id); });
    expect(count).toBe(2);
    expect(deleted).toEqual(['old', 'old-with-parent']);
  });

  it('can retry after partial failure', async () => {
    const remaining = ['first', 'second'];
    let failed = false;
    const run = () => purgeLegacyConversations([...remaining], async () => undefined, async session => {
      if (session === 'second' && !failed) { failed = true; throw new Error('temporary failure'); }
      remaining.splice(remaining.indexOf(session), 1);
    });
    await expect(run()).rejects.toThrow('temporary failure');
    expect(remaining).toEqual(['second']);
    expect(await run()).toBe(1);
  });

  it('purges every proposal page for only the selected account and draft', async () => {
    const proposals = [
      ...Array.from({ length: 202 }, (_, id) => ({ uid: 'alice', conversationId: 'draft', id })),
      { uid: 'bob', conversationId: 'draft', id: 500 },
      { uid: 'alice', conversationId: 'other', id: 501 },
    ];
    const load = async (uid: string, id: string, limit: number) => proposals
      .filter(item => item.uid === uid && item.conversationId === id).slice(0, limit);
    const remove = async (page: typeof proposals) => { page.forEach(item => proposals.splice(proposals.indexOf(item), 1)); };
    expect(await purgeConversationProposals('alice', 'draft', load, remove)).toBe(202);
    expect(proposals.map(item => item.id)).toEqual([500, 501]);
  });
});
