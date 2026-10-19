import Type from 'typebox';
import { AppError, unauthorized, unavailable } from '../../http/errors.ts';
import { Instant, Nullable, iso, route, type RouteDef } from '../../http/route.ts';
import { constantTimeEqual } from '../../lib/crypto.ts';
import type { Deps } from '../../deps.ts';
import { audit } from '../identity/repo.ts';
import { creditBalance, ensureMonthlyCredits, planFor, upsertEntitlement, PLUS_MONTHLY_CREDITS } from './service.ts';

const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/;

/** Re-fetch the subscriber from RevenueCat (source of truth) and store the result. */
export async function syncEntitlement(deps: Deps, userId: string): Promise<void> {
  if (!deps.revenueCat || !deps.config.revenueCat) return;
  const ent = await deps.revenueCat.entitlement(userId, deps.config.revenueCat.entitlementId);
  const now = deps.now();
  await deps.db.tx(async (q) => {
    const exists = await q.query('select 1 from users where id = $1', [userId]);
    if (exists.rowCount === 0) return;
    await upsertEntitlement(q, userId, 'plus', ent, now);
    await ensureMonthlyCredits(q, userId, now);
  });
}

const BillingStatus = Type.Object({
  plan: Type.String(),
  status: Type.String({ description: 'none | trial | active | cancelled_active | grace | billing_issue | expired | refunded' }),
  productId: Nullable(Type.String()),
  store: Nullable(Type.String()),
  expiresAt: Nullable(Instant),
  willRenew: Nullable(Type.Boolean()),
  credits: Type.Object({ available: Type.Integer(), introRemaining: Type.Integer(), monthlyRemaining: Type.Integer(), monthlyResetsAt: Nullable(Instant), plusMonthlyAllowance: Type.Integer() }),
  manageUrl: Nullable(Type.String()),
  syncedAt: Nullable(Instant),
});

async function status(deps: Deps, userId: string) {
  const now = deps.now();
  const p = await planFor(deps.db, userId, now);
  const credits = await creditBalance(deps.db, userId, now);
  const store = p.entitlement?.store ?? null;
  return {
    plan: p.plan,
    status: p.status,
    productId: p.entitlement?.product_id ?? null,
    store,
    expiresAt: iso(p.entitlement?.expires_at),
    willRenew: p.entitlement?.will_renew ?? null,
    credits: { ...credits, plusMonthlyAllowance: PLUS_MONTHLY_CREDITS },
    manageUrl: store === 'app_store' || store === 'mac_app_store' ? 'https://apps.apple.com/account/subscriptions' : store === 'play_store' ? 'https://play.google.com/store/account/subscriptions' : null,
    syncedAt: iso(p.entitlement?.synced_at),
  };
}

export const billingRoutes: RouteDef[] = [
  route({
    method: 'GET',
    url: '/v1/billing/status',
    operationId: 'billingStatus',
    summary: 'Plan, renewal and credit status from verified store data.',
    tags: ['billing'],
    auth: 'user',
    response: BillingStatus,
    handler: async ({ user, deps, now }) => {
      await ensureMonthlyCredits(deps.db, user.id, now);
      return status(deps, user.id);
    },
  }),

  route({
    method: 'POST',
    url: '/v1/billing/sync',
    operationId: 'billingSync',
    summary: 'Ask the server to re-verify purchases now (after purchase or restore). Never trusts the client.',
    tags: ['billing'],
    auth: 'user',
    rateLimit: { max: 10, timeWindow: '1 hour' },
    response: BillingStatus,
    handler: async ({ user, deps }) => {
      if (!deps.revenueCat) throw unavailable('billing_unavailable', 'Subscriptions are unavailable right now. Free features still work.');
      try {
        await syncEntitlement(deps, user.id);
      } catch {
        throw unavailable('billing_unavailable', "We couldn't reach the store just now. Your free features still work; try again shortly.");
      }
      return status(deps, user.id);
    },
  }),

  route({
    method: 'POST',
    url: '/v1/billing/webhooks/revenuecat',
    operationId: 'revenueCatWebhook',
    summary: 'RevenueCat webhook. Authenticated by shared secret; deduplicated by event id; state re-fetched from the API.',
    tags: ['billing'],
    auth: 'webhook',
    rateLimit: { max: 1200, timeWindow: '1 minute' },
    body: Type.Object({ api_version: Type.Optional(Type.String()), event: Type.Object({ id: Type.String({ minLength: 1, maxLength: 128 }), type: Type.String({ maxLength: 64 }), app_user_id: Type.Optional(Type.String({ maxLength: 128 })), original_app_user_id: Type.Optional(Type.String({ maxLength: 128 })), transferred_from: Type.Optional(Type.Array(Type.String({ maxLength: 128 }), { maxItems: 50 })), transferred_to: Type.Optional(Type.Array(Type.String({ maxLength: 128 }), { maxItems: 50 })) }) }),
    response: Type.Object({ ok: Type.Boolean(), duplicate: Type.Boolean() }),
    handler: async ({ body, req, deps, now }) => {
      const cfg = deps.config.revenueCat;
      if (!cfg || !deps.revenueCat) throw new AppError(404, 'route_not_found', 'Not found.');
      const header = req.headers.authorization ?? '';
      if (!constantTimeEqual(header, cfg.webhookAuth) && !constantTimeEqual(header, `Bearer ${cfg.webhookAuth}`)) throw unauthorized('invalid_webhook_auth', 'Unauthorized.');
      const ev = body.event;
      const candidates = [ev.app_user_id, ev.original_app_user_id, ...(ev.transferred_from ?? []), ...(ev.transferred_to ?? [])].filter((x): x is string => !!x && UUID.test(x));
      const userIds = [...new Set(candidates)];
      const inserted = await deps.db.query(
        `insert into billing_events (provider, event_id, user_id, event_type, received_at) values ('revenuecat', $1, $2, $3, $4) on conflict (provider, event_id) do nothing`,
        [ev.id, userIds[0] ?? null, ev.type, now],
      );
      if (inserted.rowCount === 0) {
        const done = await deps.db.query<{ processed_at: Date | null }>(`select processed_at from billing_events where provider = 'revenuecat' and event_id = $1`, [ev.id]);
        if (done.rows[0]?.processed_at) return { ok: true, duplicate: true };
      }
      for (const id of userIds) await syncEntitlement(deps, id);
      await deps.db.query(`update billing_events set processed_at = $2 where provider = 'revenuecat' and event_id = $1`, [ev.id, now]);
      await audit(deps.db, { actorUserId: null, actorKind: 'system', action: 'billing.webhook_processed', targetKind: 'billing_event', targetId: ev.id, data: { type: ev.type, users: userIds.length } });
      return { ok: true, duplicate: false };
    },
  }),
];

