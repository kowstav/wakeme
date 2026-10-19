import type { Db, Queryable } from '../../db/db.ts';
import type { ObjectStorage } from '../../storage/s3.ts';
import type { AuthAdmin } from './auth-admin.ts';
import { revokeAllOf } from '../grants/revoke.ts';
import { appendSync, audit } from './repo.ts';

/**
 * Immediate deactivation: nothing can be sent to or from this account any
 * more. Personal alarms already installed on the user's phone are local data
 * the app removes on sign-out; the server cannot ring or stop them.
 */
export async function deactivateAccount(q: Queryable, userId: string, now: Date): Promise<void> {
  await q.query(
    `update users set account_state = 'deletion_pending', deletion_requested_at = coalesce(deletion_requested_at, $2), primary_device_id = null, updated_at = $2 where id = $1`,
    [userId, now],
  );
  await revokeAllOf(q, userId, 'account_deleted', now);
  const cmds = await q.query<{ id: string; actor_id: string; target_id: string }>(
    `update commands set lifecycle = 'cancelled', updated_at = $2 where (actor_id = $1 or target_id = $1) and lifecycle = 'accepted' returning id, actor_id, target_id`,
    [userId, now],
  );
  for (const c of cmds.rows) {
    await q.query(`insert into command_events (command_id, kind, reason) values ($1, 'cancelled', 'account_deleted')`, [c.id]);
    await appendSync(q, [c.actor_id, c.target_id], 'command', c.id);
  }
  const plans = await q.query<{ id: string; owner_id: string; creator_id: string }>(
    `update alarm_plans set state = 'cancelled', cancelled_reason = 'account_deleted', updated_at = $2 where (owner_id = $1 or creator_id = $1) and state <> 'cancelled' returning id, owner_id, creator_id`,
    [userId, now],
  );
  for (const p of plans.rows) await appendSync(q, [p.owner_id, p.creator_id], 'alarm_plan', p.id);
  await q.query(`update location_sessions set ended_at = $2 where owner_id = $1 and ended_at is null`, [userId, now]);
  await q.query(`update location_session_viewers set revoked_at = $2 where viewer_id = $1 and revoked_at is null`, [userId, now]);
  await q.query(`update invites set revoked_at = $2 where creator_id = $1 and revoked_at is null`, [userId, now]);
  await q.query(`update devices set revoked_at = coalesce(revoked_at, $2), push_token = null where user_id = $1`, [userId, now]);
}

/** Permanent deletion after the grace period (worker). Idempotent. */
export async function hardDeleteAccount(db: Db, deps: { storage: ObjectStorage | null; authAdmin: AuthAdmin | null }, userId: string): Promise<'deleted' | 'skipped'> {
  const u = await db.query<{ auth_subject: string; account_state: string }>('select auth_subject, account_state from users where id = $1', [userId]);
  const user = u.rows[0];
  if (!user) return 'skipped';
  if (user.account_state !== 'deletion_pending') return 'skipped';

  const media = await db.query<{ object_key: string }>('select object_key from media_assets where owner_id = $1', [userId]);
  if (deps.storage) for (const m of media.rows) await deps.storage.deleteObject(m.object_key);
  if (deps.authAdmin) await deps.authAdmin.deleteUser(user.auth_subject);

  await db.tx(async (q) => {
    // Messages the user sent are erased; the conversation keeps a "deleted message" placeholder for others.
    await q.query(`update messages set body = null, deleted_at = coalesce(deleted_at, now()) where sender_id = $1`, [userId]);
    await audit(q, { actorUserId: null, actorKind: 'system', action: 'account.deleted', targetKind: 'user', targetId: userId });
    await q.query('delete from users where id = $1', [userId]);
  });
  return 'deleted';
}
