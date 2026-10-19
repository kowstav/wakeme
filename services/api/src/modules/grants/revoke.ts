import type { Queryable } from '../../db/db.ts';
import { appendSync, enqueue } from '../identity/repo.ts';

export type RevokeReason = 'grantor_revoked' | 'blocked' | 'friend_removed' | 'account_deleted' | 'moderation';

/**
 * Revocation is applied server-side atomically: live grants are revoked and
 * versioned, queued commands relying on them are cancelled, and routines a
 * friend created under them stop. A sync hint asks affected phones to update
 * their local grant caches; until they do, the phone's local check still
 * uses its cached copy, which is why revocation also happens on-device first.
 */
export async function revokeGrants(q: Queryable, grantIds: readonly string[], reason: RevokeReason, actorId: string | null, now: Date): Promise<number> {
  if (grantIds.length === 0) return 0;
  const r = await q.query<{ id: string; grantor_id: string; grantee_id: string; version: number }>(
    `update grants set revoked_at = $2, revoked_reason = $3, version = version + 1, updated_at = $2
      where id = any($1::uuid[]) and revoked_at is null
      returning id, grantor_id, grantee_id, version`,
    [grantIds, now, reason],
  );
  for (const g of r.rows) {
    await q.query(
      `insert into grant_versions (grant_id, version, capabilities, limits, valid_from, valid_until, paused, revoked, changed_by)
       select id, version, capabilities, limits, valid_from, valid_until, paused, true, $2 from grants where id = $1`,
      [g.id, actorId],
    );
    await cancelOpenCommandsForGrant(q, g.id, now);
    // Routines the grantee created for the grantor under this grant stop; the grantor's own routines are untouched.
    const plans = await q.query<{ id: string; owner_id: string; creator_id: string }>(
      `update alarm_plans set state = 'cancelled', cancelled_reason = 'grant_revoked', updated_at = $3
        where owner_id = $1 and creator_id = $2 and creator_id <> owner_id and state <> 'cancelled'
        returning id, owner_id, creator_id`,
      [g.grantor_id, g.grantee_id, now],
    );
    for (const p of plans.rows) await appendSync(q, [p.owner_id, p.creator_id], 'alarm_plan', p.id);
    await appendSync(q, [g.grantor_id, g.grantee_id], 'grant', g.id);
    await enqueueSyncHint(q, [g.grantor_id, g.grantee_id], `grant:${g.id}:${g.version}`);
  }
  return r.rowCount;
}

export async function cancelOpenCommandsForGrant(q: Queryable, grantId: string, now: Date): Promise<void> {
  const r = await q.query<{ id: string; actor_id: string; target_id: string }>(
    `update commands set lifecycle = 'cancelled', updated_at = $2
      where grant_id = $1 and lifecycle = 'accepted' and expires_at > $2
      returning id, actor_id, target_id`,
    [grantId, now],
  );
  for (const c of r.rows) {
    await q.query(`insert into command_events (command_id, kind, reason) values ($1, 'cancelled', 'grant_revoked')`, [c.id]);
    await appendSync(q, [c.actor_id, c.target_id], 'command', c.id);
  }
}

export async function revokeAllBetween(q: Queryable, a: string, b: string, reason: RevokeReason, actorId: string | null, now: Date): Promise<number> {
  const r = await q.query<{ id: string }>(
    `select id from grants where revoked_at is null and ((grantor_id = $1 and grantee_id = $2) or (grantor_id = $2 and grantee_id = $1)) for update`,
    [a, b],
  );
  return revokeGrants(q, r.rows.map((x) => x.id), reason, actorId, now);
}

export async function revokeAllOf(q: Queryable, userId: string, reason: RevokeReason, now: Date): Promise<number> {
  const r = await q.query<{ id: string }>(`select id from grants where revoked_at is null and (grantor_id = $1 or grantee_id = $1) for update`, [userId]);
  return revokeGrants(q, r.rows.map((x) => x.id), reason, null, now);
}

/** Content-free background push telling the users' phones to sync now. */
export async function enqueueSyncHint(q: Queryable, userIds: readonly string[], key: string): Promise<void> {
  for (const userId of new Set(userIds)) {
    await enqueue(q, { kind: 'sync_hint', dedupeKey: `sync_hint:${userId}:${key}`, payload: { userId }, maxAttempts: 3 });
  }
}
