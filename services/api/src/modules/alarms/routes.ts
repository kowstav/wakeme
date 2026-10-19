import Type from 'typebox';
import { BUNDLED_SOUNDS, isHhMm, isValidTimeZone, nextOccurrences, grantAllows, type Recurrence } from '@wakeme/policy';
import { recipientPreview, type ChallengeDefinition } from '@wakeme/challenges';
import { conflict, forbidden, notFound, unprocessable } from '../../http/errors.ts';
import { HhMm, IdParams, Instant, Nullable, Obj, Uuid, route, type RouteDef } from '../../http/route.ts';
import type { Queryable } from '../../db/db.ts';
import { appendSync, enqueue, userById, type UserRow } from '../identity/repo.ts';
import { liveGrant, toSnapshot } from '../grants/repo.ts';
import { enqueueSyncHint } from '../grants/revoke.ts';
import { areFriends } from '../relationships/repo.ts';
import { assignableRevision, canDownloadRevision } from '../challenges/repo.ts';
import { planFor, PLAN_LIMITS } from '../billing/service.ts';
import {
  checkFriendPlanAgainstGrant,
  planReadiness,
  validateRecurrence,
  type InstallationRow,
  type PlanRow,
  type PlanTerms,
  type PlanVersionRow,
} from './service.ts';

const RecurrenceSchema = Type.Union([
  Obj({ kind: Type.Literal('weekly'), days: Type.Array(Type.Integer({ minimum: 1, maximum: 7 }), { minItems: 1, maxItems: 7 }) }),
  Obj({ kind: Type.Literal('once'), date: Type.String({ pattern: '^\\d{4}-\\d{2}-\\d{2}$' }) }),
]);

const TermFields = {
  label: Type.Optional(Type.String({ maxLength: 40 })),
  localTime: HhMm,
  timeZone: Type.String({ minLength: 1, maxLength: 64 }),
  timeZonePolicy: Type.Union([Type.Literal('fixed'), Type.Literal('device')], { description: 'fixed: ring at this wall time in timeZone. device: follow the phone when travelling.' }),
  recurrence: RecurrenceSchema,
  soundId: Type.String({ maxLength: 64 }),
  ringSeconds: Type.Integer({ minimum: 10, maximum: 300 }),
  challengeRevisionId: Type.Optional(Nullable(Uuid)),
  followUpDelaySeconds: Type.Optional(Nullable(Type.Integer({ minimum: 60, maximum: 600 }))),
};

const VersionDto = Type.Object({
  version: Type.Integer(),
  label: Type.String(),
  localTime: Type.String(),
  timeZone: Type.String(),
  timeZonePolicy: Type.String(),
  recurrence: Type.Unknown(),
  soundId: Type.String(),
  ringSeconds: Type.Integer(),
  challengeRevisionId: Nullable(Type.String()),
  challengePreview: Type.Unknown({ description: 'Answer-free summary of the challenge, for the owner deciding to accept.' }),
  followUpDelaySeconds: Nullable(Type.Integer()),
  createdBy: Type.String(),
  createdAt: Instant,
});

const PlanDto = Type.Object({
  id: Type.String(),
  owner: Type.Object({ id: Type.String(), displayName: Type.String() }),
  creator: Type.Object({ id: Type.String(), displayName: Type.String() }),
  state: Type.String(),
  accepted: Nullable(VersionDto),
  pending: Nullable(VersionDto),
  readiness: Type.String({ description: 'ready | ready_edit_pending | awaiting_acceptance | awaiting_installation | install_failed | paused | permission_lost | finished | declined | cancelled' }),
  installation: Nullable(Type.Object({ deviceId: Type.String(), version: Type.Integer(), result: Type.String(), errorCode: Nullable(Type.String()), nextFireAt: Nullable(Instant), reportedAt: Instant })),
  nextOccurrences: Type.Array(Type.Object({ localDate: Type.String(), instant: Instant })),
  createdAt: Instant,
  updatedAt: Instant,
});

