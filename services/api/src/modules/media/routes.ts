import Type from 'typebox';
import { forbidden, notFound, unavailable, unprocessable } from '../../http/errors.ts';
import { IdParams, Instant, Nullable, Obj, Uuid, iso, route, type RouteDef } from '../../http/route.ts';
import type { Queryable } from '../../db/db.ts';
import { appendSync, audit, enqueue } from '../identity/repo.ts';
import { areFriends } from '../relationships/repo.ts';
import { planFor, PLAN_LIMITS } from '../billing/service.ts';

export const MAX_UPLOAD_BYTES = 1_048_576;
export const MAX_DURATION_MS = 30_000;
const EXT: Record<string, string> = { 'audio/wav': 'wav', 'audio/mp4': 'm4a' };

interface MediaRow {
  id: string;
  owner_id: string;
  kind: 'voice' | 'alarm_sound';
  object_key: string;
  mime: string;
  bytes: number;
  duration_ms: number | null;
  sha256: string;
  state: 'pending_upload' | 'uploaded' | 'clean' | 'rejected' | 'deleted';
  rejection_reason: string | null;
  created_at: Date;
  finalized_at: Date | null;
}

const MediaDto = Type.Object({
  id: Type.String(),
  soundId: Type.String({ description: 'Use as media:<id> in grants and routines once clean.' }),
  ownerId: Type.String(),
  kind: Type.String(),
  mime: Type.String(),
  bytes: Type.Integer(),
  durationMs: Nullable(Type.Integer()),
  state: Type.String(),
  rejectionReason: Nullable(Type.String()),
  createdAt: Instant,
});

function dto(m: MediaRow): Type.Static<typeof MediaDto> {
  return { id: m.id, soundId: `media:${m.id}`, ownerId: m.owner_id, kind: m.kind, mime: m.mime, bytes: m.bytes, durationMs: m.duration_ms, state: m.state, rejectionReason: m.rejection_reason, createdAt: m.created_at.toISOString() };
}

async function canListen(q: Queryable, mediaId: string, userId: string): Promise<MediaRow | null> {
  const r = await q.query<MediaRow>(
    `select m.* from media_assets m where m.id = $1 and m.state = 'clean'
       and (m.owner_id = $2 or exists (select 1 from sound_library s where s.media_id = m.id and s.owner_id = $2 and s.state in ('offered', 'approved')))`,
    [mediaId, userId],
  );
  return r.rows[0] ?? null;
}

