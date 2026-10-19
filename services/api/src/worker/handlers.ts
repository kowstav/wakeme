import { createHash } from 'node:crypto';
import { grantAllows, isQuietTime, type CommandCapability } from '@wakeme/policy';
import type { Deps } from '../deps.ts';
import type { Queryable } from '../db/db.ts';
import { appendSync, recipientSettings, userById, type DeviceRow, type UserRow } from '../modules/identity/repo.ts';
import { recomputeDelivery, type CommandRow } from '../modules/commands/routes.ts';
import { toSnapshot, type GrantRow } from '../modules/grants/repo.ts';
import { friendshipBetween } from '../modules/relationships/repo.ts';
import { hardDeleteAccount } from '../modules/identity/deletion.ts';
import { refundCredit } from '../modules/billing/service.ts';
import { createDefinition } from '../modules/challenges/routes.ts';
import { assembleDraft, type CreatorFact } from '../ai/assemble.ts';
import { inspectAudio } from '../lib/audio.ts';
import { MAX_DURATION_MS, MAX_UPLOAD_BYTES } from '../modules/media/routes.ts';
import type { PushMessage, PushTarget } from '../push/gateway.ts';
import { renderAlert, type Template } from './notifications.ts';
import { RetryLater, type HandlerSet, type OutboxJob } from './outbox.ts';

function target(d: DeviceRow): PushTarget | null {
  if (!d.push_token || !d.push_provider || d.push_token_invalid_at || d.revoked_at) return null;
  return { deviceId: d.id, platform: d.platform, provider: d.push_provider, token: d.push_token, environment: d.push_environment };
}

async function activeDevices(q: Queryable, userId: string): Promise<DeviceRow[]> {
  const r = await q.query<DeviceRow>('select * from devices where user_id = $1 and revoked_at is null and push_token is not null and push_token_invalid_at is null', [userId]);
  return r.rows;
}

async function markTokenInvalid(deps: Deps, deviceId: string): Promise<void> {
  await deps.db.query('update devices set push_token_invalid_at = $2 where id = $1', [deviceId, deps.now()]);
}

/** Best-effort fan-out of a normal notification to all of a user's devices. */
async function notifyUser(deps: Deps, user: UserRow, template: Template, actorName: string, opts: { data: Record<string, string>; collapseId?: string; minutes?: number; circle?: string | null; respectQuiet?: boolean; high?: boolean; ttlSeconds?: number }): Promise<void> {
  const now = deps.now();
  const silent = opts.respectQuiet === true && isQuietTime(now, user.time_zone, recipientSettings(user).quietHours);
  const msg: PushMessage = {
    priority: opts.high ? 'high' : 'normal',
    expiresAt: new Date(now.getTime() + (opts.ttlSeconds ?? 6 * 3600) * 1000),
    alert: renderAlert(template, actorName, { silent, ...(opts.minutes !== undefined ? { minutes: opts.minutes } : {}), circle: opts.circle ?? null }),
    data: opts.data,
    ...(opts.collapseId ? { collapseId: opts.collapseId } : {}),
  };
  let retry = false;
  for (const d of await activeDevices(deps.db, user.id)) {
    const t = target(d);
    if (!t) continue;
    const res = await deps.push.send(t, msg);
    if (res.status === 'rejected' && res.tokenInvalid) await markTokenInvalid(deps, d.id);
    if (res.status === 'retry') retry = true;
  }
  if (retry) throw new RetryLater('push_retry');
}

const COMMAND_TEMPLATE: Record<CommandCapability, Template> = {
  'nudge.send': 'nudge',
  'alarm.request': 'wake',
  'focus.request': 'focus',
  'safety.checkin.request': 'checkin_request',
  'location.request': 'location_request',
};

async function cancelCommand(deps: Deps, cmd: CommandRow, kind: 'cancelled' | 'expired', reason: string): Promise<void> {
  await deps.db.tx(async (q) => {
    await q.query('insert into command_events (command_id, kind, reason) values ($1, $2, $3)', [cmd.id, kind, reason]);
    await recomputeDelivery(q, cmd.id, deps.now());
    await appendSync(q, [cmd.actor_id, cmd.target_id], 'command', cmd.id);
  });
}

/**
 * Re-checks the command at dispatch time (revocation, pause, block, expiry,
 * kill switch) because state may have changed since it was accepted. A
 * two-minute quack is never retried hours later: retries stop at expiry.
 */
