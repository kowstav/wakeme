import { after, before, describe, it } from 'node:test';
import assert from 'node:assert/strict';
import { createHarness, idem, NUDGE_LIMITS, WAKE_LIMITS, type Harness, type TestUser } from './support/harness.ts';

let h: Harness;
before(async () => {
  h = await createHarness();
});
after(async () => h.close());

async function pair(): Promise<{ maya: TestUser; alex: TestUser }> {
  const maya = await h.createUser('Maya');
  const alex = await h.createUser('Alex');
  await h.befriend(maya, alex);
  return { maya, alex };
}

function quack(target: TestUser, extra: Record<string, unknown> = {}) {
  return { targetUserId: target.id, capability: 'nudge.send', idempotencyKey: idem(), soundId: 'bundled:quack.classic', ...extra };
}

describe('Friends -> Quack -> receipt (headline slice)', () => {
  it('friendship alone grants no capability', async () => {
    h.setNow('2026-10-19T14:00:00Z');
    const { maya, alex } = await pair();
    const r = await h.signed('POST', '/v1/commands', maya, quack(alex));
    assert.equal(r.status, 403);
    assert.equal(r.body.error.code, 'capability_not_granted');
    const friends = await h.req('GET', '/v1/friends', { user: maya });
    const actions = friends.body.friends[0].actions;
    assert.ok(actions.every((a: any) => a.granted === false && a.availableNow === false));
  });

  it('a recipient grant is directional: Alex allowing Maya does not let Alex quack Maya', async () => {
    h.setNow('2026-10-19T14:00:00Z');
    const { maya, alex } = await pair();
    await h.grant(alex, maya, { capabilities: ['nudge.send'], limits: NUDGE_LIMITS });
    const fromMaya = await h.signed('POST', '/v1/commands', maya, quack(alex));
    assert.equal(fromMaya.status, 201, JSON.stringify(fromMaya.body));
    const fromAlex = await h.signed('POST', '/v1/commands', alex, quack(maya));
    assert.equal(fromAlex.status, 403);
  });

  it('sends a quack with honest, evidence-based status through receipts', async () => {
    h.setNow('2026-10-19T14:00:00Z');
    h.push.clear();
    const { maya, alex } = await pair();
    const g = await h.grant(alex, maya, { capabilities: ['nudge.send'], limits: NUDGE_LIMITS });
    const sent = await h.signed('POST', '/v1/commands', maya, quack(alex, { expectedGrantVersion: g.version }));
    assert.equal(sent.status, 201);
    assert.equal(sent.body.displayStatus, 'sending');
    assert.equal(sent.body.expiresAt, '2026-10-19T14:02:00.000Z');

    await h.drain();
    const pushes = h.push.sent.filter((p) => p.message.data['id'] === sent.body.id);
    assert.equal(pushes.length, 1);
    const msg = pushes[0]!.message;
    assert.equal(pushes[0]!.target.deviceId, alex.device.id);
    assert.equal(msg.alert?.sound, 'quack_classic.wav');
    assert.equal(msg.expiresAt?.toISOString(), '2026-10-19T14:02:00.000Z');
    assert.deepEqual(Object.keys(msg.data).sort(), ['actor', 'cap', 'exp', 'gv', 'id', 'mode', 'snd', 't']);

    let cmd = await h.req('GET', `/v1/commands/${sent.body.id}`, { user: maya });
    assert.equal(cmd.body.displayStatus, 'sent', 'provider accepted is shown as sent, not received');
    h.advance(90);
    cmd = await h.req('GET', `/v1/commands/${sent.body.id}`, { user: maya });
    assert.equal(cmd.body.displayStatus, 'no_confirmation_yet');

    const rec = await h.signed('POST', `/v1/commands/${sent.body.id}/receipts`, alex, { kind: 'received' });
    assert.equal(rec.status, 200);
    assert.equal(rec.body.displayStatus, 'phone_received');
    const ack = await h.signed('POST', `/v1/commands/${sent.body.id}/receipts`, alex, { kind: 'acknowledged' });
    assert.equal(ack.body.displayStatus, 'acknowledged');
    await h.drain();
    assert.ok(h.push.sent.some((p) => p.target.deviceId === maya.device.id && p.message.alert?.body === 'Saw your request 👍'));
  });

  it('idempotency: same key and payload returns the same command; a different payload conflicts', async () => {
    h.setNow('2026-10-19T14:00:00Z');
    const { maya, alex } = await pair();
    await h.grant(alex, maya, { capabilities: ['nudge.send'], limits: NUDGE_LIMITS });
    const body = quack(alex);
    const a = await h.signed('POST', '/v1/commands', maya, body);
    const b = await h.signed('POST', '/v1/commands', maya, body);
    assert.equal(a.status, 201);
    assert.equal(b.body.id, a.body.id);
    const c = await h.signed('POST', '/v1/commands', maya, { ...body, soundId: 'bundled:quack.double' });
    assert.equal(c.status, 422);
    assert.equal(c.body.error.code, 'idempotency_conflict');
  });

  it('enforces the per-friend hourly limit with Retry-After', async () => {
    h.setNow('2026-10-19T14:00:00Z');
    const { maya, alex } = await pair();
    await h.grant(alex, maya, { capabilities: ['nudge.send'], limits: NUDGE_LIMITS });
    for (let i = 0; i < 3; i++) {
      assert.equal((await h.signed('POST', '/v1/commands', maya, quack(alex))).status, 201);
      h.advance(60);
    }
    const r = await h.signed('POST', '/v1/commands', maya, quack(alex));
    assert.equal(r.status, 429);
    assert.equal(r.body.error.code, 'rate_limited_sender');
    assert.equal(r.headers['retry-after'], String(3600 - 180));
  });

  it('five friends together cannot exceed the recipient sound budget', async () => {
    h.setNow('2026-10-19T15:00:00Z');
    const sam = await h.createUser('Sam');
    await h.req('PATCH', '/v1/me', { user: sam, body: { soundBudgetPerHour: 4 } });
    const friends: TestUser[] = [];
    for (let i = 0; i < 5; i++) {
      const f = await h.createUser(`Friend ${i}`);
      await h.befriend(sam, f);
      await h.grant(sam, f, { capabilities: ['nudge.send'], limits: NUDGE_LIMITS });
      friends.push(f);
    }
    const statuses = [];
    for (const f of friends) statuses.push((await h.signed('POST', '/v1/commands', f, quack(sam))).status);
    assert.deepEqual(statuses, [201, 201, 201, 201, 429]);
  });

  it('concurrent senders cannot race past the recipient budget (meaningful on server PostgreSQL)', async () => {
    h.setNow('2026-10-19T16:00:00Z');
    const sam = await h.createUser('Sam');
    await h.req('PATCH', '/v1/me', { user: sam, body: { soundBudgetPerHour: 3 } });
    const friends: TestUser[] = [];
    for (let i = 0; i < 6; i++) {
      const f = await h.createUser(`Racer ${i}`);
      await h.befriend(sam, f);
      await h.grant(sam, f, { capabilities: ['nudge.send'], limits: NUDGE_LIMITS });
      friends.push(f);
    }
    const results = await Promise.all(friends.map((f) => h.signed('POST', '/v1/commands', f, quack(sam))));
    assert.equal(results.filter((r) => r.status === 201).length, 3);
    assert.equal(results.filter((r) => r.status === 429).length, 3);
  });

  it('respects quiet hours and wake windows as explicit consent', async () => {
    const { maya, alex } = await pair();
    await h.grant(alex, maya, {
      capabilities: ['nudge.send', 'alarm.request'],
      limits: { nudge: { ...NUDGE_LIMITS.nudge, windows: [{ days: [1, 2, 3, 4, 5, 6, 7], start: '06:00', end: '23:30' }] }, alarm: WAKE_LIMITS.alarm },
    });
    h.setNow('2026-10-20T02:30:00Z'); // 22:30 in New York: inside window, inside quiet hours
    const q = await h.signed('POST', '/v1/commands', maya, quack(alex));
    assert.equal(q.status, 403);
    assert.equal(q.body.error.code, 'quiet_hours');
    h.setNow('2026-10-20T10:30:00Z'); // Tuesday 06:30: wake window overrides quiet hours
    const w = await h.signed('POST', '/v1/commands', maya, { targetUserId: alex.id, capability: 'alarm.request', idempotencyKey: idem(), soundId: 'bundled:rise.gentle', ringSeconds: 60 });
    assert.equal(w.status, 201, JSON.stringify(w.body));
    assert.equal(w.body.deliveryMode, 'audible');
  });

  it('a wake request needs a phone that reported alarm permission', async () => {
    h.setNow('2026-10-19T14:00:00Z');
    const maya = await h.createUser('Maya');
    const alex = await h.createUser('Alex', { alarmCapable: false });
    await h.befriend(maya, alex);
    await h.grant(alex, maya, { capabilities: ['alarm.request'], limits: { alarm: WAKE_LIMITS.alarm } });
    const r = await h.signed('POST', '/v1/commands', maya, { targetUserId: alex.id, capability: 'alarm.request', idempotencyKey: idem(), soundId: 'bundled:rise.gentle', ringSeconds: 60 });
    assert.equal(r.status, 409);
    assert.equal(r.body.error.code, 'recipient_not_ready');
  });

  it('revocation racing a queued command: the worker re-checks and never sends', async () => {
    h.setNow('2026-10-19T14:00:00Z');
    h.push.clear();
    const { maya, alex } = await pair();
    const g = await h.grant(alex, maya, { capabilities: ['nudge.send'], limits: NUDGE_LIMITS });
    const sent = await h.signed('POST', '/v1/commands', maya, quack(alex));
    assert.equal(sent.status, 201);
    const rev = await h.req('DELETE', `/v1/grants/${g.id}`, { user: alex });
    assert.equal(rev.status, 200);
    await h.drain();
    assert.equal(h.push.sent.filter((p) => p.message.data['id'] === sent.body.id).length, 0);
    const cmd = await h.req('GET', `/v1/commands/${sent.body.id}`, { user: maya });
    assert.equal(cmd.body.state.lifecycle, 'cancelled');
    assert.equal(cmd.body.displayStatus, 'cancelled');
    // Revocation is idempotent.
    assert.equal((await h.req('DELETE', `/v1/grants/${g.id}`, { user: alex })).body.alreadyRevoked, true);
  });

  it('an expired nudge is never sent late', async () => {
    h.setNow('2026-10-19T14:00:00Z');
    h.push.clear();
    const { maya, alex } = await pair();
    await h.grant(alex, maya, { capabilities: ['nudge.send'], limits: NUDGE_LIMITS });
    const sent = await h.signed('POST', '/v1/commands', maya, quack(alex, { expiresInSeconds: 30 }));
    h.advance(45); // worker was down
    await h.drain();
    assert.equal(h.push.sent.filter((p) => p.message.data['id'] === sent.body.id).length, 0);
    const cmd = await h.req('GET', `/v1/commands/${sent.body.id}`, { user: maya });
    assert.equal(cmd.body.displayStatus, 'expired');
  });

  it('retries a transient provider failure only until expiry', async () => {
    h.setNow('2026-10-19T14:00:00Z');
    h.push.clear();
    const { maya, alex } = await pair();
    await h.grant(alex, maya, { capabilities: ['nudge.send'], limits: NUDGE_LIMITS });
    await h.drain();
    const sent = await h.signed('POST', '/v1/commands', maya, quack(alex, { expiresInSeconds: 60 }));
    h.push.next = [{ status: 'retry', reason: 'TooManyRequests', retryAfterSeconds: 5 }];
    await h.drain();
    assert.equal(h.push.sent.filter((p) => p.message.data['id'] === sent.body.id).length, 1);
    h.advance(10);
    await h.makeJobsDue();
    await h.drain();
    const attempts = h.push.sent.filter((p) => p.message.data['id'] === sent.body.id).length;
    assert.equal(attempts, 2);
    const cmd = await h.req('GET', `/v1/commands/${sent.body.id}`, { user: maya });
    assert.equal(cmd.body.displayStatus, 'sent');
  });

  it('marks a dead push token and reports not delivered', async () => {
    h.setNow('2026-10-19T14:00:00Z');
    const { maya, alex } = await pair();
    await h.grant(alex, maya, { capabilities: ['nudge.send'], limits: NUDGE_LIMITS });
    await h.drain();
    const sent = await h.signed('POST', '/v1/commands', maya, quack(alex));
    h.push.next = [{ status: 'rejected', reason: 'Unregistered', tokenInvalid: true }];
    await h.drain();
    const cmd = await h.req('GET', `/v1/commands/${sent.body.id}`, { user: maya });
    assert.equal(cmd.body.displayStatus, 'not_delivered');
    const devices = await h.req('GET', '/v1/devices', { user: alex });
    assert.ok(devices.body.devices[0].readinessIssues.includes('push_token_invalid'));
    const again = await h.signed('POST', '/v1/commands', maya, quack(alex));
    assert.equal(again.body.error.code, 'recipient_not_ready');
  });

  it('cross-user access: strangers and third parties learn nothing and cannot forge receipts', async () => {
    h.setNow('2026-10-19T14:00:00Z');
    const { maya, alex } = await pair();
    const eve = await h.createUser('Eve');
    await h.grant(alex, maya, { capabilities: ['nudge.send'], limits: NUDGE_LIMITS });
    const sent = await h.signed('POST', '/v1/commands', maya, quack(alex));
    assert.equal((await h.req('GET', `/v1/commands/${sent.body.id}`, { user: eve })).status, 404);
    // Eve's own (valid) device cannot report for Alex's command.
    assert.equal((await h.signed('POST', `/v1/commands/${sent.body.id}/receipts`, eve, { kind: 'challenge_completed' })).status, 404);
    // The sender cannot report receipts on the recipient's behalf.
    assert.equal((await h.signed('POST', `/v1/commands/${sent.body.id}/receipts`, maya, { kind: 'acknowledged' })).status, 404);
    // A stranger gets the same answer as for a non-existent user.
    const toStranger = await h.signed('POST', '/v1/commands', eve, quack(alex));
    const toNobody = await h.signed('POST', '/v1/commands', eve, quack({ ...alex, id: '00000000-0000-4000-8000-000000000000' }));
    assert.equal(toStranger.status, 404);
    assert.deepEqual([toStranger.body.error.code, toStranger.body.error.message], [toNobody.body.error.code, toNobody.body.error.message]);
  });

  it('device signatures: required, bound to body, single use, clock-checked, revocable', async () => {
    h.setNow('2026-10-19T14:00:00Z');
    const { maya, alex } = await pair();
    await h.grant(alex, maya, { capabilities: ['nudge.send'], limits: NUDGE_LIMITS });
    const unsigned = await h.req('POST', '/v1/commands', { user: maya, body: quack(alex) });
    assert.equal(unsigned.status, 401);
    assert.equal(unsigned.body.error.code, 'device_signature_required');
    const tampered = await h.signed('POST', '/v1/commands', maya, quack(alex), { tamper: true });
    assert.equal(tampered.body.error.code, 'device_signature_invalid');
    const skewed = await h.signed('POST', '/v1/commands', maya, quack(alex), { timestamp: Math.floor(h.clock.now.getTime() / 1000) - 900 });
    assert.equal(skewed.body.error.code, 'device_clock_skew');
    // Another user's device id with my token is not mine.
    const foreign = await h.signed('POST', '/v1/commands', maya, quack(alex), { device: alex.device });
    assert.equal(foreign.body.error.code, 'device_not_registered');
    await h.req('DELETE', `/v1/devices/${maya.device.id}`, { user: maya });
    const revoked = await h.signed('POST', '/v1/commands', maya, quack(alex));
    assert.equal(revoked.body.error.code, 'device_not_registered');
  });

  it('a replayed signed request is rejected', async () => {
    h.setNow('2026-10-19T14:00:00Z');
    const { maya, alex } = await pair();
    await h.grant(alex, maya, { capabilities: ['nudge.send'], limits: NUDGE_LIMITS });
    const body = quack(alex);
    const ts = Math.floor(h.clock.now.getTime() / 1000);
    const first = await h.signed('POST', '/v1/commands', maya, body, { timestamp: ts });
    assert.equal(first.status, 201);
    // ECDSA signatures are randomized, so replay the exact headers by re-sending the same signature.
    const { sign } = await import('node:crypto');
    const { canonicalDeviceRequest } = await import('../src/http/device-auth.ts');
    const sig = sign('sha256', Buffer.from(canonicalDeviceRequest('POST', '/v1/commands', String(ts + 1), Buffer.from(JSON.stringify(body)))), { key: maya.device.privateKey, dsaEncoding: 'der' }).toString('base64');
    const headers = { 'x-wakeme-device': maya.device.id, 'x-wakeme-timestamp': String(ts + 1), 'x-wakeme-signature': sig };
    assert.equal((await h.req('POST', '/v1/commands', { user: maya, body, headers })).status, 201);
    const replay = await h.req('POST', '/v1/commands', { user: maya, body, headers });
    assert.equal(replay.status, 401);
    assert.equal(replay.body.error.code, 'device_request_replayed');
  });

  it('a kill switch stops new remote quacks but not reads', async () => {
    h.setNow('2026-10-19T14:00:00Z');
    const { maya, alex } = await pair();
    await h.grant(alex, maya, { capabilities: ['nudge.send'], limits: NUDGE_LIMITS });
    await h.db.query(`update feature_flags set enabled = false where key = 'remote_dispatch.nudge'`);
    const r = await h.signed('POST', '/v1/commands', maya, quack(alex));
    assert.equal(r.status, 503);
    assert.equal(r.body.error.code, 'feature_disabled');
    await h.db.query(`update feature_flags set enabled = true where key = 'remote_dispatch.nudge'`);
    assert.equal((await h.req('GET', '/v1/friends', { user: maya })).status, 200);
  });

  it('only the grantor can edit a grant; edits need the current version', async () => {
    h.setNow('2026-10-19T14:00:00Z');
    const { maya, alex } = await pair();
    const g = await h.grant(alex, maya, { capabilities: ['nudge.send'], limits: NUDGE_LIMITS });
    const terms = { capabilities: ['nudge.send'], limits: { nudge: { ...NUDGE_LIMITS.nudge, maxPerHour: 12 } }, validUntil: g.validUntil, paused: false };
    const byGrantee = await h.req('PUT', `/v1/grants/${g.id}`, { user: maya, body: { ...terms, expectedVersion: g.version } });
    assert.equal(byGrantee.status, 404, 'a friend cannot raise their own limits');
    const stale = await h.req('PUT', `/v1/grants/${g.id}`, { user: alex, body: { ...terms, expectedVersion: g.version + 5 } });
    assert.equal(stale.status, 409);
    const ok = await h.req('PUT', `/v1/grants/${g.id}`, { user: alex, body: { ...terms, expectedVersion: g.version } });
    assert.equal(ok.status, 200);
    assert.equal(ok.body.version, g.version + 1);
    // Sender acting on the old version learns terms changed.
    const r = await h.signed('POST', '/v1/commands', maya, quack(alex, { expectedGrantVersion: g.version }));
    assert.equal(r.body.error.code, 'grant_changed');
    const hist = await h.req('GET', `/v1/grants/${g.id}/history`, { user: alex });
    assert.deepEqual(hist.body.versions.map((v: any) => v.version), [1, 2]);
  });

  it('rejects out-of-range or unknown grant terms', async () => {
    const { maya, alex } = await pair();
    const tooMany = await h.req('POST', '/v1/grants', { user: alex, body: { granteeId: maya.id, capabilities: ['nudge.send'], limits: { nudge: { ...NUDGE_LIMITS.nudge, maxPerHour: 500 } }, validUntil: new Date(h.clock.now.getTime() + 86_400_000).toISOString() } });
    assert.equal(tooMany.status, 422);
    const unknown = await h.req('POST', '/v1/grants', { user: alex, body: { granteeId: maya.id, capabilities: ['phone.control'], limits: {}, validUntil: new Date(h.clock.now.getTime() + 86_400_000).toISOString() } });
    assert.equal(unknown.status, 422);
    const smuggled = await h.req('POST', '/v1/grants', { user: alex, body: { granteeId: maya.id, capabilities: ['nudge.send'], limits: NUDGE_LIMITS, validUntil: new Date(h.clock.now.getTime() + 86_400_000).toISOString(), grantorId: maya.id } });
    assert.equal(smuggled.status, 400, 'unknown fields are rejected, not ignored');
  });

  it('high-impact capabilities need a recent sign-in', async () => {
    const { maya, alex } = await pair();
    const body = { granteeId: maya.id, capabilities: ['alarm.request'], limits: { alarm: WAKE_LIMITS.alarm }, validUntil: new Date(h.clock.now.getTime() + 86_400_000).toISOString() };
    const stale = await h.req('POST', '/v1/grants', { token: alex.staleToken, body });
    assert.equal(stale.status, 401);
    assert.equal(stale.body.error.code, 'reauth_required');
    assert.equal((await h.req('POST', '/v1/grants', { user: alex, body })).status, 201);
  });

  it('pausing a friend cancels their queued requests', async () => {
    h.setNow('2026-10-19T14:00:00Z');
    h.push.clear();
    const { maya, alex } = await pair();
    const g = await h.grant(alex, maya, { capabilities: ['nudge.send'], limits: NUDGE_LIMITS });
    const sent = await h.signed('POST', '/v1/commands', maya, quack(alex));
    await h.req('POST', `/v1/grants/${g.id}/pause`, { user: alex, body: { paused: true } });
    await h.drain();
    assert.equal(h.push.sent.filter((p) => p.message.data['id'] === sent.body.id).length, 0);
    const again = await h.signed('POST', '/v1/commands', maya, quack(alex));
    assert.equal(again.body.error.code, 'grant_paused');
  });
});
