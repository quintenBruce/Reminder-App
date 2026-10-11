import { randomUUID } from 'node:crypto';
import { error, info, warn } from 'firebase-functions/logger';
import { HttpsError } from 'firebase-functions/v2/https';

type Field = string | number | boolean | null | undefined;
type Fields = Record<string, Field>;

export type Trace = {
  stage: (name: string, fields?: Fields) => void;
};

// Error messages and stacks can contain user content or provider request data.
export function errorFields(reason: unknown): { errorCode: string; errorType: string } {
  if (reason instanceof HttpsError) return { errorCode: reason.code, errorType: 'HttpsError' };
  const rawCode = typeof reason === 'object' && reason !== null && 'code' in reason &&
    (typeof reason.code === 'string' || typeof reason.code === 'number')
    ? String(reason.code) : 'unknown';
  const code = /^[a-zA-Z0-9_/-]{1,100}$/.test(rawCode) ? rawCode : 'unknown';
  return { errorCode: code, errorType: 'unexpected' };
}

export async function observe<T>(operation: string, uid: string | undefined, work: (trace: Trace) => Promise<T>, fields: Fields = {}): Promise<T> {
  const operationId = randomUUID();
  const startedAt = Date.now();
  const base = { operation, operationId, uid: uid ?? null, ...fields };
  let lastStage = 'started';
  info('operation.started', base);
  const trace: Trace = {
    stage(name, details = {}) {
      lastStage = name;
      info('operation.stage', { ...base, stage: name, elapsedMs: Date.now() - startedAt, ...details });
    },
  };
  try {
    const result = await work(trace);
    info('operation.succeeded', { ...base, stage: lastStage, durationMs: Date.now() - startedAt });
    return result;
  } catch (reason) {
    const log = reason instanceof HttpsError ? warn : error;
    log('operation.failed', { ...base, stage: lastStage, durationMs: Date.now() - startedAt, ...errorFields(reason) });
    throw reason;
  }
}
