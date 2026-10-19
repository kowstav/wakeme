import type { ChallengeDefinition } from '@wakeme/challenges';
import { deriveFacts } from '@wakeme/challenges';
import type { ChallengeRef } from '@wakeme/policy';
import type { Queryable } from '../../db/db.ts';

export interface RevisionRow {
  id: string;
  definition_id: string;
  revision: number;
  state: 'draft' | 'published';
  body: ChallengeDefinition;
  origin: 'manual' | 'template' | 'ai';
  generation_id: string | null;
  previewed_at: Date | null;
  payload: string | null;
  digest: string | null;
  signature: string | null;
  key_id: string | null;
  created_at: Date;
  published_at: Date | null;
}

export interface DefinitionRow {
  id: string;
  creator_id: string;
  circle_id: string | null;
  title: string;
  latest_revision: number;
  published_revision: number | null;
  archived_at: Date | null;
  created_at: Date;
  updated_at: Date;
}

/** Creator, or an active member of the circle the challenge is shared with. */
export async function canUseDefinition(q: Queryable, def: DefinitionRow, userId: string): Promise<boolean> {
  if (def.archived_at) return false;
  if (def.creator_id === userId) return true;
  if (!def.circle_id) return false;
  const r = await q.query('select 1 from circle_members where circle_id = $1 and user_id = $2 and left_at is null', [def.circle_id, userId]);
  return r.rowCount > 0;
}

/** A published revision the actor may assign to someone else. */
export async function assignableRevision(q: Queryable, revisionId: string, actorId: string): Promise<{ revision: RevisionRow; definition: DefinitionRow } | null> {
  const r = await q.query<RevisionRow & { d_creator: string }>(`select * from challenge_revisions where id = $1 and state = 'published'`, [revisionId]);
  const revision = r.rows[0];
  if (!revision) return null;
  const d = await q.query<DefinitionRow>('select * from challenge_definitions where id = $1', [revision.definition_id]);
  const definition = d.rows[0];
  if (!definition || !(await canUseDefinition(q, definition, actorId))) return null;
  return { revision, definition };
}

export function challengeRef(def: ChallengeDefinition): ChallengeRef {
  const f = deriveFacts(def);
  return { stepTypes: f.stepTypes, maxDurationSeconds: def.maxDurationSeconds, difficulty: def.difficulty };
}

/**
 * Whether `userId` may download a published revision: they created it, share
 * its circle, or it is assigned to them by an accepted routine or a command.
 */
export async function canDownloadRevision(q: Queryable, revisionId: string, userId: string): Promise<boolean> {
  const r = await q.query<{ ok: boolean }>(
    `select exists (
        select 1 from challenge_revisions r join challenge_definitions d on d.id = r.definition_id
         where r.id = $1 and r.state = 'published' and (d.creator_id = $2 or (d.circle_id is not null and exists (
           select 1 from circle_members m where m.circle_id = d.circle_id and m.user_id = $2 and m.left_at is null)))
      ) or exists (
        select 1 from alarm_plan_versions v join alarm_plans p on p.id = v.plan_id
         where v.challenge_revision_id = $1 and p.owner_id = $2 and p.state <> 'cancelled'
      ) or exists (
        select 1 from commands c where c.target_id = $2 and c.payload->>'challengeRevisionId' = $1::text
      ) as ok`,
    [revisionId, userId],
  );
  return r.rows[0]?.ok === true;
}
