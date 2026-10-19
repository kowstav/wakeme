import { generateKeyPairSync, randomUUID } from 'node:crypto';
import { mkdirSync } from 'node:fs';
import { SignJWT, createLocalJWKSet, exportJWK, generateKeyPair } from 'jose';
import { loadSigningKey } from '@wakeme/challenges';
import type { Config } from '../config.ts';
import { PgliteDb } from '../db/pglite.ts';
import { migrate } from '../db/migrate.ts';
import { FeatureFlags, type Deps } from '../deps.ts';
import { jwksVerifier } from '../http/auth.ts';
import { buildApp } from '../http/app.ts';
import { createLogger } from '../lib/log.ts';
import { allRoutes } from '../modules/index.ts';
import { ApnsClient } from '../push/apns.ts';
import { RecordingPushGateway } from '../push/gateway.ts';
import { MemoryStorage } from '../storage/s3.ts';
import { AnthropicChallengeAuthor, ScriptedChallengeAuthor } from '../ai/author.ts';
import { FakeRevenueCat } from '../modules/billing/revenuecat.ts';
import { RecordingAuthAdmin } from '../modules/identity/auth-admin.ts';
import { Worker } from '../worker/worker.ts';

// Zero-setup local backend for app development: embedded PostgreSQL on disk,
// a local token issuer, in-memory storage and a push recorder. It refuses to
// run in production and binds to loopback only. Never deploy this entrypoint.
if (process.env['WAKEME_ENV'] === 'production' || process.env['WAKEME_ENV'] === 'staging') {
  console.error('dev.ts must never run in staging or production');
  process.exit(1);
}

const log = createLogger({ service: 'wakeme-dev' });
const dataDir = process.env['DEV_DATA_DIR'] ?? new URL('../../.devdata/', import.meta.url).pathname.replace(/^\/([A-Za-z]:)/, '$1');
mkdirSync(dataDir, { recursive: true });
const db = await PgliteDb.create(dataDir);
await migrate(db, (m) => log.info(m));

const { publicKey, privateKey } = await generateKeyPair('ES256');
const jwk = { ...(await exportJWK(publicKey)), kid: 'dev-key', alg: 'ES256' };
const issuer = 'http://127.0.0.1/dev-auth';
const signing = generateKeyPairSync('ec', { namedCurve: 'P-256' }).privateKey.export({ type: 'pkcs8', format: 'pem' }).toString();
const config: Config = {
  env: 'development',
  port: Number(process.env['PORT'] ?? 8787),
  host: '127.0.0.1',
  databaseUrl: 'pglite',
  databasePoolMax: 1,
  auth: { jwksUrl: `${issuer}/jwks`, issuer, audience: 'authenticated' },
  reauthSecondsForDeletion: 900,
  reauthSecondsForHighImpactGrants: 86_400,
  adminUserIds: [],
  challengeSigning: { keyId: 'dev-1', privateKeyPem: signing },
  challengeVerifyKeys: [],
  locationKey: { keyId: 'dev', keyBase64: Buffer.alloc(32, 1).toString('base64') },
  inviteLinkBase: 'http://127.0.0.1:8787/i/',
  apns: null,
  fcm: null,
  s3: null,
  ai: process.env['ANTHROPIC_API_KEY']
    ? { apiKey: process.env['ANTHROPIC_API_KEY'], model: process.env['AI_MODEL'] ?? 'claude-opus-5-5', maxOutputTokens: 2000, inputPriceMicrosPerMTok: 4_000_000, outputPriceMicrosPerMTok: 20_000_000 }
    : null,
  revenueCat: { webhookAuth: 'dev', apiKey: 'dev', apiOrigin: 'http://127.0.0.1', entitlementId: 'plus' },
  supabaseAdmin: null,
  corsOrigins: [],
};
const push = new RecordingPushGateway();
const deps: Deps = {
  config,
  db,
  now: () => new Date(),
  verifyToken: jwksVerifier(config.auth, createLocalJWKSet({ keys: [jwk] })),
  push,
  storage: new MemoryStorage(),
  ai: config.ai ? new AnthropicChallengeAuthor(config.ai) : new ScriptedChallengeAuthor(),
  revenueCat: new FakeRevenueCat(),
  authAdmin: new RecordingAuthAdmin(),
  challengeKey: loadSigningKey(signing),
  flags: new FeatureFlags(db),
  log,
};

const app = await buildApp(deps, allRoutes(), { logger: false });

// Dev-only helpers. Not part of the API contract.
app.post('/dev/token', async (req) => {
  const body = (req.body ?? {}) as { subject?: string };
  const sub = body.subject && /^[0-9a-f-]{36}$/.test(body.subject) ? body.subject : randomUUID();
  const now = Math.floor(Date.now() / 1000);
  const token = await new SignJWT({ role: 'authenticated', amr: [{ method: 'dev', timestamp: now }], aal: 'aal1' })
    .setProtectedHeader({ alg: 'ES256', kid: 'dev-key' })
    .setSubject(sub)
    .setIssuer(issuer)
    .setAudience('authenticated')
    .setIssuedAt(now)
    .setExpirationTime(now + 12 * 3600)
    .sign(privateKey);
  return { subject: sub, accessToken: token, expiresIn: 12 * 3600 };
});
// The last pushes, numbered so a relay can ask for newer ones (?after=seq). iOS entries carry the
// exact APNs payload, which CI feeds to the Simulator with `xcrun simctl push` (no real APNs).
app.get<{ Querystring: { after?: string } }>('/dev/pushes', async (req) => {
  const after = Number(req.query.after ?? -1);
  return push.sent
    .map((p, seq) => ({ seq, p }))
    .filter(({ seq }) => seq > after)
    .slice(-50)
    .map(({ seq, p }) => ({
      seq,
      deviceId: p.target.deviceId,
      platform: p.target.platform,
      alert: p.message.alert ?? null,
      data: p.message.data,
      ...(p.target.platform === 'ios' ? { apns: ApnsClient.payload(p.message) } : {}),
    }));
});

const worker = new Worker(deps);
void worker.start();
await app.listen({ port: config.port, host: '127.0.0.1' });
log.info({ url: `http://127.0.0.1:${config.port}`, dataDir }, 'dev backend ready (POST /dev/token for a test identity)');
