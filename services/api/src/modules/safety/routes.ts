import Type from 'typebox';
import { conflict, forbidden, notFound, unavailable, unprocessable } from '../../http/errors.ts';
import { IdParams, Instant, Nullable, Obj, Uuid, iso, route, type RouteDef } from '../../http/route.ts';
import type { Queryable } from '../../db/db.ts';
import { open, seal } from '../../lib/crypto.ts';
import { appendSync, audit, enqueue } from '../identity/repo.ts';

export const CONSENT_VERSION = 'location-consent-2026-10';
const MAX_SAMPLE_AGE_MS = 15 * 60_000;

interface SessionRow {
  id: string;
  owner_id: string;
  purpose: 'share' | 'sos' | 'checkin';
  share_location: boolean;
  message: string | null;
  consent_version: string;
  started_at: Date;
  ends_at: Date;
  ended_at: Date | null;
  checkin_due_at: Date | null;
  checked_in_at: Date | null;
  missed_notified_at: Date | null;
}

const SessionDto = Type.Object({
  id: Type.String(),
  owner: Type.Object({ id: Type.String(), displayName: Type.String() }),
  purpose: Type.String(),
  shareLocation: Type.Boolean(),
  message: Nullable(Type.String()),
  active: Type.Boolean(),
  startedAt: Instant,
  endsAt: Instant,
  endedAt: Nullable(Instant),
  checkinDueAt: Nullable(Instant),
  checkedInAt: Nullable(Instant),
  viewers: Type.Array(Type.Object({ id: Type.String(), displayName: Type.String(), revoked: Type.Boolean(), acknowledgedAt: Nullable(Instant) })),
});

function isActive(s: SessionRow, now: Date): boolean {
  return !s.ended_at && s.ends_at > now;
}

async function sessionDto(q: Queryable, s: SessionRow, now: Date, forViewer: string | null): Promise<Type.Static<typeof SessionDto>> {
  const owner = await q.query<{ display_name: string }>('select display_name from users where id = $1', [s.owner_id]);
  const v = await q.query<{ viewer_id: string; display_name: string; revoked_at: Date | null; acknowledged_at: Date | null }>(
    `select v.viewer_id, u.display_name, v.revoked_at, v.acknowledged_at from location_session_viewers v join users u on u.id = v.viewer_id where v.session_id = $1 order by u.display_name`,
    [s.id],
  );
  // A viewer sees only themselves in the viewer list; the owner sees everyone.
  const viewers = v.rows.filter((x) => forViewer === null || x.viewer_id === forViewer);
  return {
    id: s.id,
    owner: { id: s.owner_id, displayName: owner.rows[0]?.display_name ?? 'Friend' },
    purpose: s.purpose,
    shareLocation: s.share_location,
    message: s.message,
    active: isActive(s, now),
    startedAt: s.started_at.toISOString(),
    endsAt: s.ends_at.toISOString(),
    endedAt: iso(s.ended_at),
    checkinDueAt: iso(s.checkin_due_at),
    checkedInAt: iso(s.checked_in_at),
    viewers: viewers.map((x) => ({ id: x.viewer_id, displayName: x.display_name, revoked: !!x.revoked_at, acknowledgedAt: iso(x.acknowledged_at) })),
  };
}

async function requireFriends(q: Queryable, owner: string, viewers: readonly string[]): Promise<void> {
  if (viewers.includes(owner)) throw unprocessable('self_viewer', "You don't need to share with yourself.");
  const r = await q.query<{ other: string }>(
    `select case when user_low = $1 then user_high else user_low end as other from friendships where state = 'accepted' and (user_low = $1 or user_high = $1)`,
    [owner],
  );
  const friends = new Set(r.rows.map((x) => x.other));
  if (!viewers.every((v) => friends.has(v))) throw notFound('friend');
}

