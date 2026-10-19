// Writes packages/policy-fixtures/fixtures/signed-envelopes.json: one envelope
// signed by the server's own signEnvelope with a throwaway test key, plus
// tampered variants. Server, Android and iOS all verify the same bytes.
// The key is generated per run and only its public half is kept: this is a
// test vector, never a production key. Re-run only to deliberately rotate.
//   node packages/challenges/scripts/generate-envelope-fixture.ts
import { generateKeyPairSync } from 'node:crypto';
import { writeFileSync } from 'node:fs';
import { buildTemplate, publicKeyDerBase64, sha256Hex, signEnvelope, validateChallenge } from '../src/index.ts';

const { privateKey } = generateKeyPairSync('ec', { namedCurve: 'prime256v1' });
const { privateKey: otherKey } = generateKeyPairSync('ec', { namedCurve: 'prime256v1' });
const keyId = 'fixture-key-1';

const definition = buildTemplate('morning-math', { difficulty: 'easy', seed: 7 });
const check = validateChallenge(definition);
if (!check.ok) throw new Error('template must validate: ' + JSON.stringify(check));
const body = {
  revisionId: '11111111-1111-4111-8111-111111111111',
  definitionId: '22222222-2222-4222-8222-222222222222',
  revision: 3,
  definition,
};
const valid = { revisionId: body.revisionId, ...signEnvelope(body, privateKey, keyId) };

const flipped = Buffer.from(valid.payload, 'base64');
flipped[flipped.length - 2] = flipped[flipped.length - 2]! ^ 0x01;
const tamperedPayload = Buffer.from(valid.payload, 'base64').toString('utf8').replace('"revision":3', '"revision":4');
const resignedByOther = signEnvelope(body, otherKey, keyId);

const cases = [
  { name: 'valid envelope verifies and parses', envelope: valid, expected: { ok: true, revision: 3, steps: definition.steps.length } },
  { name: 'payload byte flipped (digest no longer matches)', envelope: { ...valid, payload: flipped.toString('base64') }, expected: { ok: false, error: 'digest_mismatch' } },
  {
    name: 'payload edited and digest recomputed (signature no longer matches)',
    envelope: { ...valid, payload: Buffer.from(tamperedPayload).toString('base64'), digest: sha256Hex(Buffer.from(tamperedPayload)) },
    expected: { ok: false, error: 'bad_signature' },
  },
  { name: 'signed by an untrusted key under a trusted key id', envelope: { revisionId: body.revisionId, ...resignedByOther }, expected: { ok: false, error: 'bad_signature' } },
  { name: 'unknown key id', envelope: { ...valid, keyId: 'retired-key' }, expected: { ok: false, error: 'unknown_key' } },
  { name: 'unsupported algorithm', envelope: { ...valid, alg: 'none' }, expected: { ok: false, error: 'unsupported_algorithm' } },
  { name: 'envelope revision id differs from the signed payload', envelope: { ...valid, revisionId: '33333333-3333-4333-8333-333333333333' }, expected: { ok: false, error: 'revision_mismatch' } },
];

const out = {
  description: 'ES256 challenge envelopes signed by packages/challenges signEnvelope. trustedKeys maps keyId to base64 SPKI DER.',
  trustedKeys: { [keyId]: publicKeyDerBase64(privateKey) },
  cases,
};
writeFileSync(new URL('../../policy-fixtures/fixtures/signed-envelopes.json', import.meta.url), JSON.stringify(out, null, 2) + '\n');
console.log(`wrote ${cases.length} envelope cases`);
