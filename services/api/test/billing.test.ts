import { after, before, describe, it } from 'node:test';
import assert from 'node:assert/strict';
import { createHarness, type Harness } from './support/harness.ts';

let h: Harness;
before(async () => {
  h = await createHarness();
});
after(async () => h.close());

const webhook = (id: string, userId: string, type = 'INITIAL_PURCHASE') => ({ api_version: '1.0', event: { id, type, app_user_id: userId, product_id: 'plus_annual', price: 999999 } });

describe('subscriptions', () => {
  it('rejects unauthenticated webhooks', async () => {
    const u = await h.createUser('Payer');
    const r = await h.req('POST', '/v1/billing/webhooks/revenuecat', { body: webhook('evt-x', u.id), headers: { authorization: 'Bearer wrong' } });
    assert.equal(r.status, 401);
  });

  it('a forged webhook body cannot grant Plus: state comes from the RevenueCat API', async () => {
    const u = await h.createUser('Forger');
    const r = await h.req('POST', '/v1/billing/webhooks/revenuecat', { body: webhook('evt-forged', u.id), headers: { authorization: 'Bearer rc-test-secret' } });
    assert.equal(r.status, 200);
    const me = await h.req('GET', '/v1/me', { user: u });
    assert.equal(me.body.billing.plan, 'free');
  });

  it('grants Plus from verified state, adds monthly credits once, and is idempotent', async () => {
    h.setNow('2026-10-19T14:00:00Z');
    const u = await h.createUser('Subscriber');
    h.revenueCat.subscribers.set(u.id, {
      productId: 'plus_annual', store: 'app_store', periodType: 'trial', purchasedAt: new Date('2026-10-19T13:00:00Z'), expiresAt: new Date('2026-10-26T13:00:00Z'),
      gracePeriodExpiresAt: null, willRenew: true, billingIssueAt: null, unsubscribeAt: null, refundedAt: null, sandbox: true,
    });
    const auth = { authorization: 'Bearer rc-test-secret' };
    assert.equal((await h.req('POST', '/v1/billing/webhooks/revenuecat', { body: webhook('evt-1', u.id), headers: auth })).body.duplicate, false);
    assert.equal((await h.req('POST', '/v1/billing/webhooks/revenuecat', { body: webhook('evt-1', u.id), headers: auth })).body.duplicate, true);
    const calls = h.revenueCat.calls;
    await h.req('POST', '/v1/billing/sync', { user: u }); // "restore purchases"
    assert.equal(h.revenueCat.calls, calls + 1);
    const s = await h.req('GET', '/v1/billing/status', { user: u });
    assert.equal(s.body.plan, 'plus');
    assert.equal(s.body.status, 'trial');
    assert.equal(s.body.credits.available, 12, '2 introductory + 10 monthly, never duplicated by restore');
    assert.equal(s.body.manageUrl, 'https://apps.apple.com/account/subscriptions');
    const me = await h.req('GET', '/v1/me', { user: u });
    assert.equal(me.body.limits.activeRoutines.max, 30);
  });

  it('expiry downgrades creation limits but keeps existing routines and free features', async () => {
    h.setNow('2026-10-19T14:00:00Z');
    const u = await h.createUser('Lapsed');
    h.revenueCat.subscribers.set(u.id, {
      productId: 'plus_monthly', store: 'play_store', periodType: 'normal', purchasedAt: new Date('2026-09-19T13:00:00Z'), expiresAt: new Date('2026-10-20T13:00:00Z'),
      gracePeriodExpiresAt: null, willRenew: false, billingIssueAt: null, unsubscribeAt: new Date('2026-10-01T00:00:00Z'), refundedAt: null, sandbox: true,
    });
    await h.req('POST', '/v1/billing/sync', { user: u });
    assert.equal((await h.req('GET', '/v1/billing/status', { user: u })).body.status, 'cancelled_active');
    const routine = { localTime: '07:00', timeZone: 'America/New_York', timeZonePolicy: 'fixed', recurrence: { kind: 'weekly', days: [1, 2, 3, 4, 5] }, soundId: 'bundled:rise.gentle', ringSeconds: 60 };
    for (let i = 0; i < 7; i++) assert.equal((await h.req('POST', '/v1/alarm-plans', { user: u, body: routine })).status, 201);
    h.setNow('2026-11-02T14:00:00Z');
    const s = await h.req('GET', '/v1/billing/status', { user: u });
    assert.equal(s.body.status, 'expired');
    assert.equal(s.body.plan, 'free');
    const list = await h.req('GET', '/v1/alarm-plans', { user: u });
    assert.equal(list.body.mine.filter((p: any) => p.state === 'active').length, 7, 'nothing already accepted is switched off');
    assert.equal((await h.req('POST', '/v1/alarm-plans', { user: u, body: routine })).body.error.code, 'plan_limit_reached');
  });

  it('billing outages never block free features', async () => {
    const u = await h.createUser('Offline');
    const original = h.deps.revenueCat!.entitlement.bind(h.deps.revenueCat);
    h.deps.revenueCat!.entitlement = async () => {
      throw new Error('store down');
    };
    const r = await h.req('POST', '/v1/billing/sync', { user: u });
    assert.equal(r.status, 503);
    assert.equal(r.body.error.code, 'billing_unavailable');
    h.deps.revenueCat!.entitlement = original;
    assert.equal((await h.req('GET', '/v1/me', { user: u })).status, 200);
  });
});
