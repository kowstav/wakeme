import Type from 'typebox';
import { notFound, unprocessable } from '../../http/errors.ts';
import { IdParams, Instant, Nullable, Obj, iso, route, type RouteDef } from '../../http/route.ts';
import type { Queryable } from '../../db/db.ts';
import { appendSync, enqueue } from '../identity/repo.ts';
import { blockedSet } from '../relationships/repo.ts';

export const REACTIONS = ['👍', '❤️', '😂', '🦆', '🙌', '😴', '☀️'] as const;

interface Access {
  id: string;
  kind: 'direct' | 'circle';
  members: string[];
  title: string;
}

/**
 * Membership is re-checked on every read and write: a direct conversation
 * requires an accepted (not blocked) friendship, a circle conversation an
 * active membership. Blocked users' messages are hidden from the blocker.
 */
async function access(q: Queryable, conversationId: string, userId: string): Promise<Access | null> {
  const r = await q.query<{ id: string; kind: 'direct' | 'circle'; user_low: string | null; user_high: string | null; circle_id: string | null }>('select * from conversations where id = $1', [conversationId]);
  const c = r.rows[0];
  if (!c) return null;
  if (c.kind === 'direct') {
    if (c.user_low !== userId && c.user_high !== userId) return null;
    const f = await q.query<{ state: string }>('select state from friendships where user_low = $1 and user_high = $2', [c.user_low, c.user_high]);
    if (f.rows[0]?.state !== 'accepted') return null;
    const other = c.user_low === userId ? c.user_high! : c.user_low!;
    const name = await q.query<{ display_name: string }>('select display_name from users where id = $1', [other]);
    return { id: c.id, kind: 'direct', members: [c.user_low!, c.user_high!], title: name.rows[0]?.display_name ?? 'Friend' };
  }
  const m = await q.query<{ user_id: string }>(
    `select m.user_id from circle_members m join circles ci on ci.id = m.circle_id where m.circle_id = $1 and m.left_at is null and ci.archived_at is null`,
    [c.circle_id],
  );
  const members = m.rows.map((x) => x.user_id);
  if (!members.includes(userId)) return null;
  const name = await q.query<{ name: string }>('select name from circles where id = $1', [c.circle_id]);
  return { id: c.id, kind: 'circle', members, title: name.rows[0]?.name ?? 'Circle' };
}

const MessageDto = Type.Object({
  id: Type.String(),
  conversationId: Type.String(),
  senderId: Nullable(Type.String()),
  body: Nullable(Type.String({ description: 'null when deleted or removed by moderation' })),
  clientId: Type.String(),
  state: Type.String(),
  reactions: Type.Array(Type.Object({ emoji: Type.String(), userId: Type.String() })),
  createdAt: Instant,
});

interface MessageRow {
  id: string;
  conversation_id: string;
  sender_id: string | null;
  body: string | null;
  client_id: string;
  moderation_state: string;
  created_at: Date;
  deleted_at: Date | null;
}

async function messageDtos(q: Queryable, rows: MessageRow[]): Promise<Type.Static<typeof MessageDto>[]> {
  const reactions = rows.length
    ? (await q.query<{ message_id: string; emoji: string; user_id: string }>('select message_id, emoji, user_id from message_reactions where message_id = any($1::uuid[])', [rows.map((r) => r.id)])).rows
    : [];
  return rows.map((m) => ({
    id: m.id,
    conversationId: m.conversation_id,
    senderId: m.sender_id,
    body: m.deleted_at || m.moderation_state === 'removed' ? null : m.body,
    clientId: m.client_id,
    state: m.moderation_state === 'removed' ? 'removed' : m.deleted_at ? 'deleted' : 'visible',
    reactions: reactions.filter((r) => r.message_id === m.id).map((r) => ({ emoji: r.emoji, userId: r.user_id })),
    createdAt: m.created_at.toISOString(),
  }));
}

