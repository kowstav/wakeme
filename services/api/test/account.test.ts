import { after, before, describe, it } from 'node:test';
import assert from 'node:assert/strict';
import { createHarness, NUDGE_LIMITS, type Harness } from './support/harness.ts';

let h: Harness;
before(async () => {
  h = await createHarness();
});
after(async () => h.close());

describe('accounts', () => {
  it('requires a valid token, an adult attestation, and a profile', async () => {
    assert.equal((await h.req('GET', '/v1/me')).status, 401);
    assert.equal((await h.req('GET', '/v1/me', { token: 'not.a.jwt' })).status, 401);
    const token = await h.mintToken('7f1c2c4e-0000-4000-8000-000000000001');
    assert.equal((await h.req('GET', '/v1/me', { token })).body.error.code, 'profile_required');
    const noAge = await h.req('POST', '/v1/account', { token, body: { displayName: 'Kid', timeZone: 'UTC', locale: 'en' } });
    assert.equal(noAge.status, 400);
    const anon = await h.mintToken('7f1c2c4e-0000-4000-8000-000000000002', { extra: { is_anonymous: true } });
    assert.equal((await h.req('POST', '/v1/account', { token: anon, body: { displayName: 'Guest', timeZone: 'UTC', locale: 'en', ageAttestation: true } })).status, 401);
  });

  it('account creation is idempotent and grants two introductory AI credits', async () => {
    const token = await h.mintToken('7f1c2c4e-0000-4000-8000-000000000003');
    const body = { displayName: 'Maya', timeZone: 'Asia/Kolkata', locale: 'en-IN', ageAttestation: true };
    const a = await h.req('POST', '/v1/account', { token, body });
    const b = await h.req('POST', '/v1/account', { token, body });
    assert.equal(a.body.id, b.body.id);
    assert.equal(b.body.billing.credits.available, 2);
    assert.deepEqual(b.body.settings.quietHours, { enabled: true, start: '22:00', end: '07:00' });
  });

  it('a new phone install never inherits push delivery for a previous account', async () => {
    const a = await h.createUser('First owner');
    const b = await h.createUser('Second owner');
    const r = await h.req('POST', '/v1/devices', { user: b, body: { installId: 'shared_install_1', platform: 'ios', osVersion: '27', appBuild: '1', publicKey: a.device.publicKeyB64, push: { provider: 'apns', token: a.device.pushToken, environment: 'sandbox' } } });
    assert.equal(r.status, 200);
    const old = await h.db.query<{ push_token: string | null }>('select push_token from devices where id = $1', [a.device.id]);
    assert.equal(old.rows[0]!.push_token, null);
  });

  it('deletion needs a recent sign-in, deactivates at once, revokes everything, and deletes after the grace period', async () => {
    // Outbox eligibility uses PostgreSQL's clock, not the API's injected clock.
    // Anchor both to one database instant so this grace-period case never expires with the calendar.
    const dbNow = (await h.db.query<{ now: Date }>('select now() as now')).rows[0]!.now;
    h.setNow(dbNow);
    const maya = await h.createUser('Maya');
    const alex = await h.createUser('Alex');
    await h.befriend(maya, alex);
    await h.grant(alex, maya, { capabilities: ['nudge.send'], limits: NUDGE_LIMITS });
    await h.grant(maya, alex, { capabilities: ['nudge.send'], limits: NUDGE_LIMITS });
    assert.equal((await h.req('DELETE', '/v1/account', { token: maya.staleToken })).body.error.code, 'reauth_required');
    const del = await h.req('DELETE', '/v1/account', { user: maya });
    assert.equal(del.status, 200);
    assert.equal(del.body.deletionScheduledFor, new Date(h.clock.now.getTime() + 7 * 86_400_000).toISOString());
    const live = await h.db.query('select 1 from grants where (grantor_id = $1 or grantee_id = $1) and revoked_at is null', [maya.id]);
    assert.equal(live.rowCount, 0);
    assert.equal((await h.req('GET', '/v1/friends', { user: maya })).body.error.code, 'account_deleting');
    assert.equal((await h.req('GET', '/v1/me', { user: maya })).body.accountState, 'deletion_pending');
    const exp = await h.req('GET', '/v1/account/export', { user: maya });
    assert.equal(exp.status, 200, 'export stays available during the grace period');
    assert.equal(exp.body.format, 'wakeme-export-v1');
    assert.ok(!JSON.stringify(exp.body).includes(maya.device.pushToken), 'export omits push tokens');

    await h.drain();
    assert.equal((await h.db.query('select 1 from users where id = $1', [maya.id])).rowCount, 1, 'not deleted before the grace period');
    await h.makeJobsDue();
    await h.drain();
    assert.equal((await h.db.query('select 1 from users where id = $1', [maya.id])).rowCount, 0);
    assert.deepEqual(h.authAdmin.deleted, [maya.sub]);
    assert.equal((await h.req('GET', '/v1/friends', { user: alex })).body.friends.length, 0);
  });

  it('deletion can be cancelled during the grace period (permissions stay revoked)', async () => {
    const sam = await h.createUser('Sam');
    await h.req('DELETE', '/v1/account', { user: sam });
    const c = await h.req('POST', '/v1/account/deletion/cancel', { user: sam });
    assert.equal(c.body.accountState, 'active');
    await h.makeJobsDue();
    await h.drain();
    assert.equal((await h.db.query('select 1 from users where id = $1', [sam.id])).rowCount, 1);
  });
});
