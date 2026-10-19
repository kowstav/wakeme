import { after, before, describe, it } from 'node:test';
import assert from 'node:assert/strict';
import { createHarness, NUDGE_LIMITS, type Harness } from './support/harness.ts';

let h: Harness;
before(async () => {
  h = await createHarness();
});
after(async () => h.close());

const cid = () => `c_${Math.random().toString(36).slice(2, 12)}`;

describe('private messages', () => {
  it('friends can message; pushes never contain the text; strangers see nothing', async () => {
    const maya = await h.createUser('Maya');
    const alex = await h.createUser('Alex');
    const eve = await h.createUser('Eve');
    await h.befriend(maya, alex);
    const convs = await h.req('GET', '/v1/conversations', { user: maya });
    const conv = convs.body.conversations[0];
    assert.equal(conv.title, 'Alex');
    await h.drain();
    h.push.clear();
    const m = await h.req('POST', `/v1/conversations/${conv.id}/messages`, { user: maya, body: { body: 'Are you up? Meet at Bean House', clientId: cid() } });
    assert.equal(m.status, 201);
    await h.drain();
    const p = h.push.sent.find((x) => x.target.deviceId === alex.device.id)!;
    assert.equal(p.message.alert?.body, 'New message');
    assert.ok(!JSON.stringify(p.message).includes('Bean House'), 'message text never goes into a push');
    assert.equal((await h.req('GET', `/v1/conversations/${conv.id}/messages`, { user: eve })).status, 404);
    assert.equal((await h.req('POST', `/v1/conversations/${conv.id}/messages`, { user: eve, body: { body: 'hi', clientId: cid() } })).status, 404);
    const unread = await h.req('GET', '/v1/conversations', { user: alex });
    assert.equal(unread.body.conversations[0].unread, 1);
  });

  it('client ids make sends idempotent; blocking ends the conversation', async () => {
    const maya = await h.createUser('Maya');
    const alex = await h.createUser('Alex');
    const fid = await h.befriend(maya, alex);
    const conv = (await h.req('GET', '/v1/conversations', { user: maya })).body.conversations[0];
    const clientId = cid();
    const a = await h.req('POST', `/v1/conversations/${conv.id}/messages`, { user: maya, body: { body: 'one', clientId } });
    const b = await h.req('POST', `/v1/conversations/${conv.id}/messages`, { user: maya, body: { body: 'one', clientId } });
    assert.equal(a.body.id, b.body.id);
    await h.req('POST', `/v1/friendships/${fid}/block`, { user: alex, body: {} });
    assert.equal((await h.req('POST', `/v1/conversations/${conv.id}/messages`, { user: maya, body: { body: 'still there?', clientId: cid() } })).status, 404);
  });

  it('only allows the small reaction set', async () => {
    const maya = await h.createUser('Maya');
    const alex = await h.createUser('Alex');
    await h.befriend(maya, alex);
    const conv = (await h.req('GET', '/v1/conversations', { user: maya })).body.conversations[0];
    const m = await h.req('POST', `/v1/conversations/${conv.id}/messages`, { user: maya, body: { body: 'morning', clientId: cid() } });
    assert.equal((await h.req('POST', `/v1/messages/${m.body.id}/reactions`, { user: alex, body: { emoji: '🦆' } })).status, 200);
    assert.equal((await h.req('POST', `/v1/messages/${m.body.id}/reactions`, { user: alex, body: { emoji: '💩' } })).status, 400);
  });
});