export const conversationRoutes: RouteDef[] = [
  route({
    method: 'GET',
    url: '/v1/conversations',
    operationId: 'listConversations',
    summary: 'Private conversations with friends and circles.',
    tags: ['conversations'],
    auth: 'user',
    response: Type.Object({
      conversations: Type.Array(Type.Object({ id: Type.String(), kind: Type.String(), title: Type.String(), lastMessageAt: Nullable(Instant), unread: Type.Integer() })),
    }),
    handler: async ({ user, deps }) => {
      const r = await deps.db.query<{ id: string }>(
        `select c.id from conversations c
          left join friendships f on c.kind = 'direct' and f.user_low = c.user_low and f.user_high = c.user_high
          left join circle_members m on c.kind = 'circle' and m.circle_id = c.circle_id and m.user_id = $1 and m.left_at is null
         where (c.kind = 'direct' and (c.user_low = $1 or c.user_high = $1) and f.state = 'accepted') or (c.kind = 'circle' and m.user_id is not null)`,
        [user.id],
      );
      const out = [];
      for (const row of r.rows) {
        const a = await access(deps.db, row.id, user.id);
        if (!a) continue;
        const stats = await deps.db.query<{ last: Date | null; unread: number | string }>(
          `select max(created_at) as last,
                  count(*) filter (where created_at > coalesce((select read_at from conversation_reads where conversation_id = $1 and user_id = $2), 'epoch') and sender_id <> $2) as unread
             from messages where conversation_id = $1 and deleted_at is null`,
          [row.id, user.id],
        );
        out.push({ id: a.id, kind: a.kind, title: a.title, lastMessageAt: iso(stats.rows[0]?.last ?? null), unread: Number(stats.rows[0]?.unread ?? 0) });
      }
      out.sort((x, y) => (y.lastMessageAt ?? '').localeCompare(x.lastMessageAt ?? ''));
      return { conversations: out };
    },
  }),

  route({
    method: 'GET',
    url: '/v1/conversations/:id/messages',
    operationId: 'listMessages',
    summary: 'Messages, newest first.',
    tags: ['conversations'],
    auth: 'user',
    params: IdParams,
    query: Obj({ before: Type.Optional(Instant), limit: Type.Optional(Type.Integer({ minimum: 1, maximum: 100 })) }),
    response: Type.Object({ messages: Type.Array(MessageDto) }),
    handler: async ({ params, query, user, deps }) => {
      if (!(await access(deps.db, params.id, user.id))) throw notFound('conversation');
      const blocked = [...(await blockedSet(deps.db, user.id))];
      const r = await deps.db.query<MessageRow>(
        `select * from messages where conversation_id = $1 and ($2::timestamptz is null or created_at < $2)
           and (sender_id is null or not (sender_id = any($3::uuid[]))) order by created_at desc limit $4`,
        [params.id, query.before ?? null, blocked, query.limit ?? 50],
      );
      return { messages: await messageDtos(deps.db, r.rows) };
    },
  }),

  route({
    method: 'POST',
    url: '/v1/conversations/:id/messages',
    operationId: 'sendMessage',
    summary: 'Send a text message. Push notifications never include the message text.',
    tags: ['conversations'],
    auth: 'user',
    rateLimit: { max: 120, timeWindow: '1 minute' },
    params: IdParams,
    body: Obj({ body: Type.String({ minLength: 1, maxLength: 2000 }), clientId: Type.String({ minLength: 8, maxLength: 64, pattern: '^[A-Za-z0-9_-]+$' }) }),
    response: MessageDto,
    status: 201,
    handler: async ({ params, body, user, deps, now }) =>
      deps.db.tx(async (q) => {
        const a = await access(q, params.id, user.id);
        if (!a) throw notFound('conversation');
        const text = body.body.trim();
        if (!text) throw unprocessable('empty_message', 'Write something first.');
        const r = await q.query<MessageRow>(
          `insert into messages (conversation_id, sender_id, body, client_id, created_at) values ($1, $2, $3, $4, $5)
           on conflict (conversation_id, sender_id, client_id) do nothing returning *`,
          [a.id, user.id, text, body.clientId, now],
        );
        const m = r.rows[0] ?? (await q.query<MessageRow>('select * from messages where conversation_id = $1 and sender_id = $2 and client_id = $3', [a.id, user.id, body.clientId])).rows[0]!;
        if (r.rows[0]) {
          const blockedByOthers = await blockedSet(q, user.id);
          const recipients = a.members.filter((u) => u !== user.id && !blockedByOthers.has(u));
          await appendSync(q, a.members, 'conversation', a.id);
          for (const to of recipients) {
            await enqueue(q, { kind: 'notify_generic', dedupeKey: `msg:${m.id}:${to}`, payload: { userId: to, template: a.kind === 'direct' ? 'message_direct' : 'message_circle', actorId: user.id, conversationId: a.id, circleTitle: a.kind === 'circle' ? a.title : null }, maxAttempts: 3 });
          }
        }
        return (await messageDtos(q, [m]))[0]!;
      }),
  }),

  route({
    method: 'POST',
    url: '/v1/conversations/:id/read',
    operationId: 'markConversationRead',
    summary: 'Mark a conversation read.',
    tags: ['conversations'],
    auth: 'user',
    params: IdParams,
    response: Type.Object({ ok: Type.Boolean() }),
    handler: async ({ params, user, deps, now }) => {
      if (!(await access(deps.db, params.id, user.id))) throw notFound('conversation');
      await deps.db.query(
        `insert into conversation_reads (conversation_id, user_id, read_at) values ($1, $2, $3) on conflict (conversation_id, user_id) do update set read_at = greatest(conversation_reads.read_at, excluded.read_at)`,
        [params.id, user.id, now],
      );
      return { ok: true };
    },
  }),

  route({
    method: 'POST',
    url: '/v1/messages/:id/reactions',
    operationId: 'reactToMessage',
    summary: 'React with one of a small set of emoji (replaces your previous reaction).',
    tags: ['conversations'],
    auth: 'user',
    params: IdParams,
    body: Obj({ emoji: Type.Union(REACTIONS.map((e) => Type.Literal(e))) }),
    response: Type.Object({ ok: Type.Boolean() }),
    handler: async ({ params, body, user, deps, now }) =>
      deps.db.tx(async (q) => {
        const m = (await q.query<{ conversation_id: string }>('select conversation_id from messages where id = $1 and deleted_at is null', [params.id])).rows[0];
        const a = m ? await access(q, m.conversation_id, user.id) : null;
        if (!m || !a) throw notFound('message');
        await q.query(
          `insert into message_reactions (message_id, user_id, emoji, created_at) values ($1, $2, $3, $4) on conflict (message_id, user_id) do update set emoji = excluded.emoji, created_at = excluded.created_at`,
          [params.id, user.id, body.emoji, now],
        );
        await appendSync(q, a.members, 'conversation', a.id);
        return { ok: true };
      }),
  }),

  route({
    method: 'DELETE',
    url: '/v1/messages/:id/reactions',
    operationId: 'removeReaction',
    summary: 'Remove your reaction.',
    tags: ['conversations'],
    auth: 'user',
    params: IdParams,
    response: Type.Object({ ok: Type.Boolean() }),
    handler: async ({ params, user, deps }) => {
      await deps.db.query('delete from message_reactions where message_id = $1 and user_id = $2', [params.id, user.id]);
      return { ok: true };
    },
  }),

  route({
    method: 'DELETE',
    url: '/v1/messages/:id',
    operationId: 'deleteMessage',
    summary: 'Delete a message you sent (text is erased).',
    tags: ['conversations'],
    auth: 'user',
    params: IdParams,
    response: Type.Object({ ok: Type.Boolean() }),
    handler: async ({ params, user, deps, now }) =>
      deps.db.tx(async (q) => {
        const r = await q.query<{ conversation_id: string }>(`update messages set body = null, deleted_at = coalesce(deleted_at, $3) where id = $1 and sender_id = $2 returning conversation_id`, [params.id, user.id, now]);
        if (!r.rows[0]) throw notFound('message');
        const a = await access(q, r.rows[0].conversation_id, user.id);
        if (a) await appendSync(q, a.members, 'conversation', a.id);
        return { ok: true };
      }),
  }),
];

export { access as conversationAccess };
