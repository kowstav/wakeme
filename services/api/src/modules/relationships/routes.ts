import Type from 'typebox';
import { AppError, conflict, forbidden, notFound, tooMany, unprocessable } from '../../http/errors.ts';
import { IdParams, Instant, Nullable, Obj, Uuid, route, type RouteDef } from '../../http/route.ts';
import type { Queryable } from '../../db/db.ts';
import { normalizeInviteCode, randomCode, randomToken, sha256Hex } from '../../lib/crypto.ts';
import { appendSync, audit, userById, type UserRow } from '../identity/repo.ts';
import { GrantDto, grantDto, liveGrant } from '../grants/repo.ts';
import { revokeAllBetween } from '../grants/revoke.ts';
import { friendActions, FriendAction } from '../commands/availability.ts';
import { activeCircleMember, ensureDirectConversation, friendshipBetween, friendshipForUser, otherUser, pair, type FriendshipRow } from './repo.ts';
import { createReport, ReportReason } from '../moderation/service.ts';

const MAX_ACTIVE_INVITES = 20;
const MAX_FAILED_INVITE_ATTEMPTS_PER_HOUR = 10;

const PublicUser = Type.Object({ id: Type.String(), displayName: Type.String() });

const Friend = Type.Object({
  friendshipId: Type.String(),
  user: PublicUser,
  state: Type.Union([Type.Literal('accepted'), Type.Literal('blocked')]),
  since: Instant,
  conversationId: Nullable(Type.String()),
  /** What this friend allows ME to do on THEIR phone. */
  theyAllowMe: Nullable(GrantDto),
  /** What I allow THIS friend to do on MY phone. */
  iAllowThem: Nullable(GrantDto),
  actions: Type.Array(FriendAction),
});

const Circle = Type.Object({
  id: Type.String(),
  name: Type.String(),
  role: Type.String(),
  memberCount: Type.Integer(),
  conversationId: Type.String(),
  createdAt: Instant,
});

const InviteCreated = Type.Object({
  id: Type.String(),
  kind: Type.String(),
  circleId: Nullable(Type.String()),
  token: Type.String({ description: 'Shown once. Put only this opaque token in the link.' }),
  code: Type.String({ description: 'Shown once. Short recovery code if the link does not open the app.' }),
  url: Type.String(),
  expiresAt: Instant,
  maxUses: Type.Integer(),
});

const InviteRef = Obj({
  token: Type.Optional(Type.String({ minLength: 20, maxLength: 64, pattern: '^[A-Za-z0-9_-]+$' })),
  code: Type.Optional(Type.String({ minLength: 6, maxLength: 16 })),
});

async function friendDto(q: Queryable, f: FriendshipRow, me: UserRow, now: Date, disabled: ReadonlySet<string>): Promise<Type.Static<typeof Friend>> {
  const otherId = otherUser(f, me.id);
  const other = await userById(q, otherId);
  const theirs = f.state === 'accepted' ? await liveGrant(q, otherId, me.id) : null;
  const mine = f.state === 'accepted' ? await liveGrant(q, me.id, otherId) : null;
  const conv = f.state === 'accepted' ? await q.query<{ id: string }>('select id from conversations where user_low = $1 and user_high = $2', [f.user_low, f.user_high]) : null;
  return {
    friendshipId: f.id,
    user: { id: otherId, displayName: other?.display_name ?? 'Wake Me user' },
    state: f.state,
    since: f.created_at.toISOString(),
    conversationId: conv?.rows[0]?.id ?? null,
    theyAllowMe: theirs ? grantDto(theirs) : null,
    iAllowThem: mine ? grantDto(mine) : null,
    actions: f.state === 'accepted' && other ? await friendActions(q, me, other, theirs, now, disabled) : [],
  };
}

/**
 * Runs outside any transaction so a failed guess is recorded even though the
 * request then fails. Redemption re-checks atomically inside its transaction.
 */