export const mediaRoutes: RouteDef[] = [
  route({
    method: 'POST',
    url: '/v1/media/uploads',
    operationId: 'createMediaUpload',
    summary: 'Start a private upload of a short recorded voice or sound (WAV or M4A, up to 30 s).',
    tags: ['media'],
    auth: 'user',
    rateLimit: { max: 20, timeWindow: '1 hour' },
    body: Obj({
      kind: Type.Union([Type.Literal('voice'), Type.Literal('alarm_sound')]),
      mime: Type.Union([Type.Literal('audio/wav'), Type.Literal('audio/mp4')]),
      bytes: Type.Integer({ minimum: 1, maximum: MAX_UPLOAD_BYTES }),
      sha256: Type.String({ pattern: '^[0-9a-f]{64}$' }),
      durationMs: Type.Integer({ minimum: 1, maximum: MAX_DURATION_MS }),
      rightsAttestation: Type.Literal(true, { description: 'I recorded this or have the right to use it.' }),
    }),
    response: Type.Object({ media: MediaDto, upload: Type.Object({ url: Type.String(), method: Type.String(), headers: Type.Record(Type.String(), Type.String()), expiresAt: Instant }) }),
    status: 201,
    handler: async ({ body, user, deps, now }) => {
      if (!deps.storage || !(await deps.flags.isEnabled('media_uploads'))) throw unavailable('uploads_unavailable', 'Uploading sounds is unavailable right now. Built-in sounds still work.');
      return deps.db.tx(async (q) => {
        const plan = await planFor(q, user.id, now);
        const used = await q.query<{ n: number | string }>(`select count(*) as n from media_assets where owner_id = $1 and state in ('pending_upload', 'uploaded', 'clean')`, [user.id]);
        if (Number(used.rows[0]?.n ?? 0) >= PLAN_LIMITS[plan.plan].customSounds) throw forbidden('plan_limit_reached', 'Your sound library is full on your plan. Delete a sound to add another.');
        const id = crypto.randomUUID();
        const key = `media/${user.id}/${id}.${EXT[body.mime]}`;
        const r = await q.query<MediaRow>(
          `insert into media_assets (id, owner_id, kind, object_key, mime, bytes, duration_ms, sha256, state, rights_attested_at, created_at)
           values ($1, $2, $3, $4, $5, $6, $7, $8, 'pending_upload', $9, $9) returning *`,
          [id, user.id, body.kind, key, body.mime, body.bytes, body.durationMs, body.sha256, now],
        );
        const upload = deps.storage!.presignPut(key, { contentType: body.mime, contentLength: body.bytes, expiresSeconds: 600, now });
        return { media: dto(r.rows[0]!), upload: { url: upload.url, method: upload.method, headers: upload.headers, expiresAt: upload.expiresAt.toISOString() } };
      });
    },
  }),

  route({
    method: 'POST',
    url: '/v1/media/:id/finalize',
    operationId: 'finalizeMedia',
    summary: 'Mark the upload complete. The server verifies size, hash, format and duration before it can be used.',
    tags: ['media'],
    auth: 'user',
    params: IdParams,
    response: MediaDto,
    handler: async ({ params, user, deps, now }) =>
      deps.db.tx(async (q) => {
        const r = await q.query<MediaRow>(`update media_assets set state = 'uploaded', finalized_at = $3 where id = $1 and owner_id = $2 and state = 'pending_upload' returning *`, [params.id, user.id, now]);
        let m = r.rows[0];
        if (!m) {
          m = (await q.query<MediaRow>('select * from media_assets where id = $1 and owner_id = $2', [params.id, user.id])).rows[0];
          if (!m) throw notFound('sound');
          return dto(m);
        }
        await enqueue(q, { kind: 'scan_media', dedupeKey: `scan:${m.id}`, payload: { mediaId: m.id }, maxAttempts: 5 });
        return dto(m);
      }),
  }),

  route({
    method: 'GET',
    url: '/v1/media',
    operationId: 'listMedia',
    summary: 'Sounds you uploaded.',
    tags: ['media'],
    auth: 'user',
    response: Type.Object({ media: Type.Array(MediaDto) }),
    handler: async ({ user, deps }) => {
      const r = await deps.db.query<MediaRow>(`select * from media_assets where owner_id = $1 and state <> 'deleted' order by created_at desc`, [user.id]);
      return { media: r.rows.map(dto) };
    },
  }),

  route({
    method: 'GET',
    url: '/v1/media/:id/download',
    operationId: 'downloadMedia',
    summary: 'Short-lived private download URL (owner, or a recipient it was offered to or approved by).',
    tags: ['media'],
    auth: 'user',
    params: IdParams,
    response: Type.Object({ url: Type.String(), expiresAt: Instant, sha256: Type.String(), mime: Type.String() }),
    handler: async ({ params, user, deps, now }) => {
      if (!deps.storage) throw unavailable('storage_unavailable', 'Sounds are unavailable right now.');
      const m = await canListen(deps.db, params.id, user.id);
      if (!m) throw notFound('sound');
      const g = deps.storage.presignGet(m.object_key, { expiresSeconds: 300, now });
      return { url: g.url, expiresAt: g.expiresAt.toISOString(), sha256: m.sha256, mime: m.mime };
    },
  }),

  route({
    method: 'DELETE',
    url: '/v1/media/:id',
    operationId: 'deleteMedia',
    summary: 'Delete a sound you uploaded. Phones fall back to a built-in sound.',
    tags: ['media'],
    auth: 'user',
    params: IdParams,
    response: Type.Object({ ok: Type.Boolean() }),
    handler: async ({ params, user, deps, now }) =>
      deps.db.tx(async (q) => {
        const r = await q.query<{ object_key: string }>(`update media_assets set state = 'deleted', deleted_at = $3 where id = $1 and owner_id = $2 and state <> 'deleted' returning object_key`, [params.id, user.id, now]);
        if (!r.rows[0]) throw notFound('sound');
        await q.query('delete from sound_library where media_id = $1', [params.id]);
        await enqueue(q, { kind: 'delete_object', dedupeKey: `delobj:${params.id}`, payload: { objectKey: r.rows[0].object_key }, maxAttempts: 10 });
        return { ok: true };
      }),
  }),

  route({
    method: 'POST',
    url: '/v1/media/:id/offer',
    operationId: 'offerSound',
    summary: "Offer one of your sounds to a friend's library. They decide whether to approve it.",
    tags: ['media'],
    auth: 'user',
    params: IdParams,
    body: Obj({ toUserId: Uuid }),
    response: Type.Object({ ok: Type.Boolean() }),
    handler: async ({ params, body, user, deps, now }) =>
      deps.db.tx(async (q) => {
        const m = (await q.query<MediaRow>(`select * from media_assets where id = $1 and owner_id = $2 and state = 'clean'`, [params.id, user.id])).rows[0];
        if (!m) throw notFound('sound');
        if (!(await areFriends(q, user.id, body.toUserId))) throw notFound('friend');
        await q.query(
          `insert into sound_library (owner_id, media_id, offered_by, state, created_at) values ($1, $2, $3, 'offered', $4) on conflict (owner_id, media_id) do nothing`,
          [body.toUserId, m.id, user.id, now],
        );
        await appendSync(q, [body.toUserId], 'sound_library', m.id);
        await enqueue(q, { kind: 'notify_generic', dedupeKey: `soundoffer:${body.toUserId}:${m.id}`, payload: { userId: body.toUserId, template: 'sound_offered', actorId: user.id }, maxAttempts: 3 });
        return { ok: true };
      }),
  }),

  route({
    method: 'GET',
    url: '/v1/sound-library',
    operationId: 'listSoundLibrary',
    summary: 'Sounds friends offered you and the ones you approved.',
    tags: ['media'],
    auth: 'user',
    response: Type.Object({ sounds: Type.Array(Type.Object({ media: MediaDto, offeredBy: Type.Object({ id: Type.String(), displayName: Type.String() }), state: Type.String(), decidedAt: Nullable(Instant) })) }),
    handler: async ({ user, deps }) => {
      const r = await deps.db.query<MediaRow & { lib_state: string; decided_at: Date | null; offered_by: string; offered_name: string }>(
        `select m.*, s.state as lib_state, s.decided_at, s.offered_by, u.display_name as offered_name
           from sound_library s join media_assets m on m.id = s.media_id join users u on u.id = s.offered_by
          where s.owner_id = $1 and s.state in ('offered', 'approved') and m.state = 'clean' order by s.created_at desc`,
        [user.id],
      );
      return { sounds: r.rows.map((x) => ({ media: dto(x), offeredBy: { id: x.offered_by, displayName: x.offered_name }, state: x.lib_state, decidedAt: iso(x.decided_at) })) };
    },
  }),

  route({
    method: 'POST',
    url: '/v1/sound-library/:id/decision',
    operationId: 'decideSound',
    summary: 'Approve or decline an offered sound. Only approved sounds can be allowed in permissions.',
    tags: ['media'],
    auth: 'user',
    params: IdParams,
    body: Obj({ approve: Type.Boolean() }),
    response: Type.Object({ ok: Type.Boolean() }),
    handler: async ({ params, body, user, deps, now }) =>
      deps.db.tx(async (q) => {
        const r = await q.query(`update sound_library set state = $3, decided_at = $4 where owner_id = $1 and media_id = $2 returning media_id`, [user.id, params.id, body.approve ? 'approved' : 'declined', now]);
        if (r.rowCount === 0) throw notFound('sound');
        await appendSync(q, [user.id], 'sound_library', params.id);
        await audit(q, { actorUserId: user.id, actorKind: 'user', action: body.approve ? 'sound.approved' : 'sound.declined', targetKind: 'media', targetId: params.id });
        return { ok: true };
      }),
  }),
];

export function assertAllowedMime(mime: string): void {
  if (!EXT[mime]) throw unprocessable('unsupported_audio', 'Use a WAV or M4A recording.');
}
