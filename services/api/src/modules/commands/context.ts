import {
  addDays,
  localParts,
  zonedToInstant,
  type CommandCapability,
  type CommandCounts,
  type WindowCount,
} from '@wakeme/policy';
import type { Queryable } from '../../db/db.ts';

function windowCount(row: { n: number | string; oldest: Date | null } | undefined): WindowCount {
  return { count: Number(row?.n ?? 0), oldestAt: row?.oldest ? row.oldest.toISOString() : null };
}

/** Start of the recipient's current local day as an instant (DST-aware). */
export function localMidnight(now: Date, timeZone: string): Date {
  const p = localParts(now, timeZone);
  const today = { year: p.year, month: p.month, day: p.day };
  const t = zonedToInstant(today, 0, 0, timeZone);
  return t.getTime() > now.getTime() ? zonedToInstant(addDays(today, -1), 0, 0, timeZone) : t;
}

/**
 * Counts used by the policy engine. Must run inside the transaction that
 * holds the recipient's row lock so concurrent senders are serialized and an
 * aggregate budget cannot be exceeded by racing requests.
 */
export async function loadCounts(q: Queryable, actorId: string, targetId: string, capability: CommandCapability, targetTimeZone: string, now: Date): Promise<CommandCounts> {
  const hourAgo = new Date(now.getTime() - 3600_000);
  const midnight = localMidnight(now, targetTimeZone);
  const pairHour = await q.query<{ n: number | string; oldest: Date | null }>(
    `select count(*) as n, min(created_at) as oldest from commands where actor_id = $1 and target_id = $2 and capability = $3 and created_at > $4`,
    [actorId, targetId, capability, hourAgo],
  );
  const pairToday = await q.query<{ n: number | string }>(
    `select count(*) as n from commands where actor_id = $1 and target_id = $2 and capability = $3 and created_at >= $4`,
    [actorId, targetId, capability, midnight],
  );
  const audible = await q.query<{ n: number | string; oldest: Date | null }>(
    `select count(*) as n, min(created_at) as oldest from commands
      where target_id = $1 and capability in ('nudge.send', 'alarm.request') and delivery_mode = 'audible' and created_at > $2`,
    [targetId, hourAgo],
  );
  const senderAll = await q.query<{ n: number | string; oldest: Date | null }>(
    `select count(*) as n, min(created_at) as oldest from commands where actor_id = $1 and created_at > $2`,
    [actorId, hourAgo],
  );
  return {
    senderToTargetLastHour: windowCount(pairHour.rows[0]),
    senderToTargetToday: Number(pairToday.rows[0]?.n ?? 0),
    targetAudibleLastHour: windowCount(audible.rows[0]),
    senderAllLastHour: windowCount(senderAll.rows[0]),
  };
}