async function findInvite(q: Queryable, ref: { token?: string; code?: string }, me: UserRow, now: Date) {
  if (!ref.token === !ref.code) throw unprocessable('invite_ref_required', 'Provide the invite link or the code.');
  const failures = await q.query<{ n: number | string }>(
    `select count(*) as n from invite_attempts where user_id = $1 and success = false and attempted_at > $2`,
    [me.id, new Date(now.getTime() - 3600_000)],
  );
  if (Number(failures.rows[0]?.n ?? 0) >= MAX_FAILED_INVITE_ATTEMPTS_PER_HOUR) throw tooMany('invite_attempts_exceeded', 'Too many invite attempts. Try again in an hour.', 3600);
  const col = ref.token ? 'token_hash' : 'code_hash';
  const value = ref.token ? sha256Hex(ref.token) : sha256Hex(normalizeInviteCode(ref.code!));
  const r = await q.query<{ id: string; creator_id: string; kind: 'friend' | 'circle'; circle_id: string | null; expires_at: Date; max_uses: number; uses: number; revoked_at: Date | null }>(
    `select id, creator_id, kind, circle_id, expires_at, max_uses, uses, revoked_at from invites where ${col} = $1`,
    [value],
  );
  const inv = r.rows[0];
  const creator = inv ? await userById(q, inv.creator_id) : null;
  const blocked = inv && inv.creator_id !== me.id ? (await friendshipBetween(q, inv.creator_id, me.id))?.state === 'blocked' : false;
  const valid = inv && creator && creator.account_state === 'active' && !inv.revoked_at && inv.expires_at > now && inv.uses < inv.max_uses && !blocked;
  await q.query('insert into invite_attempts (user_id, success, attempted_at) values ($1, $2, $3)', [me.id, !!valid, now]);
  // One error for every failure mode so codes and links cannot be enumerated.
  if (!valid || !inv || !creator) throw new AppError(404, 'invite_invalid', 'This invite has expired or is no longer valid. Ask for a new one.');
  return { inv, creator };
}

