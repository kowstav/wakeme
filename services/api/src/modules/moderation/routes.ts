import Type from 'typebox';
import { notFound } from '../../http/errors.ts';
import { IdParams, Instant, Nullable, Obj, Uuid, iso, route, type RouteDef } from '../../http/route.ts';
import type { Queryable } from '../../db/db.ts';
import { audit, enqueue } from '../identity/repo.ts';
import { revokeAllOf } from '../grants/revoke.ts';
import { conversationAccess } from '../conversations/routes.ts';
import { canUseDefinition, type DefinitionRow } from '../challenges/repo.ts';
import { createReport, ReportReason } from './service.ts';

const TargetKind = Type.Union([Type.Literal('user'), Type.Literal('message'), Type.Literal('challenge'), Type.Literal('media'), Type.Literal('command')]);

/** Resolve what the reporter could legitimately see; anything else is "not found". */
async function evidenceFor(q: Queryable, kind: string, id: string, reporter: string): Promise<{ targetUserId: string | null; evidence: Record<string, unknown> } | null> {
  switch (kind) {
    case 'user': {
      const r = await q.query<{ id: string }>(
        `select u.id from users u where u.id = $1 and (
           exists (select 1 from friendships f where (f.user_low = least($1::uuid, $2::uuid) and f.user_high = greatest($1::uuid, $2::uuid)))
           or exists (select 1 from circle_members a join circle_members b on a.circle_id = b.circle_id where a.user_id = $1 and b.user_id = $2))`,
        [id, reporter],
      );
      return r.rows[0] ? { targetUserId: id, evidence: {} } : null;
    }
    case 'message': {
      const m = (await q.query<{ conversation_id: string; sender_id: string | null; body: string | null; created_at: Date }>('select * from messages where id = $1', [id])).rows[0];
      if (!m || !(await conversationAccess(q, m.conversation_id, reporter))) return null;
      return { targetUserId: m.sender_id, evidence: { body: m.body, sentAt: m.created_at } };
    }
    case 'challenge': {
      const d = (await q.query<DefinitionRow>('select * from challenge_definitions where id = $1', [id])).rows[0];
      const assigned = await q.query(
        `select 1 from alarm_plan_versions v join alarm_plans p on p.id = v.plan_id join challenge_revisions r on r.id = v.challenge_revision_id where r.definition_id = $1 and p.owner_id = $2`,
        [id, reporter],
      );
      if (!d || (!(await canUseDefinition(q, d, reporter)) && assigned.rowCount === 0)) return null;
      const latest = await q.query<{ body: unknown }>('select body from challenge_revisions where definition_id = $1 order by revision desc limit 1', [id]);
      return { targetUserId: d.creator_id, evidence: { title: d.title, body: latest.rows[0]?.body ?? null } };
    }
    case 'media': {
      const m = await q.query<{ owner_id: string }>(`select m.owner_id from media_assets m join sound_library s on s.media_id = m.id where m.id = $1 and s.owner_id = $2`, [id, reporter]);
      return m.rows[0] ? { targetUserId: m.rows[0].owner_id, evidence: { mediaId: id } } : null;
    }
    case 'command': {
      const c = (await q.query<{ actor_id: string; target_id: string; capability: string; created_at: Date }>('select actor_id, target_id, capability, created_at from commands where id = $1', [id])).rows[0];
      if (!c || (c.actor_id !== reporter && c.target_id !== reporter)) return null;
      return { targetUserId: c.actor_id === reporter ? c.target_id : c.actor_id, evidence: { capability: c.capability, sentAt: c.created_at } };
    }
  }
  return null;
}

const ReportDto = Type.Object({
  id: Type.String(),
  reporterId: Nullable(Type.String()),
  targetUserId: Nullable(Type.String()),
  targetKind: Type.String(),
  targetId: Type.String(),
  reason: Type.String(),
  note: Nullable(Type.String()),
  evidence: Type.Unknown(),
  state: Type.String(),
  resolution: Nullable(Type.String()),
  createdAt: Instant,
  resolvedAt: Nullable(Instant),
});