async function soundUsable(q: Queryable, ownerId: string, soundId: string): Promise<boolean> {
  if ((BUNDLED_SOUNDS as readonly string[]).includes(soundId)) return true;
  if (!soundId.startsWith('media:')) return false;
  const r = await q.query(
    `select 1 from media_assets m where m.id = $2::uuid and m.state = 'clean'
       and (m.owner_id = $1 or exists (select 1 from sound_library s where s.owner_id = $1 and s.media_id = m.id and s.state = 'approved'))`,
    [ownerId, soundId.slice(6)],
  );
  return r.rowCount > 0;
}

async function versionDto(q: Queryable, v: PlanVersionRow): Promise<Type.Static<typeof VersionDto>> {
  let preview: unknown = null;
  if (v.challenge_revision_id) {
    const r = await q.query<{ body: ChallengeDefinition }>('select body from challenge_revisions where id = $1', [v.challenge_revision_id]);
    if (r.rows[0]) preview = recipientPreview(r.rows[0].body);
  }
  return {
    version: v.version,
    label: v.label,
    localTime: v.local_time,
    timeZone: v.time_zone,
    timeZonePolicy: v.time_zone_policy,
    recurrence: v.recurrence,
    soundId: v.sound_id,
    ringSeconds: v.ring_seconds,
    challengeRevisionId: v.challenge_revision_id,
    challengePreview: preview,
    followUpDelaySeconds: v.follow_up_delay_seconds,
    createdBy: v.created_by,
    createdAt: v.created_at.toISOString(),
  };
}

export async function planDto(q: Queryable, plan: PlanRow, now: Date): Promise<Type.Static<typeof PlanDto>> {
  const versions = await q.query<PlanVersionRow>('select * from alarm_plan_versions where plan_id = $1 and version = any($2::int[])', [plan.id, [plan.accepted_version ?? -1, plan.pending_version ?? -1]]);
  const accepted = versions.rows.find((v) => v.version === plan.accepted_version) ?? null;
  const pending = versions.rows.find((v) => v.version === plan.pending_version) ?? null;
  const owner = await userById(q, plan.owner_id);
  const creator = plan.creator_id === plan.owner_id ? owner : await userById(q, plan.creator_id);
  const inst = owner?.primary_device_id
    ? (await q.query<InstallationRow>('select * from alarm_installations where plan_id = $1 and device_id = $2', [plan.id, owner.primary_device_id])).rows[0] ?? null
    : null;
  let grantOk = true;
  if (plan.creator_id !== plan.owner_id) {
    const g = await liveGrant(q, plan.owner_id, plan.creator_id);
    grantOk = !!g && grantAllows(toSnapshot(g), 'alarm.schedule', now);
  }
  const next = accepted ? nextOccurrences({ localTime: accepted.local_time, timeZone: accepted.time_zone, recurrence: accepted.recurrence }, now, 3) : [];
  return {
    id: plan.id,
    owner: { id: plan.owner_id, displayName: owner?.display_name ?? 'Deleted account' },
    creator: { id: plan.creator_id, displayName: creator?.display_name ?? 'Deleted account' },
    state: plan.state,
    accepted: accepted ? await versionDto(q, accepted) : null,
    pending: pending ? await versionDto(q, pending) : null,
    readiness: planReadiness(plan, accepted, inst, owner?.primary_device_id ?? null, grantOk, now),
    installation: inst
      ? { deviceId: inst.device_id, version: inst.version, result: inst.result, errorCode: inst.error_code, nextFireAt: inst.next_fire_at ? inst.next_fire_at.toISOString() : null, reportedAt: inst.reported_at.toISOString() }
      : null,
    nextOccurrences: plan.state === 'active' ? next.map((o) => ({ localDate: o.localDate, instant: o.instant.toISOString() })) : [],
    createdAt: plan.created_at.toISOString(),
    updatedAt: plan.updated_at.toISOString(),
  };
}

async function loadPlan(q: Queryable, id: string, userId: string, lock = false): Promise<PlanRow> {
  const r = await q.query<PlanRow>(`select * from alarm_plans where id = $1 and (owner_id = $2 or creator_id = $2)${lock ? ' for update' : ''}`, [id, userId]);
  if (!r.rows[0]) throw notFound('routine');
  return r.rows[0];
}

async function activeRoutineCount(q: Queryable, ownerId: string): Promise<number> {
  const r = await q.query<{ n: number | string }>(`select count(*) as n from alarm_plans where owner_id = $1 and state in ('active', 'paused')`, [ownerId]);
  return Number(r.rows[0]?.n ?? 0);
}

