import type { Queryable } from '../../db/db.ts';
import { isUniqueViolation } from '../../db/db.ts';
import type { SubscriberEntitlement } from './revenuecat.ts';

export const PLUS_MONTHLY_CREDITS = 10;
export const INTRO_CREDITS = 2;

/**
 * Plan limits gate creation of new things only. They never gate stopping
 * audio, revoking grants, removing restrictions, safety features, or alarms a
 * user already accepted; a lapsed subscription keeps existing routines.
 */
export const PLAN_LIMITS = {
  free: { activeRoutines: 5, customSounds: 3 },
  plus: { activeRoutines: 30, customSounds: 30 },
} as const;

export type EntitlementStatus = 'none' | 'trial' | 'active' | 'cancelled_active' | 'grace' | 'billing_issue' | 'expired' | 'refunded';

export interface EntitlementRow {
  user_id: string;
  entitlement: string;
  product_id: string | null;
  store: string | null;
  period_type: string | null;
  purchased_at: Date | null;
  expires_at: Date | null;
  grace_expires_at: Date | null;
  will_renew: boolean | null;
  billing_issue_at: Date | null;
  unsubscribe_at: Date | null;
  refunded_at: Date | null;
  sandbox: boolean;
  synced_at: Date;
}

export function entitlementStatus(e: EntitlementRow | null, now: Date): EntitlementStatus {
  if (!e) return 'none';
  if (e.refunded_at && (!e.expires_at || e.expires_at <= now)) return 'refunded';
  const t = now.getTime();
  const expires = e.expires_at?.getTime() ?? Number.POSITIVE_INFINITY;
  if (t < expires) {
    if (e.billing_issue_at) return 'billing_issue';
    if (e.period_type === 'trial') return 'trial';
    if (e.will_renew === false) return 'cancelled_active';
    return 'active';
  }
  if (e.grace_expires_at && t < e.grace_expires_at.getTime()) return 'grace';
  return 'expired';
}

export function hasPlus(status: EntitlementStatus): boolean {
  return status === 'trial' || status === 'active' || status === 'cancelled_active' || status === 'grace' || status === 'billing_issue';
}

export async function loadEntitlement(q: Queryable, userId: string, entitlement = 'plus'): Promise<EntitlementRow | null> {
  const r = await q.query<EntitlementRow>('select * from entitlements where user_id = $1 and entitlement = $2', [userId, entitlement]);
  return r.rows[0] ?? null;
}

export async function upsertEntitlement(q: Queryable, userId: string, entitlement: string, e: SubscriberEntitlement | null, now: Date): Promise<void> {
  if (!e) {
    await q.query(
      `update entitlements set expires_at = least(coalesce(expires_at, $3), $3), will_renew = false, synced_at = $3 where user_id = $1 and entitlement = $2`,
      [userId, entitlement, now],
    );
    return;
  }
  await q.query(
    `insert into entitlements (user_id, entitlement, product_id, store, period_type, purchased_at, expires_at, grace_expires_at, will_renew,
       billing_issue_at, unsubscribe_at, refunded_at, sandbox, synced_at)
     values ($1,$2,$3,$4,$5,$6,$7,$8,$9,$10,$11,$12,$13,$14)
     on conflict (user_id, entitlement) do update set product_id = excluded.product_id, store = excluded.store, period_type = excluded.period_type,
       purchased_at = excluded.purchased_at, expires_at = excluded.expires_at, grace_expires_at = excluded.grace_expires_at,
       will_renew = excluded.will_renew, billing_issue_at = excluded.billing_issue_at, unsubscribe_at = excluded.unsubscribe_at,
       refunded_at = excluded.refunded_at, sandbox = excluded.sandbox, synced_at = excluded.synced_at`,
    [userId, entitlement, e.productId, e.store, e.periodType, e.purchasedAt, e.expiresAt, e.gracePeriodExpiresAt, e.willRenew, e.billingIssueAt, e.unsubscribeAt, e.refundedAt, e.sandbox, now],
  );
}

export function monthBucket(now: Date): string {
  return `plus-${now.toISOString().slice(0, 7)}`;
}

