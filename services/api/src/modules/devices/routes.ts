import Type from 'typebox';
import { isValidPublicKey } from '../../http/device-auth.ts';
import { forbidden, notFound, unprocessable } from '../../http/errors.ts';
import { IdParams, Instant, Nullable, Obj, iso, route, type RouteDef } from '../../http/route.ts';
import type { Queryable } from '../../db/db.ts';
import { appendSync, audit, type DeviceCapabilities, type DeviceRow } from '../identity/repo.ts';

const Capabilities = Obj({
  notificationsAuthorized: Type.Optional(Type.Boolean()),
  timeSensitiveAuthorized: Type.Optional(Type.Boolean()),
  alarmAuthorized: Type.Optional(Type.Boolean()),
  exactAlarmPermitted: Type.Optional(Type.Boolean()),
  fullScreenIntentPermitted: Type.Optional(Type.Boolean()),
  backgroundRefreshAvailable: Type.Optional(Type.Boolean()),
  batteryOptimizationExempt: Type.Optional(Type.Boolean()),
  speakerPreferenceSupported: Type.Optional(Type.Boolean()),
  focusAuthorized: Type.Optional(Type.Boolean()),
  locationAuthorized: Type.Optional(Type.Union([Type.Literal('none'), Type.Literal('when_in_use'), Type.Literal('always')])),
});

const Push = Obj({
  provider: Type.Union([Type.Literal('apns'), Type.Literal('fcm')]),
  token: Type.String({ minLength: 16, maxLength: 4096 }),
  environment: Type.Optional(Type.Union([Type.Literal('sandbox'), Type.Literal('production')])),
});

const Device = Type.Object({
  id: Type.String(),
  platform: Type.String(),
  osVersion: Type.String(),
  appBuild: Type.String(),
  isPrimary: Type.Boolean(),
  pushRegistered: Type.Boolean(),
  capabilities: Type.Unknown(),
  readinessIssues: Type.Array(Type.String()),
  lastSeenAt: Instant,
  createdAt: Instant,
  revokedAt: Nullable(Instant),
});

/**
 * Plain-language readiness issues shown on "Make this phone ready". These are
 * self-reported by the app and are hints, not verification of audibility.
 */
export function readinessIssues(d: DeviceRow): string[] {
  const c: DeviceCapabilities = d.capabilities ?? {};
  const issues: string[] = [];
  if (!d.push_token) issues.push('push_not_registered');
  if (d.push_token_invalid_at) issues.push('push_token_invalid');
  if (c.notificationsAuthorized === false) issues.push('notifications_off');
  if (d.platform === 'ios') {
    if (c.alarmAuthorized !== true) issues.push('alarm_permission_missing');
    if (c.timeSensitiveAuthorized === false) issues.push('time_sensitive_off');
  } else {
    if (c.exactAlarmPermitted !== true) issues.push('exact_alarm_permission_missing');
    if (c.fullScreenIntentPermitted === false) issues.push('full_screen_alarm_off');
    if (c.batteryOptimizationExempt === false) issues.push('battery_optimization_may_delay');
  }
  return issues;
}

function dto(d: DeviceRow, primaryId: string | null): Type.Static<typeof Device> {
  return {
    id: d.id,
    platform: d.platform,
    osVersion: d.os_version,
    appBuild: d.app_build,
    isPrimary: d.id === primaryId,
    pushRegistered: !!d.push_token && !d.push_token_invalid_at,
    capabilities: d.capabilities ?? {},
    readinessIssues: readinessIssues(d),
    lastSeenAt: d.last_seen_at.toISOString(),
    createdAt: d.created_at.toISOString(),
    revokedAt: iso(d.revoked_at),
  };
}

async function claimPushToken(q: Queryable, deviceId: string, token: string): Promise<void> {
  // A token belongs to one installation. If another account previously used this
  // phone, its stale registration must never receive this user's alerts (or vice versa).
  await q.query('update devices set push_token = null where push_token = $1 and id <> $2', [token, deviceId]);
}

