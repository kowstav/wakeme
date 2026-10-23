import assert from 'node:assert/strict';
import { createPublicKey } from 'node:crypto';
import { readFileSync } from 'node:fs';
import { describe, it } from 'node:test';
import { verifyEnvelope } from '../src/index.ts';

// The same vectors are verified by Android (:core) and iOS (WakeMeCore).
const fx = JSON.parse(readFileSync(new URL('../../policy-fixtures/fixtures/signed-envelopes.json', import.meta.url), 'utf8')) as {
  trustedKeys: Record<string, string>;
  cases: { name: string; envelope: { revisionId: string; payload: string; signature: string; keyId: string; digest: string; alg: string }; expected: { ok: boolean; error?: string; revision?: number } }[];
};

describe('shared signed-envelope fixture', () => {
  for (const c of fx.cases) {
    // Key, algorithm and revision-id checks happen on devices; the server's verifier covers digest and signature.
    if (!['digest_mismatch', 'bad_signature', undefined].includes(c.expected.error)) continue;
    it(c.name, () => {
      const key = createPublicKey({ key: Buffer.from(fx.trustedKeys[c.envelope.keyId]!, 'base64'), format: 'der', type: 'spki' });
      if (c.expected.ok) {
        const body = verifyEnvelope({ ...c.envelope, alg: 'ES256' }, key) as { revision: number; revisionId: string };
        assert.equal(body.revision, c.expected.revision);
        assert.equal(body.revisionId, c.envelope.revisionId);
      } else {
        assert.throws(() => verifyEnvelope({ ...c.envelope, alg: 'ES256' }, key), c.expected.error === 'digest_mismatch' ? /digest mismatch/ : /bad signature/);
      }
    });
  }
});
