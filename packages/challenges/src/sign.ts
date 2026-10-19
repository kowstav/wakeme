import { createHash, createPrivateKey, createPublicKey, sign, verify, type KeyObject } from 'node:crypto';

/**
 * Canonical JSON: object keys sorted by UTF-16 code units, no whitespace,
 * integers only. Challenge payloads never contain floats (schema-enforced),
 * so this matches RFC 8785 for every value we sign.
 */
export function canonicalJson(value: unknown): string {
  if (value === null) return 'null';
  switch (typeof value) {
    case 'boolean':
      return value ? 'true' : 'false';
    case 'number':
      if (!Number.isSafeInteger(value)) throw new Error('canonical JSON only supports safe integers');
      return String(value);
    case 'string':
      return JSON.stringify(value);
    case 'object': {
      if (Array.isArray(value)) return `[${value.map(canonicalJson).join(',')}]`;
      const entries = Object.entries(value as Record<string, unknown>)
        .filter(([, v]) => v !== undefined)
        .sort(([a], [b]) => (a < b ? -1 : a > b ? 1 : 0));
      return `{${entries.map(([k, v]) => `${JSON.stringify(k)}:${canonicalJson(v)}`).join(',')}}`;
    }
    default:
      throw new Error(`cannot canonicalize ${typeof value}`);
  }
}

export function sha256Hex(data: string | Buffer): string {
  return createHash('sha256').update(data).digest('hex');
}

/**
 * A published challenge revision as delivered to devices. Devices verify the
 * ECDSA P-256 (SHA-256, DER) signature over the exact payload bytes and only
 * then parse them, so no client-side canonicalization is needed. A signature
 * detects tampering; it does not make content safe (validation does that).
 */
export interface SignedEnvelope {
  /** base64 of the canonical JSON bytes */
  payload: string;
  /** base64 DER ECDSA signature */
  signature: string;
  keyId: string;
  /** sha256 hex of the payload bytes */
  digest: string;
  alg: 'ES256';
}

export function loadSigningKey(pem: string): KeyObject {
  const key = createPrivateKey(pem);
  if (key.asymmetricKeyType !== 'ec' || key.asymmetricKeyDetails?.namedCurve !== 'prime256v1') {
    throw new Error('challenge signing key must be an EC P-256 private key');
  }
  return key;
}

export function publicKeyDerBase64(privateOrPublic: KeyObject): string {
  const pub = privateOrPublic.type === 'private' ? createPublicKey(privateOrPublic) : privateOrPublic;
  return pub.export({ type: 'spki', format: 'der' }).toString('base64');
}

export function signEnvelope(body: unknown, key: KeyObject, keyId: string): SignedEnvelope {
  const bytes = Buffer.from(canonicalJson(body), 'utf8');
  const signature = sign('sha256', bytes, { key, dsaEncoding: 'der' });
  return { payload: bytes.toString('base64'), signature: signature.toString('base64'), keyId, digest: sha256Hex(bytes), alg: 'ES256' };
}

export function verifyEnvelope(env: SignedEnvelope, publicKey: KeyObject): unknown {
  const bytes = Buffer.from(env.payload, 'base64');
  if (sha256Hex(bytes) !== env.digest) throw new Error('digest mismatch');
  const ok = verify('sha256', bytes, { key: publicKey, dsaEncoding: 'der' }, Buffer.from(env.signature, 'base64'));
  if (!ok) throw new Error('bad signature');
  return JSON.parse(bytes.toString('utf8'));
}