export const moderationRoutes: RouteDef[] = [
  route({
    method: 'POST',
    url: '/v1/reports',
    operationId: 'createReport',
    summary: 'Report a person, message, challenge, sound or request.',
    tags: ['moderation'],
    auth: 'user',
    rateLimit: { max: 30, timeWindow: '1 hour' },
    body: Obj({ targetKind: TargetKind, targetId: Uuid, reason: ReportReason, note: Type.Optional(Type.String({ maxLength: 500 })) }),
    response: Type.Object({ id: Type.String() }),
    status: 201,
    handler: async ({ body, user, deps }) =>
      deps.db.tx(async (q) => {
        const ev = await evidenceFor(q, body.targetKind, body.targetId, user.id);
        if (!ev) throw notFound(body.targetKind);
        const id = await createReport(q, { reporterId: user.id, targetUserId: ev.targetUserId, targetKind: body.targetKind, targetId: body.targetId, reason: body.reason, note: body.note?.trim() || null, evidence: ev.evidence });
        await audit(q, { actorUserId: user.id, actorKind: 'user', action: 'report.created', targetKind: 'report', targetId: id });
        return { id };
      }),
  }),

  // -------------------------------------------------------------- admin only
  route({
    method: 'GET',
    url: '/v1/admin/reports',
    operationId: 'adminListReports',
    summary: 'Moderation queue (moderators with MFA only; every view is audited).',
    tags: ['admin'],
    auth: 'admin',
    query: Obj({ state: Type.Optional(Type.Union([Type.Literal('open'), Type.Literal('actioned'), Type.Literal('dismissed')])), limit: Type.Optional(Type.Integer({ minimum: 1, maximum: 100 })) }),
    response: Type.Object({ reports: Type.Array(ReportDto) }),
    handler: async ({ query, user, deps }) => {
      const r = await deps.db.query<{ id: string; reporter_id: string | null; target_user_id: string | null; target_kind: string; target_id: string; reason: string; note: string | null; evidence: unknown; state: string; resolution: string | null; created_at: Date; resolved_at: Date | null }>(
        'select * from reports where state = $1 order by created_at limit $2',
        [query.state ?? 'open', query.limit ?? 50],
      );
      await audit(deps.db, { actorUserId: user.id, actorKind: 'admin', action: 'admin.reports_viewed', data: { count: r.rowCount } });
      return {
        reports: r.rows.map((x) => ({ id: x.id, reporterId: x.reporter_id, targetUserId: x.target_user_id, targetKind: x.target_kind, targetId: x.target_id, reason: x.reason, note: x.note, evidence: x.evidence, state: x.state, resolution: x.resolution, createdAt: x.created_at.toISOString(), resolvedAt: iso(x.resolved_at) })),
      };
    },
  }),

  route({
    method: 'POST',
    url: '/v1/admin/reports/:id/resolve',
    operationId: 'adminResolveReport',
    summary: 'Dismiss, remove content, or suspend the reported account (revokes all its permissions).',
    tags: ['admin'],
    auth: 'admin',
    params: IdParams,
    body: Obj({ action: Type.Union([Type.Literal('dismiss'), Type.Literal('remove_content'), Type.Literal('suspend_user')]), note: Type.String({ minLength: 1, maxLength: 500 }) }),
    response: Type.Object({ ok: Type.Boolean() }),
    handler: async ({ params, body, user, deps, now }) =>
      deps.db.tx(async (q) => {
        const r = (await q.query<{ id: string; target_kind: string; target_id: string; target_user_id: string | null; state: string }>('select * from reports where id = $1 for update', [params.id])).rows[0];
        if (!r) throw notFound('report');
        if (body.action === 'remove_content') {
          if (r.target_kind === 'message') await q.query(`update messages set moderation_state = 'removed' where id = $1`, [r.target_id]);
          if (r.target_kind === 'challenge') await q.query('update challenge_definitions set archived_at = coalesce(archived_at, $2) where id = $1', [r.target_id, now]);
          if (r.target_kind === 'media') {
            const m = await q.query<{ object_key: string }>(`update media_assets set state = 'rejected', rejection_reason = 'moderation' where id = $1 returning object_key`, [r.target_id]);
            if (m.rows[0]) await enqueue(q, { kind: 'delete_object', dedupeKey: `delobj:${r.target_id}`, payload: { objectKey: m.rows[0].object_key }, maxAttempts: 10 });
          }
        }
        if (body.action === 'suspend_user' && r.target_user_id) {
          await q.query(`update users set account_state = 'suspended', updated_at = $2 where id = $1 and account_state = 'active'`, [r.target_user_id, now]);
          await revokeAllOf(q, r.target_user_id, 'moderation', now);
          await q.query(`update commands set lifecycle = 'cancelled', updated_at = $2 where actor_id = $1 and lifecycle = 'accepted'`, [r.target_user_id, now]);
        }
        await q.query(`update reports set state = $2, resolution = $3, resolved_by = $4, resolved_at = $5 where id = $1`, [r.id, body.action === 'dismiss' ? 'dismissed' : 'actioned', `${body.action}: ${body.note}`, user.id, now]);
        await audit(q, { actorUserId: user.id, actorKind: 'admin', action: `admin.report_${body.action}`, targetKind: 'report', targetId: r.id, data: { targetKind: r.target_kind } });
        return { ok: true };
      }),
  }),

  route({
    method: 'POST',
    url: '/v1/admin/users/:id/reinstate',
    operationId: 'adminReinstateUser',
    summary: 'Lift a suspension. Revoked permissions are not restored.',
    tags: ['admin'],
    auth: 'admin',
    params: IdParams,
    body: Obj({ note: Type.String({ minLength: 1, maxLength: 500 }) }),
    response: Type.Object({ ok: Type.Boolean() }),
    handler: async ({ params, body, user, deps, now }) =>
      deps.db.tx(async (q) => {
        const r = await q.query(`update users set account_state = 'active', updated_at = $2 where id = $1 and account_state = 'suspended'`, [params.id, now]);
        if (r.rowCount === 0) throw notFound('suspended user');
        await audit(q, { actorUserId: user.id, actorKind: 'admin', action: 'admin.user_reinstated', targetKind: 'user', targetId: params.id, data: { note: body.note } });
        return { ok: true };
      }),
  }),

  route({
    method: 'GET',
    url: '/v1/admin/feature-flags',
    operationId: 'adminListFlags',
    summary: 'Kill switches for remote dispatch, AI, location, uploads and focus.',
    tags: ['admin'],
    auth: 'admin',
    response: Type.Object({ flags: Type.Array(Type.Object({ key: Type.String(), enabled: Type.Boolean(), updatedAt: Instant })) }),
    handler: async ({ deps }) => {
      const r = await deps.db.query<{ key: string; enabled: boolean; updated_at: Date }>('select key, enabled, updated_at from feature_flags order by key');
      return { flags: r.rows.map((f) => ({ key: f.key, enabled: f.enabled, updatedAt: f.updated_at.toISOString() })) };
    },
  }),

  route({
    method: 'PUT',
    url: '/v1/admin/feature-flags/:key',
    operationId: 'adminSetFlag',
    summary: 'Flip a kill switch. Personal alarms and escape controls are never affected.',
    tags: ['admin'],
    auth: 'admin',
    params: Obj({ key: Type.String({ pattern: '^[a-z_.]{3,40}$' }) }),
    body: Obj({ enabled: Type.Boolean(), note: Type.String({ minLength: 1, maxLength: 200 }) }),
    response: Type.Object({ ok: Type.Boolean() }),
    handler: async ({ params, body, user, deps, now }) => {
      const r = await deps.db.query('update feature_flags set enabled = $2, updated_by = $3, updated_at = $4 where key = $1', [params.key, body.enabled, user.id, now]);
      if (r.rowCount === 0) throw notFound('flag');
      await audit(deps.db, { actorUserId: user.id, actorKind: 'admin', action: 'admin.flag_set', targetKind: 'feature_flag', targetId: params.key, data: { enabled: body.enabled, note: body.note } });
      deps.flags.invalidate();
      return { ok: true };
    },
  }),
];