async function createSession(
  q: Queryable,
  ownerId: string,
  input: { purpose: SessionRow['purpose']; viewerIds: string[]; minutes: number; shareLocation: boolean; message: string | null; checkinDueAt: Date | null },
  now: Date,
): Promise<SessionRow> {
  const viewers = [...new Set(input.viewerIds)];
  await requireFriends(q, ownerId, viewers);
  const endsAt = new Date(now.getTime() + input.minutes * 60_000);
  if (input.checkinDueAt && (input.checkinDueAt <= now || input.checkinDueAt > endsAt)) throw unprocessable('invalid_checkin_time', 'Choose a check-in time before the session ends.');
  const r = await q.query<SessionRow>(
    `insert into location_sessions (owner_id, purpose, share_location, message, consent_version, started_at, ends_at, checkin_due_at) values ($1, $2, $3, $4, $5, $6, $7, $8) returning *`,
    [ownerId, input.purpose, input.shareLocation, input.message, CONSENT_VERSION, now, endsAt, input.checkinDueAt],
  );
  const s = r.rows[0]!;
  for (const v of viewers) await q.query('insert into location_session_viewers (session_id, viewer_id) values ($1, $2)', [s.id, v]);
  await appendSync(q, [ownerId, ...viewers], 'location_session', s.id);
  for (const v of viewers) {
    await enqueue(q, { kind: 'notify_generic', dedupeKey: `safety:${s.id}:${v}:started`, payload: { userId: v, template: input.purpose === 'sos' ? 'sos' : input.purpose === 'checkin' ? 'checkin_started' : 'share_started', actorId: ownerId, sessionId: s.id }, maxAttempts: input.purpose === 'sos' ? 6 : 3 });
  }
  await audit(q, { actorUserId: ownerId, actorKind: 'user', action: `safety.${input.purpose}_started`, targetKind: 'location_session', targetId: s.id, data: { viewers: viewers.length, shareLocation: input.shareLocation } });
  return s;
}