async function dispatchCommand(job: OutboxJob, deps: Deps): Promise<void> {
  const id = String(job.payload['commandId']);
  const r = await deps.db.query<CommandRow>('select * from commands where id = $1', [id]);
  const cmd = r.rows[0];
  if (!cmd || cmd.lifecycle !== 'accepted') return;
  const now = deps.now();
  if (cmd.expires_at.getTime() <= now.getTime() + 2000) return cancelCommand(deps, cmd, 'expired', 'expired_before_dispatch');

  const [actor, targetUser] = [await userById(deps.db, cmd.actor_id), await userById(deps.db, cmd.target_id)];
  const friendship = await friendshipBetween(deps.db, cmd.actor_id, cmd.target_id);
  const g = await deps.db.query<GrantRow>('select * from grants where id = $1', [cmd.grant_id]);
  const grant = g.rows[0];
  const disabled = await deps.flags.disabled();
  const feature = { 'nudge.send': 'remote_dispatch.nudge', 'alarm.request': 'remote_dispatch.alarm', 'focus.request': 'focus_requests', 'safety.checkin.request': 'safety_requests', 'location.request': 'location' }[cmd.capability];
  if (!actor || !targetUser || actor.account_state !== 'active' || targetUser.account_state !== 'active' || friendship?.state !== 'accepted') return cancelCommand(deps, cmd, 'cancelled', 'relationship_changed');
  if (!grant || !grantAllows(toSnapshot(grant), cmd.capability, now)) return cancelCommand(deps, cmd, 'cancelled', 'grant_changed');
  if (cmd.capability === 'nudge.send' && !grant.limits.nudge?.sounds.includes(cmd.payload.soundId ?? '')) return cancelCommand(deps, cmd, 'cancelled', 'grant_changed');
  if (cmd.capability === 'alarm.request' && (!grant.limits.alarm || (cmd.payload.ringSeconds ?? 0) > grant.limits.alarm.maxAlarmSeconds || !grant.limits.alarm.sounds.includes(cmd.payload.soundId ?? ''))) {
    return cancelCommand(deps, cmd, 'cancelled', 'grant_changed');
  }
  if (disabled.has(feature)) return cancelCommand(deps, cmd, 'cancelled', 'feature_disabled');

  const devId = cmd.target_device_id ?? targetUser.primary_device_id;
  const dev = devId ? (await deps.db.query<DeviceRow>('select * from devices where id = $1 and user_id = $2', [devId, targetUser.id])).rows[0] : undefined;
  const t = dev ? target(dev) : null;
  if (!t || !dev) {
    await deps.db.tx(async (q) => {
      await q.query(`insert into command_events (command_id, kind, reason) values ($1, 'not_sent', 'no_device')`, [cmd.id]);
      await recomputeDelivery(q, cmd.id, now);
      await appendSync(q, [cmd.actor_id, cmd.target_id], 'command', cmd.id);
    });
    return;
  }

  const silent = cmd.delivery_mode === 'silent';
  const data: Record<string, string> = {
    t: 'command',
    id: cmd.id,
    cap: cmd.capability,
    actor: cmd.actor_id,
    gv: String(cmd.grant_version),
    exp: cmd.expires_at.toISOString(),
    mode: cmd.delivery_mode,
  };
  if (cmd.payload.soundId) data['snd'] = cmd.payload.soundId;
  if (cmd.payload.ringSeconds) data['ring'] = String(cmd.payload.ringSeconds);
  if (cmd.payload.challengeRevisionId) data['ch'] = cmd.payload.challengeRevisionId;
  if (cmd.payload.minutes) data['min'] = String(cmd.payload.minutes);
  const alert = renderAlert(COMMAND_TEMPLATE[cmd.capability], actor.display_name, {
    soundId: cmd.capability === 'nudge.send' || cmd.capability === 'alarm.request' ? (cmd.payload.soundId ?? null) : null,
    silent,
    ...(cmd.payload.minutes ? { minutes: cmd.payload.minutes } : {}),
    threadId: `friend:${friendship.id}`,
  });
  const message: PushMessage = {
    priority: silent ? 'normal' : 'high',
    expiresAt: cmd.expires_at,
    collapseId: cmd.id,
    alert,
    data,
  };
  const res = await deps.push.send(t, message);
  if (res.status === 'retry') {
    if (cmd.expires_at.getTime() - deps.now().getTime() < 15_000) return cancelCommand(deps, cmd, 'expired', 'expired_during_retry');
    throw new RetryLater(res.reason, res.retryAfterSeconds ?? 3);
  }
  // A content-available update lets the iOS app schedule the native alarm path when the OS allows it.
  if (res.status === 'accepted' && cmd.capability === 'alarm.request' && t.platform === 'ios') {
    await deps.push.send(t, { priority: 'normal', expiresAt: cmd.expires_at, background: true, data, collapseId: `${cmd.id}:bg` });
  }
  await deps.db.tx(async (q) => {
    if (res.status === 'accepted') await q.query(`insert into command_events (command_id, kind) values ($1, 'provider_accepted')`, [cmd.id]);
    else {
      await q.query(`insert into command_events (command_id, kind, reason) values ($1, 'provider_rejected', $2)`, [cmd.id, res.reason.slice(0, 64)]);
      if (res.tokenInvalid) await q.query('update devices set push_token_invalid_at = $2 where id = $1', [dev.id, now]);
    }
    await recomputeDelivery(q, cmd.id, deps.now());
    await appendSync(q, [cmd.actor_id, cmd.target_id], 'command', cmd.id);
  });
}