export const deviceRoutes: RouteDef[] = [
  route({
    method: 'POST',
    url: '/v1/devices',
    operationId: 'registerDevice',
    summary: 'Register or refresh this installation with its hardware-backed public key.',
    tags: ['devices'],
    auth: 'user',
    rateLimit: { max: 20, timeWindow: '1 hour' },
    body: Obj({
      installId: Type.String({ minLength: 8, maxLength: 64, pattern: '^[A-Za-z0-9_-]+$' }),
      platform: Type.Union([Type.Literal('ios'), Type.Literal('android')]),
      osVersion: Type.String({ minLength: 1, maxLength: 32 }),
      appBuild: Type.String({ minLength: 1, maxLength: 32 }),
      publicKey: Type.String({ minLength: 40, maxLength: 512, description: 'base64 SPKI DER, EC P-256' }),
      push: Type.Optional(Push),
      capabilities: Type.Optional(Capabilities),
      makePrimary: Type.Optional(Type.Boolean()),
    }),
    response: Device,
    handler: async ({ body, user, deps, now }) => {
      if (!isValidPublicKey(body.publicKey)) throw unprocessable('invalid_public_key', 'Device key must be an EC P-256 public key.');
      if (body.push && body.push.provider !== (body.platform === 'ios' ? 'apns' : 'fcm')) throw unprocessable('push_provider_mismatch', 'Push provider does not match the platform.');
      return deps.db.tx(async (q) => {
        const r = await q.query<DeviceRow>(
          `insert into devices (user_id, install_id, platform, os_version, app_build, public_key, push_provider, push_token, push_environment, capabilities, last_seen_at)
           values ($1, $2, $3, $4, $5, $6, $7, $8, $9, $10::jsonb, $11)
           on conflict (user_id, install_id) do update set
             platform = excluded.platform, os_version = excluded.os_version, app_build = excluded.app_build, public_key = excluded.public_key,
             push_provider = coalesce(excluded.push_provider, devices.push_provider), push_token = coalesce(excluded.push_token, devices.push_token),
             push_environment = coalesce(excluded.push_environment, devices.push_environment),
             push_token_invalid_at = case when excluded.push_token is not null then null else devices.push_token_invalid_at end,
             capabilities = excluded.capabilities, last_seen_at = excluded.last_seen_at, revoked_at = null
           returning *`,
          [
            user.id,
            body.installId,
            body.platform,
            body.osVersion,
            body.appBuild,
            body.publicKey,
            body.push?.provider ?? null,
            body.push?.token ?? null,
            body.push?.environment ?? (body.push?.provider === 'apns' ? 'production' : null),
            JSON.stringify(body.capabilities ?? {}),
            now,
          ],
        );
        const device = r.rows[0]!;
        if (body.push) await claimPushToken(q, device.id, body.push.token);
        let primary = user.primary_device_id;
        if ((body.makePrimary || !primary) && primary !== device.id) {
          await q.query('update users set primary_device_id = $2, updated_at = $3 where id = $1', [user.id, device.id, now]);
          primary = device.id;
          // Phones learn which one rings from the profile (ADR 0007).
          await appendSync(q, [user.id], 'me', user.id);
        }
        await appendSync(q, [user.id], 'device', device.id);
        await audit(q, { actorUserId: user.id, actorKind: 'user', action: 'device.registered', targetKind: 'device', targetId: device.id, data: { platform: body.platform } });
        return dto(device, primary);
      });
    },
  }),

  route({
    method: 'GET',
    url: '/v1/devices',
    operationId: 'listDevices',
    summary: 'Your signed-in devices and their readiness.',
    tags: ['devices'],
    auth: 'user',
    response: Type.Object({ devices: Type.Array(Device) }),
    handler: async ({ user, deps }) => {
      const r = await deps.db.query<DeviceRow>('select * from devices where user_id = $1 and revoked_at is null order by created_at', [user.id]);
      return { devices: r.rows.map((d) => dto(d, user.primary_device_id)) };
    },
  }),

  route({
    method: 'PATCH',
    url: '/v1/devices/:id',
    operationId: 'updateDevice',
    summary: 'Refresh push token and capability snapshot. Must be signed by this device.',
    tags: ['devices'],
    auth: 'device',
    params: IdParams,
    body: Obj({
      osVersion: Type.Optional(Type.String({ minLength: 1, maxLength: 32 })),
      appBuild: Type.Optional(Type.String({ minLength: 1, maxLength: 32 })),
      push: Type.Optional(Push),
      capabilities: Type.Optional(Capabilities),
    }),
    response: Device,
    handler: async ({ params, body, user, device, deps, now }) => {
      if (device!.id !== params.id) throw forbidden('wrong_device', 'A device can only update itself.');
      return deps.db.tx(async (q) => {
        const r = await q.query<DeviceRow>(
          `update devices set os_version = coalesce($2, os_version), app_build = coalesce($3, app_build),
             push_provider = coalesce($4, push_provider), push_token = coalesce($5, push_token), push_environment = coalesce($6, push_environment),
             push_token_invalid_at = case when $5::text is not null then null else push_token_invalid_at end,
             capabilities = coalesce($7::jsonb, capabilities), last_seen_at = $8
           where id = $1 returning *`,
          [params.id, body.osVersion ?? null, body.appBuild ?? null, body.push?.provider ?? null, body.push?.token ?? null, body.push?.environment ?? null, body.capabilities ? JSON.stringify(body.capabilities) : null, now],
        );
        if (body.push) await claimPushToken(q, params.id, body.push.token);
        await appendSync(q, [user.id], 'device', params.id);
        return dto(r.rows[0]!, user.primary_device_id);
      });
    },
  }),

  route({
    method: 'POST',
    url: '/v1/devices/:id/primary',
    operationId: 'setPrimaryDevice',
    summary: 'Choose the phone that rings for wake alarms. Other devices get passive updates.',
    tags: ['devices'],
    auth: 'user',
    params: IdParams,
    response: Device,
    handler: async ({ params, user, deps, now }) =>
      deps.db.tx(async (q) => {
        const r = await q.query<DeviceRow>('select * from devices where id = $1 and user_id = $2 and revoked_at is null', [params.id, user.id]);
        const d = r.rows[0];
        if (!d) throw notFound('device');
        await q.query('update users set primary_device_id = $2, updated_at = $3 where id = $1', [user.id, d.id, now]);
        await appendSync(q, [user.id], 'me', user.id);
        return dto(d, d.id);
      }),
  }),

  route({
    method: 'DELETE',
    url: '/v1/devices/:id',
    operationId: 'revokeDevice',
    summary: 'Sign out a device. It can no longer submit receipts or send requests.',
    tags: ['devices'],
    auth: 'user',
    params: IdParams,
    response: Type.Object({ ok: Type.Boolean(), primaryCleared: Type.Boolean() }),
    handler: async ({ params, user, deps, now }) =>
      deps.db.tx(async (q) => {
        const r = await q.query('update devices set revoked_at = coalesce(revoked_at, $3), push_token = null where id = $1 and user_id = $2', [params.id, user.id, now]);
        if (r.rowCount === 0) throw notFound('device');
        const cleared = user.primary_device_id === params.id;
        if (cleared) {
          // The most recently seen remaining device rings from now on; with no primary at all,
          // every remaining phone would count as primary and ring (ADR 0007).
          const next = await q.query<{ id: string }>('select id from devices where user_id = $1 and revoked_at is null order by last_seen_at desc nulls last, created_at desc limit 1', [user.id]);
          await q.query('update users set primary_device_id = $2, updated_at = $3 where id = $1', [user.id, next.rows[0]?.id ?? null, now]);
          await appendSync(q, [user.id], 'me', user.id);
        }
        await appendSync(q, [user.id], 'device', params.id, 'delete');
        await audit(q, { actorUserId: user.id, actorKind: 'user', action: 'device.revoked', targetKind: 'device', targetId: params.id });
        return { ok: true, primaryCleared: cleared };
      }),
  }),
];

