import Type from 'typebox';
import { isValidTimeZone } from '@wakeme/policy';
import { requireRecentAuth } from '../../http/auth.ts';
import { AppError, conflict, unprocessable } from '../../http/errors.ts';
import { HhMm, Instant, Nullable, Obj, iso, route, type RouteDef } from '../../http/route.ts';
import { creditBalance, grantIntroCredits, ensureMonthlyCredits, planFor, PLAN_LIMITS } from '../billing/service.ts';
import { appendSync, audit, enqueue, userBySubject, type UserRow } from './repo.ts';
import type { Queryable } from '../../db/db.ts';
import { exportAccount } from './export.ts';
import { deactivateAccount } from './deletion.ts';

export const DELETION_GRACE_DAYS = 7;

const Settings = Type.Object({
  quietHours: Type.Object({ enabled: Type.Boolean(), start: Type.String(), end: Type.String() }),
  pausedUntil: Nullable(Instant),
  soundBudgetPerHour: Type.Integer(),
  shareBypass: Type.Boolean(),
});

export const Me = Type.Object({
  id: Type.String(),
  displayName: Type.String(),
  locale: Type.String(),
  timeZone: Type.String(),
  accountState: Type.String(),
  deletionScheduledFor: Nullable(Instant),
  primaryDeviceId: Nullable(Type.String()),
  settings: Settings,
  billing: Type.Object({
    plan: Type.Union([Type.Literal('free'), Type.Literal('plus')]),
    status: Type.String(),
    expiresAt: Nullable(Instant),
    willRenew: Nullable(Type.Boolean()),
    store: Nullable(Type.String()),
    credits: Type.Object({ available: Type.Integer(), introRemaining: Type.Integer(), monthlyRemaining: Type.Integer(), monthlyResetsAt: Nullable(Instant) }),
  }),
  limits: Type.Object({
    activeRoutines: Type.Object({ used: Type.Integer(), max: Type.Integer() }),
    customSounds: Type.Object({ used: Type.Integer(), max: Type.Integer() }),
  }),
  createdAt: Instant,
});

export async function meDto(q: Queryable, u: UserRow, now: Date): Promise<Type.Static<typeof Me>> {
  await ensureMonthlyCredits(q, u.id, now);
  const plan = await planFor(q, u.id, now);
  const credits = await creditBalance(q, u.id, now);
  const counts = await q.query<{ routines: number | string; sounds: number | string }>(
    `select (select count(*) from alarm_plans where owner_id = $1 and state in ('active', 'paused', 'awaiting_acceptance')) as routines,
            (select count(*) from media_assets where owner_id = $1 and state in ('uploaded', 'clean', 'pending_upload')) as sounds`,
    [u.id],
  );
  const limits = PLAN_LIMITS[plan.plan];
  return {
    id: u.id,
    displayName: u.display_name,
    locale: u.locale,
    timeZone: u.time_zone,
    accountState: u.account_state,
    deletionScheduledFor: u.deletion_requested_at ? new Date(u.deletion_requested_at.getTime() + DELETION_GRACE_DAYS * 86_400_000).toISOString() : null,
    primaryDeviceId: u.primary_device_id,
    settings: {
      quietHours: { enabled: u.quiet_enabled, start: u.quiet_start, end: u.quiet_end },
      pausedUntil: iso(u.paused_until),
      soundBudgetPerHour: u.sound_budget_per_hour,
      shareBypass: u.share_bypass,
    },
    billing: {
      plan: plan.plan,
      status: plan.status,
      expiresAt: iso(plan.entitlement?.expires_at),
      willRenew: plan.entitlement?.will_renew ?? null,
      store: plan.entitlement?.store ?? null,
      credits,
    },
    limits: {
      activeRoutines: { used: Number(counts.rows[0]?.routines ?? 0), max: limits.activeRoutines },
      customSounds: { used: Number(counts.rows[0]?.sounds ?? 0), max: limits.customSounds },
    },
    createdAt: u.created_at.toISOString(),
  };
}

