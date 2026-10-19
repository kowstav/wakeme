import { importPKCS8, SignJWT } from 'jose';
import type { FcmConfig } from '../config.ts';
import type { ProviderClient, PushMessage, PushResult, PushTarget } from './gateway.ts';

const SCOPE = 'https://www.googleapis.com/auth/firebase.messaging';

/**
 * FCM HTTP v1. Android always receives data-only messages so the app can
 * re-validate the command against its local grant cache before showing or
 * playing anything. High priority is used only for user-visible requests,
 * since FCM deprioritises apps that misuse it.
 */
export class FcmClient implements ProviderClient {
  private readonly cfg: FcmConfig;
  private access: { token: string; expiresAt: number } | null = null;
  private readonly fetchImpl: typeof fetch;

  constructor(cfg: FcmConfig, fetchImpl: typeof fetch = fetch) {
    this.cfg = cfg;
    this.fetchImpl = fetchImpl;
  }

  private get tokenUri(): string {
    return this.cfg.tokenUri ?? 'https://oauth2.googleapis.com/token';
  }

  private async accessToken(force = false): Promise<string> {
    const now = Date.now();
    if (!force && this.access && now < this.access.expiresAt - 5 * 60_000) return this.access.token;
    const key = await importPKCS8(this.cfg.privateKey, 'RS256');
    const iat = Math.floor(now / 1000);
    const assertion = await new SignJWT({ scope: SCOPE })
      .setProtectedHeader({ alg: 'RS256', typ: 'JWT' })
      .setIssuer(this.cfg.clientEmail)
      .setAudience(this.tokenUri)
      .setIssuedAt(iat)
      .setExpirationTime(iat + 3600)
      .sign(key);
    const res = await this.fetchImpl(this.tokenUri, {
      method: 'POST',
      headers: { 'content-type': 'application/x-www-form-urlencoded' },
      body: new URLSearchParams({ grant_type: 'urn:ietf:params:oauth:grant-type:jwt-bearer', assertion }).toString(),
      signal: AbortSignal.timeout(10_000),
    });
    if (!res.ok) throw new Error(`fcm_oauth_${res.status}`);
    const json = (await res.json()) as { access_token: string; expires_in: number };
    this.access = { token: json.access_token, expiresAt: now + json.expires_in * 1000 };
    return json.access_token;
  }

  static body(target: PushTarget, message: PushMessage, nowMs: number): Record<string, unknown> {
    const ttlSeconds = message.expiresAt ? Math.max(0, Math.floor((message.expiresAt.getTime() - nowMs) / 1000)) : 3600;
    const data: Record<string, string> = { ...message.data };
    if (message.alert) {
      data['title'] = message.alert.title;
      data['body'] = message.alert.body;
      data['interruption'] = message.alert.interruption;
      if (message.alert.sound) data['sound'] = message.alert.sound;
      if (message.alert.locKey) data['locKey'] = message.alert.locKey;
      if (message.alert.titleLocKey) data['titleLocKey'] = message.alert.titleLocKey;
      if (message.alert.locArgs) data['locArgs'] = JSON.stringify(message.alert.locArgs);
    }
    const android: Record<string, unknown> = { priority: message.priority === 'high' ? 'HIGH' : 'NORMAL', ttl: `${ttlSeconds}s` };
    if (message.collapseId) android['collapse_key'] = message.collapseId.slice(0, 64);
    return { message: { token: target.token, data, android } };
  }

  async send(target: PushTarget, message: PushMessage, retried = false): Promise<PushResult> {
    const token = await this.accessToken();
    const origin = this.cfg.sendOrigin ?? 'https://fcm.googleapis.com';
    const res = await this.fetchImpl(`${origin}/v1/projects/${encodeURIComponent(this.cfg.projectId)}/messages:send`, {
      method: 'POST',
      headers: { authorization: `Bearer ${token}`, 'content-type': 'application/json' },
      body: JSON.stringify(FcmClient.body(target, message, Date.now())),
      signal: AbortSignal.timeout(10_000),
    });
    if (res.ok) {
      const json = (await res.json().catch(() => ({}))) as { name?: string };
      return json.name ? { status: 'accepted', providerId: json.name } : { status: 'accepted' };
    }
    const err = (await res.json().catch(() => ({}))) as { error?: { status?: string; details?: { errorCode?: string }[] } };
    const code = err.error?.details?.find((d) => d.errorCode)?.errorCode ?? err.error?.status ?? `http_${res.status}`;
    if (code === 'UNREGISTERED' || res.status === 404) return { status: 'rejected', reason: code, tokenInvalid: true };
    if (res.status === 401 && !retried) {
      await this.accessToken(true);
      return this.send(target, message, true);
    }
    if (res.status === 429 || res.status >= 500 || code === 'UNAVAILABLE' || code === 'INTERNAL' || code === 'QUOTA_EXCEEDED') {
      const ra = Number(res.headers.get('retry-after'));
      return Number.isFinite(ra) && ra > 0 ? { status: 'retry', reason: code, retryAfterSeconds: ra } : { status: 'retry', reason: code };
    }
    return { status: 'rejected', reason: code, tokenInvalid: code === 'INVALID_ARGUMENT' && /token/i.test(JSON.stringify(err)) };
  }
}