export const safetyRoutes: RouteDef[] = [
  route({
    method: 'POST',
    url: '/v1/location-sessions',
    operationId: 'startSafetySession',
    summary: 'Start a check-in or explicit location share with chosen friends, for a limited time.',
    description: 'Never stealth: viewers are listed, the session expires automatically, and the sharer sees a live indicator with Stop. Wake Me is not an emergency service.',
    tags: ['safety'],
    auth: 'user',
    rateLimit: { max: 20, timeWindow: '1 hour' },
    body: Obj({
      purpose: Type.Union([Type.Literal('share'), Type.Literal('checkin')]),
      viewerIds: Type.Array(Uuid, { minItems: 1, maxItems: 10 }),
      durationMinutes: Type.Integer({ minimum: 5, maximum: 480 }),
      shareLocation: Type.Boolean(),
      checkinDueAt: Type.Optional(Instant),
      message: Type.Optional(Type.String({ maxLength: 200 })),
    }),
    response: SessionDto,
    status: 201,
    handler: async ({ body, user, deps, now }) => {
      if (body.shareLocation && !(await deps.flags.isEnabled('location'))) throw unavailable('location_unavailable', 'Location sharing is unavailable right now. Check-ins still work.');
      if (body.purpose === 'checkin' && !body.checkinDueAt) throw unprocessable('checkin_time_required', 'Choose when you will check in.');
      return deps.db.tx(async (q) => {
        const s = await createSession(q, user.id, { purpose: body.purpose, viewerIds: body.viewerIds, minutes: body.durationMinutes, shareLocation: body.shareLocation, message: body.message?.trim() || null, checkinDueAt: body.checkinDueAt ? new Date(body.checkinDueAt) : null }, now);
        return sessionDto(q, s, now, null);
      });
    },
  }),

  route({
    method: 'POST',
    url: '/v1/sos',
    operationId: 'sendSos',
    summary: 'Alert chosen friends that you need help, optionally sharing your location for two hours.',
    description: 'Delivery to friends is best-effort and not monitored. Call your local emergency number in an emergency.',
    tags: ['safety'],
    auth: 'user',
    rateLimit: { max: 5, timeWindow: '1 hour' },
    body: Obj({ viewerIds: Type.Array(Uuid, { minItems: 1, maxItems: 10 }), shareLocation: Type.Boolean(), message: Type.Optional(Type.String({ maxLength: 200 })) }),
    response: SessionDto,
    status: 201,
    handler: async ({ body, user, deps, now }) => {
      const share = body.shareLocation && (await deps.flags.isEnabled('location'));
      return deps.db.tx(async (q) => {
        const s = await createSession(q, user.id, { purpose: 'sos', viewerIds: body.viewerIds, minutes: 120, shareLocation: share, message: body.message?.trim() || null, checkinDueAt: null }, now);
        return sessionDto(q, s, now, null);
      });
    },
  }),

  route({
    method: 'GET',
    url: '/v1/location-sessions',
    operationId: 'listSafetySessions',
    summary: 'Your sessions and sessions friends are sharing with you.',
    tags: ['safety'],
    auth: 'user',
    response: Type.Object({ mine: Type.Array(SessionDto), sharedWithMe: Type.Array(SessionDto) }),
    handler: async ({ user, deps, now }) => {
      const since = new Date(now.getTime() - 24 * 3600_000);
      const mine = await deps.db.query<SessionRow>('select * from location_sessions where owner_id = $1 and started_at > $2 order by started_at desc', [user.id, since]);
      const shared = await deps.db.query<SessionRow>(
        `select s.* from location_sessions s join location_session_viewers v on v.session_id = s.id
          where v.viewer_id = $1 and v.revoked_at is null and s.ended_at is null and s.ends_at > $2 order by s.started_at desc`,
        [user.id, now],
      );
      const out = { mine: [] as Type.Static<typeof SessionDto>[], sharedWithMe: [] as Type.Static<typeof SessionDto>[] };
      for (const s of mine.rows) out.mine.push(await sessionDto(deps.db, s, now, null));
      for (const s of shared.rows) out.sharedWithMe.push(await sessionDto(deps.db, s, now, user.id));
      return out;
    },
  }),

  route({
    method: 'POST',
    url: '/v1/location-sessions/:id/samples',
    operationId: 'postLocationSample',
    summary: 'Owner device uploads its latest location. Only the latest sample is kept, encrypted.',
    tags: ['safety'],
    auth: 'device',
    rateLimit: { max: 120, timeWindow: '1 hour' },
    params: IdParams,
    body: Obj({
      capturedAt: Instant,
      latitude: Type.Number({ minimum: -90, maximum: 90 }),
      longitude: Type.Number({ minimum: -180, maximum: 180 }),
      accuracyMeters: Type.Integer({ minimum: 0, maximum: 100_000 }),
    }),
    response: Type.Object({ ok: Type.Boolean() }),
    handler: async ({ params, body, user, deps, now }) => {
      const r = await deps.db.query<SessionRow>('select * from location_sessions where id = $1 and owner_id = $2', [params.id, user.id]);
      const s = r.rows[0];
      if (!s) throw notFound('session');
      if (!isActive(s, now)) throw conflict('session_ended', 'This sharing session has ended.');
      if (!s.share_location) throw forbidden('location_not_shared', 'This session does not share location.');
      const captured = new Date(body.capturedAt);
      if (captured.getTime() > now.getTime() + 60_000 || now.getTime() - captured.getTime() > MAX_SAMPLE_AGE_MS) throw unprocessable('stale_sample', 'That location is too old to share.');
      const sealed = seal(JSON.stringify({ lat: body.latitude, lon: body.longitude }), deps.config.locationKey.keyBase64, deps.config.locationKey.keyId, s.id);
      await deps.db.query(
        `insert into location_samples (session_id, captured_at, received_at, accuracy_m, ciphertext, nonce, key_id, expires_at) values ($1, $2, $3, $4, $5, $6, $7, $8)
         on conflict (session_id) do update set captured_at = excluded.captured_at, received_at = excluded.received_at, accuracy_m = excluded.accuracy_m,
           ciphertext = excluded.ciphertext, nonce = excluded.nonce, key_id = excluded.key_id, expires_at = excluded.expires_at
         where location_samples.captured_at <= excluded.captured_at`,
        [s.id, captured, now, body.accuracyMeters, sealed.ciphertext, sealed.nonce, sealed.keyId, s.ends_at],
      );
      return { ok: true };
    },
  }),

  route({
    method: 'GET',
    url: '/v1/location-sessions/:id/latest',
    operationId: 'getLatestLocation',
    summary: 'Latest shared location for an authorized viewer, with capture time and accuracy. Access is logged.',
    tags: ['safety'],
    auth: 'user',
    params: IdParams,
    response: Type.Object({
      status: Type.Union([Type.Literal('available'), Type.Literal('no_location'), Type.Literal('not_shared')]),
      capturedAt: Nullable(Instant),
      receivedAt: Nullable(Instant),
      ageSeconds: Nullable(Type.Integer()),
      accuracyMeters: Nullable(Type.Integer()),
      latitude: Nullable(Type.Number()),
      longitude: Nullable(Type.Number()),
    }),
    handler: async ({ params, user, deps, now }) =>
      deps.db.tx(async (q) => {
        const r = await q.query<SessionRow>(
          `select s.* from location_sessions s join location_session_viewers v on v.session_id = s.id
            where s.id = $1 and v.viewer_id = $2 and v.revoked_at is null`,
          [params.id, user.id],
        );
        const s = r.rows[0];
        if (!s || !isActive(s, now)) throw notFound('session');
        const f = await q.query<{ state: string }>(
          `select state from friendships where user_low = least($1::uuid, $2::uuid) and user_high = greatest($1::uuid, $2::uuid)`,
          [s.owner_id, user.id],
        );
        if (f.rows[0]?.state !== 'accepted') throw notFound('session');
        const empty = { capturedAt: null, receivedAt: null, ageSeconds: null, accuracyMeters: null, latitude: null, longitude: null };
        if (!s.share_location) return { status: 'not_shared' as const, ...empty };
        const sample = (await q.query<{ captured_at: Date; received_at: Date; accuracy_m: number; ciphertext: string; nonce: string; key_id: string; expires_at: Date }>('select * from location_samples where session_id = $1', [s.id])).rows[0];
        await q.query('insert into location_access_log (session_id, viewer_id, accessed_at) values ($1, $2, $3)', [s.id, user.id, now]);
        if (!sample || sample.expires_at <= now) return { status: 'no_location' as const, ...empty };
        const coords = JSON.parse(open({ ciphertext: sample.ciphertext, nonce: sample.nonce, keyId: sample.key_id }, deps.config.locationKey.keyBase64, s.id)) as { lat: number; lon: number };
        return {
          status: 'available' as const,
          capturedAt: sample.captured_at.toISOString(),
          receivedAt: sample.received_at.toISOString(),
          ageSeconds: Math.max(0, Math.round((now.getTime() - sample.captured_at.getTime()) / 1000)),
          accuracyMeters: sample.accuracy_m,
          latitude: coords.lat,
          longitude: coords.lon,
        };
      }),
  }),

  route({
    method: 'POST',
    url: '/v1/location-sessions/:id/end',
    operationId: 'endSafetySession',
    summary: 'Stop sharing now. The stored sample is deleted.',
    tags: ['safety'],
    auth: 'user',
    params: IdParams,
    response: SessionDto,
    handler: async ({ params, user, deps, now }) =>
      deps.db.tx(async (q) => {
        const r = await q.query<SessionRow>('update location_sessions set ended_at = coalesce(ended_at, $3) where id = $1 and owner_id = $2 returning *', [params.id, user.id, now]);
        if (!r.rows[0]) throw notFound('session');
        await q.query('delete from location_samples where session_id = $1', [params.id]);
        const viewers = await q.query<{ viewer_id: string }>('select viewer_id from location_session_viewers where session_id = $1', [params.id]);
        await appendSync(q, [user.id, ...viewers.rows.map((v) => v.viewer_id)], 'location_session', params.id);
        return sessionDto(q, r.rows[0], now, null);
      }),
  }),

  route({
    method: 'POST',
    url: '/v1/location-sessions/:id/viewers/:viewerId/revoke',
    operationId: 'revokeSafetyViewer',
    summary: 'Remove one viewer. Cannot erase what they already saw.',
    tags: ['safety'],
    auth: 'user',
    params: Obj({ id: Uuid, viewerId: Uuid }),
    response: SessionDto,
    handler: async ({ params, user, deps, now }) =>
      deps.db.tx(async (q) => {
        const s = (await q.query<SessionRow>('select * from location_sessions where id = $1 and owner_id = $2', [params.id, user.id])).rows[0];
        if (!s) throw notFound('session');
        const r = await q.query('update location_session_viewers set revoked_at = coalesce(revoked_at, $3) where session_id = $1 and viewer_id = $2', [params.id, params.viewerId, now]);
        if (r.rowCount === 0) throw notFound('viewer');
        await appendSync(q, [user.id, params.viewerId], 'location_session', params.id);
        return sessionDto(q, s, now, null);
      }),
  }),

  route({
    method: 'POST',
    url: '/v1/location-sessions/:id/check-in',
    operationId: 'checkIn',
    summary: '"I\'m safe": complete a check-in and let viewers know.',
    tags: ['safety'],
    auth: 'user',
    params: IdParams,
    body: Obj({ endSession: Type.Optional(Type.Boolean()) }),
    response: SessionDto,
    handler: async ({ params, body, user, deps, now }) =>
      deps.db.tx(async (q) => {
        const r = await q.query<SessionRow>(
          `update location_sessions set checked_in_at = coalesce(checked_in_at, $3), ended_at = case when $4::boolean then coalesce(ended_at, $3) else ended_at end
            where id = $1 and owner_id = $2 returning *`,
          [params.id, user.id, now, body?.endSession !== false],
        );
        const s = r.rows[0];
        if (!s) throw notFound('session');
        if (s.ended_at) await q.query('delete from location_samples where session_id = $1', [s.id]);
        const viewers = await q.query<{ viewer_id: string }>('select viewer_id from location_session_viewers where session_id = $1 and revoked_at is null', [s.id]);
        for (const v of viewers.rows) await enqueue(q, { kind: 'notify_generic', dedupeKey: `safety:${s.id}:${v.viewer_id}:safe`, payload: { userId: v.viewer_id, template: 'checked_in', actorId: user.id, sessionId: s.id }, maxAttempts: 3 });
        await appendSync(q, [user.id, ...viewers.rows.map((v) => v.viewer_id)], 'location_session', s.id);
        return sessionDto(q, s, now, null);
      }),
  }),

  route({
    method: 'POST',
    url: '/v1/location-sessions/:id/acknowledge',
    operationId: 'acknowledgeSafetySession',
    summary: 'Viewer acknowledges an SOS or check-in so the sender knows someone saw it.',
    tags: ['safety'],
    auth: 'user',
    params: IdParams,
    response: Type.Object({ ok: Type.Boolean() }),
    handler: async ({ params, user, deps, now }) =>
      deps.db.tx(async (q) => {
        const r = await q.query<{ owner_id: string }>(
          `update location_session_viewers v set acknowledged_at = coalesce(v.acknowledged_at, $3) from location_sessions s
            where v.session_id = s.id and v.session_id = $1 and v.viewer_id = $2 and v.revoked_at is null returning s.owner_id`,
          [params.id, user.id, now],
        );
        if (!r.rows[0]) throw notFound('session');
        await enqueue(q, { kind: 'notify_generic', dedupeKey: `safety:${params.id}:ack:${user.id}`, payload: { userId: r.rows[0].owner_id, template: 'safety_acknowledged', actorId: user.id, sessionId: params.id }, maxAttempts: 3 });
        await appendSync(q, [r.rows[0].owner_id, user.id], 'location_session', params.id);
        return { ok: true };
      }),
  }),
];