export const identityRoutes: RouteDef[] = [
  route({
    method: 'POST',
    url: '/v1/account',
    operationId: 'createAccount',
    summary: 'Create the Wake Me profile for the signed-in identity (idempotent).',
    tags: ['account'],
    auth: 'subject',
    body: Obj({
      displayName: Type.String({ minLength: 1, maxLength: 40 }),
      timeZone: Type.String({ minLength: 1, maxLength: 64 }),
      locale: Type.String({ minLength: 2, maxLength: 16, pattern: '^[A-Za-z]{2,3}([-_][A-Za-z0-9]{2,8})*$' }),
      ageAttestation: Type.Literal(true, { description: 'User confirms they are 18 or older (pilot audience is adults only).' }),
    }),
    response: Me,
    handler: async ({ body, claims, deps, now }) => {
      if (!isValidTimeZone(body.timeZone)) throw unprocessable('invalid_time_zone', 'Choose a valid time zone.');
      const name = body.displayName.trim();
      if (!name) throw unprocessable('invalid_display_name', 'Enter a name your friends will recognise.');
      return deps.db.tx(async (q) => {
        let user = await userBySubject(q, claims!.sub);
        if (!user) {
          const r = await q.query<UserRow>(
            `insert into users (auth_subject, display_name, time_zone, locale, age_attested_at) values ($1, $2, $3, $4, $5)
             on conflict (auth_subject) do nothing returning *`,
            [claims!.sub, name, body.timeZone, body.locale, now],
          );
          user = r.rows[0] ?? (await userBySubject(q, claims!.sub));
          if (!user) throw new AppError(500, 'account_create_failed', 'Could not create your account.');
          await grantIntroCredits(q, user.id);
          await audit(q, { actorUserId: user.id, actorKind: 'user', action: 'account.created', targetKind: 'user', targetId: user.id });
        }
        return meDto(q, user, now);
      });
    },
  }),

  route({
    method: 'GET',
    url: '/v1/me',
    operationId: 'getMe',
    summary: 'Profile, settings, plan and credits.',
    tags: ['account'],
    auth: 'user',
    response: Me,
    handler: async ({ user, deps, now }) => meDto(deps.db, user, now),
  }),

  route({
    method: 'PATCH',
    url: '/v1/me',
    operationId: 'updateMe',
    summary: 'Update profile and recipient-wide settings (quiet hours, pause, sound budget).',
    tags: ['account'],
    auth: 'user',
    body: Obj({
      displayName: Type.Optional(Type.String({ minLength: 1, maxLength: 40 })),
      timeZone: Type.Optional(Type.String({ minLength: 1, maxLength: 64 })),
      locale: Type.Optional(Type.String({ minLength: 2, maxLength: 16 })),
      quietHours: Type.Optional(Obj({ enabled: Type.Boolean(), start: HhMm, end: HhMm })),
      pausedUntil: Type.Optional(Nullable(Instant)),
      soundBudgetPerHour: Type.Optional(Type.Integer({ minimum: 1, maximum: 20 })),
      shareBypass: Type.Optional(Type.Boolean()),
    }),
    response: Me,
    handler: async ({ body, user, deps, now }) => {
      if (body.timeZone !== undefined && !isValidTimeZone(body.timeZone)) throw unprocessable('invalid_time_zone', 'Choose a valid time zone.');
      if (body.quietHours && body.quietHours.start === body.quietHours.end) throw unprocessable('invalid_quiet_hours', 'Quiet hours need different start and end times.');
      if (body.pausedUntil) {
        const t = Date.parse(body.pausedUntil);
        if (t > now.getTime() + 30 * 86_400_000) throw unprocessable('pause_too_long', 'You can pause for up to 30 days at a time.');
      }
      return deps.db.tx(async (q) => {
        const r = await q.query<UserRow>(
          `update users set
             display_name = coalesce($2, display_name),
             time_zone = coalesce($3, time_zone),
             locale = coalesce($4, locale),
             quiet_enabled = coalesce($5, quiet_enabled),
             quiet_start = coalesce($6, quiet_start),
             quiet_end = coalesce($7, quiet_end),
             paused_until = case when $8::boolean then $9::timestamptz else paused_until end,
             sound_budget_per_hour = coalesce($10, sound_budget_per_hour),
             share_bypass = coalesce($11, share_bypass),
             updated_at = $12
           where id = $1 returning *`,
          [
            user.id,
            body.displayName?.trim() ?? null,
            body.timeZone ?? null,
            body.locale ?? null,
            body.quietHours?.enabled ?? null,
            body.quietHours?.start ?? null,
            body.quietHours?.end ?? null,
            body.pausedUntil !== undefined,
            body.pausedUntil ?? null,
            body.soundBudgetPerHour ?? null,
            body.shareBypass ?? null,
            now,
          ],
        );
        await appendSync(q, [user.id], 'me', user.id);
        return meDto(q, r.rows[0]!, now);
      });
    },
  }),

  route({
    method: 'DELETE',
    url: '/v1/account',
    operationId: 'deleteAccount',
    summary: 'Deactivate immediately and schedule permanent deletion. Requires a recent sign-in.',
    description: `Revokes every grant (given and received), cancels pending requests and routines, ends location sessions and signs out devices now. Data is permanently deleted after ${DELETION_GRACE_DAYS} days unless cancelled.`,
    tags: ['account'],
    auth: 'user',
    response: Type.Object({ deletionScheduledFor: Instant }),
    handler: async ({ user, claims, deps, now }) => {
      requireRecentAuth(claims!, now, deps.config.reauthSecondsForDeletion);
      const due = new Date(now.getTime() + DELETION_GRACE_DAYS * 86_400_000);
      await deps.db.tx(async (q) => {
        await deactivateAccount(q, user.id, now);
        await enqueue(q, { kind: 'delete_account', dedupeKey: `delete_account:${user.id}:${user.deletion_requested_at?.getTime() ?? now.getTime()}`, payload: { userId: user.id }, dueAt: due, maxAttempts: 20 });
        await audit(q, { actorUserId: user.id, actorKind: 'user', action: 'account.deletion_requested', targetKind: 'user', targetId: user.id });
      });
      return { deletionScheduledFor: due.toISOString() };
    },
  }),

  route({
    method: 'POST',
    url: '/v1/account/deletion/cancel',
    operationId: 'cancelAccountDeletion',
    summary: 'Cancel a scheduled deletion during the grace period. Revoked grants are not restored.',
    tags: ['account'],
    auth: 'user',
    response: Me,
    handler: async ({ user, deps, now }) => {
      if (user.account_state !== 'deletion_pending') throw conflict('not_pending_deletion', 'This account is not scheduled for deletion.');
      return deps.db.tx(async (q) => {
        const r = await q.query<UserRow>(
          `update users set account_state = 'active', deletion_requested_at = null, updated_at = $2 where id = $1 and account_state = 'deletion_pending' returning *`,
          [user.id, now],
        );
        await q.query(`update outbox set processed_at = $2, last_error = 'cancelled' where kind = 'delete_account' and processed_at is null and payload->>'userId' = $1`, [user.id, now]);
        await audit(q, { actorUserId: user.id, actorKind: 'user', action: 'account.deletion_cancelled', targetKind: 'user', targetId: user.id });
        return meDto(q, r.rows[0]!, now);
      });
    },
  }),

  route({
    method: 'GET',
    url: '/v1/account/export',
    operationId: 'exportAccount',
    summary: 'Export your own data as JSON.',
    tags: ['account'],
    auth: 'user',
    rateLimit: { max: 5, timeWindow: '1 hour' },
    response: Type.Unknown(),
    handler: async ({ user, deps, now }) => exportAccount(deps.db, user, now),
  }),
];

