import type { KeyObject } from 'node:crypto';
import type { Config } from './config.ts';
import type { Db } from './db/db.ts';
import type { TokenVerifier } from './http/auth.ts';
import type { PushGateway } from './push/gateway.ts';
import type { ObjectStorage } from './storage/s3.ts';
import type { ChallengeAuthor } from './ai/author.ts';
import type { RevenueCatClient } from './modules/billing/revenuecat.ts';
import type { AuthAdmin } from './modules/identity/auth-admin.ts';

export interface Logger {
  info(obj: object | string, msg?: string): void;
  warn(obj: object | string, msg?: string): void;
  error(obj: object | string, msg?: string): void;
}

export interface Deps {
  config: Config;
  db: Db;
  now: () => Date;
  verifyToken: TokenVerifier;
  push: PushGateway;
  storage: ObjectStorage | null;
  ai: ChallengeAuthor | null;
  revenueCat: RevenueCatClient | null;
  authAdmin: AuthAdmin | null;
  challengeKey: KeyObject;
  flags: FeatureFlags;
  log: Logger;
}

/** Kill switches cached briefly; they stop new risky remote behaviour, never local alarms or escape. */
export class FeatureFlags {
  private cache: { at: number; disabled: Set<string> } | null = null;
  private readonly db: Db;
  private readonly ttlMs: number;

  constructor(db: Db, ttlMs = 5_000) {
    this.db = db;
    this.ttlMs = ttlMs;
  }

  async disabled(): Promise<ReadonlySet<string>> {
    const now = Date.now();
    if (this.cache && now - this.cache.at < this.ttlMs) return this.cache.disabled;
    const r = await this.db.query<{ key: string }>('select key from feature_flags where enabled = false');
    this.cache = { at: now, disabled: new Set(r.rows.map((x) => x.key)) };
    return this.cache.disabled;
  }

  async isEnabled(key: string): Promise<boolean> {
    return !(await this.disabled()).has(key);
  }

  invalidate(): void {
    this.cache = null;
  }
}