describe('reports and moderation', () => {
  it('reports keep minimal evidence and require the reporter could see the item', async () => {
    const maya = await h.createUser('Maya');
    const alex = await h.createUser('Alex');
    const eve = await h.createUser('Eve');
    await h.befriend(maya, alex);
    const conv = (await h.req('GET', '/v1/conversations', { user: maya })).body.conversations[0];
    const m = await h.req('POST', `/v1/conversations/${conv.id}/messages`, { user: maya, body: { body: 'rude words', clientId: cid() } });
    assert.equal((await h.req('POST', '/v1/reports', { user: eve, body: { targetKind: 'message', targetId: m.body.id, reason: 'harassment' } })).status, 404);
    const r = await h.req('POST', '/v1/reports', { user: alex, body: { targetKind: 'message', targetId: m.body.id, reason: 'harassment', note: 'not ok' } });
    assert.equal(r.status, 201);
    const row = await h.db.query<{ evidence: { body: string }; target_user_id: string }>('select evidence, target_user_id from reports where id = $1', [r.body.id]);
    assert.equal(row.rows[0]!.evidence.body, 'rude words');
    assert.equal(row.rows[0]!.target_user_id, maya.id);
  });

  it('admin routes are invisible to normal users and require MFA plus the server allowlist', async () => {
    const user = await h.createUser('Normal');
    assert.equal((await h.req('GET', '/v1/admin/reports', { user })).status, 404);
    const roleOnly = await h.mintToken(user.sub, { extra: { app_metadata: { wakeme_role: 'moderator' }, aal: 'aal2' } });
    assert.equal((await h.req('GET', '/v1/admin/reports', { token: roleOnly })).status, 404, 'a role claim alone is not enough');
  });

  it('suspending an account revokes all of its permissions', async () => {
    const mod = await h.createUser('Moderator');
    h.deps.config.adminUserIds.push(mod.id);
    const modToken = await h.mintToken(mod.sub, { extra: { app_metadata: { wakeme_role: 'moderator' }, aal: 'aal2' } });
    const noMfa = await h.mintToken(mod.sub, { extra: { app_metadata: { wakeme_role: 'moderator' }, aal: 'aal1' } });
    assert.equal((await h.req('GET', '/v1/admin/reports', { token: noMfa })).status, 404);
    const spammer = await h.createUser('Spammer');
    const victim = await h.createUser('Victim');
    await h.befriend(spammer, victim);
    await h.grant(victim, spammer, { capabilities: ['nudge.send'], limits: NUDGE_LIMITS });
    const rep = await h.req('POST', '/v1/reports', { user: victim, body: { targetKind: 'user', targetId: spammer.id, reason: 'unwanted_alerts' } });
    const list = await h.req('GET', '/v1/admin/reports', { token: modToken });
    assert.ok(list.body.reports.some((x: any) => x.id === rep.body.id));
    const res = await h.req('POST', `/v1/admin/reports/${rep.body.id}/resolve`, { token: modToken, body: { action: 'suspend_user', note: 'repeated unwanted alerts' } });
    assert.equal(res.status, 200);
    assert.equal((await h.req('GET', '/v1/friends', { user: spammer })).body.error.code, 'account_suspended');
    const live = await h.db.query('select 1 from grants where grantee_id = $1 and revoked_at is null', [spammer.id]);
    assert.equal(live.rowCount, 0);
    const audit = await h.db.query(`select 1 from audit_events where actor_kind = 'admin' and action = 'admin.report_suspend_user'`);
    assert.equal(audit.rowCount, 1);
  });

  it('kill switches are admin-controlled and audited', async () => {
    const mod = await h.createUser('Operator');
    h.deps.config.adminUserIds.push(mod.id);
    const t = await h.mintToken(mod.sub, { extra: { app_metadata: { wakeme_role: 'operator' }, aal: 'aal2' } });
    assert.equal((await h.req('PUT', '/v1/admin/feature-flags/ai_generation', { token: t, body: { enabled: false, note: 'provider incident' } })).status, 200);
    const cfg = await h.req('GET', '/v1/config');
    assert.equal(cfg.body.features.ai_generation, false);
    const u = await h.createUser('Creator');
    assert.equal((await h.req('POST', '/v1/challenges/generations', { user: u, body: { prompt: 'a quiz' } })).body.error.code, 'ai_unavailable');
    await h.req('PUT', '/v1/admin/feature-flags/ai_generation', { token: t, body: { enabled: true, note: 'resolved' } });
  });
});
