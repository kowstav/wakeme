import type { Queryable } from '../../db/db.ts';

export interface FriendshipRow {
  id: string;
  user_low: string;
  user_high: string;
  state: 'accepted' | 'blocked';
  blocked_by: string | null;
  created_at: Date;
  updated_at: Date;
}

export function pair(a: string, b: string): [string, string] {
  return a < b ? [a, b] : [b, a];
}

export function otherUser(f: FriendshipRow, me: string): string {
  return f.user_low === me ? f.user_high : f.user_low;
}

export async function friendshipBetween(q: Queryable, a: string, b: string, lock = false): Promise<FriendshipRow | null> {
  const [lo, hi] = pair(a, b);
  const r = await q.query<FriendshipRow>(`select * from friendships where user_low = $1 and user_high = $2${lock ? ' for update' : ''}`, [lo, hi]);
  return r.rows[0] ?? null;
}

/** Friendship visible to `me`: accepted, or blocked by me. A friendship that blocked me is invisible. */
export async function friendshipForUser(q: Queryable, id: string, me: string, lock = false): Promise<FriendshipRow | null> {
  const r = await q.query<FriendshipRow>(`select * from friendships where id = $1 and (user_low = $2 or user_high = $2)${lock ? ' for update' : ''}`, [id, me]);
  const f = r.rows[0];
  if (!f) return null;
  if (f.state === 'blocked' && f.blocked_by !== me) return null;
  return f;
}

export async function areFriends(q: Queryable, a: string, b: string): Promise<boolean> {
  const f = await friendshipBetween(q, a, b);
  return f?.state === 'accepted';
}

export async function ensureDirectConversation(q: Queryable, a: string, b: string): Promise<string> {
  const [lo, hi] = pair(a, b);
  const r = await q.query<{ id: string }>(
    `insert into conversations (kind, user_low, user_high) values ('direct', $1, $2)
     on conflict (user_low, user_high) do update set user_low = excluded.user_low returning id`,
    [lo, hi],
  );
  return r.rows[0]!.id;
}

export async function activeCircleMember(q: Queryable, circleId: string, userId: string): Promise<{ role: 'owner' | 'admin' | 'member' } | null> {
  const r = await q.query<{ role: 'owner' | 'admin' | 'member' }>(
    `select m.role from circle_members m join circles c on c.id = m.circle_id
      where m.circle_id = $1 and m.user_id = $2 and m.left_at is null and c.archived_at is null`,
    [circleId, userId],
  );
  return r.rows[0] ?? null;
}

/** Users who blocked `me` or whom `me` blocked. */
export async function blockedSet(q: Queryable, me: string): Promise<Set<string>> {
  const r = await q.query<{ other: string }>(
    `select case when user_low = $1 then user_high else user_low end as other from friendships where state = 'blocked' and (user_low = $1 or user_high = $1)`,
    [me],
  );
  return new Set(r.rows.map((x) => x.other));
}
