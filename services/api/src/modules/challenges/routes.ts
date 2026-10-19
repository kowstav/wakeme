import Type from 'typebox';
import {
  TEMPLATES,
  buildTemplate,
  recipientPreview,
  scanText,
  signEnvelope,
  validateChallenge,
  withDerivedFields,
  type ChallengeDefinition,
  type TemplateId,
} from '@wakeme/challenges';
import { AppError, conflict, forbidden, notFound, unavailable } from '../../http/errors.ts';
import { IdParams, Instant, Nullable, Obj, Uuid, iso, route, type RouteDef } from '../../http/route.ts';
import type { Queryable } from '../../db/db.ts';
import { appendSync, audit, enqueue, type UserRow } from '../identity/repo.ts';
import { activeCircleMember } from '../relationships/repo.ts';
import { creditBalance, spendCredit } from '../billing/service.ts';
import { canDownloadRevision, canUseDefinition, type DefinitionRow, type RevisionRow } from './repo.ts';
import { recomputeDelivery } from '../commands/routes.ts';

const RevisionDto = Type.Object({
  id: Type.String(),
  revision: Type.Integer(),
  state: Type.String(),
  origin: Type.String(),
  body: Type.Unknown(),
  previewedAt: Nullable(Instant),
  publishedAt: Nullable(Instant),
  digest: Nullable(Type.String()),
  createdAt: Instant,
});

const DefinitionDto = Type.Object({
  id: Type.String(),
  creatorId: Type.String(),
  circleId: Nullable(Type.String()),
  title: Type.String(),
  latestRevision: Type.Integer(),
  publishedRevision: Nullable(Type.Integer()),
  revisions: Type.Array(RevisionDto),
  createdAt: Instant,
  updatedAt: Instant,
});

const GenerationDto = Type.Object({
  id: Type.String(),
  status: Type.String(),
  difficulty: Type.String(),
  definitionId: Nullable(Type.String()),
  rejection: Type.Unknown(),
  errorCode: Nullable(Type.String()),
  creditsRemaining: Type.Integer(),
  createdAt: Instant,
  completedAt: Nullable(Instant),
});

function revisionDto(r: RevisionRow): Type.Static<typeof RevisionDto> {
  return {
    id: r.id,
    revision: r.revision,
    state: r.state,
    origin: r.origin,
    body: r.body,
    previewedAt: iso(r.previewed_at),
    publishedAt: iso(r.published_at),
    digest: r.digest,
    createdAt: r.created_at.toISOString(),
  };
}

async function definitionDto(q: Queryable, d: DefinitionRow): Promise<Type.Static<typeof DefinitionDto>> {
  const revs = await q.query<RevisionRow>('select * from challenge_revisions where definition_id = $1 order by revision', [d.id]);
  return {
    id: d.id,
    creatorId: d.creator_id,
    circleId: d.circle_id,
    title: d.title,
    latestRevision: d.latest_revision,
    publishedRevision: d.published_revision,
    revisions: revs.rows.map(revisionDto),
    createdAt: d.created_at.toISOString(),
    updatedAt: d.updated_at.toISOString(),
  };
}

/** Server derives verification/permissions so clients never have to compute them. */
export function vetBody(body: unknown): ChallengeDefinition {
  const candidate = typeof body === 'object' && body !== null ? withDerivedFields(body as ChallengeDefinition) : body;
  const v = validateChallenge(candidate);
  if (!v.ok) throw new AppError(422, 'challenge_invalid', 'This challenge needs changes before it can be saved.', { details: { issues: v.issues, safety: v.safety } });
  return v.value;
}

async function loadDefinition(q: Queryable, id: string, user: UserRow, lock = false): Promise<DefinitionRow> {
  const r = await q.query<DefinitionRow>(`select * from challenge_definitions where id = $1${lock ? ' for update' : ''}`, [id]);
  const d = r.rows[0];
  if (!d || !(await canUseDefinition(q, d, user.id))) throw notFound('challenge');
  return d;
}

