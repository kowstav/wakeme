import { after, before, describe, it } from 'node:test';
import assert from 'node:assert/strict';
import { createHarness, idem, NUDGE_LIMITS, type Harness } from './support/harness.ts';

let h: Harness;
before(async () => {
  h = await createHarness();
});
after(async () => h.close());

describe('invites', () => {
  it('previews the inviter before accepting, and the link grants nothing', async () => {
    const maya = await h.createUser('Maya');
    const alex = await h.createUser('Alex');
    const inv = await h.req('POST', '/v1/invites', { user: maya, body: { kind: 'friend' } });
    assert.equal(inv.status, 201);
    assert.match(inv.body.url, /^https:\/\/wakeme\.app\/i\/[A-Za-z0-9_-]+$/);
    assert.ok(!inv.body.url.includes(maya.id), 'links carry an opaque token, not profile data');
    const preview = await h.req('POST', '/v1/invites/preview', { user: alex, body: { token: inv.body.token } });
    assert.equal(preview.body.inviter.displayName, 'Maya');
    assert.equal(preview.body.alreadyConnected, false);
    const red = await h.req('POST', '/v1/invites/redeem', { user: alex, body: { token: inv.body.token } });
    assert.equal(red.status, 200);
    assert.equal(red.body.friend.theyAllowMe, null);
    assert.equal(red.body.friend.iAllowThem, null);
    const g = await h.req('GET', '/v1/grants?direction=given', { user: maya });
    assert.equal(g.body.grants.length, 0);
  });

  it('works with the short recovery code and is single use', async () => {
    const maya = await h.createUser('Maya');
    const alex = await h.createUser('Alex');
    const sam = await h.createUser('Sam');
    const inv = await h.req('POST', '/v1/invites', { user: maya, body: { kind: 'friend' } });
    assert.match(inv.body.code, /^[A-Z0-9]{3}-[A-Z0-9]{3}-[A-Z0-9]{3}$/);
    const red = await h.req('POST', '/v1/invites/redeem', { user: alex, body: { code: inv.body.code.toLowerCase().replace(/-/g, ' ') } });
    assert.equal(red.status, 200);
    const again = await h.req('POST', '/v1/invites/redeem', { user: sam, body: { code: inv.body.code } });
    assert.equal(again.status, 404);
    assert.equal(again.body.error.code, 'invite_invalid');
  });

  it('uses one indistinguishable error for wrong, expired and revoked invites, and throttles guessing', async () => {
    const maya = await h.createUser('Maya');
    const guesser = await h.createUser('Guesser');
    const inv = await h.req('POST', '/v1/invites', { user: maya, body: { kind: 'friend' } });
    await h.req('DELETE', `/v1/invites/${inv.body.id}`, { user: maya });
    const revoked = await h.req('POST', '/v1/invites/preview', { user: guesser, body: { token: inv.body.token } });
    const wrong = await h.req('POST', '/v1/invites/preview', { user: guesser, body: { code: 'AAA-BBB-CCC' } });
    assert.deepEqual([revoked.status, revoked.body.error.code, revoked.body.error.message], [wrong.status, wrong.body.error.code, wrong.body.error.message]);
    for (let i = 0; i < 8; i++) await h.req('POST', '/v1/invites/preview', { user: guesser, body: { code: `ZZZ-ZZZ-ZZ${i}` } });
    const blocked = await h.req('POST', '/v1/invites/preview', { user: guesser, body: { code: 'QQQ-QQQ-QQQ' } });
    assert.equal(blocked.status, 429);
    assert.equal(blocked.body.error.code, 'invite_attempts_exceeded');
  });

  it("refuses to redeem your own invite", async () => {
    const maya = await h.createUser('Maya');
    const inv = await h.req('POST', '/v1/invites', { user: maya, body: { kind: 'friend' } });
    const r = await h.req('POST', '/v1/invites/redeem', { user: maya, body: { token: inv.body.token } });
    assert.equal(r.body.error.code, 'own_invite');
  });
});

