import Type from 'typebox';
import {
  DENY_HTTP_STATUS,
  DEVICE_RECEIPT_KINDS,
  EXPIRY_BOUNDS,
  displayStatus,
  evaluateCommand,
  reduceDelivery,
  type CommandCapability,
  type CommandRequest,
  type DeliveryEvent,
  type DeliveryEventKind,
} from '@wakeme/policy';
import { canonicalJson } from '@wakeme/challenges';
import { AppError, DENY_MESSAGES, forbidden, notFound, unprocessable } from '../../http/errors.ts';
import { IdParams, Instant, Nullable, Obj, Uuid, route, type RouteDef } from '../../http/route.ts';
import type { Queryable } from '../../db/db.ts';
import { sha256Hex } from '../../lib/crypto.ts';
import { appendSync, deviceReadiness, enqueue, isActive, primaryDevice, recipientSettings, userById } from '../identity/repo.ts';
import { liveGrant, toSnapshot } from '../grants/repo.ts';
import { friendshipBetween } from '../relationships/repo.ts';
import { assignableRevision, challengeRef } from '../challenges/repo.ts';
import { loadCounts } from './context.ts';

export interface CommandRow {
  id: string;
  actor_id: string;
  target_id: string;
  capability: CommandCapability;
  grant_id: string;
  grant_version: number;
  payload: { soundId?: string; ringSeconds?: number; challengeRevisionId?: string; minutes?: number; goal?: string };
  payload_hash: string;
  idempotency_key: string;
  delivery_mode: 'audible' | 'notify' | 'silent';
  target_device_id: string | null;
  expires_at: Date;
  lifecycle: 'accepted' | 'expired' | 'cancelled';
  transport: 'queued' | 'provider_accepted' | 'provider_rejected' | 'not_sent';
  device_state: 'unknown' | 'received' | 'installed' | 'execution_reported';
  human_state: 'none' | 'opened' | 'acknowledged' | 'challenge_completed' | 'bypassed';
  refused_reason: string | null;
  created_at: Date;
  updated_at: Date;
}

export const CommandDto = Type.Object({
  id: Type.String(),
  capability: Type.String(),
  actor: Type.Object({ id: Type.String(), displayName: Type.String() }),
  target: Type.Object({ id: Type.String(), displayName: Type.String() }),
  grantVersion: Type.Integer(),
  payload: Type.Object({
    soundId: Type.Optional(Type.String()),
    ringSeconds: Type.Optional(Type.Integer()),
    challengeRevisionId: Type.Optional(Type.String()),
    minutes: Type.Optional(Type.Integer()),
    goal: Type.Optional(Type.String()),
  }),
  deliveryMode: Type.String(),
  expiresAt: Instant,
  createdAt: Instant,
  state: Type.Object({ lifecycle: Type.String(), transport: Type.String(), device: Type.String(), human: Type.String(), refusedReason: Nullable(Type.String()) }),
  /** Literal evidence for the sender: "sent" is not "heard", "challenge_completed" is not "awake". */
  displayStatus: Type.String(),
});

export async function commandDto(q: Queryable, c: CommandRow, now: Date): Promise<Type.Static<typeof CommandDto>> {
  const names = await q.query<{ id: string; display_name: string; share_bypass: boolean }>('select id, display_name, share_bypass from users where id = any($1::uuid[])', [[c.actor_id, c.target_id]]);
  const byId = new Map(names.rows.map((r) => [r.id, r]));
  const target = byId.get(c.target_id);
  const state = { lifecycle: c.lifecycle, transport: c.transport, device: c.device_state, human: c.human_state, refusedReason: c.refused_reason };
  return {
    id: c.id,
    capability: c.capability,
    actor: { id: c.actor_id, displayName: byId.get(c.actor_id)?.display_name ?? 'Deleted account' },
    target: { id: c.target_id, displayName: target?.display_name ?? 'Deleted account' },
    grantVersion: c.grant_version,
    payload: c.payload,
    deliveryMode: c.delivery_mode,
    expiresAt: c.expires_at.toISOString(),
    createdAt: c.created_at.toISOString(),
    state,
    displayStatus: displayStatus(state, {
      now,
      sentAt: c.created_at,
      kind: c.capability === 'alarm.request' ? 'alarm' : c.capability === 'nudge.send' ? 'nudge' : 'other',
      shareBypass: target?.share_bypass ?? false,
    }),
  };
}

