import { after, before, describe, it } from 'node:test';
import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { createHarness, NUDGE_LIMITS, type Harness, type TestUser } from './support/harness.ts';
import { encodeWav, parseMp4, parseWav } from '../src/lib/audio.ts';
import { presign, S3Storage } from '../src/storage/s3.ts';

let h: Harness;
before(async () => {
  h = await createHarness();
});
after(async () => h.close());

function tone(seconds: number): Buffer {
  const rate = 16000;
  const s = new Float32Array(rate * seconds);
  for (let i = 0; i < s.length; i++) s[i] = 0.3 * Math.sin((2 * Math.PI * 440 * i) / rate);
  return encodeWav(s, rate);
}

function box(type: string, payload: Buffer): Buffer {
  const head = Buffer.alloc(8);
  head.writeUInt32BE(8 + payload.length, 0);
  head.write(type, 4, 'ascii');
  return Buffer.concat([head, payload]);
}

function m4a(durationSeconds: number): Buffer {
  const mvhd = Buffer.alloc(100);
  mvhd.writeUInt32BE(1000, 12);
  mvhd.writeUInt32BE(durationSeconds * 1000, 16);
  const ftyp = Buffer.concat([Buffer.from('M4A '), Buffer.alloc(4), Buffer.from('isomM4A ')]);
  return Buffer.concat([box('ftyp', ftyp), box('moov', Buffer.concat([box('mvhd', mvhd), box('trak', Buffer.alloc(8))]))]);
}

describe('audio parsing', () => {
  it('reads WAV and M4A durations and rejects malformed files', () => {
    assert.equal(parseWav(tone(2))?.durationMs, 2000);
    assert.equal(parseMp4(m4a(12))?.durationMs, 12000);
    assert.equal(parseWav(Buffer.from('RIFF....WAVEjunk')), null);
    const truncated = tone(1).subarray(0, 60);
    assert.equal(parseWav(truncated), null);
    const hugeBox = Buffer.from(m4a(3));
    hugeBox.writeUInt32BE(0xffffff00, 0);
    assert.equal(parseMp4(hugeBox), null);
  });
});

describe('SigV4', () => {
  it("reproduces AWS's published presigned-URL example", () => {
    const url = presign({ method: 'GET', url: new URL('https://examplebucket.s3.amazonaws.com/test.txt'), region: 'us-east-1', service: 's3', accessKeyId: 'AKIAIOSFODNN7EXAMPLE', secretAccessKey: 'wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY', now: new Date('2013-05-24T00:00:00Z'), headers: {}, expiresSeconds: 86400 }); // AWS's documented example key. secret-scan: allow
    assert.ok(url.endsWith('X-Amz-Signature=aeeed9bbccd4d02ee5c0109b86d86835f995330da4c265957d157751f604d404'));
  });

  it('refuses path traversal in object keys', () => {
    const s = new S3Storage({ endpoint: 'https://acc.r2.cloudflarestorage.com', region: 'auto', bucket: 'b', accessKeyId: 'a', secretAccessKey: 's', forcePathStyle: true });
    assert.throws(() => s.presignGet('../../etc/passwd', { expiresSeconds: 60 }));
    assert.match(s.presignPut('media/u/x.wav', { contentType: 'audio/wav', contentLength: 10, expiresSeconds: 60 }).url, /^https:\/\/acc\.r2\.cloudflarestorage\.com\/b\/media\/u\/x\.wav\?X-Amz-Algorithm=/);
  });
});

describe('private sound uploads', () => {
  async function upload(user: TestUser, data: Buffer, declared: { bytes?: number; sha?: string; durationMs?: number; mime?: string } = {}) {
    const r = await h.req('POST', '/v1/media/uploads', {
      user,
      body: { kind: 'voice', mime: declared.mime ?? 'audio/wav', bytes: declared.bytes ?? data.length, sha256: declared.sha ?? createHash('sha256').update(data).digest('hex'), durationMs: declared.durationMs ?? 2000, rightsAttestation: true },
    });
    assert.equal(r.status, 201, JSON.stringify(r.body));
    const key = r.body.upload.url.replace('memory://', '');
    h.storage.objects.set(key, data);
    await h.req('POST', `/v1/media/${r.body.media.id}/finalize`, { user });
    await h.drain();
    return r.body.media.id as string;
  }

  it('verifies size, hash, format and duration before a sound can be used', async () => {
    const maya = await h.createUser('Maya');
    const good = await upload(maya, tone(2));
    const bad = await upload(maya, tone(2), { sha: '0'.repeat(64) });
    const fake = await upload(maya, Buffer.from('this is not audio at all, just text'), { durationMs: 1000 });
    const list = (await h.req('GET', '/v1/media', { user: maya })).body.media;
    const state = (id: string) => list.find((m: any) => m.id === id);
    assert.equal(state(good).state, 'clean');
    assert.equal(state(bad).rejectionReason, 'hash_mismatch');
    assert.equal(state(fake).rejectionReason, 'unsupported_or_malformed_audio');
    assert.equal(h.storage.objects.has(`media/${maya.id}/${bad}.wav`), false, 'rejected objects are deleted');
  });

  it('a friend sound plays only after the recipient approves it and allows it in a grant', async () => {
    h.setNow('2026-10-19T14:00:00Z');
    const maya = await h.createUser('Maya');
    const alex = await h.createUser('Alex');
    await h.befriend(maya, alex);
    const id = await upload(maya, tone(3), { durationMs: 3000 });
    const soundId = `media:${id}`;
    const tryGrant = () => h.req('POST', '/v1/grants', { user: alex, body: { granteeId: maya.id, capabilities: ['nudge.send'], limits: { nudge: { ...NUDGE_LIMITS.nudge, sounds: [soundId] } }, validUntil: '2026-12-01T00:00:00Z' } });
    assert.equal((await tryGrant()).body.error.code, 'sound_not_in_library');
    await h.req('POST', `/v1/media/${id}/offer`, { user: maya, body: { toUserId: alex.id } });
    assert.equal((await tryGrant()).body.error.code, 'sound_not_in_library', 'an offer is not approval');
    await h.req('POST', `/v1/sound-library/${id}/decision`, { user: alex, body: { approve: true } });
    assert.equal((await tryGrant()).status, 201);
    const dl = await h.req('GET', `/v1/media/${id}/download`, { user: alex });
    assert.equal(dl.status, 200);
    const eve = await h.createUser('Eve');
    assert.equal((await h.req('GET', `/v1/media/${id}/download`, { user: eve })).status, 404);
  });

  it('enforces upload limits', async () => {
    const maya = await h.createUser('Maya');
    const big = await h.req('POST', '/v1/media/uploads', { user: maya, body: { kind: 'voice', mime: 'audio/wav', bytes: 5_000_000, sha256: '0'.repeat(64), durationMs: 2000, rightsAttestation: true } });
    assert.equal(big.status, 400);
    const mp3 = await h.req('POST', '/v1/media/uploads', { user: maya, body: { kind: 'voice', mime: 'audio/mpeg', bytes: 100, sha256: '0'.repeat(64), durationMs: 2000, rightsAttestation: true } });
    assert.equal(mp3.status, 400);
    const noRights = await h.req('POST', '/v1/media/uploads', { user: maya, body: { kind: 'voice', mime: 'audio/wav', bytes: 100, sha256: '0'.repeat(64), durationMs: 2000 } });
    assert.equal(noRights.status, 400);
  });
});
