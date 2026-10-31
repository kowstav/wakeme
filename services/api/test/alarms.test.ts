import { after, before, describe, it } from 'node:test';
import assert from 'node:assert/strict';
import { createHarness, WAKE_LIMITS, type Harness, type TestUser } from './support/harness.ts';

let h: Harness;
before(async () => {
  h = await createHarness();
});
after(async () => h.close());

const weekday7 = { localTime: '07:00', timeZone: 'America/New_York', timeZonePolicy: 'fixed', recurrence: { kind: 'weekly', days: [1, 2, 3, 4, 5] }, soundId: 'bundled:rise.gentle', ringSeconds: 90 };

async function wakeBuddies(): Promise<{ sam: TestUser; maya: TestUser; grantId: string }> {
  const sam = await h.createUser('Sam');
  const maya = await h.createUser('Maya');
  await h.befriend(sam, maya);
  const g = await h.grant(sam, maya, { capabilities: ['alarm.schedule', 'challenge.assign'], limits: WAKE_LIMITS });
  return { sam, maya, grantId: g.id };
}

describe('personal routines', () => {
  it('is only "ready" after the phone reports the OS scheduled it', async () => {
    h.setNow('2026-10-18T12:00:00Z');
    const sam = await h.createUser('Sam');
    const p = await h.req('POST', '/v1/alarm-plans', { user: sam, body: weekday7 });
    assert.equal(p.status, 201, JSON.stringify(p.body));
    assert.equal(p.body.readiness, 'awaiting_installation', 'saved on the server is not "scheduled"');
    assert.deepEqual(p.body.nextOccurrences.map((o: any) => o.instant), ['2026-10-19T11:00:00.000Z', '2026-10-20T11:00:00.000Z', '2026-10-21T11:00:00.000Z']);
    const failed = await h.signed('POST', `/v1/alarm-plans/${p.body.id}/installations`, sam, { version: 1, result: 'failed', errorCode: 'alarmkit.not_authorized' });
    assert.equal(failed.body.readiness, 'install_failed');
    const ok = await h.signed('POST', `/v1/alarm-plans/${p.body.id}/installations`, sam, { version: 1, result: 'scheduled', nextFireAt: '2026-10-19T11:00:00Z', scheduledInstants: ['2026-10-19T11:00:00Z'] });
    assert.equal(ok.body.readiness, 'ready');
    // Another device of mine that isn't primary doesn't count.
    const tablet = await h.addDevice(sam, { platform: 'android', makePrimary: true });
    const detail = await h.req('GET', `/v1/alarm-plans/${p.body.id}`, { user: sam });
    assert.equal(detail.body.readiness, 'awaiting_installation');
    void tablet;
  });

  it('stays ready while the phone has the next occurrence armed, and not once its window has passed', async () => {
    const sam = await h.createUser('Sam');
    for (const timeZonePolicy of ['fixed', 'device']) {
      h.setNow('2026-10-18T12:00:00Z');
      const p = await h.req('POST', '/v1/alarm-plans', { user: sam, body: { ...weekday7, timeZonePolicy } });
      const armed = ['2026-10-19T11:00:00Z', '2026-10-20T11:00:00Z', '2026-10-21T11:00:00Z'];
      const ok = await h.signed('POST', `/v1/alarm-plans/${p.body.id}/installations`, sam, { version: 1, result: 'scheduled', nextFireAt: armed[0], scheduledInstants: armed });
      assert.equal(ok.body.readiness, 'ready', timeZonePolicy);
      h.setNow('2026-10-19T11:05:00Z'); // Monday's rang; Tuesday's is armed
      assert.equal((await h.req('GET', `/v1/alarm-plans/${p.body.id}`, { user: sam })).body.readiness, 'ready', timeZonePolicy);
      h.setNow('2026-10-21T11:05:00Z'); // everything the phone armed has passed
      assert.equal((await h.req('GET', `/v1/alarm-plans/${p.body.id}`, { user: sam })).body.readiness, 'awaiting_installation', timeZonePolicy);
    }
  });

  it('a fixed-zone routine is not ready when the phone armed a different instant', async () => {
    h.setNow('2026-10-18T12:00:00Z');
    const sam = await h.createUser('Sam');
    const p = await h.req('POST', '/v1/alarm-plans', { user: sam, body: weekday7 });
    const off = await h.signed('POST', `/v1/alarm-plans/${p.body.id}/installations`, sam, { version: 1, result: 'scheduled', nextFireAt: '2026-10-19T12:00:00Z', scheduledInstants: ['2026-10-19T12:00:00Z'] });
    assert.equal(off.body.readiness, 'awaiting_installation', 'an hour off is not the routine the owner accepted');
  });

  it('free plan limits creation of new routines but never disables existing ones', async () => {
    h.setNow('2026-10-18T12:00:00Z');
    const sam = await h.createUser('Sam');
    for (let i = 0; i < 5; i++) assert.equal((await h.req('POST', '/v1/alarm-plans', { user: sam, body: { ...weekday7, label: `R${i}` } })).status, 201);
    const sixth = await h.req('POST', '/v1/alarm-plans', { user: sam, body: weekday7 });
    assert.equal(sixth.status, 403);
    assert.equal(sixth.body.error.code, 'plan_limit_reached');
    const list = await h.req('GET', '/v1/alarm-plans', { user: sam });
    assert.equal(list.body.mine.filter((p: any) => p.state === 'active').length, 5);
  });

  it('validates time zones and recurrence', async () => {
    const sam = await h.createUser('Sam');
    assert.equal((await h.req('POST', '/v1/alarm-plans', { user: sam, body: { ...weekday7, timeZone: 'Mars/Olympus' } })).status, 422);
    assert.equal((await h.req('POST', '/v1/alarm-plans', { user: sam, body: { ...weekday7, recurrence: { kind: 'weekly', days: [1, 1] } } })).status, 422);
    assert.equal((await h.req('POST', '/v1/alarm-plans', { user: sam, body: { ...weekday7, soundId: 'https://evil.example/loud.mp3' } })).status, 422);
  });
});

