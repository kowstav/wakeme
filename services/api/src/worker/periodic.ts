import type { Deps } from '../deps.ts';
import { recomputeDelivery } from '../modules/commands/routes.ts';
import { appendSync, enqueue } from '../modules/identity/repo.ts';
import { syncEntitlement } from '../modules/billing/routes.ts';

// Retention follows the engineering blueprint's proposals, pending legal
// review: minimal command metadata 30 days, latest location only and only
// until the session ends, sync feed 45 days, audit records 365 days. What
// people typed to draft an AI challenge is removed after 30 days; the token
// and cost record stays for credit accounting.
export const RETENTION = {
  commandsDays: 30,
  aiPromptDays: 30,
  syncEventsDays: 45,
  outboxDays: 7,
  inviteAttemptsDays: 7,
  expiredInvitesDays: 30,
  auditDays: 365,
  nonceMinutes: 15,
} as const;

const DAY = 86_400_000;

/** Stands in for a removed AI prompt (the column keeps its length check). */
export const PROMPT_REMOVED = '[removed after retention period]';

export async function expireCommands(deps: Deps): Promise<number> {
  const now = deps.now();
  const due = await deps.db.query<{ id: string; actor_id: string; target_id: string }>(
    `select id, actor_id, target_id from commands where lifecycle = 'accepted' and expires_at < $1 order by expires_at limit 500`,
    [now],
  );
  for (const c of due.rows) {
    await deps.db.tx(async (q) => {
      await q.query(`insert into command_events (command_id, kind, reason) values ($1, 'expired', 'ttl')`, [c.id]);
      await recomputeDelivery(q, c.id, now);
      await appendSync(q, [c.actor_id, c.target_id], 'command', c.id);
    });
  }
  return due.rowCount;
}

export async function endExpiredSafetySessions(deps: Deps): Promise<void> {
  const now = deps.now();
  await deps.db.query('delete from location_samples where expires_at <= $1', [now]);
  await deps.db.query('update location_sessions set ended_at = ends_at where ended_at is null and ends_at <= $1', [now]);
  await deps.db.query('delete from location_samples s using location_sessions l where s.session_id = l.id and l.ended_at is not null', []);
}

/** Notify viewers once when a check-in deadline passes without a check-in. */
export async function missedCheckins(deps: Deps): Promise<number> {
  const now = deps.now();
  const r = await deps.db.query<{ id: string; owner_id: string }>(
    `update location_sessions set missed_notified_at = $1
      where checkin_due_at < $1 and checked_in_at is null and ended_at is null and missed_notified_at is null
      returning id, owner_id`,
    [now],
  );
  for (const s of r.rows) {
    await deps.db.tx(async (q) => {
      const viewers = await q.query<{ viewer_id: string }>('select viewer_id from location_session_viewers where session_id = $1 and revoked_at is null', [s.id]);
      for (const v of viewers.rows) {
        await enqueue(q, { kind: 'notify_generic', dedupeKey: `safety:${s.id}:${v.viewer_id}:missed`, payload: { userId: v.viewer_id, template: 'checkin_missed', actorId: s.owner_id, sessionId: s.id }, maxAttempts: 6 });
      }
      await appendSync(q, [s.owner_id, ...viewers.rows.map((v) => v.viewer_id)], 'location_session', s.id);
    });
  }
  return r.rowCount;
}

export async function retention(deps: Deps): Promise<void> {
  const now = deps.now().getTime();
  const at = (days: number) => new Date(now - days * DAY);
  await deps.db.query('delete from device_nonces where seen_at < $1', [new Date(now - RETENTION.nonceMinutes * 60_000)]);
  await deps.db.query('delete from commands where created_at < $1', [at(RETENTION.commandsDays)]);
  await deps.db.query('delete from outbox where processed_at is not null and processed_at < $1', [at(RETENTION.outboxDays)]);
  await deps.db.query('delete from invite_attempts where attempted_at < $1', [at(RETENTION.inviteAttemptsDays)]);
  await deps.db.query('delete from invites where expires_at < $1', [at(RETENTION.expiredInvitesDays)]);
  await deps.db.query('delete from audit_events where created_at < $1', [at(RETENTION.auditDays)]);
  await deps.db.query('delete from location_access_log where accessed_at < $1', [at(RETENTION.commandsDays)]);
  await deps.db.query(
    `update challenge_generations set prompt = $2, facts = '[]'::jsonb, rejection = null
      where coalesce(completed_at, created_at) < $1 and prompt <> $2`,
    [at(RETENTION.aiPromptDays), PROMPT_REMOVED],
  );
  const cutoff = at(RETENTION.syncEventsDays);
  await deps.db.query(
    `insert into sync_floor (user_id, floor_seq) select user_id, max(seq) from sync_events where created_at < $1 group by user_id
     on conflict (user_id) do update set floor_seq = greatest(sync_floor.floor_seq, excluded.floor_seq)`,
    [cutoff],
  );
  await deps.db.query('delete from sync_events where created_at < $1', [cutoff]);
}

/** Webhooks can be missed; periodically re-verify entitlements near renewal or long unsynced. */
export async function reconcileEntitlements(deps: Deps, limit = 200): Promise<number> {
  if (!deps.revenueCat) return 0;
  const now = deps.now().getTime();
  const r = await deps.db.query<{ user_id: string }>(
    `select user_id from entitlements
      where (expires_at between $1 and $2) or (synced_at < $3 and (expires_at is null or expires_at > $4))
      order by synced_at limit $5`,
    [new Date(now - 3 * DAY), new Date(now + 3 * DAY), new Date(now - DAY), new Date(now), limit],
  );
  let n = 0;
  for (const row of r.rows) {
    try {
      await syncEntitlement(deps, row.user_id);
      n++;
    } catch (e) {
      deps.log.warn({ err: e }, 'entitlement reconciliation failed');
    }
  }
  return n;
}