export const relationshipRoutes: RouteDef[] = [
  route({
    method: 'POST',
    url: '/v1/invites',
    operationId: 'createInvite',
    summary: 'Create a private invite link and short code. Opening it never grants permissions.',
    tags: ['relationships'],
    auth: 'user',
    rateLimit: { max: 30, timeWindow: '1 hour' },
    body: Obj({
      kind: Type.Union([Type.Literal('friend'), Type.Literal('circle')]),
      circleId: Type.Optional(Uuid),
      maxUses: Type.Optional(Type.Integer({ minimum: 1, maximum: 20 })),
      expiresInHours: Type.Optional(Type.Integer({ minimum: 1, maximum: 168 })),
    }),
    response: InviteCreated,
    status: 201,
    handler: async ({ body, user, deps, now }) =>
      deps.db.tx(async (q) => {
        if (body.kind === 'circle') {
          if (!body.circleId) throw unprocessable('circle_required', 'Choose a circle to invite to.');
          const m = await activeCircleMember(q, body.circleId, user.id);
          if (!m) throw notFound('circle');
          if (m.role === 'member') throw forbidden('circle_admin_required', 'Only circle admins can invite people.');
        } else if (body.circleId) {
          throw unprocessable('unexpected_circle', 'Friend invites do not take a circle.');
        }
        const active = await q.query<{ n: number | string }>(
          `select count(*) as n from invites where creator_id = $1 and revoked_at is null and expires_at > $2 and uses < max_uses`,
          [user.id, now],
        );
        if (Number(active.rows[0]?.n ?? 0) >= MAX_ACTIVE_INVITES) throw conflict('too_many_invites', 'You have a lot of open invites. Cancel some first.');
        const token = randomToken(24);
        const code = randomCode(9);
        const expiresAt = new Date(now.getTime() + (body.expiresInHours ?? 72) * 3600_000);
        const maxUses = body.maxUses ?? (body.kind === 'friend' ? 1 : 10);
        const r = await q.query<{ id: string }>(
          `insert into invites (creator_id, kind, circle_id, token_hash, code_hash, expires_at, max_uses) values ($1, $2, $3, $4, $5, $6, $7) returning id`,
          [user.id, body.kind, body.circleId ?? null, sha256Hex(token), sha256Hex(code), expiresAt, maxUses],
        );
        await audit(q, { actorUserId: user.id, actorKind: 'user', action: 'invite.created', targetKind: 'invite', targetId: r.rows[0]!.id, data: { kind: body.kind } });
        return {
          id: r.rows[0]!.id,
          kind: body.kind,
          circleId: body.circleId ?? null,
          token,
          code: `${code.slice(0, 3)}-${code.slice(3, 6)}-${code.slice(6)}`,
          url: `${deps.config.inviteLinkBase}${token}`,
          expiresAt: expiresAt.toISOString(),
          maxUses,
        };
      }),
  }),

  route({
    method: 'GET',
    url: '/v1/invites',
    operationId: 'listInvites',
    summary: 'Your open invites (tokens are never shown again).',
    tags: ['relationships'],
    auth: 'user',
    response: Type.Object({ invites: Type.Array(Type.Object({ id: Type.String(), kind: Type.String(), circleId: Nullable(Type.String()), uses: Type.Integer(), maxUses: Type.Integer(), expiresAt: Instant, createdAt: Instant })) }),
    handler: async ({ user, deps, now }) => {
      const r = await deps.db.query<{ id: string; kind: string; circle_id: string | null; uses: number; max_uses: number; expires_at: Date; created_at: Date }>(
        `select id, kind, circle_id, uses, max_uses, expires_at, created_at from invites where creator_id = $1 and revoked_at is null and expires_at > $2 and uses < max_uses order by created_at desc`,
        [user.id, now],
      );
      return { invites: r.rows.map((i) => ({ id: i.id, kind: i.kind, circleId: i.circle_id, uses: i.uses, maxUses: i.max_uses, expiresAt: i.expires_at.toISOString(), createdAt: i.created_at.toISOString() })) };
    },
  }),

  route({
    method: 'DELETE',
    url: '/v1/invites/:id',
    operationId: 'revokeInvite',
    summary: 'Cancel an invite.',
    tags: ['relationships'],
    auth: 'user',
    params: IdParams,
    response: Type.Object({ ok: Type.Boolean() }),
    handler: async ({ params, user, deps, now }) => {
      const r = await deps.db.query('update invites set revoked_at = coalesce(revoked_at, $3) where id = $1 and creator_id = $2', [params.id, user.id, now]);
      if (r.rowCount === 0) throw notFound('invite');
      return { ok: true };
    },
  }),

  route({
    method: 'POST',
    url: '/v1/invites/preview',
    operationId: 'previewInvite',
    summary: 'Show who sent an invite before accepting it.',
    tags: ['relationships'],
    auth: 'user',
    rateLimit: { max: 30, timeWindow: '1 hour' },
    body: InviteRef,
    response: Type.Object({
      kind: Type.String(),
      inviter: PublicUser,
      circle: Nullable(Type.Object({ name: Type.String(), memberCount: Type.Integer() })),
      expiresAt: Instant,
      ownInvite: Type.Boolean(),
      alreadyConnected: Type.Boolean(),
    }),
    handler: async ({ body, user, deps, now }) => {
      const q = deps.db;
      const { inv, creator } = await findInvite(q, body, user, now);
      let circle = null as null | { name: string; memberCount: number };
      let alreadyConnected = false;
      if (inv.kind === 'circle' && inv.circle_id) {
        const c = await q.query<{ name: string; n: number | string }>(
          `select c.name, (select count(*) from circle_members m where m.circle_id = c.id and m.left_at is null) as n from circles c where c.id = $1`,
          [inv.circle_id],
        );
        circle = { name: c.rows[0]?.name ?? 'Circle', memberCount: Number(c.rows[0]?.n ?? 0) };
        alreadyConnected = !!(await activeCircleMember(q, inv.circle_id, user.id));
      } else {
        alreadyConnected = (await friendshipBetween(q, creator.id, user.id))?.state === 'accepted';
      }
      return { kind: inv.kind, inviter: { id: creator.id, displayName: creator.display_name }, circle, expiresAt: inv.expires_at.toISOString(), ownInvite: creator.id === user.id, alreadyConnected };
    },
  }),

  route({
    method: 'POST',
    url: '/v1/invites/redeem',
    operationId: 'redeemInvite',
    summary: 'Accept an invite. Creates a friendship or circle membership; grants nothing.',
    tags: ['relationships'],
    auth: 'user',
    rateLimit: { max: 30, timeWindow: '1 hour' },
    body: InviteRef,
    response: Type.Object({ kind: Type.String(), friend: Nullable(Friend), circle: Nullable(Circle) }),
    handler: async ({ body, user, deps, now }) => {
      const disabled = await deps.flags.disabled();
      const { inv, creator } = await findInvite(deps.db, body, user, now);
      return deps.db.tx(async (q) => {
        if (creator.id === user.id) throw unprocessable('own_invite', 'This is your own invite. Share it with a friend.');
        const used = await q.query('update invites set uses = uses + 1 where id = $1 and uses < max_uses and revoked_at is null and expires_at > $2', [inv.id, now]);
        if (used.rowCount === 0) throw new AppError(404, 'invite_invalid', 'This invite has expired or is no longer valid. Ask for a new one.');

        if (inv.kind === 'friend') {
          const [lo, hi] = pair(creator.id, user.id);
          await q.query(
            `insert into friendships (user_low, user_high, state, created_via_invite) values ($1, $2, 'accepted', $3) on conflict (user_low, user_high) do nothing`,
            [lo, hi, inv.id],
          );
          const f = (await friendshipBetween(q, creator.id, user.id))!;
          if (f.state !== 'accepted') throw new AppError(404, 'invite_invalid', 'This invite has expired or is no longer valid. Ask for a new one.');
          await ensureDirectConversation(q, creator.id, user.id);
          await appendSync(q, [creator.id, user.id], 'friendship', f.id);
          await audit(q, { actorUserId: user.id, actorKind: 'user', action: 'friendship.created', targetKind: 'friendship', targetId: f.id });
          return { kind: 'friend', friend: await friendDto(q, f, user, now, disabled), circle: null };
        }

        const circleId = inv.circle_id!;
        await q.query(
          `insert into circle_members (circle_id, user_id, role) values ($1, $2, 'member')
           on conflict (circle_id, user_id) do update set left_at = null, joined_at = case when circle_members.left_at is null then circle_members.joined_at else excluded.joined_at end`,
          [circleId, user.id],
        );
        const members = await q.query<{ user_id: string }>('select user_id from circle_members where circle_id = $1 and left_at is null', [circleId]);
        await appendSync(q, members.rows.map((m) => m.user_id), 'circle', circleId);
        const c = await circleDto(q, circleId, user.id);
        return { kind: 'circle', friend: null, circle: c };
      });
    },
  }),

  route({
    method: 'GET',
    url: '/v1/friends',
    operationId: 'listFriends',
    summary: 'Friends, what each allows you to do, what you allow them, and what you can send right now.',
    tags: ['relationships'],
    auth: 'user',
    query: Obj({ includeBlocked: Type.Optional(Type.Boolean()) }),
    response: Type.Object({ friends: Type.Array(Friend) }),
    handler: async ({ query, user, deps, now }) => {
      const disabled = await deps.flags.disabled();
      const r = await deps.db.query<FriendshipRow>(
        `select * from friendships where (user_low = $1 or user_high = $1) and (state = 'accepted' or ($2::boolean and blocked_by = $1)) order by created_at`,
        [user.id, query.includeBlocked === true],
      );
      const friends = [];
      for (const f of r.rows) friends.push(await friendDto(deps.db, f, user, now, disabled));
      return { friends };
    },
  }),

  route({
    method: 'GET',
    url: '/v1/friendships/:id',
    operationId: 'getFriend',
    summary: 'One friend with permissions and available actions.',
    tags: ['relationships'],
    auth: 'user',
    params: IdParams,
    response: Friend,
    handler: async ({ params, user, deps, now }) => {
      const f = await friendshipForUser(deps.db, params.id, user.id);
      if (!f) throw notFound('friend');
      return friendDto(deps.db, f, user, now, await deps.flags.disabled());
    },
  }),

  route({
    method: 'DELETE',
    url: '/v1/friendships/:id',
    operationId: 'removeFriend',
    summary: 'Remove a friend. Revokes permissions in both directions and stops their routines for you.',
    tags: ['relationships'],
    auth: 'user',
    params: IdParams,
    response: Type.Object({ ok: Type.Boolean(), revokedGrants: Type.Integer() }),
    handler: async ({ params, user, deps, now }) =>
      deps.db.tx(async (q) => {
        const f = await friendshipForUser(q, params.id, user.id, true);
        if (!f || f.state !== 'accepted') throw notFound('friend');
        const other = otherUser(f, user.id);
        const revoked = await revokeAllBetween(q, user.id, other, 'friend_removed', user.id, now);
        await q.query(`update location_session_viewers v set revoked_at = $3 from location_sessions s where v.session_id = s.id and v.revoked_at is null and ((s.owner_id = $1 and v.viewer_id = $2) or (s.owner_id = $2 and v.viewer_id = $1))`, [user.id, other, now]);
        await q.query('delete from friendships where id = $1', [f.id]);
        await appendSync(q, [user.id, other], 'friendship', f.id, 'delete');
        await audit(q, { actorUserId: user.id, actorKind: 'user', action: 'friendship.removed', targetKind: 'friendship', targetId: f.id });
        return { ok: true, revokedGrants: revoked };
      }),
  }),

  route({
    method: 'POST',
    url: '/v1/friendships/:id/block',
    operationId: 'blockFriend',
    summary: 'Block (and optionally report). Atomically revokes all permissions both ways and ends shared location access.',
    tags: ['relationships'],
    auth: 'user',
    params: IdParams,
    body: Obj({ report: Type.Optional(Obj({ reason: ReportReason, note: Type.Optional(Type.String({ maxLength: 500 })) })) }),
    response: Type.Object({ ok: Type.Boolean(), revokedGrants: Type.Integer(), reportId: Nullable(Type.String()) }),
    handler: async ({ params, body, user, deps, now }) =>
      deps.db.tx(async (q) => {
        const f = await friendshipForUser(q, params.id, user.id, true);
        if (!f) throw notFound('friend');
        const other = otherUser(f, user.id);
        await q.query(`update friendships set state = 'blocked', blocked_by = $2, updated_at = $3 where id = $1`, [f.id, user.id, now]);
        const revoked = await revokeAllBetween(q, user.id, other, 'blocked', user.id, now);
        await q.query(`update location_session_viewers v set revoked_at = $3 from location_sessions s where v.session_id = s.id and v.revoked_at is null and ((s.owner_id = $1 and v.viewer_id = $2) or (s.owner_id = $2 and v.viewer_id = $1))`, [user.id, other, now]);
        await q.query(`update sound_library set state = 'declined', decided_at = $3 where owner_id = $1 and offered_by = $2 and state = 'offered'`, [user.id, other, now]);
        let reportId: string | null = null;
        if (body?.report) reportId = await createReport(q, { reporterId: user.id, targetUserId: other, targetKind: 'user', targetId: other, reason: body.report.reason, note: body.report.note ?? null, evidence: {} });
        await appendSync(q, [user.id, other], 'friendship', f.id);
        await audit(q, { actorUserId: user.id, actorKind: 'user', action: 'friendship.blocked', targetKind: 'friendship', targetId: f.id });
        return { ok: true, revokedGrants: revoked, reportId };
      }),
  }),

  route({
    method: 'POST',
    url: '/v1/friendships/:id/unblock',
    operationId: 'unblockFriend',
    summary: 'Unblock. Does not restore the friendship or any permission; a new invite is needed.',
    tags: ['relationships'],
    auth: 'user',
    params: IdParams,
    response: Type.Object({ ok: Type.Boolean() }),
    handler: async ({ params, user, deps }) =>
      deps.db.tx(async (q) => {
        const f = await friendshipForUser(q, params.id, user.id, true);
        if (!f || f.state !== 'blocked' || f.blocked_by !== user.id) throw notFound('blocked user');
        await q.query('delete from friendships where id = $1', [f.id]);
        await appendSync(q, [user.id], 'friendship', f.id, 'delete');
        return { ok: true };
      }),
  }),

  // ---------------------------------------------------------------- circles
  route({
    method: 'POST',
    url: '/v1/circles',
    operationId: 'createCircle',
    summary: 'Create a private circle. Membership never grants phone permissions.',
    tags: ['circles'],
    auth: 'user',
    rateLimit: { max: 20, timeWindow: '1 day' },
    body: Obj({ name: Type.String({ minLength: 1, maxLength: 40 }) }),
    response: Circle,
    status: 201,
    handler: async ({ body, user, deps }) =>
      deps.db.tx(async (q) => {
        const name = body.name.trim();
        if (!name) throw unprocessable('invalid_name', 'Give your circle a name.');
        const c = await q.query<{ id: string }>('insert into circles (name, created_by) values ($1, $2) returning id', [name, user.id]);
        const id = c.rows[0]!.id;
        await q.query(`insert into circle_members (circle_id, user_id, role) values ($1, $2, 'owner')`, [id, user.id]);
        await q.query(`insert into conversations (kind, circle_id) values ('circle', $1)`, [id]);
        await appendSync(q, [user.id], 'circle', id);
        return circleDto(q, id, user.id);
      }),
  }),

  route({
    method: 'GET',
    url: '/v1/circles',
    operationId: 'listCircles',
    summary: 'Circles you belong to.',
    tags: ['circles'],
    auth: 'user',
    response: Type.Object({ circles: Type.Array(Circle) }),
    handler: async ({ user, deps }) => {
      const r = await deps.db.query<{ circle_id: string }>(
        `select m.circle_id from circle_members m join circles c on c.id = m.circle_id where m.user_id = $1 and m.left_at is null and c.archived_at is null order by m.joined_at`,
        [user.id],
      );
      const circles = [];
      for (const row of r.rows) circles.push(await circleDto(deps.db, row.circle_id, user.id));
      return { circles };
    },
  }),

  route({
    method: 'GET',
    url: '/v1/circles/:id',
    operationId: 'getCircle',
    summary: 'Circle details and members.',
    tags: ['circles'],
    auth: 'user',
    params: IdParams,
    response: Type.Object({ circle: Circle, members: Type.Array(Type.Object({ user: PublicUser, role: Type.String(), isFriend: Type.Boolean(), joinedAt: Instant })) }),
    handler: async ({ params, user, deps }) => {
      if (!(await activeCircleMember(deps.db, params.id, user.id))) throw notFound('circle');
      const r = await deps.db.query<{ user_id: string; role: string; joined_at: Date; display_name: string; friend: boolean }>(
        `select m.user_id, m.role, m.joined_at, u.display_name,
                exists (select 1 from friendships f where f.state = 'accepted' and ((f.user_low = $2 and f.user_high = m.user_id) or (f.user_high = $2 and f.user_low = m.user_id))) as friend
           from circle_members m join users u on u.id = m.user_id
          where m.circle_id = $1 and m.left_at is null and u.account_state = 'active' order by m.joined_at`,
        [params.id, user.id],
      );
      return {
        circle: await circleDto(deps.db, params.id, user.id),
        members: r.rows.map((m) => ({ user: { id: m.user_id, displayName: m.display_name }, role: m.role, isFriend: m.friend, joinedAt: m.joined_at.toISOString() })),
      };
    },
  }),

  route({
    method: 'PATCH',
    url: '/v1/circles/:id',
    operationId: 'renameCircle',
    summary: 'Rename a circle (owner or admin).',
    tags: ['circles'],
    auth: 'user',
    params: IdParams,
    body: Obj({ name: Type.String({ minLength: 1, maxLength: 40 }) }),
    response: Circle,
    handler: async ({ params, body, user, deps }) =>
      deps.db.tx(async (q) => {
        const m = await activeCircleMember(q, params.id, user.id);
        if (!m) throw notFound('circle');
        if (m.role === 'member') throw forbidden('circle_admin_required', 'Only circle admins can rename the circle.');
        await q.query('update circles set name = $2 where id = $1', [params.id, body.name.trim()]);
        const members = await q.query<{ user_id: string }>('select user_id from circle_members where circle_id = $1 and left_at is null', [params.id]);
        await appendSync(q, members.rows.map((x) => x.user_id), 'circle', params.id);
        return circleDto(q, params.id, user.id);
      }),
  }),

  route({
    method: 'POST',
    url: '/v1/circles/:id/leave',
    operationId: 'leaveCircle',
    summary: 'Leave a circle. Ownership passes to the longest-standing admin or member.',
    tags: ['circles'],
    auth: 'user',
    params: IdParams,
    response: Type.Object({ ok: Type.Boolean() }),
    handler: async ({ params, user, deps, now }) =>
      deps.db.tx(async (q) => {
        const m = await activeCircleMember(q, params.id, user.id);
        if (!m) throw notFound('circle');
        await q.query('update circle_members set left_at = $3 where circle_id = $1 and user_id = $2', [params.id, user.id, now]);
        if (m.role === 'owner') {
          const next = await q.query<{ user_id: string }>(
            `select user_id from circle_members where circle_id = $1 and left_at is null order by (role = 'admin') desc, joined_at limit 1`,
            [params.id],
          );
          if (next.rows[0]) await q.query(`update circle_members set role = 'owner' where circle_id = $1 and user_id = $2`, [params.id, next.rows[0].user_id]);
          else await q.query('update circles set archived_at = $2 where id = $1', [params.id, now]);
        }
        const members = await q.query<{ user_id: string }>('select user_id from circle_members where circle_id = $1', [params.id]);
        await appendSync(q, members.rows.map((x) => x.user_id), 'circle', params.id);
        return { ok: true };
      }),
  }),

  route({
    method: 'DELETE',
    url: '/v1/circles/:id/members/:userId',
    operationId: 'removeCircleMember',
    summary: 'Remove a member (owner or admin).',
    tags: ['circles'],
    auth: 'user',
    params: Obj({ id: Uuid, userId: Uuid }),
    response: Type.Object({ ok: Type.Boolean() }),
    handler: async ({ params, user, deps, now }) =>
      deps.db.tx(async (q) => {
        const me = await activeCircleMember(q, params.id, user.id);
        if (!me) throw notFound('circle');
        if (me.role === 'member') throw forbidden('circle_admin_required', 'Only circle admins can remove members.');
        const target = await activeCircleMember(q, params.id, params.userId);
        if (!target) throw notFound('member');
        if (target.role === 'owner') throw forbidden('cannot_remove_owner', 'The owner cannot be removed.');
        await q.query('update circle_members set left_at = $3 where circle_id = $1 and user_id = $2', [params.id, params.userId, now]);
        const members = await q.query<{ user_id: string }>('select user_id from circle_members where circle_id = $1', [params.id]);
        await appendSync(q, members.rows.map((x) => x.user_id), 'circle', params.id);
        return { ok: true };
      }),
  }),
];

async function circleDto(q: Queryable, circleId: string, me: string): Promise<Type.Static<typeof Circle>> {
  const r = await q.query<{ id: string; name: string; created_at: Date; role: string; n: number | string; conv: string }>(
    `select c.id, c.name, c.created_at, m.role,
            (select count(*) from circle_members x where x.circle_id = c.id and x.left_at is null) as n,
            (select id from conversations v where v.circle_id = c.id) as conv
       from circles c join circle_members m on m.circle_id = c.id and m.user_id = $2
      where c.id = $1`,
    [circleId, me],
  );
  const c = r.rows[0];
  if (!c) throw notFound('circle');
  return { id: c.id, name: c.name, role: c.role, memberCount: Number(c.n), conversationId: c.conv, createdAt: c.created_at.toISOString() };
}