const CreateCommand = Obj({
  targetUserId: Uuid,
  capability: Type.Union([
    Type.Literal('nudge.send'),
    Type.Literal('alarm.request'),
    Type.Literal('focus.request'),
    Type.Literal('safety.checkin.request'),
    Type.Literal('location.request'),
  ]),
  idempotencyKey: Type.String({ minLength: 8, maxLength: 64, pattern: '^[A-Za-z0-9_-]+$' }),
  expectedGrantVersion: Type.Optional(Type.Integer({ minimum: 1 })),
  expiresInSeconds: Type.Optional(Type.Integer({ minimum: 30, maximum: 21_600 })),
  soundId: Type.Optional(Type.String({ maxLength: 64 })),
  ringSeconds: Type.Optional(Type.Integer({ minimum: 10, maximum: 300 })),
  challengeRevisionId: Type.Optional(Uuid),
  minutes: Type.Optional(Type.Integer({ minimum: 1, maximum: 120 })),
  goal: Type.Optional(Type.String({ minLength: 1, maxLength: 80 })),
});

/** Rebuilds the materialized delivery state from all events (precedence reduction). */
export async function recomputeDelivery(q: Queryable, commandId: string, now: Date): Promise<CommandRow> {
  const ev = await q.query<{ kind: DeliveryEventKind; seq: number | string; reason: string | null }>('select kind, seq, reason from command_events where command_id = $1 order by seq', [commandId]);
  const events: DeliveryEvent[] = ev.rows.map((e) => (e.reason ? { kind: e.kind, seq: Number(e.seq), reason: e.reason } : { kind: e.kind, seq: Number(e.seq) }));
  const s = reduceDelivery(events);
  const r = await q.query<CommandRow>(
    `update commands set lifecycle = $2, transport = $3, device_state = $4, human_state = $5, refused_reason = $6, updated_at = $7 where id = $1 returning *`,
    [commandId, s.lifecycle, s.transport, s.device, s.human, s.refusedReason, now],
  );
  return r.rows[0]!;
}

