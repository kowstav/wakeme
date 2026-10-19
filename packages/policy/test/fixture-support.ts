import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { dirname, join } from 'node:path';

const here = dirname(fileURLToPath(import.meta.url));
export const FIXTURE_DIR = join(here, '..', '..', 'policy-fixtures', 'fixtures');

export function loadFixture<T = any>(name: string): T {
  return JSON.parse(readFileSync(join(FIXTURE_DIR, `${name}.json`), 'utf8')) as T;
}

/** Documented fixture merge: objects merge recursively; arrays, scalars and null replace. */
export function deepMerge(base: unknown, override: unknown): unknown {
  if (override === undefined) return structuredClone(base);
  if (override === null || typeof override !== 'object' || Array.isArray(override)) return structuredClone(override);
  if (base === null || typeof base !== 'object' || Array.isArray(base)) return structuredClone(override);
  const out: Record<string, unknown> = { ...(structuredClone(base) as Record<string, unknown>) };
  for (const [k, v] of Object.entries(override as Record<string, unknown>)) {
    out[k] = deepMerge((base as Record<string, unknown>)[k], v);
  }
  return out;
}