export async function createDefinition(
  q: Queryable,
  creatorId: string,
  def: ChallengeDefinition,
  opts: { circleId: string | null; origin: 'manual' | 'template' | 'ai'; generationId?: string | null },
  now: Date,
): Promise<DefinitionRow> {
  const d = await q.query<DefinitionRow>(
    `insert into challenge_definitions (creator_id, circle_id, title, latest_revision, created_at, updated_at) values ($1, $2, $3, 1, $4, $4) returning *`,
    [creatorId, opts.circleId, def.title, now],
  );
  await q.query(
    `insert into challenge_revisions (definition_id, revision, state, body, origin, generation_id, created_at) values ($1, 1, 'draft', $2::jsonb, $3, $4, $5)`,
    [d.rows[0]!.id, JSON.stringify(def), opts.origin, opts.generationId ?? null, now],
  );
  await appendSync(q, [creatorId], 'challenge', d.rows[0]!.id);
  return d.rows[0]!;
}

async function checkCircle(q: Queryable, circleId: string | undefined | null, userId: string): Promise<string | null> {
  if (!circleId) return null;
  if (!(await activeCircleMember(q, circleId, userId))) throw notFound('circle');
  return circleId;
}

const Fact = Obj({
  question: Type.String({ minLength: 1, maxLength: 200 }),
  answer: Type.String({ minLength: 1, maxLength: 60 }),
  distractors: Type.Optional(Type.Array(Type.String({ minLength: 1, maxLength: 80 }), { maxItems: 3 })),
});