/** Validates terms for whoever is creating or editing, returning the challenge (if any). */
async function vetTerms(q: Queryable, actor: UserRow, owner: UserRow, terms: PlanTerms, now: Date, planId: string | null): Promise<{ grantId: string | null; grantVersion: number | null }> {
  if (!isHhMm(terms.localTime)) throw unprocessable('invalid_time', 'Choose a valid time.');
  if (!isValidTimeZone(terms.timeZone)) throw unprocessable('invalid_time_zone', 'Choose a valid time zone.');
  validateRecurrence(terms.recurrence);
  let challenge: ChallengeDefinition | null = null;
  if (terms.challengeRevisionId) {
    const a = await assignableRevision(q, terms.challengeRevisionId, actor.id);
    if (!a && !(actor.id === owner.id && (await canDownloadRevision(q, terms.challengeRevisionId, actor.id)))) throw notFound('challenge');
    const r = await q.query<{ body: ChallengeDefinition }>(`select body from challenge_revisions where id = $1 and state = 'published'`, [terms.challengeRevisionId]);
    if (!r.rows[0]) throw notFound('challenge');
    challenge = r.rows[0].body;
  }
  if (actor.id === owner.id) {
    if (!(await soundUsable(q, owner.id, terms.soundId))) throw unprocessable('sound_not_available', 'Choose a sound from your library.');
    return { grantId: null, grantVersion: null };
  }
  if (!(await areFriends(q, actor.id, owner.id))) throw notFound('friend');
  const grant = await liveGrant(q, owner.id, actor.id, true);
  if (!grant) throw forbidden('capability_not_granted', "They haven't allowed you to schedule wake-ups.");
  if (grant.paused) throw forbidden('grant_paused', 'They have paused requests from you for now.');
  await checkFriendPlanAgainstGrant(q, toSnapshot(grant), owner, actor.id, terms, challenge, now, planId);
  if (!(await soundUsable(q, owner.id, terms.soundId))) throw unprocessable('sound_not_allowed', "That sound isn't in the set they approved.");
  return { grantId: grant.id, grantVersion: grant.version };
}

async function insertVersion(q: Queryable, planId: string, version: number, terms: PlanTerms, createdBy: string, grant: { grantId: string | null; grantVersion: number | null }, now: Date): Promise<void> {
  await q.query(
    `insert into alarm_plan_versions (plan_id, version, label, local_time, time_zone, time_zone_policy, recurrence, sound_id, ring_seconds, challenge_revision_id, follow_up_delay_seconds, created_by, grant_id, grant_version, created_at)
     values ($1, $2, $3, $4, $5, $6, $7::jsonb, $8, $9, $10, $11, $12, $13, $14, $15)`,
    [planId, version, terms.label, terms.localTime, terms.timeZone, terms.timeZonePolicy, JSON.stringify(terms.recurrence), terms.soundId, terms.ringSeconds, terms.challengeRevisionId, terms.followUpDelaySeconds, createdBy, grant.grantId, grant.grantVersion, now],
  );
}

/**
 * A helper proposes wall-clock times in the owner's own time zone; the owner's
 * zone is private, so the server applies it rather than trusting the client.
 */
function forOwner(terms: PlanTerms, actor: UserRow, owner: UserRow): PlanTerms {
  return actor.id === owner.id ? terms : { ...terms, timeZone: owner.time_zone, timeZonePolicy: 'fixed' };
}

function termsFromBody(b: { label?: string; localTime: string; timeZone: string; timeZonePolicy: 'fixed' | 'device'; recurrence: Recurrence; soundId: string; ringSeconds: number; challengeRevisionId?: string | null; followUpDelaySeconds?: number | null }): PlanTerms {
  return {
    label: (b.label ?? '').trim(),
    localTime: b.localTime,
    timeZone: b.timeZone,
    timeZonePolicy: b.timeZonePolicy,
    recurrence: b.recurrence,
    soundId: b.soundId,
    ringSeconds: b.ringSeconds,
    challengeRevisionId: b.challengeRevisionId ?? null,
    followUpDelaySeconds: b.followUpDelaySeconds ?? null,
  };
}

