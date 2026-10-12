import { describe, expect, it } from 'vitest';
import { purgeRelatedRecords, type RelatedRecordKind } from '../src/reminderDeletion';

type Row = { uid: string; reminderId: string; kind: RelatedRecordKind; id: number };

describe('reminder cleanup', () => {
  it('deletes every matching page and leaves another account untouched', async () => {
    const rows: Row[] = [
      ...Array.from({ length: 205 }, (_, id) => ({ uid: 'alice', reminderId: 'shared-id', kind: 'occurrences' as const, id })),
      { uid: 'bob', reminderId: 'shared-id', kind: 'occurrences', id: 999 },
      { uid: 'alice', reminderId: 'shared-id', kind: 'proposalsByTarget', id: 1000 },
      { uid: 'alice', reminderId: 'shared-id', kind: 'proposalsByResult', id: 1001 },
    ];
    const load = async (uid: string, reminderId: string, kind: RelatedRecordKind, limit: number) =>
      rows.filter(row => row.uid === uid && row.reminderId === reminderId && row.kind === kind).slice(0, limit);
    const remove = async (page: Row[]) => { page.forEach(row => rows.splice(rows.indexOf(row), 1)); };
    expect(await purgeRelatedRecords('alice', 'shared-id', load, remove)).toBe(207);
    expect(rows).toEqual([{ uid: 'bob', reminderId: 'shared-id', kind: 'occurrences', id: 999 }]);
  });

  it('resumes after a failed batch and safely repeats after completion', async () => {
    const rows: Row[] = Array.from({ length: 201 }, (_, id) => ({ uid: 'alice', reminderId: 'r1', kind: 'events', id }));
    const load = async (uid: string, reminderId: string, kind: RelatedRecordKind, limit: number) =>
      rows.filter(row => row.uid === uid && row.reminderId === reminderId && row.kind === kind).slice(0, limit);
    let attempts = 0;
    const remove = async (page: Row[]) => {
      if (++attempts === 2) throw new Error('temporary failure');
      page.forEach(row => rows.splice(rows.indexOf(row), 1));
    };
    await expect(purgeRelatedRecords('alice', 'r1', load, remove)).rejects.toThrow('temporary failure');
    expect(rows).toHaveLength(1);
    expect(await purgeRelatedRecords('alice', 'r1', load, remove)).toBe(1);
    expect(await purgeRelatedRecords('alice', 'r1', load, remove)).toBe(0);
  });
});