describe('wake buddy routines', () => {
  it('a friend proposes; nothing is active until the owner accepts the exact version', async () => {
    h.setNow('2026-10-18T12:00:00Z');
    const { sam, maya } = await wakeBuddies();
    const p = await h.req('POST', '/v1/alarm-plans', { user: maya, body: { ...weekday7, ownerId: sam.id } });
    assert.equal(p.status, 201, JSON.stringify(p.body));
    assert.equal(p.body.state, 'awaiting_acceptance');
    assert.equal(p.body.readiness, 'awaiting_acceptance');
    await h.drain();
    assert.ok(h.push.sent.some((x) => x.target.deviceId === sam.device.id && x.message.alert?.title === 'Maya suggested a wake-up routine'));
    assert.equal((await h.req('POST', `/v1/alarm-plans/${p.body.id}/accept`, { user: maya, body: { version: 1 } })).status, 403, 'only the owner accepts');
    const acc = await h.req('POST', `/v1/alarm-plans/${p.body.id}/accept`, { user: sam, body: { version: 1 } });
    assert.equal(acc.body.state, 'active');
    assert.equal(acc.body.accepted.version, 1);
  });

  it('a friend cannot exceed what the owner granted', async () => {
    h.setNow('2026-10-18T12:00:00Z');
    const { sam, maya } = await wakeBuddies();
    const cases: [Record<string, unknown>, string][] = [
      [{ localTime: '05:00' }, 'outside_allowed_window'],
      [{ recurrence: { kind: 'weekly', days: [6] } }, 'outside_allowed_window'],
      [{ ringSeconds: 300 }, 'duration_exceeds_limit'],
      [{ soundId: 'bundled:pulse.wake' }, 'sound_not_allowed'],
      [{ followUpDelaySeconds: 600 }, 'follow_up_not_allowed'],
    ];
    for (const [patch, code] of cases) {
      const r = await h.req('POST', '/v1/alarm-plans', { user: maya, body: { ...weekday7, ownerId: sam.id, ...patch } });
      assert.equal(r.body.error?.code, code, JSON.stringify(patch));
    }
    // The helper's zone is ignored: times are always interpreted in the owner's zone.
    const tz = await h.req('POST', '/v1/alarm-plans', { user: maya, body: { ...weekday7, ownerId: sam.id, timeZone: 'Asia/Tokyo', timeZonePolicy: 'device' } });
    assert.equal(tz.status, 201);
    assert.equal(tz.body.pending.timeZone, 'America/New_York');
    assert.equal(tz.body.pending.timeZonePolicy, 'fixed');
    await h.req('DELETE', `/v1/alarm-plans/${tz.body.id}`, { user: maya });
    // Daily limit (maxPerDay 1): a second routine on the same weekdays is refused.
    assert.equal((await h.req('POST', '/v1/alarm-plans', { user: maya, body: { ...weekday7, ownerId: sam.id } })).status, 201);
    const second = await h.req('POST', '/v1/alarm-plans', { user: maya, body: { ...weekday7, localTime: '08:00', ownerId: sam.id } });
    assert.equal(second.body.error.code, 'daily_limit_reached');
  });

  it("a friend's edit is pending and never replaces the accepted version until accepted", async () => {
    h.setNow('2026-10-18T12:00:00Z');
    const { sam, maya } = await wakeBuddies();
    const p = await h.req('POST', '/v1/alarm-plans', { user: maya, body: { ...weekday7, ownerId: sam.id } });
    await h.req('POST', `/v1/alarm-plans/${p.body.id}/accept`, { user: sam, body: { version: 1 } });
    const edit = await h.req('PATCH', `/v1/alarm-plans/${p.body.id}`, { user: maya, body: { ...weekday7, localTime: '07:30', expectedLatestVersion: 1 } });
    assert.equal(edit.status, 200, JSON.stringify(edit.body));
    assert.equal(edit.body.accepted.localTime, '07:00');
    assert.equal(edit.body.pending.localTime, '07:30');
    const declined = await h.req('POST', `/v1/alarm-plans/${p.body.id}/decline`, { user: sam, body: { version: 2 } });
    assert.equal(declined.body.state, 'active');
    assert.equal(declined.body.accepted.version, 1);
    assert.equal(declined.body.pending, null);
    const stale = await h.req('PATCH', `/v1/alarm-plans/${p.body.id}`, { user: maya, body: { ...weekday7, expectedLatestVersion: 1 } });
    assert.equal(stale.status, 409);
  });

  it('revoking the grant stops routines that friend created', async () => {
    h.setNow('2026-10-18T12:00:00Z');
    const { sam, maya, grantId } = await wakeBuddies();
    const p = await h.req('POST', '/v1/alarm-plans', { user: maya, body: { ...weekday7, ownerId: sam.id } });
    await h.req('POST', `/v1/alarm-plans/${p.body.id}/accept`, { user: sam, body: { version: 1 } });
    const own = await h.req('POST', '/v1/alarm-plans', { user: sam, body: weekday7 });
    await h.req('DELETE', `/v1/grants/${grantId}`, { user: sam });
    assert.equal((await h.req('GET', `/v1/alarm-plans/${p.body.id}`, { user: sam })).body.state, 'cancelled');
    assert.equal((await h.req('GET', `/v1/alarm-plans/${own.body.id}`, { user: sam })).body.state, 'active', "the owner's own routines are untouched");
  });

  it('occurrence outcomes only move forward and completion notifies the helper', async () => {
    h.setNow('2026-10-18T12:00:00Z');
    const { sam, maya } = await wakeBuddies();
    const p = await h.req('POST', '/v1/alarm-plans', { user: maya, body: { ...weekday7, ownerId: sam.id } });
    await h.req('POST', `/v1/alarm-plans/${p.body.id}/accept`, { user: sam, body: { version: 1 } });
    await h.drain();
    h.push.clear();
    await h.signed('POST', `/v1/alarm-plans/${p.body.id}/occurrences`, sam, { occurrenceKey: '2026-10-19', version: 1, outcome: 'challenge_completed' });
    await h.signed('POST', `/v1/alarm-plans/${p.body.id}/occurrences`, sam, { occurrenceKey: '2026-10-19', version: 1, outcome: 'rang' });
    const r = await h.db.query<{ outcome: string }>('select outcome from alarm_occurrences where plan_id = $1', [p.body.id]);
    assert.equal(r.rows[0]!.outcome, 'challenge_completed');
    await h.drain();
    assert.ok(h.push.sent.some((x) => x.target.deviceId === maya.device.id && /Finished their wake-up challenge/.test(x.message.alert?.body ?? '')));
    const bad = await h.signed('POST', `/v1/alarm-plans/${p.body.id}/occurrences`, maya, { occurrenceKey: '2026-10-20', version: 1, outcome: 'challenge_completed' });
    assert.equal(bad.status, 404, "the helper's phone cannot report the owner's outcome");
  });
});