async function syncHint(job: OutboxJob, deps: Deps): Promise<void> {
  const userId = String(job.payload['userId']);
  const now = deps.now();
  for (const d of await activeDevices(deps.db, userId)) {
    const t = target(d);
    if (!t) continue;
    const res = await deps.push.send(t, { priority: 'normal', expiresAt: new Date(now.getTime() + 3600_000), background: true, collapseId: 'sync', data: { t: 'sync' } });
    if (res.status === 'rejected' && res.tokenInvalid) await markTokenInvalid(deps, d.id);
  }
}

async function notifyOutcome(job: OutboxJob, deps: Deps): Promise<void> {
  const cmd = (await deps.db.query<CommandRow>('select * from commands where id = $1', [String(job.payload['commandId'])])).rows[0];
  if (!cmd) return;
  const [actor, recipient] = [await userById(deps.db, cmd.actor_id), await userById(deps.db, cmd.target_id)];
  if (!actor || !recipient || actor.account_state !== 'active') return;
  const template: Template | null =
    cmd.human_state === 'challenge_completed' ? 'outcome_challenge_completed' : cmd.human_state === 'acknowledged' ? 'outcome_acknowledged' : cmd.human_state === 'bypassed' && recipient.share_bypass ? 'outcome_bypassed' : null;
  if (!template) return;
  await notifyUser(deps, actor, template, recipient.display_name, { data: { t: 'command', id: cmd.id }, collapseId: `outcome:${cmd.id}`, respectQuiet: true });
}

async function notifyPlan(job: OutboxJob, deps: Deps): Promise<void> {
  const plan = (await deps.db.query<{ owner_id: string; creator_id: string; state: string }>('select owner_id, creator_id, state from alarm_plans where id = $1', [String(job.payload['planId'])])).rows[0];
  if (!plan || plan.state === 'cancelled') return;
  const event = String(job.payload['event']);
  const owner = await userById(deps.db, plan.owner_id);
  const creator = await userById(deps.db, plan.creator_id);
  if (!owner || !creator) return;
  const data = { t: 'plan', id: String(job.payload['planId']) };
  if (event === 'proposed') return notifyUser(deps, owner, 'plan_proposed', creator.display_name, { data, respectQuiet: true });
  if (event === 'accepted') return notifyUser(deps, creator, 'plan_accepted', owner.display_name, { data, respectQuiet: true });
  if (event === 'challenge_completed') return notifyUser(deps, creator, 'plan_challenge_completed', owner.display_name, { data, respectQuiet: true });
  if (event === 'bypassed' && owner.share_bypass) return notifyUser(deps, creator, 'plan_bypassed', owner.display_name, { data, respectQuiet: true });
}

