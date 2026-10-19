import { after, before, describe, it } from 'node:test';
import assert from 'node:assert/strict';
import { createServer as createH2, type Http2Server, type IncomingHttpHeaders } from 'node:http2';
import { createServer, type Server } from 'node:http';
import { generateKeyPairSync } from 'node:crypto';
import { decodeJwt, decodeProtectedHeader } from 'jose';
import type { AddressInfo } from 'node:net';
import { ApnsClient } from '../src/push/apns.ts';
import { FcmClient } from '../src/push/fcm.ts';
import { RoutingPushGateway, type PushMessage, type PushTarget } from '../src/push/gateway.ts';
import { renderAlert } from '../src/worker/notifications.ts';

const iosTarget: PushTarget = { deviceId: 'd1', platform: 'ios', provider: 'apns', token: 'a'.repeat(64), environment: 'sandbox' };
const androidTarget: PushTarget = { deviceId: 'd2', platform: 'android', provider: 'fcm', token: 'fcm-token-123456', environment: null };
const nudge = (): PushMessage => ({
  priority: 'high',
  expiresAt: new Date(Date.now() + 120_000),
  collapseId: 'cmd-1',
  alert: renderAlert('nudge', 'Maya', { soundId: 'bundled:quack.classic' }),
  data: { t: 'command', id: 'cmd-1', cap: 'nudge.send' },
});

describe('APNs client (HTTP/2, token auth)', () => {
  let server: Http2Server;
  let origin: string;
  const seen: { headers: IncomingHttpHeaders; body: string }[] = [];
  const replies: { status: number; body?: object }[] = [];
  const { privateKey } = generateKeyPairSync('ec', { namedCurve: 'P-256' });
  const pem = privateKey.export({ type: 'pkcs8', format: 'pem' }).toString();

  before(async () => {
    server = createH2();
    server.on('stream', (stream, headers) => {
      let body = '';
      stream.on('data', (c) => (body += c));
      stream.on('end', () => {
        seen.push({ headers, body });
        const reply = replies.shift() ?? { status: 200 };
        stream.respond({ ':status': reply.status, 'apns-id': 'apns-123' });
        stream.end(reply.body ? JSON.stringify(reply.body) : '');
      });
    });
    await new Promise<void>((r) => server.listen(0, '127.0.0.1', r));
    origin = `http://127.0.0.1:${(server.address() as AddressInfo).port}`;
  });
  after(() => server.close());

  it('sends a correctly addressed, expiring, localizable alert', async () => {
    const client = new ApnsClient({ keyId: 'KEY123', teamId: 'TEAM456', privateKey: pem, topic: 'app.wakeme.ios', origin });
    const res = await client.send(iosTarget, nudge());
    assert.deepEqual(res, { status: 'accepted', providerId: 'apns-123' });
    const req = seen.at(-1)!;
    assert.equal(req.headers[':path'], `/3/device/${'a'.repeat(64)}`);
    assert.equal(req.headers['apns-topic'], 'app.wakeme.ios');
    assert.equal(req.headers['apns-push-type'], 'alert');
    assert.equal(req.headers['apns-priority'], '10');
    assert.equal(req.headers['apns-collapse-id'], 'cmd-1');
    assert.ok(Number(req.headers['apns-expiration']) > Date.now() / 1000);
    const jwt = String(req.headers['authorization']).replace('bearer ', '');
    assert.equal(decodeProtectedHeader(jwt).kid, 'KEY123');
    assert.equal(decodeJwt(jwt).iss, 'TEAM456');
    const body = JSON.parse(req.body);
    assert.equal(body.aps.sound, 'quack_classic.wav');
    assert.equal(body.aps['interruption-level'], 'time-sensitive');
    assert.equal(body.aps.alert['loc-key'], 'push.nudge.body');
    assert.deepEqual(body.wm, { t: 'command', id: 'cmd-1', cap: 'nudge.send' });
    client.close();
  });

  it('marks unregistered tokens invalid and refreshes an expired provider token once', async () => {
    const client = new ApnsClient({ keyId: 'KEY123', teamId: 'TEAM456', privateKey: pem, topic: 'app.wakeme.ios', origin });
    replies.push({ status: 410, body: { reason: 'Unregistered' } });
    assert.deepEqual(await client.send(iosTarget, nudge()), { status: 'rejected', reason: 'Unregistered', tokenInvalid: true });
    replies.push({ status: 403, body: { reason: 'ExpiredProviderToken' } }, { status: 200 });
    assert.equal((await client.send(iosTarget, nudge())).status, 'accepted');
    replies.push({ status: 429, body: { reason: 'TooManyRequests' } });
    assert.equal((await client.send(iosTarget, nudge())).status, 'retry');
    client.close();
  });

  it('sends background updates at priority 5 with no alert', async () => {
    const client = new ApnsClient({ keyId: 'K', teamId: 'T', privateKey: pem, topic: 'app.wakeme.ios', origin });
    await client.send(iosTarget, { priority: 'normal', expiresAt: null, background: true, data: { t: 'sync' } });
    const req = seen.at(-1)!;
    assert.equal(req.headers['apns-push-type'], 'background');
    assert.equal(req.headers['apns-priority'], '5');
    assert.deepEqual(JSON.parse(req.body), { aps: { 'content-available': 1 }, wm: { t: 'sync' } });
    client.close();
  });

  it('the gateway never sends an already expired message', async () => {
    const gw = new RoutingPushGateway(new ApnsClient({ keyId: 'K', teamId: 'T', privateKey: pem, topic: 't', origin }), null);
    const n = seen.length;
    const r = await gw.send(iosTarget, { ...nudge(), expiresAt: new Date(Date.now() - 1000) });
    assert.equal(r.status, 'rejected');
    assert.equal(seen.length, n);
    assert.equal((await gw.send(androidTarget, nudge())).status, 'rejected', 'unconfigured provider is reported, not faked');
  });
});

