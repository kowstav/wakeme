import { connect, constants, type ClientHttp2Session } from 'node:http2';
import { importPKCS8, SignJWT } from 'jose';
import type { ApnsConfig } from '../config.ts';
import type { ProviderClient, PushMessage, PushResult, PushTarget } from './gateway.ts';

const TOKEN_INVALID_REASONS = new Set(['BadDeviceToken', 'Unregistered', 'DeviceTokenNotForTopic', 'ExpiredToken']);
const REFRESH_REASONS = new Set(['ExpiredProviderToken', 'InvalidProviderToken']);

/**
 * Direct APNs over HTTP/2 with token-based (.p8) authentication. Provider
 * tokens are reused for 50 minutes (Apple requires 20-60). The connection is
 * kept open and re-established on failure.
 */
export class ApnsClient implements ProviderClient {
  private readonly cfg: ApnsConfig;
  private jwt: { token: string; at: number } | null = null;
  private readonly sessions = new Map<string, ClientHttp2Session>();

  constructor(cfg: ApnsConfig) {
    this.cfg = cfg;
  }

  private origin(target: PushTarget): string {
    if (this.cfg.origin) return this.cfg.origin;
    return target.environment === 'sandbox' ? 'https://api.sandbox.push.apple.com' : 'https://api.push.apple.com';
  }

  private async providerToken(force = false): Promise<string> {
    const now = Date.now();
    if (!force && this.jwt && now - this.jwt.at < 50 * 60_000) return this.jwt.token;
    const key = await importPKCS8(this.cfg.privateKey, 'ES256');
    const token = await new SignJWT({})
      .setProtectedHeader({ alg: 'ES256', kid: this.cfg.keyId })
      .setIssuer(this.cfg.teamId)
      .setIssuedAt(Math.floor(now / 1000))
      .sign(key);
    this.jwt = { token, at: now };
    return token;
  }

  private session(origin: string): ClientHttp2Session {
    const existing = this.sessions.get(origin);
    if (existing && !existing.closed && !existing.destroyed) return existing;
    const s = connect(origin);
    s.on('error', () => this.sessions.delete(origin));
    s.on('close', () => this.sessions.delete(origin));
    s.on('goaway', () => this.sessions.delete(origin));
    s.unref();
    this.sessions.set(origin, s);
    return s;
  }

  static payload(message: PushMessage): Record<string, unknown> {
    if (message.background || !message.alert) return { aps: { 'content-available': 1 }, wm: message.data };
    const a = message.alert;
    const aps: Record<string, unknown> = {
      alert: {
        title: a.title,
        body: a.body,
        ...(a.titleLocKey ? { "title-loc-key": a.titleLocKey, "title-loc-args": a.locArgs ?? [] } : {}),
        ...(a.locKey ? { "loc-key": a.locKey, "loc-args": a.locArgs ?? [] } : {}),
      },
      'interruption-level': a.interruption,
      'mutable-content': 1,
    };
    if (a.sound) aps['sound'] = a.sound;
    if (a.category) aps['category'] = a.category;
    if (a.threadId) aps['thread-id'] = a.threadId;
    return { aps, wm: message.data };
  }

  async send(target: PushTarget, message: PushMessage, retried = false): Promise<PushResult> {
    if (!/^[0-9a-fA-F]{64,200}$/.test(target.token)) return { status: 'rejected', reason: 'BadDeviceToken', tokenInvalid: true };
    const origin = this.origin(target);
    const body = JSON.stringify(ApnsClient.payload(message));
    if (Buffer.byteLength(body) > 4096) return { status: 'rejected', reason: 'PayloadTooLarge', tokenInvalid: false };
    const headers: Record<string, string> = {
      [constants.HTTP2_HEADER_METHOD]: 'POST',
      [constants.HTTP2_HEADER_PATH]: `/3/device/${target.token}`,
      authorization: `bearer ${await this.providerToken()}`,
      'apns-topic': this.cfg.topic,
      'apns-push-type': message.background || !message.alert ? 'background' : 'alert',
      'apns-priority': message.background || !message.alert ? '5' : message.priority === 'high' ? '10' : '5',
      'apns-expiration': message.expiresAt ? String(Math.floor(message.expiresAt.getTime() / 1000)) : '0',
      'content-type': 'application/json',
    };
    if (message.collapseId) headers['apns-collapse-id'] = message.collapseId.slice(0, 64);

    const { status, responseHeaders, text } = await new Promise<{ status: number; responseHeaders: Record<string, unknown>; text: string }>((resolve, reject) => {
      const req = this.session(origin).request(headers);
      req.setTimeout(10_000, () => req.close(constants.NGHTTP2_CANCEL));
      let status = 0;
      let responseHeaders: Record<string, unknown> = {};
      const chunks: Buffer[] = [];
      req.on('response', (h) => {
        status = Number(h[constants.HTTP2_HEADER_STATUS]);
        responseHeaders = h as Record<string, unknown>;
      });
      req.on('data', (c: Buffer) => chunks.push(c));
      req.on('end', () => resolve({ status, responseHeaders, text: Buffer.concat(chunks).toString('utf8') }));
      req.on('error', reject);
      req.on('close', () => {
        if (status === 0) reject(new Error('apns_stream_closed'));
      });
      req.end(body);
    });

    if (status === 200) {
      const id = responseHeaders['apns-id'];
      return typeof id === 'string' ? { status: 'accepted', providerId: id } : { status: 'accepted' };
    }
    let reason = `http_${status}`;
    try {
      reason = (JSON.parse(text) as { reason?: string }).reason ?? reason;
    } catch {
      /* non-JSON body */
    }
    if (status === 410 || TOKEN_INVALID_REASONS.has(reason)) return { status: 'rejected', reason, tokenInvalid: true };
    if (status === 403 && REFRESH_REASONS.has(reason) && !retried) {
      await this.providerToken(true);
      return this.send(target, message, true);
    }
    if (status === 429 || status >= 500) return { status: 'retry', reason };
    return { status: 'rejected', reason, tokenInvalid: false };
  }

  close(): void {
    for (const s of this.sessions.values()) s.close();
    this.sessions.clear();
  }
}
