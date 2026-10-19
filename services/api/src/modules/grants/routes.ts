import Type from 'typebox';
import { presetDraft, validateGrantDraft, type Capability, type GrantDraft, type GrantLimits } from '@wakeme/policy';
import { requireRecentAuth } from '../../http/auth.ts';
import { conflict, forbidden, notFound, unprocessable } from '../../http/errors.ts';
import { IdParams, Instant, Obj, Uuid, route, type RouteDef } from '../../http/route.ts';
import type { Queryable } from '../../db/db.ts';
import { appendSync, audit } from '../identity/repo.ts';
import { areFriends } from '../relationships/repo.ts';
import { GrantDto, grantDto, liveGrant, recordGrantVersion, type GrantRow } from './repo.ts';
import { cancelOpenCommandsForGrant, enqueueSyncHint, revokeGrants } from './revoke.ts';

/** Capabilities that can make the phone ring loudly, restrict apps, or relate to location need a recent sign-in. */
const HIGH_IMPACT: ReadonlySet<Capability> = new Set(['alarm.request', 'alarm.schedule', 'focus.request', 'location.request']);

const Window = Obj({ days: Type.Array(Type.Integer({ minimum: 1, maximum: 7 }), { minItems: 1, maxItems: 7 }), start: Type.String(), end: Type.String() });
const Limits = Obj({
  nudge: Type.Optional(Obj({ maxPerHour: Type.Integer(), windows: Type.Array(Window, { maxItems: 7 }), sounds: Type.Array(Type.String(), { maxItems: 20 }), overridesQuietHours: Type.Boolean() })),
  alarm: Type.Optional(
    Obj({
      maxAlarmSeconds: Type.Integer(),
      maxPerDay: Type.Integer(),
      windows: Type.Array(Window, { maxItems: 7 }),
      sounds: Type.Array(Type.String(), { maxItems: 20 }),
      followUp: Obj({ allowed: Type.Boolean(), maxDelaySeconds: Type.Integer() }),
    }),
  ),
  challenge: Type.Optional(Obj({ types: Type.Array(Type.String(), { maxItems: 8 }), maxDurationSeconds: Type.Integer(), maxDifficulty: Type.String() })),
  focus: Type.Optional(Obj({ maxSessionMinutes: Type.Integer(), maxPerDay: Type.Integer(), windows: Type.Array(Window, { maxItems: 7 }) })),
  checkin: Type.Optional(Obj({ maxPerDay: Type.Integer() })),
});

const Terms = {
  capabilities: Type.Array(Type.String(), { minItems: 1, maxItems: 7 }),
  limits: Limits,
  validUntil: Instant,
};

async function validateSounds(q: Queryable, grantorId: string, limits: GrantLimits): Promise<void> {
  const ids = [...(limits.nudge?.sounds ?? []), ...(limits.alarm?.sounds ?? [])].filter((s) => s.startsWith('media:')).map((s) => s.slice(6));
  if (ids.length === 0) return;
  // A custom sound must be in the recipient's own approved library (theirs, or a friend's they approved).
  const r = await q.query<{ media_id: string }>(
    `select m.id as media_id from media_assets m
      where m.id = any($2::uuid[]) and m.state = 'clean'
        and (m.owner_id = $1 or exists (select 1 from sound_library s where s.owner_id = $1 and s.media_id = m.id and s.state = 'approved'))`,
    [grantorId, ids],
  );
  if (r.rowCount !== new Set(ids).size) throw unprocessable('sound_not_in_library', 'Custom sounds must be in your approved sound library.');
}

function checkDraft(draft: GrantDraft, now: Date): void {
  const issues = validateGrantDraft(draft, now);
  if (issues.length > 0) throw unprocessable('invalid_grant', 'Some permission settings are out of range.', issues);
}

function needsReauth(caps: readonly string[], previous: readonly string[] = []): boolean {
  return caps.some((c) => HIGH_IMPACT.has(c as Capability) && !previous.includes(c));
}