async function notifyGeneric(job: OutboxJob, deps: Deps): Promise<void> {
  const user = await userById(deps.db, String(job.payload['userId']));
  const actor = job.payload['actorId'] ? await userById(deps.db, String(job.payload['actorId'])) : null;
  if (!user || user.account_state !== 'active') return;
  const template = String(job.payload['template']) as Template;
  const name = actor?.display_name ?? 'A friend';
  const isSafety = ['sos', 'checkin_started', 'share_started', 'checked_in', 'checkin_missed', 'safety_acknowledged'].includes(template);
  const data: Record<string, string> = { t: isSafety ? 'safety' : template.startsWith('message') ? 'message' : 'generic' };
  if (job.payload['sessionId']) data['id'] = String(job.payload['sessionId']);
  if (job.payload['conversationId']) data['id'] = String(job.payload['conversationId']);
  await notifyUser(deps, user, template, name, {
    data,
    respectQuiet: !isSafety,
    high: template === 'sos' || template === 'checkin_missed',
    circle: (job.payload['circleTitle'] as string | null) ?? null,
    ...(template.startsWith('message') && job.payload['conversationId'] ? { collapseId: `conv:${String(job.payload['conversationId'])}` } : {}),
  });
}

async function scanMedia(job: OutboxJob, deps: Deps): Promise<void> {
  const m = (await deps.db.query<{ id: string; object_key: string; mime: string; bytes: number; duration_ms: number | null; sha256: string; state: string; finalized_at: Date | null }>('select * from media_assets where id = $1', [String(job.payload['mediaId'])])).rows[0];
  if (!m || m.state !== 'uploaded') return;
  const reject = async (reason: string) => {
    await deps.db.query(`update media_assets set state = 'rejected', rejection_reason = $2 where id = $1`, [m.id, reason]);
    await deps.storage?.deleteObject(m.object_key).catch(() => undefined);
  };
  if (!deps.storage) throw new RetryLater('storage_unavailable', 60);
  let buf: Buffer | null;
  try {
    buf = await deps.storage.getObject(m.object_key, MAX_UPLOAD_BYTES);
  } catch (e) {
    if (e instanceof Error && e.message === 'object_too_large') return reject('too_large');
    throw new RetryLater('storage_error');
  }
  if (!buf) {
    if (m.finalized_at && deps.now().getTime() - m.finalized_at.getTime() > 3600_000) return reject('missing_upload');
    throw new RetryLater('not_uploaded_yet', 30);
  }
  if (buf.length !== m.bytes) return reject('size_mismatch');
  if (createHash('sha256').update(buf).digest('hex') !== m.sha256) return reject('hash_mismatch');
  const info = inspectAudio(buf, m.mime);
  if (!info) return reject('unsupported_or_malformed_audio');
  if (info.durationMs > MAX_DURATION_MS) return reject('too_long');
  if (m.duration_ms !== null && Math.abs(info.durationMs - m.duration_ms) > 1500) return reject('duration_mismatch');
  await deps.db.tx(async (q) => {
    await q.query(`update media_assets set state = 'clean', duration_ms = $2 where id = $1`, [m.id, info.durationMs]);
    const owner = await q.query<{ owner_id: string }>('select owner_id from media_assets where id = $1', [m.id]);
    if (owner.rows[0]) await appendSync(q, [owner.rows[0].owner_id], 'media', m.id);
  });
}

async function deleteObject(job: OutboxJob, deps: Deps): Promise<void> {
  if (!deps.storage) return;
  try {
    await deps.storage.deleteObject(String(job.payload['objectKey']));
  } catch {
    throw new RetryLater('storage_delete_failed');
  }
}

interface GenerationRow {
  id: string;
  user_id: string;
  status: string;
  prompt: string;
  facts: CreatorFact[];
  difficulty: 'easy' | 'medium' | 'hard';
  attempts: number;
  input_tokens: number | null;
  output_tokens: number | null;
  cost_micros: number | string | null;
}

async function finishGeneration(deps: Deps, g: GenerationRow, fields: { status: 'succeeded' | 'rejected' | 'failed'; definitionId?: string | null; rejection?: unknown; errorCode?: string | null }, refund: boolean): Promise<void> {
  await deps.db.tx(async (q) => {
    await q.query(
      `update challenge_generations set status = $2, result_definition_id = $3, rejection = $4::jsonb, error_code = $5, completed_at = $6 where id = $1`,
      [g.id, fields.status, fields.definitionId ?? null, fields.rejection === undefined ? null : JSON.stringify(fields.rejection), fields.errorCode ?? null, deps.now()],
    );
    if (refund) await refundCredit(q, g.user_id, g.id);
    await appendSync(q, [g.user_id], 'challenge_generation', g.id);
  });
}

/**
 * AI drafting. Safety rejections and model refusals use the credit (no
 * reward for probing); our own or the provider's failures are refunded.
 */