export const challengeRoutes: RouteDef[] = [
  route({
    method: 'GET',
    url: '/v1/challenges/templates',
    operationId: 'listChallengeTemplates',
    summary: 'Built-in templates with answer-free previews.',
    tags: ['challenges'],
    auth: 'user',
    response: Type.Object({ templates: Type.Array(Type.Object({ id: Type.String(), title: Type.String(), description: Type.String(), preview: Type.Unknown() })) }),
    handler: async () => ({ templates: TEMPLATES.map((t) => ({ ...t, preview: recipientPreview(buildTemplate(t.id)) })) }),
  }),

  route({
    method: 'POST',
    url: '/v1/challenges',
    operationId: 'createChallenge',
    summary: 'Create a challenge draft from a declarative definition (manual builder).',
    tags: ['challenges'],
    auth: 'user',
    rateLimit: { max: 60, timeWindow: '1 hour' },
    body: Obj({ circleId: Type.Optional(Uuid), body: Type.Unknown() }),
    response: DefinitionDto,
    status: 201,
    handler: async ({ body, user, deps, now }) => {
      const def = vetBody(body.body);
      return deps.db.tx(async (q) => definitionDto(q, await createDefinition(q, user.id, def, { circleId: await checkCircle(q, body.circleId, user.id), origin: 'manual' }, now)));
    },
  }),

  route({
    method: 'POST',
    url: '/v1/challenges/from-template',
    operationId: 'createChallengeFromTemplate',
    summary: 'Create a challenge draft from a built-in template.',
    tags: ['challenges'],
    auth: 'user',
    body: Obj({
      templateId: Type.Union(TEMPLATES.map((t) => Type.Literal(t.id))),
      difficulty: Type.Optional(Type.Union([Type.Literal('easy'), Type.Literal('medium'), Type.Literal('hard')])),
      seed: Type.Optional(Type.Integer({ minimum: 0, maximum: 2_147_483_647 })),
      circleId: Type.Optional(Uuid),
    }),
    response: DefinitionDto,
    status: 201,
    handler: async ({ body, user, deps, now }) => {
      const def = vetBody(buildTemplate(body.templateId as TemplateId, { difficulty: body.difficulty ?? 'easy', seed: body.seed ?? Math.floor(Math.random() * 2_147_483_647) }));
      return deps.db.tx(async (q) => definitionDto(q, await createDefinition(q, user.id, def, { circleId: await checkCircle(q, body.circleId, user.id), origin: 'template' }, now)));
    },
  }),

  route({
    method: 'GET',
    url: '/v1/challenges',
    operationId: 'listChallenges',
    summary: 'Your challenges and those shared in your circles.',
    tags: ['challenges'],
    auth: 'user',
    response: Type.Object({ challenges: Type.Array(DefinitionDto) }),
    handler: async ({ user, deps }) => {
      const r = await deps.db.query<DefinitionRow>(
        `select d.* from challenge_definitions d
          where d.archived_at is null and (d.creator_id = $1 or (d.circle_id is not null and exists (
            select 1 from circle_members m where m.circle_id = d.circle_id and m.user_id = $1 and m.left_at is null)))
          order by d.updated_at desc limit 200`,
        [user.id],
      );
      const challenges = [];
      for (const d of r.rows) challenges.push(await definitionDto(deps.db, d));
      return { challenges };
    },
  }),

  route({
    method: 'GET',
    url: '/v1/challenges/:id',
    operationId: 'getChallenge',
    summary: 'A challenge with all revisions (creator and circle members).',
    tags: ['challenges'],
    auth: 'user',
    params: IdParams,
    response: DefinitionDto,
    handler: async ({ params, user, deps }) => definitionDto(deps.db, await loadDefinition(deps.db, params.id, user)),
  }),

  route({
    method: 'POST',
    url: '/v1/challenges/:id/revisions',
    operationId: 'createChallengeRevision',
    summary: 'Save an edit as a new draft revision. Published revisions never change.',
    tags: ['challenges'],
    auth: 'user',
    params: IdParams,
    body: Obj({ body: Type.Unknown() }),
    response: DefinitionDto,
    handler: async ({ params, body, user, deps, now }) => {
      const def = vetBody(body.body);
      return deps.db.tx(async (q) => {
        const d = await loadDefinition(q, params.id, user, true);
        if (d.creator_id !== user.id) throw forbidden('creator_only', 'Only the creator can edit this challenge.');
        const next = d.latest_revision + 1;
        await q.query(`insert into challenge_revisions (definition_id, revision, state, body, origin, created_at) values ($1, $2, 'draft', $3::jsonb, 'manual', $4)`, [d.id, next, JSON.stringify(def), now]);
        const u = await q.query<DefinitionRow>('update challenge_definitions set latest_revision = $2, updated_at = $3 where id = $1 returning *', [d.id, next, now]);
        await appendSync(q, [user.id], 'challenge', d.id);
        return definitionDto(q, u.rows[0]!);
      });
    },
  }),

  route({
    method: 'POST',
    url: '/v1/challenges/:id/revisions/:revision/previewed',
    operationId: 'markChallengePreviewed',
    summary: 'Record that the creator tried this draft. Required before publishing.',
    tags: ['challenges'],
    auth: 'user',
    params: Obj({ id: Uuid, revision: Type.Integer({ minimum: 1 }) }),
    response: Type.Object({ ok: Type.Boolean() }),
    handler: async ({ params, user, deps, now }) => {
      const d = await loadDefinition(deps.db, params.id, user);
      if (d.creator_id !== user.id) throw forbidden('creator_only', 'Only the creator can publish this challenge.');
      const r = await deps.db.query(`update challenge_revisions set previewed_at = $3 where definition_id = $1 and revision = $2 and state = 'draft'`, [d.id, params.revision, now]);
      if (r.rowCount === 0) throw notFound('draft');
      return { ok: true };
    },
  }),

  route({
    method: 'POST',
    url: '/v1/challenges/:id/publish',
    operationId: 'publishChallenge',
    summary: 'Freeze a previewed draft as an immutable, signed revision that can be assigned.',
    tags: ['challenges'],
    auth: 'user',
    params: IdParams,
    body: Obj({ revision: Type.Integer({ minimum: 1 }) }),
    response: DefinitionDto,
    handler: async ({ params, body, user, deps, now }) =>
      deps.db.tx(async (q) => {
        const d = await loadDefinition(q, params.id, user, true);
        if (d.creator_id !== user.id) throw forbidden('creator_only', 'Only the creator can publish this challenge.');
        const r = await q.query<RevisionRow>('select * from challenge_revisions where definition_id = $1 and revision = $2 for update', [d.id, body.revision]);
        const rev = r.rows[0];
        if (!rev) throw notFound('draft');
        if (rev.state === 'published') return definitionDto(q, d);
        if (!rev.previewed_at) throw conflict('preview_required', 'Try the challenge yourself before publishing it.');
        const def = vetBody(rev.body);
        const env = signEnvelope({ revisionId: rev.id, definitionId: d.id, revision: rev.revision, definition: def }, deps.challengeKey, deps.config.challengeSigning.keyId);
        await q.query(
          `update challenge_revisions set state = 'published', body = $2::jsonb, payload = $3, digest = $4, signature = $5, key_id = $6, published_at = $7 where id = $1`,
          [rev.id, JSON.stringify(def), env.payload, env.digest, env.signature, env.keyId, now],
        );
        const u = await q.query<DefinitionRow>('update challenge_definitions set published_revision = $2, title = $3, updated_at = $4 where id = $1 returning *', [d.id, rev.revision, def.title, now]);
        const members = d.circle_id ? (await q.query<{ user_id: string }>('select user_id from circle_members where circle_id = $1 and left_at is null', [d.circle_id])).rows.map((m) => m.user_id) : [];
        await appendSync(q, [user.id, ...members], 'challenge', d.id);
        await audit(q, { actorUserId: user.id, actorKind: 'user', action: 'challenge.published', targetKind: 'challenge_revision', targetId: rev.id });
        return definitionDto(q, u.rows[0]!);
      }),
  }),

  route({
    method: 'DELETE',
    url: '/v1/challenges/:id',
    operationId: 'archiveChallenge',
    summary: 'Archive a challenge. Routines that already installed it keep their local copy.',
    tags: ['challenges'],
    auth: 'user',
    params: IdParams,
    response: Type.Object({ ok: Type.Boolean() }),
    handler: async ({ params, user, deps, now }) => {
      const r = await deps.db.query('update challenge_definitions set archived_at = coalesce(archived_at, $3) where id = $1 and creator_id = $2', [params.id, user.id, now]);
      if (r.rowCount === 0) throw notFound('challenge');
      return { ok: true };
    },
  }),

  route({
    method: 'GET',
    url: '/v1/challenge-revisions/:id',
    operationId: 'downloadChallengeRevision',
    summary: 'Signed revision payload for offline installation (verify the signature, then parse the payload).',
    tags: ['challenges'],
    auth: 'user',
    params: IdParams,
    response: Type.Object({ revisionId: Type.String(), payload: Type.String(), signature: Type.String(), keyId: Type.String(), digest: Type.String(), alg: Type.String() }),
    handler: async ({ params, user, deps }) => {
      if (!(await canDownloadRevision(deps.db, params.id, user.id))) throw notFound('challenge');
      const r = await deps.db.query<RevisionRow>(`select * from challenge_revisions where id = $1 and state = 'published'`, [params.id]);
      const rev = r.rows[0];
      if (!rev) throw notFound('challenge');
      return { revisionId: rev.id, payload: rev.payload!, signature: rev.signature!, keyId: rev.key_id!, digest: rev.digest!, alg: 'ES256' };
    },
  }),

  route({
    method: 'GET',
    url: '/v1/challenge-revisions/:id/preview',
    operationId: 'previewChallengeRevision',
    summary: 'Answer-free summary: steps, duration, permissions, verification and escape.',
    tags: ['challenges'],
    auth: 'user',
    params: IdParams,
    response: Type.Unknown(),
    handler: async ({ params, user, deps }) => {
      const r = await deps.db.query<RevisionRow>(`select * from challenge_revisions where id = $1 and state = 'published'`, [params.id]);
      const rev = r.rows[0];
      if (!rev) throw notFound('challenge');
      const pending = await deps.db.query(
        `select 1 from alarm_plan_versions v join alarm_plans p on p.id = v.plan_id where v.challenge_revision_id = $1 and p.owner_id = $2`,
        [params.id, user.id],
      );
      if (pending.rowCount === 0 && !(await canDownloadRevision(deps.db, params.id, user.id))) throw notFound('challenge');
      return recipientPreview(rev.body);
    },
  }),

  // ------------------------------------------------------------ AI authoring
  route({
    method: 'POST',
    url: '/v1/challenges/generations',
    operationId: 'createChallengeGeneration',
    summary: 'Ask the AI author to draft a challenge from your description and facts. Uses one credit.',
    description: 'Returns 202; poll the generation. The result is an editable draft, never an active restriction. Quiz answers come only from the facts you supply.',
    tags: ['challenges'],
    auth: 'user',
    rateLimit: { max: 10, timeWindow: '1 hour' },
    body: Obj({
      prompt: Type.String({ minLength: 1, maxLength: 500 }),
      facts: Type.Optional(Type.Array(Fact, { maxItems: 8 })),
      difficulty: Type.Optional(Type.Union([Type.Literal('easy'), Type.Literal('medium'), Type.Literal('hard')])),
    }),
    response: GenerationDto,
    status: 202,
    handler: async ({ body, user, deps, now }) => {
      if (!deps.ai || !(await deps.flags.isEnabled('ai_generation'))) throw unavailable('ai_unavailable', 'Challenge drafting is unavailable right now. You can still build a quiz yourself.');
      const facts = (body.facts ?? []).map((f) => ({ question: f.question.trim(), answer: f.answer.trim(), distractors: (f.distractors ?? []).map((x) => x.trim()) }));
      const findings = [scanText(body.prompt, '/prompt', false), ...facts.flatMap((f, i) => [scanText(f.question, `/facts/${i}/question`, false), scanText(f.answer, `/facts/${i}/answer`, false), ...f.distractors.map((d, j) => scanText(d, `/facts/${i}/distractors/${j}`, false))])].flat();
      if (findings.length > 0) throw new AppError(422, 'request_unsafe', findings[0]!.message, { details: { safety: findings } });
      return deps.db.tx(async (q) => {
        const g = await q.query<{ id: string; created_at: Date }>(
          `insert into challenge_generations (user_id, status, prompt, facts, difficulty, created_at) values ($1, 'queued', $2, $3::jsonb, $4, $5) returning id, created_at`,
          [user.id, body.prompt, JSON.stringify(facts.map((f, i) => ({ id: `f${i + 1}`, ...f }))), body.difficulty ?? 'easy', now],
        );
        const id = g.rows[0]!.id;
        const bucket = await spendCredit(q, user.id, id, now);
        if (!bucket) throw new AppError(402, 'credits_exhausted', 'You have used your AI creations for now. You can build a quiz yourself, or Plus includes 10 a month.');
        await enqueue(q, { kind: 'generate_challenge', dedupeKey: `gen:${id}`, payload: { generationId: id }, maxAttempts: 4 });
        const bal = await creditBalance(q, user.id, now);
        return { id, status: 'queued', difficulty: body.difficulty ?? 'easy', definitionId: null, rejection: null, errorCode: null, creditsRemaining: bal.available, createdAt: g.rows[0]!.created_at.toISOString(), completedAt: null };
      });
    },
  }),

  route({
    method: 'GET',
    url: '/v1/challenges/generations/:id',
    operationId: 'getChallengeGeneration',
    summary: 'Status of an AI draft request.',
    tags: ['challenges'],
    auth: 'user',
    params: IdParams,
    response: GenerationDto,
    handler: async ({ params, user, deps, now }) => {
      const r = await deps.db.query<{ id: string; status: string; difficulty: string; result_definition_id: string | null; rejection: unknown; error_code: string | null; created_at: Date; completed_at: Date | null }>(
        'select * from challenge_generations where id = $1 and user_id = $2',
        [params.id, user.id],
      );
      const g = r.rows[0];
      if (!g) throw notFound('generation');
      const bal = await creditBalance(deps.db, user.id, now);
      return { id: g.id, status: g.status, difficulty: g.difficulty, definitionId: g.result_definition_id, rejection: g.rejection ?? null, errorCode: g.error_code, creditsRemaining: bal.available, createdAt: g.created_at.toISOString(), completedAt: iso(g.completed_at) };
    },
  }),

  // --------------------------------------------------------------- sessions
  route({
    method: 'POST',
    url: '/v1/challenge-sessions',
    operationId: 'startChallengeSession',
    summary: 'Recipient device starts a challenge attempt (alarm, request, focus unlock or practice).',
    tags: ['challenges'],
    auth: 'device',
    body: Obj({
      revisionId: Uuid,
      contextKind: Type.Union([Type.Literal('alarm'), Type.Literal('command'), Type.Literal('practice'), Type.Literal('focus')]),
      contextId: Type.Optional(Type.String({ maxLength: 64 })),
    }),
    response: Type.Object({ id: Type.String(), state: Type.String(), startedAt: Instant }),
    status: 201,
    handler: async ({ body, user, device, deps, now }) => {
      if (!(await canDownloadRevision(deps.db, body.revisionId, user.id))) throw notFound('challenge');
      if (body.contextKind === 'command') {
        const c = await deps.db.query('select 1 from commands where id = $1::uuid and target_id = $2', [body.contextId ?? '00000000-0000-0000-0000-000000000000', user.id]);
        if (c.rowCount === 0) throw notFound('request');
      }
      const r = await deps.db.query<{ id: string; started_at: Date }>(
        `insert into challenge_sessions (revision_id, assignee_id, device_id, context_kind, context_id, state, started_at) values ($1, $2, $3, $4, $5, 'started', $6) returning id, started_at`,
        [body.revisionId, user.id, device!.id, body.contextKind, body.contextId ?? null, now],
      );
      return { id: r.rows[0]!.id, state: 'started', startedAt: r.rows[0]!.started_at.toISOString() };
    },
  }),

  route({
    method: 'POST',
    url: '/v1/challenge-sessions/:id/events',
    operationId: 'endChallengeSession',
    summary: 'Report completion, bypass (safe escape) or expiry. Completion is evidence, not proof of being awake.',
    tags: ['challenges'],
    auth: 'device',
    params: IdParams,
    body: Obj({ kind: Type.Union([Type.Literal('completed'), Type.Literal('bypassed'), Type.Literal('expired')]), clientTime: Type.Optional(Instant) }),
    response: Type.Object({ id: Type.String(), state: Type.String(), evidenceTier: Nullable(Type.String()) }),
    handler: async ({ params, body, user, device, deps, now }) =>
      deps.db.tx(async (q) => {
        const r = await q.query<{ id: string; assignee_id: string; state: string; revision_id: string; context_kind: string; context_id: string | null; evidence_tier: string | null }>(
          'select * from challenge_sessions where id = $1 for update',
          [params.id],
        );
        const s = r.rows[0];
        if (!s || s.assignee_id !== user.id) throw notFound('session');
        if (s.state !== 'started') return { id: s.id, state: s.state, evidenceTier: s.evidence_tier };
        const rev = await q.query<{ body: ChallengeDefinition }>('select body from challenge_revisions where id = $1', [s.revision_id]);
        const tier = body.kind === 'completed' ? (rev.rows[0]?.body.verification ?? 'self_report') : null;
        const u = await q.query<{ state: string; evidence_tier: string | null }>(
          `update challenge_sessions set state = $2, evidence_tier = $3, ended_at = $4 where id = $1 returning state, evidence_tier`,
          [s.id, body.kind, tier, now],
        );
        if (s.context_kind === 'command' && s.context_id && body.kind !== 'expired') {
          const kind = body.kind === 'completed' ? 'challenge_completed' : 'bypassed';
          const cmd = await q.query<{ id: string; actor_id: string; target_id: string }>('select id, actor_id, target_id from commands where id = $1::uuid and target_id = $2 for update', [s.context_id, user.id]);
          if (cmd.rows[0]) {
            await q.query(
              `insert into command_events (command_id, kind, actor_user_id, device_id) values ($1, $2, $3, $4) on conflict (command_id, kind, device_id) where device_id is not null do nothing`,
              [cmd.rows[0].id, kind, user.id, device!.id],
            );
            await recomputeDelivery(q, cmd.rows[0].id, now);
            await appendSync(q, [cmd.rows[0].actor_id, cmd.rows[0].target_id], 'command', cmd.rows[0].id);
            await enqueue(q, { kind: 'notify_outcome', dedupeKey: `outcome:${cmd.rows[0].id}:${kind}`, payload: { commandId: cmd.rows[0].id }, maxAttempts: 3 });
          }
        }
        return { id: s.id, state: u.rows[0]!.state, evidenceTier: u.rows[0]!.evidence_tier };
      }),
  }),
];

