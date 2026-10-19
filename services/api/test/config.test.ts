import { describe, it } from 'node:test';
import assert from 'node:assert/strict';
import { generateKeyPairSync, randomBytes } from 'node:crypto';
import { ConfigError, loadConfig } from '../src/config.ts';

function baseEnv(extra: Record<string, string> = {}): NodeJS.ProcessEnv {
  const { privateKey } = generateKeyPairSync('ec', { namedCurve: 'prime256v1' });
  return {
    DATABASE_URL: 'postgres://localhost/wakeme',
    AUTH_JWKS_URL: 'https://auth.example/jwks',
    AUTH_ISSUER: 'https://auth.example',
    CHALLENGE_SIGNING_KEY: privateKey.export({ type: 'pkcs8', format: 'pem' }).toString(),
    LOCATION_ENCRYPTION_KEY: randomBytes(32).toString('base64'),
    ...extra,
  };
}

function spki(curve: string): string {
  const { publicKey } = generateKeyPairSync('ec', { namedCurve: curve });
  return publicKey.export({ type: 'spki', format: 'der' }).toString('base64');
}

describe('config: retired challenge verification keys', () => {
  it('is empty unless set', () => {
    assert.deepEqual(loadConfig(baseEnv()).challengeVerifyKeys, []);
  });

  it('keeps each key exactly, base64 padding included', () => {
    const a = spki('prime256v1');
    const b = spki('prime256v1');
    assert.ok(a.endsWith('='), 'a P-256 SPKI is 91 bytes, so its base64 is padded');
    const keys = loadConfig(baseEnv({ CHALLENGE_VERIFY_KEYS: `c0=${a}, c-old = ${b}` })).challengeVerifyKeys;
    assert.deepEqual(keys, [{ keyId: 'c0', spki: a }, { keyId: 'c-old', spki: b }]);
  });

  it('rejects anything that is not keyId=EC P-256 SPKI', () => {
    for (const bad of ['c0', `=${spki('prime256v1')}`, 'c0=bm90IGEga2V5', `c0=${spki('secp384r1')}`]) {
      assert.throws(() => loadConfig(baseEnv({ CHALLENGE_VERIFY_KEYS: bad })), ConfigError, bad);
    }
  });
});
