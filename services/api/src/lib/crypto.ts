import { createCipheriv, createDecipheriv, createHash, randomBytes, randomInt, timingSafeEqual } from 'node:crypto';

export function sha256Hex(data: string | Buffer): string {
  return createHash('sha256').update(data).digest('hex');
}

export function randomToken(bytes = 32): string {
  return randomBytes(bytes).toString('base64url');
}

// Crockford-style alphabet without easily confused characters (no I, L, O, U, 0, 1).
const CODE_ALPHABET = 'ABCDEFGHJKMNPQRSTVWXYZ23456789';

/** Short human-typable code (~44 bits for 9 chars) for invite recovery when deep links fail. */
export function randomCode(length = 9): string {
  let out = '';
  for (let i = 0; i < length; i++) out += CODE_ALPHABET[randomInt(CODE_ALPHABET.length)];
  return out;
}

export function normalizeInviteCode(input: string): string {
  return input.toUpperCase().replace(/[^A-Z0-9]/g, '');
}

export function constantTimeEqual(a: string, b: string): boolean {
  const ab = Buffer.from(a, 'utf8');
  const bb = Buffer.from(b, 'utf8');
  if (ab.length !== bb.length) {
    timingSafeEqual(ab, ab);
    return false;
  }
  return timingSafeEqual(ab, bb);
}

export interface SealedValue {
  ciphertext: string;
  nonce: string;
  keyId: string;
}

/**
 * AES-256-GCM application-layer encryption for location coordinates, with the
 * session id as associated data so a ciphertext cannot be moved to another
 * session. In production the key is supplied by the secret manager and
 * rotated by key id; decrypt access is limited to the viewer endpoint.
 */
export function seal(plaintext: string, keyBase64: string, keyId: string, aad: string): SealedValue {
  const key = Buffer.from(keyBase64, 'base64');
  const nonce = randomBytes(12);
  const cipher = createCipheriv('aes-256-gcm', key, nonce);
  cipher.setAAD(Buffer.from(aad, 'utf8'));
  const ct = Buffer.concat([cipher.update(plaintext, 'utf8'), cipher.final(), cipher.getAuthTag()]);
  return { ciphertext: ct.toString('base64'), nonce: nonce.toString('base64'), keyId };
}

export function open(sealed: SealedValue, keyBase64: string, aad: string): string {
  const key = Buffer.from(keyBase64, 'base64');
  const data = Buffer.from(sealed.ciphertext, 'base64');
  const tag = data.subarray(data.length - 16);
  const ct = data.subarray(0, data.length - 16);
  const decipher = createDecipheriv('aes-256-gcm', key, Buffer.from(sealed.nonce, 'base64'));
  decipher.setAAD(Buffer.from(aad, 'utf8'));
  decipher.setAuthTag(tag);
  return Buffer.concat([decipher.update(ct), decipher.final()]).toString('utf8');
}
