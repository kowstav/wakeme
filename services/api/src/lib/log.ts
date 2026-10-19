import type { Logger } from '../deps.ts';

// Minimal structured JSON logger for the worker and app-level events. Field
// names listed here are always redacted: logs must never carry tokens,
// coordinates, quiz answers, prompts or message bodies.
const REDACT = new Set([
  'authorization',
  'token',
  'pushToken',
  'push_token',
  'password',
  'secret',
  'apiKey',
  'privateKey',
  'body',
  'prompt',
  'facts',
  'latitude',
  'longitude',
  'coordinates',
  'answer',
  'acceptedAnswers',
  'ciphertext',
  'signature',
]);

function scrub(value: unknown, depth = 0): unknown {
  if (depth > 6 || value === null || typeof value !== 'object') return value;
  if (value instanceof Error) return { name: value.name, message: value.message, code: (value as { code?: unknown }).code };
  if (Array.isArray(value)) return value.slice(0, 50).map((v) => scrub(v, depth + 1));
  const out: Record<string, unknown> = {};
  for (const [k, v] of Object.entries(value as Record<string, unknown>)) out[k] = REDACT.has(k) ? '[redacted]' : scrub(v, depth + 1);
  return out;
}

export function createLogger(base: Record<string, unknown> = {}, sink: (line: string) => void = (l) => process.stdout.write(l + '\n')): Logger {
  const write = (level: string, obj: object | string, msg?: string) => {
    const fields = typeof obj === 'string' ? { msg: obj } : { ...(scrub(obj) as object), ...(msg ? { msg } : {}) };
    sink(JSON.stringify({ level, time: new Date().toISOString(), ...base, ...fields }));
  };
  return {
    info: (o, m) => write('info', o, m),
    warn: (o, m) => write('warn', o, m),
    error: (o, m) => write('error', o, m),
  };
}

export const silentLogger: Logger = { info: () => undefined, warn: () => undefined, error: () => undefined };