async function generateChallenge(job: OutboxJob, deps: Deps): Promise<void> {
  const g = (await deps.db.query<GenerationRow>('select * from challenge_generations where id = $1', [String(job.payload['generationId'])])).rows[0];
  if (!g || (g.status !== 'queued' && g.status !== 'running')) return;
  if (!deps.ai) return finishGeneration(deps, g, { status: 'failed', errorCode: 'ai_unavailable' }, true);
  await deps.db.query(`update challenge_generations set status = 'running', attempts = attempts + 1 where id = $1`, [g.id]);
  const attempt = g.attempts + 1;
  const result = await deps.ai.draft({ prompt: g.prompt, facts: g.facts, difficulty: g.difficulty });
  if ('usage' in result && result.usage) {
    await deps.db.query(
      `update challenge_generations set model = $2, input_tokens = coalesce(input_tokens, 0) + $3, output_tokens = coalesce(output_tokens, 0) + $4, cost_micros = coalesce(cost_micros, 0) + $5 where id = $1`,
      [g.id, result.usage.model, result.usage.inputTokens, result.usage.outputTokens, result.usage.costMicros],
    );
  }
  if (result.kind === 'refused') return finishGeneration(deps, g, { status: 'rejected', rejection: { reason: 'declined', message: "That request can't be made into a challenge. Try describing something gentler." } }, false);
  if (result.kind === 'failed') {
    if (result.retryable) throw new RetryLater(result.reason);
    return finishGeneration(deps, g, { status: 'failed', errorCode: result.reason }, true);
  }
  const assembled = assembleDraft(result.draft, g.facts, g.difficulty);
  if (!assembled.ok) {
    if (assembled.code === 'validation_failed' && assembled.validation && !assembled.validation.ok && assembled.validation.safety.length > 0) {
      return finishGeneration(deps, g, { status: 'rejected', rejection: { reason: 'unsafe', safety: assembled.validation.safety, message: assembled.validation.safety[0]!.message } }, false);
    }
    // The model did not follow the format or used invented facts: retry once, then refund.
    if (attempt < 2) throw new RetryLater(assembled.code, 1);
    return finishGeneration(deps, g, { status: 'failed', errorCode: assembled.code, rejection: { message: assembled.message } }, true);
  }
  const def = await deps.db.tx(async (q) => createDefinition(q, g.user_id, assembled.definition, { circleId: null, origin: 'ai', generationId: g.id }, deps.now()));
  await finishGeneration(deps, g, { status: 'succeeded', definitionId: def.id, rejection: assembled.notes ? { notes: assembled.notes } : null }, false);
}

async function deleteAccount(job: OutboxJob, deps: Deps): Promise<void> {
  try {
    await hardDeleteAccount(deps.db, { storage: deps.storage, authAdmin: deps.authAdmin }, String(job.payload['userId']));
  } catch (e) {
    throw new RetryLater(e instanceof Error ? e.message : 'delete_failed', 300);
  }
}

export const workerHandlers: HandlerSet = {
  handlers: {
    dispatch_command: dispatchCommand,
    sync_hint: syncHint,
    notify_outcome: notifyOutcome,
    notify_plan: notifyPlan,
    notify_generic: notifyGeneric,
    scan_media: scanMedia,
    delete_object: deleteObject,
    generate_challenge: generateChallenge,
    delete_account: deleteAccount,
  },
  onGiveUp: {
    dispatch_command: async (job, deps) => {
      const cmd = (await deps.db.query<CommandRow>('select * from commands where id = $1', [String(job.payload['commandId'])])).rows[0];
      if (cmd && cmd.transport === 'queued') {
        await deps.db.tx(async (q) => {
          await q.query(`insert into command_events (command_id, kind, reason) values ($1, 'provider_rejected', 'gave_up')`, [cmd.id]);
          await recomputeDelivery(q, cmd.id, deps.now());
          await appendSync(q, [cmd.actor_id, cmd.target_id], 'command', cmd.id);
        });
      }
    },
    generate_challenge: async (job, deps) => {
      const g = (await deps.db.query<GenerationRow>('select * from challenge_generations where id = $1', [String(job.payload['generationId'])])).rows[0];
      if (g && (g.status === 'queued' || g.status === 'running')) await finishGeneration(deps, g, { status: 'failed', errorCode: 'provider_unavailable' }, true);
    },
  },
};