describe('FCM client (HTTP v1, service-account OAuth)', () => {
  let server: Server;
  let base: string;
  const requests: { url: string; headers: Record<string, unknown>; body: string }[] = [];
  const replies: { status: number; body: object }[] = [];
  const { privateKey } = generateKeyPairSync('rsa', { modulusLength: 2048 });
  const pem = privateKey.export({ type: 'pkcs8', format: 'pem' }).toString();

  before(async () => {
    server = createServer((req, res) => {
      let body = '';
      req.on('data', (c) => (body += c));
      req.on('end', () => {
        requests.push({ url: req.url ?? '', headers: req.headers, body });
        if (req.url === '/token') {
          res.writeHead(200, { 'content-type': 'application/json' });
          res.end(JSON.stringify({ access_token: 'ya29.test', expires_in: 3600 }));
          return;
        }
        const reply = replies.shift() ?? { status: 200, body: { name: 'projects/p/messages/1' } };
        res.writeHead(reply.status, { 'content-type': 'application/json' });
        res.end(JSON.stringify(reply.body));
      });
    });
    await new Promise<void>((r) => server.listen(0, '127.0.0.1', r));
    base = `http://127.0.0.1:${(server.address() as AddressInfo).port}`;
  });
  after(() => server.close());

  it('exchanges a signed assertion for a token and sends a data-only, expiring message', async () => {
    const client = new FcmClient({ projectId: 'wakeme-test', clientEmail: 'push@wakeme-test.iam.gserviceaccount.com', privateKey: pem, tokenUri: `${base}/token`, sendOrigin: base });
    const res = await client.send(androidTarget, nudge());
    assert.equal(res.status, 'accepted');
    const tokenReq = requests.find((r) => r.url === '/token')!;
    const assertion = new URLSearchParams(tokenReq.body).get('assertion')!;
    const claims = decodeJwt(assertion);
    assert.equal(claims.iss, 'push@wakeme-test.iam.gserviceaccount.com');
    assert.equal(claims['scope'], 'https://www.googleapis.com/auth/firebase.messaging');
    const send = requests.find((r) => r.url === '/v1/projects/wakeme-test/messages:send')!;
    assert.equal(send.headers['authorization'], 'Bearer ya29.test');
    const msg = JSON.parse(send.body).message;
    assert.equal(msg.token, 'fcm-token-123456');
    assert.equal(msg.notification, undefined, 'data-only so the app re-validates before showing anything');
    assert.equal(msg.android.priority, 'HIGH');
    assert.match(msg.android.ttl, /^1[01]\ds$|^120s$/);
    assert.equal(msg.data.cap, 'nudge.send');
    assert.equal(msg.data.locKey, 'push.nudge.body');
  });

  it('classifies unregistered tokens and retryable errors', async () => {
    const client = new FcmClient({ projectId: 'wakeme-test', clientEmail: 'push@x', privateKey: pem, tokenUri: `${base}/token`, sendOrigin: base });
    replies.push({ status: 404, body: { error: { status: 'NOT_FOUND', details: [{ errorCode: 'UNREGISTERED' }] } } });
    assert.deepEqual(await client.send(androidTarget, nudge()), { status: 'rejected', reason: 'UNREGISTERED', tokenInvalid: true });
    replies.push({ status: 503, body: { error: { status: 'UNAVAILABLE' } } });
    assert.equal((await client.send(androidTarget, nudge())).status, 'retry');
  });
});
