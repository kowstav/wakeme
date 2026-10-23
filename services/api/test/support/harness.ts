import { generateKeyPairSync, randomUUID, sign, createHash, type KeyObject } from 'node:crypto';
import { SignJWT, createLocalJWKSet, exportJWK, generateKeyPair } from 'jose';
import type { FastifyInstance } from 'fastify';
import { loadSigningKey } from '@wakeme/challenges';
import type { Config } from '../../src/config.ts';
import { PgliteDb } from '../../src/db/pglite.ts';
import { PgDb, type Db } from '../../src/db/db.ts';
import { migrate } from '../../src/db/migrate.ts';
import { FeatureFlags, type Deps } from '../../src/deps.ts';
import { jwksVerifier } from '../../src/http/auth.ts';
import { buildApp } from '../../src/http/app.ts';
import { canonicalDeviceRequest } from '../../src/http/device-auth.ts';
import { silentLogger } from '../../src/lib/log.ts';
import { allRoutes } from '../../src/modules/index.ts';
import { RecordingPushGateway } from '../../src/push/gateway.ts';
import { MemoryStorage } from '../../src/storage/s3.ts';
import { ScriptedChallengeAuthor } from '../../src/ai/author.ts';
import { FakeRevenueCat } from '../../src/modules/billing/revenuecat.ts';
import { RecordingAuthAdmin } from '../../src/modules/identity/auth-admin.ts';
import { Worker } from '../../src/worker/worker.ts';

export const ISSUER = 'https://test.supabase.co/auth/v1';

export interface TestUser {
  id: string;
  sub: string;
  name: string;
  token: string;
  /** Token whose last sign-in is long ago (no recent auth). */
  staleToken: string;
  device: TestDevice;
}

export interface TestDevice {
  id: string;
  installId: string;
  privateKey: KeyObject;
  publicKeyB64: string;
  pushToken: string;
}

export interface Harness {
  app: FastifyInstance;
  deps: Deps;
  db: Db;
  push: RecordingPushGateway;
  storage: MemoryStorage;
  ai: ScriptedChallengeAuthor;
  revenueCat: FakeRevenueCat;
  authAdmin: RecordingAuthAdmin;
  worker: Worker;
  clock: { now: Date };
  setNow(iso: string | Date): void;
  advance(seconds: number): void;
  mintToken(sub: string, opts?: { authAgeSeconds?: number; extra?: Record<string, unknown> }): Promise<string>;
  createUser(name: string, opts?: { timeZone?: string; platform?: 'ios' | 'android'; alarmCapable?: boolean }): Promise<TestUser>;
  addDevice(user: TestUser, opts?: { platform?: 'ios' | 'android'; makePrimary?: boolean; alarmCapable?: boolean }): Promise<TestDevice>;
  req(method: string, url: string, opts?: { user?: TestUser; token?: string; body?: unknown; headers?: Record<string, string> }): Promise<{ status: number; body: any; headers: Record<string, unknown> }>;
  signed(method: string, url: string, user: TestUser, body?: unknown, opts?: { device?: TestDevice; timestamp?: number; tamper?: boolean }): Promise<{ status: number; body: any; headers: Record<string, unknown> }>;
  befriend(a: TestUser, b: TestUser): Promise<string>;
  grant(grantor: TestUser, grantee: TestUser, draft: { capabilities: string[]; limits: unknown; validUntil?: string }): Promise<any>;
  drain(): Promise<number>;
  /** Make every pending outbox job due now (the outbox runs on the database clock). */
  makeJobsDue(): Promise<void>;
  close(): Promise<void>;
}

export function testConfig(overrides: Partial<Config> = {}): Config {
  const { privateKey } = generateKeyPairSync('ec', { namedCurve: 'P-256' });
  return {
    env: 'test',
    port: 0,
    host: '127.0.0.1',
    databaseUrl: 'pglite://memory',
    databasePoolMax: 1,
    auth: { jwksUrl: `${ISSUER}/.well-known/jwks.json`, issuer: ISSUER, audience: 'authenticated' },
    reauthSecondsForDeletion: 900,
    reauthSecondsForHighImpactGrants: 86_400,
    adminUserIds: [],
    challengeSigning: { keyId: 'test-1', privateKeyPem: privateKey.export({ type: 'pkcs8', format: 'pem' }).toString() },
    challengeVerifyKeys: [],
    locationKey: { keyId: 'l-test', keyBase64: Buffer.alloc(32, 7).toString('base64') },
    inviteLinkBase: 'https://wakeme.app/i/',
    apns: null,
    fcm: null,
    s3: null,
    ai: null,
    revenueCat: { webhookAuth: 'rc-test-secret', apiKey: 'rc-test-key', apiOrigin: 'https://revenuecat.invalid', entitlementId: 'plus' },
    supabaseAdmin: null,
    corsOrigins: [],
    ...overrides,
  };
}