export const commandRoutes: RouteDef[] = [
  route({
    method: 'POST',
    url: '/v1/commands',
    operationId: 'createCommand',
    summary: 'Send a quack, wake request, focus request, check-in or location request to a friend.',
    description:
      'Authorized against the recipient-owned grant, recipient settings, device readiness, kill switches and aggregate rate limits in one transaction. Idempotent per idempotencyKey. Success means accepted for delivery, not that a phone rang.',
    tags: ['commands'],
    auth: 'device',
    rateLimit: { max: 60, timeWindow: '1 minute' },
    body: CreateCommand,
    response: CommandDto,
    status: 201,
    handler: async ({ body, user, device, deps, now }) => {
      const disabled = await deps.flags.disabled();
      const cap = body.capability;
      const expiresInSeconds = body.expiresInSeconds ?? EXPIRY_BOUNDS[cap].default;
      const payload: CommandRow['payload'] = {};
      if (cap === 'nudge.send' || cap === 'alarm.request') {
        if (!body.soundId) throw unprocessable('sound_required', 'Choose a sound.');
        payload.soundId = body.soundId;
      }
      if (cap === 'alarm.request') payload.ringSeconds = body.ringSeconds ?? 60;
      if (cap === 'focus.request') {
        if (!body.minutes) throw unprocessable('minutes_required', 'Choose how long to focus.');
        payload.minutes = body.minutes;
        if (body.goal) payload.goal = body.goal.trim();
      }
      if (body.challengeRevisionId) {
        if (cap !== 'alarm.request' && cap !== 'focus.request') throw unprocessable('challenge_not_applicable', 'Only wake and focus requests can include a challenge.');
        payload.challengeRevisionId = body.challengeRevisionId;
      }
      const payloadHash = sha256Hex(canonicalJson({ cap, target: body.targetUserId, expiresInSeconds, payload }));

      return deps.db.tx(async (q) => {
        const prior = await q.query<CommandRow>('select * from commands where actor_id = $1 and idempotency_key = $2', [user.id, body.idempotencyKey]);
        if (prior.rows[0]) {
          if (prior.rows[0].payload_hash !== payloadHash) throw unprocessable('idempotency_conflict', 'This request id was already used for a different request.');
          return commandDto(q, prior.rows[0], now);
        }
        if (body.targetUserId === user.id) throw new AppError(422, 'self_target', DENY_MESSAGES['self_target']!);
        // Serialize all senders to this recipient so aggregate limits hold under concurrency.
        const target = await userById(q, body.targetUserId, true);
        const friendship = target ? await friendshipBetween(q, user.id, target.id) : null;
        if (!target || !friendship) throw new AppError(404, 'not_found', DENY_MESSAGES['not_found']!);
        const grant = await liveGrant(q, target.id, user.id, true);
        const devRow = await primaryDevice(q, target);

        let request: CommandRequest;
        switch (cap) {
          case 'nudge.send':
            request = { capability: cap, soundId: payload.soundId!, expiresInSeconds };
            break;
          case 'alarm.request':
          case 'focus.request': {
            let challenge;
            if (payload.challengeRevisionId) {
              const a = await assignableRevision(q, payload.challengeRevisionId, user.id);
              if (!a) throw notFound('challenge');
              challenge = challengeRef(a.revision.body);
            }
            request =
              cap === 'alarm.request'
                ? { capability: cap, soundId: payload.soundId!, ringSeconds: payload.ringSeconds!, expiresInSeconds, ...(challenge ? { challenge } : {}) }
                : { capability: cap, minutes: payload.minutes!, expiresInSeconds, ...(challenge ? { challenge } : {}) };
            break;
          }
          default:
            request = { capability: cap, expiresInSeconds };
        }

        const decision = evaluateCommand({
          now,
          actor: { id: user.id, active: isActive(user) },
          target: { id: target.id, active: isActive(target), timeZone: target.time_zone, settings: recipientSettings(target) },
          friendship: { state: friendship.state },
          grant: grant ? toSnapshot(grant) : null,
          ...(body.expectedGrantVersion !== undefined ? { expectedGrantVersion: body.expectedGrantVersion } : {}),
          request,
          counts: await loadCounts(q, user.id, target.id, cap, target.time_zone, now),
          disabledFeatures: disabled,
          device: deviceReadiness(devRow),
        });
        if (!decision.allowed) {
          throw new AppError(DENY_HTTP_STATUS[decision.reason], decision.reason, DENY_MESSAGES[decision.reason] ?? 'Not allowed.', decision.retryAfterSeconds !== undefined ? { retryAfterSeconds: decision.retryAfterSeconds } : {});
        }

        const r = await q.query<CommandRow>(
          `insert into commands (actor_id, target_id, capability, grant_id, grant_version, payload, payload_hash, idempotency_key, delivery_mode, target_device_id, expires_at, created_at, updated_at)
           values ($1, $2, $3, $4, $5, $6::jsonb, $7, $8, $9, $10, $11, $12, $12) returning *`,
          [user.id, target.id, cap, grant!.id, grant!.version, JSON.stringify(payload), payloadHash, body.idempotencyKey, decision.deliveryMode, devRow?.id ?? null, decision.expiresAt, now],
        );
        const cmd = r.rows[0]!;
        await q.query(`insert into command_events (command_id, kind, actor_user_id, device_id) values ($1, 'accepted', $2, null), ($1, 'queued', null, null)`, [cmd.id, user.id]);
        await q.query('update grants set last_used_at = $2 where id = $1', [grant!.id, now]);
        await enqueue(q, { kind: 'dispatch_command', dedupeKey: `cmd:${cmd.id}:dispatch`, payload: { commandId: cmd.id }, maxAttempts: 6 });
        await appendSync(q, [user.id, target.id], 'command', cmd.id);
        void device;
        return commandDto(q, cmd, now);
      });
    },
  }),

  route({
    method: 'GET',
    url: '/v1/commands/:id',
    operationId: 'getCommand',
    summary: 'Delivery evidence for a request (sender or recipient only).',
    tags: ['commands'],
    auth: 'user',
    params: IdParams,
    response: CommandDto,
    handler: async ({ params, user, deps, now }) => {
      const r = await deps.db.query<CommandRow>('select * from commands where id = $1 and (actor_id = $2 or target_id = $2)', [params.id, user.id]);
      if (!r.rows[0]) throw notFound('request');
      return commandDto(deps.db, r.rows[0], now);
    },
  }),

  route({
    method: 'GET',
    url: '/v1/commands',
    operationId: 'listCommands',
    summary: 'Recent requests you sent or received.',
    tags: ['commands'],
    auth: 'user',
    query: Obj({
      direction: Type.Union([Type.Literal('sent'), Type.Literal('received')]),
      friendId: Type.Optional(Uuid),
      limit: Type.Optional(Type.Integer({ minimum: 1, maximum: 50 })),
      before: Type.Optional(Instant),
    }),
    response: Type.Object({ commands: Type.Array(CommandDto) }),
    handler: async ({ query, user, deps, now }) => {
      const mine = query.direction === 'sent' ? 'actor_id' : 'target_id';
      const theirs = query.direction === 'sent' ? 'target_id' : 'actor_id';
      const r = await deps.db.query<CommandRow>(
        `select * from commands where ${mine} = $1 and ($2::uuid is null or ${theirs} = $2) and ($3::timestamptz is null or created_at < $3)
          order by created_at desc limit $4`,
        [user.id, query.friendId ?? null, query.before ?? null, query.limit ?? 20],
      );
      const commands = [];
      for (const c of r.rows) commands.push(await commandDto(deps.db, c, now));
      return { commands };
    },
  }),

  route({
    method: 'POST',
    url: '/v1/commands/:id/receipts',
    operationId: 'postCommandReceipt',
    summary: "Recipient device reports what actually happened. Signed by the recipient's registered device.",
    tags: ['commands'],
    auth: 'device',
    params: IdParams,
    body: Obj({
      kind: Type.Union(DEVICE_RECEIPT_KINDS.map((k) => Type.Literal(k))),
      clientTime: Type.Optional(Instant),
      reason: Type.Optional(Type.Union(['duplicate', 'expired', 'not_authorized_locally', 'needs_sync', 'outside_allowed_window', 'quiet_hours', 'recipient_paused', 'os_scheduling_failed', 'audio_focus_denied', 'permission_missing'].map((k) => Type.Literal(k)))),
    }),
    response: CommandDto,
    handler: async ({ params, body, user, device, deps, now }) =>
      deps.db.tx(async (q) => {
        const r = await q.query<CommandRow>('select * from commands where id = $1 for update', [params.id]);
        const cmd = r.rows[0];
        // Only the recipient's devices can report receipts; anyone else learns nothing.
        if (!cmd || cmd.target_id !== user.id) throw notFound('request');
        if (body.kind === 'execution_refused' && !body.reason) throw unprocessable('reason_required', 'Refusals need a reason.');
        const ins = await q.query(
          `insert into command_events (command_id, kind, actor_user_id, device_id, reason, client_time) values ($1, $2, $3, $4, $5, $6)
           on conflict (command_id, kind, device_id) where device_id is not null do nothing`,
          [cmd.id, body.kind, user.id, device!.id, body.reason ?? null, body.clientTime ?? null],
        );
        if (ins.rowCount === 0) return commandDto(q, cmd, now);
        const updated = await recomputeDelivery(q, cmd.id, now);
        await appendSync(q, [cmd.actor_id, cmd.target_id], 'command', cmd.id);
        if (updated.human_state !== cmd.human_state && (updated.human_state === 'acknowledged' || updated.human_state === 'challenge_completed' || updated.human_state === 'bypassed')) {
          await enqueue(q, { kind: 'notify_outcome', dedupeKey: `outcome:${cmd.id}:${updated.human_state}`, payload: { commandId: cmd.id }, maxAttempts: 3 });
        }
        return commandDto(q, updated, now);
      }),
  }),

  route({
    method: 'POST',
    url: '/v1/commands/:id/cancel',
    operationId: 'cancelCommand',
    summary: 'Sender cancels a pending request. A notification the OS already showed cannot be recalled.',
    tags: ['commands'],
    auth: 'user',
    params: IdParams,
    response: CommandDto,
    handler: async ({ params, user, deps, now }) =>
      deps.db.tx(async (q) => {
        const r = await q.query<CommandRow>('select * from commands where id = $1 for update', [params.id]);
        const cmd = r.rows[0];
        if (!cmd || (cmd.actor_id !== user.id && cmd.target_id !== user.id)) throw notFound('request');
        if (cmd.actor_id !== user.id) throw forbidden('not_sender', 'Only the sender can cancel this request.');
        if (cmd.lifecycle !== 'accepted') return commandDto(q, cmd, now);
        await q.query(`insert into command_events (command_id, kind, actor_user_id, reason) values ($1, 'cancelled', $2, 'sender_cancelled')`, [cmd.id, user.id]);
        const updated = await recomputeDelivery(q, cmd.id, now);
        await appendSync(q, [cmd.actor_id, cmd.target_id], 'command', cmd.id);
        await enqueue(q, { kind: 'sync_hint', dedupeKey: `sync_hint:${cmd.target_id}:cancel:${cmd.id}`, payload: { userId: cmd.target_id }, maxAttempts: 3 });
        return commandDto(q, updated, now);
      }),
  }),
];

