import { describe, expect, it, vi } from 'vitest';
import { HttpsError } from 'firebase-functions/v2/https';

const logs = vi.hoisted(() => ({ info: vi.fn(), warn: vi.fn(), error: vi.fn() }));
vi.mock('firebase-functions/logger', () => logs);
import { observe } from '../src/observability';

describe('backend operation logs', () => {
  it('correlates stages and success without logging returned content', async () => {
    logs.info.mockClear();
    const result = await observe('transcribeAudio', 'uid-1', async trace => {
      trace.stage('transcript.validated', { transcriptLength: 12 });
      return { transcript: 'private text' };
    });
    expect(result.transcript).toBe('private text');
    const entries = logs.info.mock.calls;
    expect(entries.map(([message]) => message)).toEqual(['operation.started', 'operation.stage', 'operation.succeeded']);
    expect(new Set(entries.map(([, details]) => details.operationId)).size).toBe(1);
    expect(JSON.stringify(entries)).not.toContain('private text');
  });

  it('logs only safe error codes and rethrows the original error', async () => {
    logs.error.mockClear();
    const failure = Object.assign(new Error('secret audio and token'), { code: 'bad code: secret' });
    await expect(observe('transcribeAudio', undefined, async () => { throw failure; })).rejects.toBe(failure);
    expect(logs.error).toHaveBeenCalledWith('operation.failed', expect.objectContaining({ errorCode: 'unknown', errorType: 'unexpected' }));
    expect(JSON.stringify(logs.error.mock.calls)).not.toContain('secret');
  });

  it('records client errors as warnings with their public status code', async () => {
    logs.warn.mockClear();
    await expect(observe('confirmProposal', 'uid-1', async () => {
      throw new HttpsError('not-found', 'Private proposal detail');
    })).rejects.toBeInstanceOf(HttpsError);
    expect(logs.warn).toHaveBeenCalledWith('operation.failed', expect.objectContaining({ errorCode: 'not-found' }));
    expect(JSON.stringify(logs.warn.mock.calls)).not.toContain('Private proposal detail');
  });
});