async function notifyOwnerOfProposal(q: Queryable, plan: PlanRow, version: number): Promise<void> {
  await enqueue(q, { kind: 'notify_plan', dedupeKey: `plan:${plan.id}:v${version}:proposed`, payload: { planId: plan.id, event: 'proposed', version }, maxAttempts: 3 });
}

export const alarmRoutes: RouteDef[] = [
  route({
    method: 'POST',
    url: '/v1/alarm-plans',
    operationId: 'createAlarmPlan',
    summary: 'Create a personal routine, or propose a wake-up routine for a friend (they must accept it).',
    tags: ['alarms'],
    auth: 'user',
    rateLimit: { max: 60, timeWindow: '1 hour' },
    body: Obj({ ownerId: Type.Optional(Uuid), ...TermFields }),
    response: PlanDto,
    status: 201,
    handler: async ({ body, user, deps, now }) =>
      deps.db.tx(async (q) => {
        const ownerId = body.ownerId ?? user.id;
        const owner = ownerId === user.id ? user : await userById(q, ownerId, true);
        if (!owner || owner.account_state !== 'active') throw notFound('friend');
        const terms = forOwner(termsFromBody(body), user, owner);
        const grant = await vetTerms(q, user, owner, terms, now, null);
        const self = owner.id === user.id;
        if (self) {
          const plan = await planFor(q, owner.id, now);
          if ((await activeRoutineCount(q, owner.id)) >= PLAN_LIMITS[plan.plan].activeRoutines) {
            throw forbidden('plan_limit_reached', 'You have reached the number of active routines on your plan. Existing routines keep working.');
          }
        }
        const r = await q.query<PlanRow>(
          `insert into alarm_plans (owner_id, creator_id, state, accepted_version, pending_version, latest_version, created_at, updated_at)
           values ($1, $2, $3, $4, $5, 1, $6, $6) returning *`,
          [owner.id, user.id, self ? 'active' : 'awaiting_acceptance', self ? 1 : null, self ? null : 1, now],
        );
        const plan = r.rows[0]!;
        await insertVersion(q, plan.id, 1, terms, user.id, grant, now);
        await appendSync(q, [owner.id, user.id], 'alarm_plan', plan.id);
        if (self) await enqueueSyncHint(q, [owner.id], `plan:${plan.id}:1`);
        else await notifyOwnerOfProposal(q, plan, 1);
        return planDto(q, plan, now);
      }),
  }),

  route({
    method: 'GET',
    url: '/v1/alarm-plans',
    operationId: 'listAlarmPlans',
    summary: 'Routines on your phone and routines you help friends with.',
    tags: ['alarms'],
    auth: 'user',
    query: Obj({ includeEnded: Type.Optional(Type.Boolean()) }),
    response: Type.Object({ mine: Type.Array(PlanDto), helping: Type.Array(PlanDto) }),
    handler: async ({ query, user, deps, now }) => {
      const r = await deps.db.query<PlanRow>(
        `select * from alarm_plans where (owner_id = $1 or creator_id = $1) and ($2::boolean or state not in ('cancelled', 'declined')) order by created_at`,
        [user.id, query.includeEnded === true],
      );
      const mine = [];
      const helping = [];
      for (const p of r.rows) {
        const dto = await planDto(deps.db, p, now);
        if (p.owner_id === user.id) mine.push(dto);
        else helping.push(dto);
      }
      return { mine, helping };
    },
  }),

  route({
    method: 'GET',
    url: '/v1/alarm-plans/:id',
    operationId: 'getAlarmPlan',
    summary: 'Routine with accepted and pending versions and per-phone readiness.',
    tags: ['alarms'],
    auth: 'user',
    params: IdParams,
    response: PlanDto,
    handler: async ({ params, user, deps, now }) => planDto(deps.db, await loadPlan(deps.db, params.id, user.id), now),
  }),

  route({
    method: 'PATCH',
    url: '/v1/alarm-plans/:id',
    operationId: 'editAlarmPlan',
    summary: "Edit as a new version. The owner's edits apply at once; a helper's edit waits for the owner's acceptance.",
    tags: ['alarms'],
    auth: 'user',
    params: IdParams,
    body: Obj({ expectedLatestVersion: Type.Integer({ minimum: 1 }), ...TermFields }),
    response: PlanDto,
    handler: async ({ params, body, user, deps, now }) =>
      deps.db.tx(async (q) => {
        const plan = await loadPlan(q, params.id, user.id, true);
        if (plan.state === 'cancelled' || plan.state === 'declined') throw conflict('routine_ended', 'This routine has ended.');
        if (plan.latest_version !== body.expectedLatestVersion) throw conflict('version_conflict', 'This routine changed. Review the latest version first.', { latestVersion: plan.latest_version });
        if (user.id !== plan.owner_id && user.id !== plan.creator_id) throw notFound('routine');
        const owner = (await userById(q, plan.owner_id))!;
        const terms = forOwner(termsFromBody(body), user, owner);
        const grant = await vetTerms(q, user, owner, terms, now, plan.id);
        const version = plan.latest_version + 1;
        await insertVersion(q, plan.id, version, terms, user.id, grant, now);
        const byOwner = user.id === plan.owner_id;
        const r = await q.query<PlanRow>(
          byOwner
            ? `update alarm_plans set latest_version = $2, accepted_version = $2, pending_version = null, state = case when state = 'awaiting_acceptance' then 'active' else state end, updated_at = $3 where id = $1 returning *`
            : `update alarm_plans set latest_version = $2, pending_version = $2, updated_at = $3 where id = $1 returning *`,
          [plan.id, version, now],
        );
        const updated = r.rows[0]!;
        await appendSync(q, [plan.owner_id, plan.creator_id], 'alarm_plan', plan.id);
        if (byOwner) await enqueueSyncHint(q, [plan.owner_id], `plan:${plan.id}:${version}`);
        else await notifyOwnerOfProposal(q, updated, version);
        return planDto(q, updated, now);
      }),
  }),

  route({
    method: 'POST',
    url: '/v1/alarm-plans/:id/accept',
    operationId: 'acceptAlarmPlan',
    summary: 'Owner accepts a proposed routine or a pending edit (exact version).',
    tags: ['alarms'],
    auth: 'user',
    params: IdParams,
    body: Obj({ version: Type.Integer({ minimum: 1 }) }),
    response: PlanDto,
    handler: async ({ params, body, user, deps, now }) =>
      deps.db.tx(async (q) => {
        const plan = await loadPlan(q, params.id, user.id, true);
        if (plan.owner_id !== user.id) throw forbidden('owner_only', 'Only the person whose phone rings can accept a routine.');
        if (plan.pending_version !== body.version) throw conflict('version_conflict', 'There is a newer proposal. Review it first.', { pendingVersion: plan.pending_version });
        const v = (await q.query<PlanVersionRow>('select * from alarm_plan_versions where plan_id = $1 and version = $2', [plan.id, body.version])).rows[0]!;
        if (plan.creator_id !== user.id) {
          // Re-check against the grant as it is now: it may have narrowed since the proposal.
          await vetTerms(q, (await userById(q, plan.creator_id))!, user, {
            label: v.label, localTime: v.local_time, timeZone: v.time_zone, timeZonePolicy: v.time_zone_policy, recurrence: v.recurrence, soundId: v.sound_id, ringSeconds: v.ring_seconds, challengeRevisionId: v.challenge_revision_id, followUpDelaySeconds: v.follow_up_delay_seconds,
          }, now, plan.id);
        }
        if (plan.accepted_version === null) {
          const p = await planFor(q, user.id, now);
          if ((await activeRoutineCount(q, user.id)) >= PLAN_LIMITS[p.plan].activeRoutines) {
            throw forbidden('plan_limit_reached', 'You have reached the number of active routines on your plan. Existing routines keep working.');
          }
        }
        const r = await q.query<PlanRow>(
          `update alarm_plans set accepted_version = $2, pending_version = null, state = case when state = 'awaiting_acceptance' then 'active' else state end, updated_at = $3 where id = $1 returning *`,
          [plan.id, body.version, now],
        );
        await appendSync(q, [plan.owner_id, plan.creator_id], 'alarm_plan', plan.id);
        await enqueueSyncHint(q, [plan.owner_id], `plan:${plan.id}:${body.version}`);
        await enqueue(q, { kind: 'notify_plan', dedupeKey: `plan:${plan.id}:v${body.version}:accepted`, payload: { planId: plan.id, event: 'accepted', version: body.version }, maxAttempts: 3 });
        return planDto(q, r.rows[0]!, now);
      }),
  }),

  route({
    method: 'POST',
    url: '/v1/alarm-plans/:id/decline',
    operationId: 'declineAlarmPlan',
    summary: 'Owner declines a proposal or pending edit. The previously accepted version keeps working.',
    tags: ['alarms'],
    auth: 'user',
    params: IdParams,
    body: Obj({ version: Type.Integer({ minimum: 1 }) }),
    response: PlanDto,
    handler: async ({ params, body, user, deps, now }) =>
      deps.db.tx(async (q) => {
        const plan = await loadPlan(q, params.id, user.id, true);
        if (plan.owner_id !== user.id) throw forbidden('owner_only', 'Only the person whose phone rings can decline a routine.');
        if (plan.pending_version !== body.version) throw conflict('version_conflict', 'That proposal is no longer pending.');
        const r = await q.query<PlanRow>(
          `update alarm_plans set pending_version = null, state = case when accepted_version is null then 'declined' else state end, updated_at = $2 where id = $1 returning *`,
          [plan.id, now],
        );
        await appendSync(q, [plan.owner_id, plan.creator_id], 'alarm_plan', plan.id);
        return planDto(q, r.rows[0]!, now);
      }),
  }),

  route({
    method: 'POST',
    url: '/v1/alarm-plans/:id/pause',
    operationId: 'pauseAlarmPlan',
    summary: 'Owner pauses or resumes a routine.',
    tags: ['alarms'],
    auth: 'user',
    params: IdParams,
    body: Obj({ paused: Type.Boolean() }),
    response: PlanDto,
    handler: async ({ params, body, user, deps, now }) =>
      deps.db.tx(async (q) => {
        const plan = await loadPlan(q, params.id, user.id, true);
        if (plan.owner_id !== user.id) throw forbidden('owner_only', 'Only the owner can pause a routine.');
        if (plan.state !== 'active' && plan.state !== 'paused') throw conflict('not_active', 'Only active routines can be paused.');
        const r = await q.query<PlanRow>(`update alarm_plans set state = $2, updated_at = $3 where id = $1 returning *`, [plan.id, body.paused ? 'paused' : 'active', now]);
        await appendSync(q, [plan.owner_id, plan.creator_id], 'alarm_plan', plan.id);
        await enqueueSyncHint(q, [plan.owner_id], `plan:${plan.id}:${body.paused ? 'pause' : 'resume'}:${now.getTime()}`);
        return planDto(q, r.rows[0]!, now);
      }),
  }),

  route({
    method: 'DELETE',
    url: '/v1/alarm-plans/:id',
    operationId: 'cancelAlarmPlan',
    summary: 'End a routine (owner, or the helper who created it). Phones remove it on next sync; cancel locally first.',
    tags: ['alarms'],
    auth: 'user',
    params: IdParams,
    response: PlanDto,
    handler: async ({ params, user, deps, now }) =>
      deps.db.tx(async (q) => {
        const plan = await loadPlan(q, params.id, user.id, true);
        const r = await q.query<PlanRow>(
          `update alarm_plans set state = 'cancelled', pending_version = null, cancelled_reason = $2, updated_at = $3 where id = $1 returning *`,
          [plan.id, user.id === plan.owner_id ? 'owner_cancelled' : 'creator_cancelled', now],
        );
        await appendSync(q, [plan.owner_id, plan.creator_id], 'alarm_plan', plan.id);
        await enqueueSyncHint(q, [plan.owner_id], `plan:${plan.id}:cancel`);
        return planDto(q, r.rows[0]!, now);
      }),
  }),

  route({
    method: 'POST',
    url: '/v1/alarm-plans/:id/installations',
    operationId: 'reportAlarmInstallation',
    summary: "Owner's phone reports what the OS scheduler actually accepted.",
    tags: ['alarms'],
    auth: 'device',
    params: IdParams,
    body: Obj({
      version: Type.Integer({ minimum: 1 }),
      result: Type.Union([Type.Literal('scheduled'), Type.Literal('failed'), Type.Literal('removed')]),
      nextFireAt: Type.Optional(Nullable(Instant)),
      scheduledInstants: Type.Optional(Type.Array(Instant, { maxItems: 64 })),
      assetDigest: Type.Optional(Type.String({ maxLength: 128 })),
      errorCode: Type.Optional(Type.String({ maxLength: 64, pattern: '^[a-z0-9_.]+$' })),
    }),
    response: PlanDto,
    handler: async ({ params, body, user, device, deps, now }) =>
      deps.db.tx(async (q) => {
        const plan = await loadPlan(q, params.id, user.id, true);
        if (plan.owner_id !== user.id) throw notFound('routine');
        if (body.version > plan.latest_version) throw unprocessable('unknown_version', 'That routine version does not exist.');
        await q.query(
          `insert into alarm_installations (plan_id, device_id, version, result, error_code, next_fire_at, scheduled_instants, asset_digest, reported_at)
           values ($1, $2, $3, $4, $5, $6, $7::jsonb, $8, $9)
           on conflict (plan_id, device_id) do update set version = excluded.version, result = excluded.result, error_code = excluded.error_code,
             next_fire_at = excluded.next_fire_at, scheduled_instants = excluded.scheduled_instants, asset_digest = excluded.asset_digest, reported_at = excluded.reported_at`,
          [plan.id, device!.id, body.version, body.result, body.errorCode ?? null, body.nextFireAt ?? null, JSON.stringify(body.scheduledInstants ?? []), body.assetDigest ?? null, now],
        );
        await appendSync(q, [plan.owner_id, plan.creator_id], 'alarm_plan', plan.id);
        return planDto(q, plan, now);
      }),
  }),

  route({
    method: 'POST',
    url: '/v1/alarm-plans/:id/occurrences',
    operationId: 'reportAlarmOccurrence',
    summary: 'Owner\'s phone reports an occurrence outcome. "Stopped" (OS stop) and "challenge_completed" are different outcomes.',
    tags: ['alarms'],
    auth: 'device',
    params: IdParams,
    body: Obj({
      occurrenceKey: Type.String({ pattern: '^\\d{4}-\\d{2}-\\d{2}$' }),
      version: Type.Integer({ minimum: 1 }),
      outcome: Type.Union(['rang', 'stopped', 'challenge_completed', 'bypassed', 'follow_up_rang', 'session_ended', 'missed_unknown'].map((o) => Type.Literal(o))),
    }),
    response: Type.Object({ ok: Type.Boolean() }),
    handler: async ({ params, body, user, device, deps }) =>
      deps.db.tx(async (q) => {
        const plan = await loadPlan(q, params.id, user.id);
        if (plan.owner_id !== user.id) throw notFound('routine');
        // Outcomes only move forward: a later "rang" never overwrites "challenge_completed".
        const rank: Record<string, number> = { missed_unknown: 0, rang: 1, follow_up_rang: 2, stopped: 3, session_ended: 4, bypassed: 5, challenge_completed: 5 };
        const prev = await q.query<{ outcome: string }>('select outcome from alarm_occurrences where plan_id = $1 and occurrence_key = $2', [plan.id, body.occurrenceKey]);
        const before = prev.rows[0]?.outcome;
        if (before && (rank[before] ?? 0) >= (rank[body.outcome] ?? 0)) return { ok: true };
        await q.query(
          `insert into alarm_occurrences (plan_id, occurrence_key, version, outcome, device_id) values ($1, $2, $3, $4, $5)
           on conflict (plan_id, occurrence_key) do update set outcome = excluded.outcome, version = excluded.version, device_id = excluded.device_id, reported_at = now()`,
          [plan.id, body.occurrenceKey, body.version, body.outcome, device!.id],
        );
        if (plan.creator_id !== plan.owner_id && (body.outcome === 'challenge_completed' || (body.outcome === 'bypassed' && user.share_bypass))) {
          await enqueue(q, { kind: 'notify_plan', dedupeKey: `plan:${plan.id}:${body.occurrenceKey}:${body.outcome}`, payload: { planId: plan.id, event: body.outcome, occurrenceKey: body.occurrenceKey }, maxAttempts: 3 });
        }
        await appendSync(q, [plan.owner_id, plan.creator_id], 'alarm_plan', plan.id);
        return { ok: true };
      }),
  }),
];
