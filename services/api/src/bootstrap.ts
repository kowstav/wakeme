import { loadSigningKey } from '@wakeme/challenges';
import type { Config } from './config.ts';
import { PgDb, type Db } from './db/db.ts';
import { FeatureFlags, type Deps, type Logger } from './deps.ts';
import { jwksVerifier } from './http/auth.ts';
import { ApnsClient } from './push/apns.ts';
import { FcmClient } from './push/fcm.ts';
import { RoutingPushGateway } from './push/gateway.ts';
import { S3Storage } from './storage/s3.ts';
import { AnthropicChallengeAuthor } from './ai/author.ts';
import { RevenueCatRest } from './modules/billing/revenuecat.ts';
import { SupabaseAuthAdmin } from './modules/identity/auth-admin.ts';

/** Production wiring. Each optional integration is off when unconfigured, never faked. */
export function buildDeps(config: Config, log: Logger, opts: { db?: Db; role: 'api' | 'worker' }): Deps {
  const db = opts.db ?? new PgDb(config.databaseUrl, config.databasePoolMax, (e) => log.warn({ err: e.message }, 'idle database client error'));
  return {
    config,
    db,
    now: () => new Date(),
    verifyToken: jwksVerifier(config.auth),
    push: new RoutingPushGateway(config.apns ? new ApnsClient(config.apns) : null, config.fcm ? new FcmClient(config.fcm) : null),
    storage: config.s3 ? new S3Storage(config.s3) : null,
    ai: config.ai ? new AnthropicChallengeAuthor(config.ai) : null,
    revenueCat: config.revenueCat ? new RevenueCatRest(config.revenueCat.apiKey, config.revenueCat.apiOrigin) : null,
    // Identity-provider admin rights are only given to the worker, which performs deletions.
    authAdmin: opts.role === 'worker' && config.supabaseAdmin ? new SupabaseAuthAdmin(config.supabaseAdmin.url, config.supabaseAdmin.serviceRoleKey) : null,
    challengeKey: loadSigningKey(config.challengeSigning.privateKeyPem),
    flags: new FeatureFlags(db),
    log,
  };
}
