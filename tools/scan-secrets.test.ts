import { describe, it } from 'node:test';
import assert from 'node:assert/strict';
import { scanText } from './scan-secrets.ts';

// Look-alike values are assembled at runtime so this file doesn't trip the scan itself.
const jwt = (claims: object) =>
  [Buffer.from('{"alg":"HS256"}').toString('base64url'), Buffer.from(JSON.stringify(claims)).toString('base64url'), 'x'.repeat(24)].join('.');

describe('secret scan', () => {
  it('finds private keys, cloud keys and service-role tokens, and masks them', () => {
    const text = ['-----BEGIN ' + 'PRIVATE KEY-----', 'key = "' + 'AKIA' + 'Q'.repeat(16) + '"', 'SUPABASE_SERVICE_ROLE=' + jwt({ role: 'service_role' })].join('\n');
    const found = scanText('x.env', text);
    assert.deepEqual(found.map((f) => [f.line, f.rule]), [[1, 'private-key'], [2, 'aws-access-key'], [3, 'service-role-jwt']]);
    assert.ok(found.every((f) => f.excerpt.length <= 7), 'reports never repeat the value');
  });

  it('allows publishable keys and marked look-alikes', () => {
    assert.deepEqual(scanText('a.ts', 'anon = "' + jwt({ role: 'anon' }) + '"'), [], 'the Supabase anon key is publishable');
    assert.deepEqual(scanText('b.ts', 'id = "' + 'AKIA' + 'Q'.repeat(16) + '" // secret-scan: allow'), []);
  });
});
