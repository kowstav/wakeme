import { after, before, describe, it } from 'node:test';
import assert from 'node:assert/strict';
import { createHarness, type Harness } from './support/harness.ts';
import { Worker } from '../src/worker/worker.ts';

let h: Harness;
before(async () => {
  h = await createHarness();
});
after(async () => h.close());

describe('safety sessions', () => {
  it('shares only with chosen friends, encrypted at rest, with capture time and accuracy', async () => {
    h.setNow('2026-10-19T22:00:00Z');
    const maya = await h.createUser('Maya');
    const alex = await h.createUser('Alex');
    const eve = await h.createUser('Eve');
    await h.befriend(maya, alex);
    assert.equal((await h.req('POST', '/v1/location-sessions', { user: maya, body: { purpose: 'share', viewerIds: [eve.id], durationMinutes: 30, shareLocation: true } })).status, 404, 'only friends can be viewers');
    const s = await h.req('POST', '/v1/location-sessions', { user: maya, body: { purpose: 'share', viewerIds: [alex.id], durationMinutes: 30, shareLocation: true } });
    assert.equal(s.status, 201);
    const sid = s.body.id;
    const none = await h.req('GET', `/v1/location-sessions/${sid}/latest`, { user: alex });
    assert.equal(none.body.status, 'no_location', 'no sample is not the same as a location');
    h.advance(30);
    const sample = await h.signed('POST', `/v1/location-sessions/${sid}/samples`, maya, { capturedAt: '2026-10-19T22:00:10Z', latitude: 51.5007, longitude: -0.1246, accuracyMeters: 80 });
    assert.equal(sample.status, 200);
    const raw = await h.db.query<{ ciphertext: string }>('select ciphertext from location_samples where session_id = $1', [sid]);
    assert.ok(!raw.rows[0]!.ciphertext.includes('51.5'), 'coordinates are not stored in plaintext');
    h.advance(12 * 60);
    const latest = await h.req('GET', `/v1/location-sessions/${sid}/latest`, { user: alex });
    assert.equal(latest.body.status, 'available');
    assert.equal(latest.body.latitude, 51.5007);
    assert.equal(latest.body.accuracyMeters, 80);
    assert.equal(latest.body.ageSeconds, 12 * 60 + 20);
    assert.equal((await h.req('GET', `/v1/location-sessions/${sid}/latest`, { user: eve })).status, 404);
    const log = await h.db.query('select 1 from location_access_log where session_id = $1', [sid]);
    assert.ok(log.rowCount >= 1, 'viewer access is audited');
  });

  it('after a location-key rotation an old sample reads as "no location", never an error, until replaced', async () => {
    h.setNow('2026-10-19T22:00:00Z');
    const maya = await h.createUser('Maya');
    const alex = await h.createUser('Alex');
    await h.befriend(maya, alex);
    const s = await h.req('POST', '/v1/location-sessions', { user: maya, body: { purpose: 'share', viewerIds: [alex.id], durationMinutes: 60, shareLocation: true } });
    await h.signed('POST', `/v1/location-sessions/${s.body.id}/samples`, maya, { capturedAt: '2026-10-19T22:00:00Z', latitude: 51.5, longitude: -0.12, accuracyMeters: 20 });
    const original = h.deps.config.locationKey;
    try {
      (h.deps.config as { locationKey: typeof original }).locationKey = { keyId: 'l2', keyBase64: Buffer.alloc(32, 7).toString('base64') };
      const after = await h.req('GET', `/v1/location-sessions/${s.body.id}/latest`, { user: alex });
      assert.equal(after.status, 200);
      assert.equal(after.body.status, 'no_location');
      h.advance(60);
      await h.signed('POST', `/v1/location-sessions/${s.body.id}/samples`, maya, { capturedAt: '2026-10-19T22:01:00Z', latitude: 51.6, longitude: -0.13, accuracyMeters: 25 });
      const fresh = await h.req('GET', `/v1/location-sessions/${s.body.id}/latest`, { user: alex });
      assert.equal(fresh.body.status, 'available');
      assert.equal(fresh.body.latitude, 51.6);
    } finally {
      (h.deps.config as { locationKey: typeof original }).locationKey = original;
    }
  });

  it('rejects stale samples; revoking a viewer or ending the session cuts access', async () => {
    h.setNow('2026-10-19T22:00:00Z');
    const maya = await h.createUser('Maya');
    const alex = await h.createUser('Alex');
    const fid = await h.befriend(maya, alex);
    const s = await h.req('POST', '/v1/location-sessions', { user: maya, body: { purpose: 'share', viewerIds: [alex.id], durationMinutes: 60, shareLocation: true } });
    const stale = await h.signed('POST', `/v1/location-sessions/${s.body.id}/samples`, maya, { capturedAt: '2026-10-19T21:30:00Z', latitude: 1, longitude: 1, accuracyMeters: 10 });
    assert.equal(stale.body.error.code, 'stale_sample');
    await h.signed('POST', `/v1/location-sessions/${s.body.id}/samples`, maya, { capturedAt: '2026-10-19T22:00:00Z', latitude: 1, longitude: 1, accuracyMeters: 10 });
    await h.req('POST', `/v1/friendships/${fid}/block`, { user: maya, body: {} });
    assert.equal((await h.req('GET', `/v1/location-sessions/${s.body.id}/latest`, { user: alex })).status, 404, 'blocking ends viewer access');
    await h.req('POST', `/v1/location-sessions/${s.body.id}/end`, { user: maya });
    const left = await h.db.query('select 1 from location_samples where session_id = $1', [s.body.id]);
    assert.equal(left.rowCount, 0, 'ending deletes the stored sample');
  });

  it('notifies viewers once when a check-in is missed, and "I\'m safe" ends it', async () => {
    h.setNow('2026-10-19T22:00:00Z');
    const maya = await h.createUser('Maya');
    const alex = await h.createUser('Alex');
    await h.befriend(maya, alex);
    const s = await h.req('POST', '/v1/location-sessions', { user: maya, body: { purpose: 'checkin', viewerIds: [alex.id], durationMinutes: 120, shareLocation: false, checkinDueAt: '2026-10-19T23:00:00Z' } });
    assert.equal(s.status, 201, JSON.stringify(s.body));
    await h.drain();
    h.push.clear();
    h.setNow('2026-10-19T23:05:00Z');
    const w = new Worker(h.deps);
    await w.tickPeriodic(true);
    await w.tickPeriodic(true);
    await h.drain();
    const missed = h.push.sent.filter((p) => p.target.deviceId === alex.device.id && /Hasn't checked in/.test(p.message.alert?.body ?? ''));
    assert.equal(missed.length, 1);
    assert.equal(missed[0]!.message.alert?.interruption, 'time-sensitive');
    const done = await h.req('POST', `/v1/location-sessions/${s.body.id}/check-in`, { user: maya, body: {} });
    assert.ok(done.body.checkedInAt);
    assert.equal(done.body.active, false);
  });

  it('SOS alerts chosen friends with literal copy and records acknowledgement', async () => {
    h.setNow('2026-10-19T22:00:00Z');
    const maya = await h.createUser('Maya');
    const alex = await h.createUser('Alex');
    await h.befriend(maya, alex);
    await h.drain();
    h.push.clear();
    const s = await h.req('POST', '/v1/sos', { user: maya, body: { viewerIds: [alex.id], shareLocation: true } });
    assert.equal(s.status, 201);
    await h.drain();
    const p = h.push.sent.find((x) => x.target.deviceId === alex.device.id);
    assert.equal(p?.message.alert?.title, 'SOS from Maya');
    assert.match(p?.message.alert?.body ?? '', /emergency services/);
    assert.equal(p?.message.priority, 'high');
    await h.req('POST', `/v1/location-sessions/${s.body.id}/acknowledge`, { user: alex });
    const mine = await h.req('GET', '/v1/location-sessions', { user: maya });
    assert.ok(mine.body.mine[0].viewers[0].acknowledgedAt);
  });
});