/** With TEST_DATABASE_URL, each harness gets its own throwaway database on that server. */
async function serverDatabase(url: string): Promise<{ db: Db; drop: () => Promise<void> }> {
  const admin = new PgDb(url, 1);
  const name = `wakeme_t_${randomUUID().replace(/-/g, '').slice(0, 16)}`;
  await admin.query(`create database ${name}`);
  const u = new URL(url);
  u.pathname = `/${name}`;
  const db = new PgDb(u.toString(), 5);
  return {
    db,
    drop: async () => {
      await db.close();
      await admin.query(`drop database if exists ${name} with (force)`);
      await admin.close();
    },
  };
}

export async function createHarness(opts: { config?: Partial<Config> } = {}): Promise<Harness> {
  const server = process.env['TEST_DATABASE_URL'] ? await serverDatabase(process.env['TEST_DATABASE_URL']) : null;
  const db: Db = server ? server.db : await PgliteDb.create();
  await migrate(db as Db & { exec?: (s: string) => Promise<void> });

  const { publicKey, privateKey } = await generateKeyPair('ES256');
  const jwk = { ...(await exportJWK(publicKey)), kid: 'test-key', alg: 'ES256' };
  const jwks = createLocalJWKSet({ keys: [jwk] });
  const config = testConfig(opts.config);
  const clock = { now: new Date('2026-10-19T14:00:00Z') }; // Monday, 10:00 in New York
  const push = new RecordingPushGateway();
  const storage = new MemoryStorage();
  const ai = new ScriptedChallengeAuthor();
  const revenueCat = new FakeRevenueCat();
  const authAdmin = new RecordingAuthAdmin();
  const deps: Deps = {
    config,
    db,
    now: () => new Date(clock.now.getTime()),
    verifyToken: jwksVerifier(config.auth, jwks),
    push,
    storage,
    ai,
    revenueCat,
    authAdmin,
    challengeKey: loadSigningKey(config.challengeSigning.privateKeyPem),
    flags: new FeatureFlags(db, 0),
    log: silentLogger,
  };
  const app = await buildApp(deps, allRoutes());
  await app.ready();
  const worker = new Worker(deps);

  const mintToken: Harness['mintToken'] = async (sub, o = {}) => {
    const realNow = Math.floor(Date.now() / 1000);
    const authAt = Math.floor(clock.now.getTime() / 1000) - (o.authAgeSeconds ?? 60);
    return new SignJWT({ role: 'authenticated', amr: [{ method: 'otp', timestamp: authAt }], session_id: randomUUID(), aal: 'aal1', ...(o.extra ?? {}) })
      .setProtectedHeader({ alg: 'ES256', kid: 'test-key' })
      .setSubject(sub)
      .setIssuer(ISSUER)
      .setAudience('authenticated')
      .setIssuedAt(realNow)
      .setExpirationTime(realNow + 3600)
      .sign(privateKey);
  };

  const req: Harness['req'] = async (method, url, o = {}) => {
    const headers: Record<string, string> = { ...(o.headers ?? {}) };
    const token = o.token ?? o.user?.token;
    if (token) headers['authorization'] = `Bearer ${token}`;
    const payload = o.body === undefined ? undefined : JSON.stringify(o.body);
    if (payload !== undefined) headers['content-type'] = 'application/json';
    const res = await app.inject({ method: method as 'GET', url, headers, ...(payload !== undefined ? { payload } : {}) });
    let body: unknown = res.body;
    try {
      body = res.body ? JSON.parse(res.body) : undefined;
    } catch {
      /* not json */
    }
    return { status: res.statusCode, body, headers: res.headers as Record<string, unknown> };
  };

  const signed: Harness['signed'] = async (method, url, user, body, o = {}) => {
    const device = o.device ?? user.device;
    const ts = String(o.timestamp ?? Math.floor(clock.now.getTime() / 1000));
    const payload = body === undefined ? '' : JSON.stringify(body);
    const message = canonicalDeviceRequest(method, url, ts, Buffer.from(o.tamper ? payload + ' ' : payload, 'utf8'));
    const signature = sign('sha256', Buffer.from(message), { key: device.privateKey, dsaEncoding: 'der' }).toString('base64');
    return req(method, url, { user, ...(body !== undefined ? { body } : {}), headers: { 'x-wakeme-device': device.id, 'x-wakeme-timestamp': ts, 'x-wakeme-signature': signature } });
  };

  const addDevice: Harness['addDevice'] = async (user, o = {}) => {
    const { privateKey: pk, publicKey: pub } = generateKeyPairSync('ec', { namedCurve: 'P-256' });
    const publicKeyB64 = pub.export({ type: 'spki', format: 'der' }).toString('base64');
    const installId = `inst_${randomUUID().replace(/-/g, '')}`;
    const platform = o.platform ?? 'ios';
    const pushToken = platform === 'ios' ? createHash('sha256').update(installId).digest('hex') : `fcm-${installId}-token`;
    const alarm = o.alarmCapable ?? true;
    const r = await req('POST', '/v1/devices', {
      user,
      body: {
        installId,
        platform,
        osVersion: platform === 'ios' ? '27.0.1' : '17',
        appBuild: '1',
        publicKey: publicKeyB64,
        push: { provider: platform === 'ios' ? 'apns' : 'fcm', token: pushToken, ...(platform === 'ios' ? { environment: 'sandbox' } : {}) },
        capabilities: platform === 'ios' ? { notificationsAuthorized: true, alarmAuthorized: alarm } : { notificationsAuthorized: true, exactAlarmPermitted: alarm },
        ...(o.makePrimary !== undefined ? { makePrimary: o.makePrimary } : {}),
      },
    });
    if (r.status !== 200) throw new Error(`device registration failed: ${r.status} ${JSON.stringify(r.body)}`);
    return { id: r.body.id, installId, privateKey: pk, publicKeyB64, pushToken };
  };

  const createUser: Harness['createUser'] = async (name, o = {}) => {
    const sub = randomUUID();
    const token = await mintToken(sub);
    const staleToken = await mintToken(sub, { authAgeSeconds: 40 * 86_400 });
    const r = await req('POST', '/v1/account', { token, body: { displayName: name, timeZone: o.timeZone ?? 'America/New_York', locale: 'en-US', ageAttestation: true } });
    if (r.status !== 200) throw new Error(`account creation failed: ${r.status} ${JSON.stringify(r.body)}`);
    const user = { id: r.body.id, sub, name, token, staleToken } as TestUser;
    user.device = await addDevice(user, { ...(o.platform ? { platform: o.platform } : {}), ...(o.alarmCapable !== undefined ? { alarmCapable: o.alarmCapable } : {}) });
    return user;
  };

  const befriend: Harness['befriend'] = async (a, b) => {
    const inv = await req('POST', '/v1/invites', { user: a, body: { kind: 'friend' } });
    if (inv.status !== 201) throw new Error(`invite failed ${JSON.stringify(inv.body)}`);
    const red = await req('POST', '/v1/invites/redeem', { user: b, body: { token: inv.body.token } });
    if (red.status !== 200) throw new Error(`redeem failed ${JSON.stringify(red.body)}`);
    return red.body.friend.friendshipId;
  };

  const grant: Harness['grant'] = async (grantor, grantee, draft) => {
    const r = await req('POST', '/v1/grants', {
      user: grantor,
      body: { granteeId: grantee.id, capabilities: draft.capabilities, limits: draft.limits, validUntil: draft.validUntil ?? new Date(clock.now.getTime() + 30 * 86_400_000).toISOString() },
    });
    if (r.status !== 201) throw new Error(`grant failed ${r.status} ${JSON.stringify(r.body)}`);
    return r.body;
  };

  return {
    app,
    deps,
    db,
    push,
    storage,
    ai,
    revenueCat,
    authAdmin,
    worker,
    clock,
    setNow: (t) => {
      clock.now = new Date(t);
    },
    advance: (s) => {
      clock.now = new Date(clock.now.getTime() + s * 1000);
    },
    mintToken,
    createUser,
    addDevice,
    req,
    signed,
    befriend,
    grant,
    drain: () => worker.outbox.drain(),
    makeJobsDue: async () => {
      await db.query('update outbox set due_at = now() - interval \x271 second\x27, lease_until = null where processed_at is null');
    },
    close: async () => {
      await app.close();
      if (server) await server.drop();
      else await db.close();
    },
  };
}

export const NUDGE_LIMITS = {
  nudge: { maxPerHour: 3, windows: [{ days: [1, 2, 3, 4, 5, 6, 7], start: '08:00', end: '22:00' }], sounds: ['bundled:quack.classic', 'bundled:quack.double'], overridesQuietHours: false },
};

export const WAKE_LIMITS = {
  alarm: { maxAlarmSeconds: 120, maxPerDay: 1, windows: [{ days: [1, 2, 3, 4, 5], start: '06:00', end: '11:00' }], sounds: ['bundled:rise.gentle'], followUp: { allowed: true, maxDelaySeconds: 300 } },
  challenge: { types: ['multiple_choice', 'math', 'chess'], maxDurationSeconds: 90, maxDifficulty: 'medium' },
};

export function idem(): string {
  return `k_${randomUUID().replace(/-/g, '')}`;
}
