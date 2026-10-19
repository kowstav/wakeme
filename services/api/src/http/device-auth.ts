import { createPublicKey, verify } from 'node:crypto';
import type { Queryable } from '../db/db.ts';
import { isUniqueViolation } from '../db/db.ts';
import { sha256Hex } from '../lib/crypto.ts';
import type { DeviceRow, UserRow } from '../modules/identity/repo.ts';
import { unauthorized } from './errors.ts';

export const DEVICE_HEADER = 'x-wakeme-device';
export const TIMESTAMP_HEADER = 'x-wakeme-timestamp';
export const SIGNATURE_HEADER = 'x-wakeme-signature';
export const MAX_SKEW_SECONDS = 300;

/**
 * String a device signs with its hardware-backed P-256 key (Secure Enclave /
 * Android Keystore). Binding method, path, time and body hash stops a stolen
 * receipt from being replayed against another command or replayed later.
 */
export function canonicalDeviceRequest(method: string, pathWithQuery: string, timestamp: string, body: Buffer | string): string {
  return ['WAKEME-V1', method.toUpperCase(), pathWithQuery, timestamp, sha256Hex(body)].join('\n');
}

export function isValidPublicKey(spkiBase64: string): boolean {
  try {
    const key = createPublicKey({ key: Buffer.from(spkiBase64, 'base64'), format: 'der', type: 'spki' });
    return key.asymmetricKeyType === 'ec' && key.asymmetricKeyDetails?.namedCurve === 'prime256v1';
  } catch {
    return false;
  }
}

export interface DeviceRequest {
  method: string;
  url: string;
  headers: Record<string, string | string[] | undefined>;
  rawBody: Buffer;
}

function header(h: DeviceRequest['headers'], name: string): string | undefined {
  const v = h[name];
  return Array.isArray(v) ? v[0] : v;
}

/**
 * Verifies that the request was signed by a registered, non-revoked device
 * owned by the authenticated user, within the skew window, exactly once.
 */
export async function verifyDeviceRequest(q: Queryable, user: UserRow, req: DeviceRequest, now: Date): Promise<DeviceRow> {
  const deviceId = header(req.headers, DEVICE_HEADER);
  const ts = header(req.headers, TIMESTAMP_HEADER);
  const sig = header(req.headers, SIGNATURE_HEADER);
  if (!deviceId || !ts || !sig) throw unauthorized('device_signature_required', 'This action must come from your registered phone.');
  if (!/^[0-9a-f-]{36}$/.test(deviceId) || !/^\d{9,11}$/.test(ts) || sig.length > 200) throw unauthorized('device_signature_invalid', 'Device signature is invalid.');

  const skew = Math.abs(Math.floor(now.getTime() / 1000) - Number(ts));
  if (skew > MAX_SKEW_SECONDS) throw unauthorized('device_clock_skew', "Your phone's clock looks wrong. Check date and time settings.");

  const r = await q.query<DeviceRow>('select * from devices where id = $1 and user_id = $2', [deviceId, user.id]);
  const device = r.rows[0];
  if (!device || device.revoked_at) throw unauthorized('device_not_registered', 'This phone is signed out. Sign in again on it.');

  const key = createPublicKey({ key: Buffer.from(device.public_key, 'base64'), format: 'der', type: 'spki' });
  const message = canonicalDeviceRequest(req.method, req.url, ts, req.rawBody);
  let ok = false;
  try {
    ok = verify('sha256', Buffer.from(message, 'utf8'), { key, dsaEncoding: 'der' }, Buffer.from(sig, 'base64'));
  } catch {
    ok = false;
  }
  if (!ok) throw unauthorized('device_signature_invalid', 'Device signature is invalid.');

  try {
    await q.query('insert into device_nonces (device_id, signature_hash) values ($1, $2)', [device.id, sha256Hex(sig)]);
  } catch (e) {
    if (isUniqueViolation(e)) throw unauthorized('device_request_replayed', 'This request was already used.');
    throw e;
  }
  await q.query('update devices set last_seen_at = $2 where id = $1', [device.id, now]);
  return device;
}