describe('block, remove and unblock', () => {
  it('blocking atomically revokes grants in both directions and hides the blocker', async () => {
    h.setNow('2026-10-19T14:00:00Z');
    const maya = await h.createUser('Maya');
    const alex = await h.createUser('Alex');
    const fid = await h.befriend(maya, alex);
    const g1 = await h.grant(alex, maya, { capabilities: ['nudge.send'], limits: NUDGE_LIMITS });
    const g2 = await h.grant(maya, alex, { capabilities: ['nudge.send'], limits: NUDGE_LIMITS });
    const queued = await h.signed('POST', '/v1/commands', maya, { targetUserId: alex.id, capability: 'nudge.send', idempotencyKey: idem(), soundId: 'bundled:quack.classic' });
    const b = await h.req('POST', `/v1/friendships/${fid}/block`, { user: alex, body: { report: { reason: 'unwanted_alerts' } } });
    assert.equal(b.status, 200);
    assert.equal(b.body.revokedGrants, 2);
    assert.ok(b.body.reportId);
    for (const id of [g1.id, g2.id]) {
      const r = await h.db.query<{ revoked_reason: string }>('select revoked_reason from grants where id = $1', [id]);
      assert.equal(r.rows[0]!.revoked_reason, 'blocked');
    }
    const cmd = await h.req('GET', `/v1/commands/${queued.body.id}`, { user: maya });
    assert.equal(cmd.body.state.lifecycle, 'cancelled');
    // Maya no longer sees Alex at all; the friendship is visible only to the blocker.
    assert.equal((await h.req('GET', '/v1/friends', { user: maya })).body.friends.length, 0);
    assert.equal((await h.req('GET', `/v1/friendships/${fid}`, { user: maya })).status, 404);
    assert.equal((await h.req('GET', '/v1/friends?includeBlocked=true', { user: alex })).body.friends[0].state, 'blocked');
    // A blocked user cannot reconnect through an invite.
    const inv = await h.req('POST', '/v1/invites', { user: maya, body: { kind: 'friend' } });
    assert.equal((await h.req('POST', '/v1/invites/redeem', { user: alex, body: { token: inv.body.token } })).status, 404);
  });

  it('unblocking does not restore the friendship or permissions', async () => {
    const maya = await h.createUser('Maya');
    const alex = await h.createUser('Alex');
    const fid = await h.befriend(maya, alex);
    await h.grant(alex, maya, { capabilities: ['nudge.send'], limits: NUDGE_LIMITS });
    await h.req('POST', `/v1/friendships/${fid}/block`, { user: alex, body: {} });
    assert.equal((await h.req('POST', `/v1/friendships/${fid}/unblock`, { user: maya })).status, 404, 'only the blocker can unblock');
    assert.equal((await h.req('POST', `/v1/friendships/${fid}/unblock`, { user: alex })).status, 200);
    assert.equal((await h.req('GET', '/v1/friends', { user: alex })).body.friends.length, 0);
    const fid2 = await h.befriend(maya, alex);
    const f = await h.req('GET', `/v1/friendships/${fid2}`, { user: maya });
    assert.equal(f.body.theyAllowMe, null);
  });

  it('removing a friend revokes both directions', async () => {
    const maya = await h.createUser('Maya');
    const alex = await h.createUser('Alex');
    const fid = await h.befriend(maya, alex);
    await h.grant(alex, maya, { capabilities: ['nudge.send'], limits: NUDGE_LIMITS });
    const r = await h.req('DELETE', `/v1/friendships/${fid}`, { user: maya });
    assert.equal(r.body.revokedGrants, 1);
    assert.equal((await h.req('GET', '/v1/grants?direction=given', { user: alex })).body.grants.length, 0);
  });
});

describe('circles', () => {
  it('circle membership never grants phone permissions', async () => {
    h.setNow('2026-10-19T14:00:00Z');
    const owner = await h.createUser('Owner');
    const m1 = await h.createUser('Member One');
    const m2 = await h.createUser('Member Two');
    const c = await h.req('POST', '/v1/circles', { user: owner, body: { name: 'Flat 4B' } });
    assert.equal(c.status, 201);
    const inv = await h.req('POST', '/v1/invites', { user: owner, body: { kind: 'circle', circleId: c.body.id } });
    for (const m of [m1, m2]) assert.equal((await h.req('POST', '/v1/invites/redeem', { user: m, body: { token: inv.body.token } })).status, 200);
    const detail = await h.req('GET', `/v1/circles/${c.body.id}`, { user: m1 });
    assert.equal(detail.body.members.length, 3);
    assert.ok(detail.body.members.every((m: any) => m.isFriend === false));
    const q = await h.signed('POST', '/v1/commands', m1, { targetUserId: m2.id, capability: 'nudge.send', idempotencyKey: idem(), soundId: 'bundled:quack.classic' });
    assert.equal(q.status, 404, 'co-members are not friends and get the stranger answer');
    const g = await h.req('POST', '/v1/grants', { user: m1, body: { granteeId: m2.id, capabilities: ['nudge.send'], limits: NUDGE_LIMITS, validUntil: new Date(h.clock.now.getTime() + 86_400_000).toISOString() } });
    assert.equal(g.status, 404, 'grants require an accepted friendship');
  });

  it('members cannot invite or remove; owner leaving hands over ownership', async () => {
    const owner = await h.createUser('Owner');
    const m1 = await h.createUser('Member');
    const c = await h.req('POST', '/v1/circles', { user: owner, body: { name: 'Study group' } });
    const inv = await h.req('POST', '/v1/invites', { user: owner, body: { kind: 'circle', circleId: c.body.id } });
    await h.req('POST', '/v1/invites/redeem', { user: m1, body: { token: inv.body.token } });
    assert.equal((await h.req('POST', '/v1/invites', { user: m1, body: { kind: 'circle', circleId: c.body.id } })).status, 403);
    assert.equal((await h.req('DELETE', `/v1/circles/${c.body.id}/members/${owner.id}`, { user: m1 })).status, 403);
    await h.req('POST', `/v1/circles/${c.body.id}/leave`, { user: owner });
    const after = await h.req('GET', `/v1/circles/${c.body.id}`, { user: m1 });
    assert.equal(after.body.circle.role, 'owner');
    assert.equal((await h.req('GET', `/v1/circles/${c.body.id}`, { user: owner })).status, 404);
  });
});