export function nextMonthStart(now: Date): Date {
  return new Date(Date.UTC(now.getUTCFullYear(), now.getUTCMonth() + 1, 1));
}

async function insertLedger(q: Queryable, userId: string, delta: number, reason: string, ref: string): Promise<boolean> {
  try {
    const r = await q.query(
      'insert into credit_ledger (user_id, delta, reason, ref) values ($1, $2, $3, $4) on conflict (user_id, reason, ref) do nothing',
      [userId, delta, reason, ref],
    );
    return r.rowCount > 0;
  } catch (e) {
    if (isUniqueViolation(e)) return false;
    throw e;
  }
}

export async function grantIntroCredits(q: Queryable, userId: string): Promise<void> {
  await insertLedger(q, userId, INTRO_CREDITS, 'intro_grant', 'intro:grant');
}

/** Idempotent per calendar month, so restores and replays never duplicate credits. */
export async function ensureMonthlyCredits(q: Queryable, userId: string, now: Date): Promise<void> {
  const ent = await loadEntitlement(q, userId);
  if (!hasPlus(entitlementStatus(ent, now))) return;
  await insertLedger(q, userId, PLUS_MONTHLY_CREDITS, 'plus_monthly', `${monthBucket(now)}:grant`);
}

export interface CreditBalance {
  available: number;
  introRemaining: number;
  monthlyRemaining: number;
  monthlyResetsAt: string | null;
}

/** Monthly Plus credits do not carry over; introductory credits never expire. */
export async function creditBalance(q: Queryable, userId: string, now: Date): Promise<CreditBalance> {
  const month = monthBucket(now);
  const r = await q.query<{ intro: number | string | null; monthly: number | string | null }>(
    `select coalesce(sum(delta) filter (where ref like 'intro:%'), 0) as intro,
            coalesce(sum(delta) filter (where ref like $2 || ':%'), 0) as monthly
       from credit_ledger where user_id = $1`,
    [userId, month],
  );
  const intro = Math.max(0, Number(r.rows[0]?.intro ?? 0));
  const monthly = Math.max(0, Number(r.rows[0]?.monthly ?? 0));
  const ent = await loadEntitlement(q, userId);
  const plus = hasPlus(entitlementStatus(ent, now));
  return { available: intro + monthly, introRemaining: intro, monthlyRemaining: monthly, monthlyResetsAt: plus ? nextMonthStart(now).toISOString() : null };
}

/**
 * Reserves one credit for a generation inside the caller's transaction.
 * Monthly credits are spent before introductory ones. Returns the bucket used.
 */
export async function spendCredit(q: Queryable, userId: string, generationId: string, now: Date): Promise<string | null> {
  await q.query('select id from users where id = $1 for update', [userId]);
  await ensureMonthlyCredits(q, userId, now);
  const bal = await creditBalance(q, userId, now);
  const bucket = bal.monthlyRemaining > 0 ? monthBucket(now) : bal.introRemaining > 0 ? 'intro' : null;
  if (!bucket) return null;
  await insertLedger(q, userId, -1, 'generation', `${bucket}:gen:${generationId}`);
  return bucket;
}

/** Refund for failures on our side or the provider's. Never refunds safety rejections. */
export async function refundCredit(q: Queryable, userId: string, generationId: string): Promise<void> {
  const r = await q.query<{ ref: string }>("select ref from credit_ledger where user_id = $1 and reason = 'generation' and ref like '%:gen:' || $2", [userId, generationId]);
  const spent = r.rows[0];
  if (!spent) return;
  const bucket = spent.ref.split(':gen:')[0]!;
  await insertLedger(q, userId, 1, 'refund_system_failure', `${bucket}:refund:${generationId}`);
}

export async function planFor(q: Queryable, userId: string, now: Date): Promise<{ plan: 'free' | 'plus'; status: EntitlementStatus; entitlement: EntitlementRow | null }> {
  const ent = await loadEntitlement(q, userId);
  const status = entitlementStatus(ent, now);
  return { plan: hasPlus(status) ? 'plus' : 'free', status, entitlement: ent };
}
