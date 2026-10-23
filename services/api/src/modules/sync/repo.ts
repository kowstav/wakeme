import type { Queryable } from '../../db/db.ts';

/**
 * The newest position a user's feed can be at: the latest event, or the
 * floor (set by retention or after a restore) when that is higher. Cursors
 * handed to phones never exceed it.
 */
export async function feedPosition(q: Queryable, userId: string): Promise<{ head: number; floor: number | null }> {
  const r = await q.query<{ head: string | number | null; floor: string | number | null }>(
    'select (select max(seq) from sync_events where user_id = $1) as head, (select floor_seq from sync_floor where user_id = $1) as floor',
    [userId],
  );
  const floor = r.rows[0]?.floor == null ? null : Number(r.rows[0].floor);
  return { head: Math.max(Number(r.rows[0]?.head ?? 0), floor ?? 0), floor };
}

/**
 * Run once after restoring the database to an earlier point
 * (docs/runbooks/restore.md). Phones may hold cursors past the restored
 * sequence and state the server no longer has. The sequence jumps past every
 * cursor issued before the incident and every user's floor moves there, so
 * each phone re-fetches its lists on its next sync and then continues.
 */
export async function afterRestore(q: Queryable, gap = 1_000_000_000): Promise<number> {
  const r = await q.query<{ floor: string | number }>(
    `select setval(s, greatest(coalesce((select max(seq) from sync_events), 0), coalesce(pg_sequence_last_value(s::regclass), 0)) + $1) as floor
       from pg_get_serial_sequence('sync_events', 'seq') as s`,
    [gap],
  );
  const floor = Number(r.rows[0]!.floor);
  await q.query(
    `insert into sync_floor (user_id, floor_seq) select id, $1 from users
     on conflict (user_id) do update set floor_seq = greatest(sync_floor.floor_seq, excluded.floor_seq)`,
    [floor],
  );
  return floor;
}