export const grantRoutes: RouteDef[] = [
  route({
    method: 'GET',
    url: '/v1/grants',
    operationId: 'listGrants',
    summary: 'Permissions you have given friends, or that friends have given you.',
    tags: ['grants'],
    auth: 'user',
    query: Obj({ direction: Type.Union([Type.Literal('given'), Type.Literal('received')]), includeRevoked: Type.Optional(Type.Boolean()) }),
    response: Type.Object({ grants: Type.Array(GrantDto) }),
    handler: async ({ query, user, deps }) => {
      const col = query.direction === 'given' ? 'grantor_id' : 'grantee_id';
      const r = await deps.db.query<GrantRow>(
        `select * from grants where ${col} = $1 and ($2::boolean or revoked_at is null) order by updated_at desc limit 200`,
        [user.id, query.includeRevoked === true],
      );
      return { grants: r.rows.map(grantDto) };
    },
  }),

  route({
    method: 'GET',
    url: '/v1/grants/presets',
    operationId: 'grantPresets',
    summary: 'Editable starting points: Gentle nudges, Wake buddy, Focus buddy. There is no "allow everything".',
    tags: ['grants'],
    auth: 'user',
    response: Type.Object({ presets: Type.Array(Type.Object({ id: Type.String(), capabilities: Type.Array(Type.String()), limits: Type.Unknown(), validUntil: Instant })) }),
    handler: async ({ now }) => ({
      presets: (['gentle_nudges', 'wake_buddy', 'focus_buddy'] as const).map((id) => ({ id, ...presetDraft(id, now) })),
    }),
  }),

  route({
    method: 'POST',
    url: '/v1/grants',
    operationId: 'createGrant',
    summary: 'Allow a friend to do specific, bounded things on YOUR phone. Only the recipient can grant.',
    tags: ['grants'],
    auth: 'user',
    rateLimit: { max: 60, timeWindow: '1 hour' },
    body: Obj({ granteeId: Uuid, ...Terms }),
    response: GrantDto,
    status: 201,
    handler: async ({ body, user, claims, deps, now }) => {
      const draft: GrantDraft = { capabilities: body.capabilities, limits: body.limits as GrantLimits, validUntil: body.validUntil };
      checkDraft(draft, now);
      if (needsReauth(draft.capabilities)) requireRecentAuth(claims!, now, deps.config.reauthSecondsForHighImpactGrants);
      return deps.db.tx(async (q) => {
        if (body.granteeId === user.id) throw unprocessable('self_grant', "You can't grant permissions to yourself.");
        if (!(await areFriends(q, user.id, body.granteeId))) throw notFound('friend');
        if (await liveGrant(q, user.id, body.granteeId, true)) throw conflict('grant_exists', 'You already gave this friend permissions. Edit them instead.');
        await validateSounds(q, user.id, draft.limits);
        const r = await q.query<GrantRow>(
          `insert into grants (grantor_id, grantee_id, capabilities, limits, valid_from, valid_until, created_at, updated_at)
           values ($1, $2, $3::text[], $4::jsonb, $5, $6, $5, $5) returning *`,
          [user.id, body.granteeId, draft.capabilities, JSON.stringify(draft.limits), now, draft.validUntil],
        );
        const g = r.rows[0]!;
        await recordGrantVersion(q, g.id, user.id);
        await appendSync(q, [user.id, body.granteeId], 'grant', g.id);
        await enqueueSyncHint(q, [user.id, body.granteeId], `grant:${g.id}:${g.version}`);
        await audit(q, { actorUserId: user.id, actorKind: 'user', action: 'grant.created', targetKind: 'grant', targetId: g.id, data: { capabilities: draft.capabilities } });
        return grantDto(g);
      });
    },
  }),

  route({
    method: 'PUT',
    url: '/v1/grants/:id',
    operationId: 'updateGrant',
    summary: 'Replace the terms of a grant you gave. Requires the current version (optimistic concurrency).',
    tags: ['grants'],
    auth: 'user',
    params: IdParams,
    body: Obj({ expectedVersion: Type.Integer({ minimum: 1 }), paused: Type.Boolean(), ...Terms }),
    response: GrantDto,
    handler: async ({ params, body, user, claims, deps, now }) => {
      const draft: GrantDraft = { capabilities: body.capabilities, limits: body.limits as GrantLimits, validUntil: body.validUntil };
      checkDraft(draft, now);
      return deps.db.tx(async (q) => {
        const r0 = await q.query<GrantRow>('select * from grants where id = $1 for update', [params.id]);
        const g0 = r0.rows[0];
        // The grantee can see a grant but never change it; to them it simply doesn't exist here.
        if (!g0 || g0.grantor_id !== user.id) throw notFound('grant');
        if (g0.revoked_at) throw conflict('grant_revoked', 'This permission was revoked. Create a new one.');
        if (g0.version !== body.expectedVersion) throw conflict('version_conflict', 'These permissions changed on another device. Review the latest version.', { currentVersion: g0.version });
        if (needsReauth(draft.capabilities, g0.capabilities)) requireRecentAuth(claims!, now, deps.config.reauthSecondsForHighImpactGrants);
        await validateSounds(q, user.id, draft.limits);
        const r = await q.query<GrantRow>(
          `update grants set capabilities = $2::text[], limits = $3::jsonb, valid_until = $4, paused = $5, version = version + 1, updated_at = $6 where id = $1 returning *`,
          [g0.id, draft.capabilities, JSON.stringify(draft.limits), draft.validUntil, body.paused, now],
        );
        const g = r.rows[0]!;
        await recordGrantVersion(q, g.id, user.id);
        // Requests issued under the old terms are re-checked at dispatch; cancel any that the new terms no longer allow.
        const removed = g0.capabilities.filter((c) => !draft.capabilities.includes(c));
        if (removed.length > 0 || body.paused) await cancelOpenCommandsForGrant(q, g.id, now);
        await appendSync(q, [user.id, g.grantee_id], 'grant', g.id);
        await enqueueSyncHint(q, [user.id, g.grantee_id], `grant:${g.id}:${g.version}`);
        await audit(q, { actorUserId: user.id, actorKind: 'user', action: 'grant.updated', targetKind: 'grant', targetId: g.id, data: { version: g.version, paused: g.paused } });
        return grantDto(g);
      });
    },
  }),

  route({
    method: 'POST',
    url: '/v1/grants/:id/pause',
    operationId: 'pauseGrant',
    summary: "Pause this friend's permissions without changing them.",
    tags: ['grants'],
    auth: 'user',
    params: IdParams,
    body: Obj({ paused: Type.Boolean() }),
    response: GrantDto,
    handler: async ({ params, body, user, deps, now }) =>
      deps.db.tx(async (q) => {
        const r = await q.query<GrantRow>(
          `update grants set paused = $3, version = version + 1, updated_at = $4 where id = $1 and grantor_id = $2 and revoked_at is null and paused <> $3 returning *`,
          [params.id, user.id, body.paused, now],
        );
        let g = r.rows[0];
        if (!g) {
          const cur = await q.query<GrantRow>('select * from grants where id = $1 and grantor_id = $2 and revoked_at is null', [params.id, user.id]);
          g = cur.rows[0];
          if (!g) throw notFound('grant');
          return grantDto(g);
        }
        await recordGrantVersion(q, g.id, user.id);
        if (body.paused) await cancelOpenCommandsForGrant(q, g.id, now);
        await appendSync(q, [user.id, g.grantee_id], 'grant', g.id);
        await enqueueSyncHint(q, [user.id, g.grantee_id], `grant:${g.id}:${g.version}`);
        return grantDto(g);
      }),
  }),

  route({
    method: 'DELETE',
    url: '/v1/grants/:id',
    operationId: 'revokeGrant',
    summary: 'Revoke a grant you gave. Idempotent; immediately stops new server-side actions.',
    tags: ['grants'],
    auth: 'user',
    params: IdParams,
    response: Type.Object({ ok: Type.Boolean(), alreadyRevoked: Type.Boolean() }),
    handler: async ({ params, user, deps, now }) =>
      deps.db.tx(async (q) => {
        const r = await q.query<GrantRow>('select * from grants where id = $1 for update', [params.id]);
        const g = r.rows[0];
        if (!g || g.grantor_id !== user.id) throw notFound('grant');
        if (g.revoked_at) return { ok: true, alreadyRevoked: true };
        await revokeGrants(q, [g.id], 'grantor_revoked', user.id, now);
        await audit(q, { actorUserId: user.id, actorKind: 'user', action: 'grant.revoked', targetKind: 'grant', targetId: g.id });
        return { ok: true, alreadyRevoked: false };
      }),
  }),

  route({
    method: 'GET',
    url: '/v1/grants/:id/history',
    operationId: 'grantHistory',
    summary: 'Every version of a grant you gave.',
    tags: ['grants'],
    auth: 'user',
    params: IdParams,
    response: Type.Object({
      versions: Type.Array(Type.Object({ version: Type.Integer(), capabilities: Type.Array(Type.String()), limits: Type.Unknown(), validUntil: Instant, paused: Type.Boolean(), revoked: Type.Boolean(), changedAt: Instant })),
    }),
    handler: async ({ params, user, deps }) => {
      const g = await deps.db.query<{ grantor_id: string }>('select grantor_id from grants where id = $1', [params.id]);
      if (!g.rows[0] || g.rows[0].grantor_id !== user.id) {
        if (g.rows[0]) throw forbidden('not_grantor', 'Only the person who gave a permission can see its history.');
        throw notFound('grant');
      }
      const r = await deps.db.query<{ version: number; capabilities: string[]; limits: unknown; valid_until: Date; paused: boolean; revoked: boolean; created_at: Date }>(
        'select * from grant_versions where grant_id = $1 order by version',
        [params.id],
      );
      return { versions: r.rows.map((v) => ({ version: v.version, capabilities: v.capabilities, limits: v.limits, validUntil: v.valid_until.toISOString(), paused: v.paused, revoked: v.revoked, changedAt: v.created_at.toISOString() })) };
    },
  }),
];
