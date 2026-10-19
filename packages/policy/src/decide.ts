import {
  difficultyRank,
  grantAllows,
  isGrantActive,
  type Capability,
  type ChallengeStepType,
  type Difficulty,
  type GrantSnapshot,
} from './grants.ts';
import { isQuietTime, isWithinAnyWindow, type LocalWindow, type QuietHours } from './windows.ts';
import { toIsoSeconds } from './time.ts';

export type CommandCapability = Extract<
  Capability,
  'nudge.send' | 'alarm.request' | 'focus.request' | 'safety.checkin.request' | 'location.request'
>;

export const COMMAND_CAPABILITIES: readonly CommandCapability[] = [
  'nudge.send',
  'alarm.request',
  'focus.request',
  'safety.checkin.request',
  'location.request',
];

/** Allowed expiry range per capability, seconds. A nudge is never worth replaying later. */
export const EXPIRY_BOUNDS: Record<CommandCapability, { min: number; max: number; default: number }> = {
  'nudge.send': { min: 30, max: 300, default: 120 },
  'alarm.request': { min: 30, max: 600, default: 300 },
  'focus.request': { min: 60, max: 3600, default: 900 },
  'safety.checkin.request': { min: 300, max: 21_600, default: 3600 },
  'location.request': { min: 60, max: 900, default: 300 },
};

export const GLOBAL_LIMITS = {
  /** Commands one sender may issue to all recipients per hour (anti-spam). */
  senderAllTargetsPerHour: 30,
  /** location.request per sender per recipient per hour. */
  locationRequestsPerHour: 4,
  clockSkewToleranceSeconds: 30,
} as const;

export interface RecipientSettings {
  quietHours: QuietHours;
  /** ISO instant until which all incoming requests are paused, or null. */
  pausedUntil: string | null;
  /** Aggregate audible requests (quacks + wake alarms) from all friends per hour. */
  soundBudgetPerHour: number;
}

export interface ChallengeRef {
  stepTypes: ChallengeStepType[];
  maxDurationSeconds: number;
  difficulty: Difficulty;
}

export type CommandRequest =
  | { capability: 'nudge.send'; soundId: string; expiresInSeconds: number }
  | {
      capability: 'alarm.request';
      soundId: string;
      ringSeconds: number;
      expiresInSeconds: number;
      challenge?: ChallengeRef;
    }
  | { capability: 'focus.request'; minutes: number; expiresInSeconds: number; challenge?: ChallengeRef }
  | { capability: 'safety.checkin.request'; expiresInSeconds: number }
  | { capability: 'location.request'; expiresInSeconds: number };

export interface WindowCount {
  count: number;
  /** ISO instant of the oldest counted event in the window (for Retry-After). */
  oldestAt: string | null;
}

export interface CommandCounts {
  /** Same capability, this sender to this recipient, last 60 minutes. */
  senderToTargetLastHour: WindowCount;
  /** Same capability, this sender to this recipient, since recipient-local midnight. */
  senderToTargetToday: number;
  /** Audible commands (nudge + alarm) from all senders to this recipient, last 60 minutes. */
  targetAudibleLastHour: WindowCount;
  /** All commands from this sender to anyone, last 60 minutes. */
  senderAllLastHour: WindowCount;
}

export interface DeviceReadiness {
  /** Has a non-revoked primary device with a push token. */
  pushable: boolean;
  /** Primary device reports native alarm authorization (AlarmKit / exact alarms). */
  alarmCapable: boolean;
}

export interface CommandContext {
  now: Date;
  actor: { id: string; active: boolean };
  target: { id: string; active: boolean; timeZone: string; settings: RecipientSettings };
  friendship: { state: 'accepted' | 'blocked' } | null;
  /** Grant from target (grantor) to actor (grantee), if any. */
  grant: GrantSnapshot | null;
  /** Version the sender's UI was showing; mismatch means terms changed. */
  expectedGrantVersion?: number;
  request: CommandRequest;
  counts: CommandCounts;
  /** Kill switches: key -> disabled. */
  disabledFeatures: ReadonlySet<string>;
  device: DeviceReadiness;
}

export type DenyReason =
  | 'self_target'
  | 'not_found'
  | 'actor_inactive'
  | 'feature_disabled'
  | 'capability_not_granted'
  | 'grant_changed'
  | 'grant_paused'
  | 'invalid_expiry'
  | 'sound_not_allowed'
  | 'duration_exceeds_limit'
  | 'challenge_not_allowed'
  | 'recipient_paused'
  | 'outside_allowed_window'
  | 'quiet_hours'
  | 'rate_limited_sender'
  | 'daily_limit_reached'
  | 'rate_limited_recipient'
  | 'rate_limited_global'
  | 'recipient_not_ready';

export const DENY_HTTP_STATUS: Record<DenyReason, number> = {
  self_target: 422,
  not_found: 404,
  actor_inactive: 403,
  feature_disabled: 503,
  capability_not_granted: 403,
  grant_changed: 409,
  grant_paused: 403,
  invalid_expiry: 422,
  sound_not_allowed: 422,
  duration_exceeds_limit: 422,
  challenge_not_allowed: 422,
  recipient_paused: 403,
  outside_allowed_window: 403,
  quiet_hours: 403,
  rate_limited_sender: 429,
  daily_limit_reached: 429,
  rate_limited_recipient: 429,
  rate_limited_global: 429,
  recipient_not_ready: 409,
};

export type Decision =
  | { allowed: true; deliveryMode: 'audible' | 'notify' | 'silent'; expiresAt: string }
  | { allowed: false; reason: DenyReason; retryAfterSeconds?: number };

export const FEATURE_FOR_CAPABILITY: Record<CommandCapability, string> = {
  'nudge.send': 'remote_dispatch.nudge',
  'alarm.request': 'remote_dispatch.alarm',
  'focus.request': 'focus_requests',
  'safety.checkin.request': 'safety_requests',
  'location.request': 'location',
};

const AUDIBLE: ReadonlySet<CommandCapability> = new Set(['nudge.send', 'alarm.request']);
const SAFETY: ReadonlySet<CommandCapability> = new Set(['safety.checkin.request', 'location.request']);

function deny(reason: DenyReason, retryAfterSeconds?: number): Decision {
  return retryAfterSeconds === undefined ? { allowed: false, reason } : { allowed: false, reason, retryAfterSeconds };
}

function retryAfter(oldestAt: string | null, now: Date, windowSeconds: number): number {
  if (!oldestAt) return windowSeconds;
  const ms = Date.parse(oldestAt) + windowSeconds * 1000 - now.getTime();
  return Math.max(1, Math.ceil(ms / 1000));
}

function challengeWithin(grant: GrantSnapshot, now: Date, ch: ChallengeRef | undefined): boolean {
  if (!ch) return true;
  if (!grantAllows(grant, 'challenge.assign', now)) return false;
  const limits = grant.limits.challenge;
  if (!limits) return false;
  if (ch.maxDurationSeconds > limits.maxDurationSeconds) return false;
  if (difficultyRank(ch.difficulty) > difficultyRank(limits.maxDifficulty)) return false;
  return ch.stepTypes.every((t) => limits.types.includes(t));
}

/**
 * Server-side authorization of a remote command. Pure: callers load the
 * snapshot (inside the same transaction that records the command) and act on
 * the decision. Order matters: strangers and blocked users learn nothing.
 */
export function evaluateCommand(ctx: CommandContext): Decision {
  const { now, request, grant } = ctx;
  const capability = request.capability;

  if (ctx.actor.id === ctx.target.id) return deny('self_target');
  if (!ctx.friendship || ctx.friendship.state !== 'accepted' || !ctx.target.active) return deny('not_found');
  if (!ctx.actor.active) return deny('actor_inactive');
  if (ctx.disabledFeatures.has(FEATURE_FOR_CAPABILITY[capability])) return deny('feature_disabled');

  if (!grant || grant.grantorId !== ctx.target.id || grant.granteeId !== ctx.actor.id) return deny('capability_not_granted');
  if (!isGrantActive(grant, now) || !grant.capabilities.includes(capability)) return deny('capability_not_granted');
  if (ctx.expectedGrantVersion !== undefined && ctx.expectedGrantVersion !== grant.version) return deny('grant_changed');
  if (grant.paused) return deny('grant_paused');

  const bounds = EXPIRY_BOUNDS[capability];
  if (!Number.isInteger(request.expiresInSeconds) || request.expiresInSeconds < bounds.min || request.expiresInSeconds > bounds.max) {
    return deny('invalid_expiry');
  }

  // Capability-specific payload limits.
  let windows: LocalWindow[] | null = null;
  let quietOverride = false;
  switch (request.capability) {
    case 'nudge.send': {
      const l = grant.limits.nudge;
      if (!l) return deny('capability_not_granted');
      if (!l.sounds.includes(request.soundId)) return deny('sound_not_allowed');
      windows = l.windows;
      quietOverride = l.overridesQuietHours;
      break;
    }
    case 'alarm.request': {
      const l = grant.limits.alarm;
      if (!l) return deny('capability_not_granted');
      if (!l.sounds.includes(request.soundId)) return deny('sound_not_allowed');
      if (!Number.isInteger(request.ringSeconds) || request.ringSeconds < 10 || request.ringSeconds > l.maxAlarmSeconds) {
        return deny('duration_exceeds_limit');
      }
      if (!challengeWithin(grant, now, request.challenge)) return deny('challenge_not_allowed');
      windows = l.windows;
      quietOverride = true; // wake windows are explicit consent to be woken
      break;
    }
    case 'focus.request': {
      const l = grant.limits.focus;
      if (!l) return deny('capability_not_granted');
      if (!Number.isInteger(request.minutes) || request.minutes < 1 || request.minutes > l.maxSessionMinutes) {
        return deny('duration_exceeds_limit');
      }
      if (!challengeWithin(grant, now, request.challenge)) return deny('challenge_not_allowed');
      windows = l.windows;
      break;
    }
    case 'safety.checkin.request':
    case 'location.request':
      break;
  }

  const settings = ctx.target.settings;
  const tz = ctx.target.timeZone;
  const isSafety = SAFETY.has(capability);
  // audible: the approved sound or alarm; notify: an ordinary notification; silent: no sound.
  let deliveryMode: 'audible' | 'notify' | 'silent' = AUDIBLE.has(capability) ? 'audible' : 'notify';

  if (settings.pausedUntil && Date.parse(settings.pausedUntil) > now.getTime()) {
    if (!isSafety) return deny('recipient_paused', Math.ceil((Date.parse(settings.pausedUntil) - now.getTime()) / 1000));
    deliveryMode = 'silent';
  }

  if (windows && !isWithinAnyWindow(now, tz, windows)) return deny('outside_allowed_window');

  if (isQuietTime(now, tz, settings.quietHours)) {
    if (isSafety) deliveryMode = 'silent';
    else if (!quietOverride) return deny('quiet_hours');
  }

  // Rate limits: per-sender first, then recipient aggregate so that five
  // friends together cannot exceed the recipient's own sound budget.
  const c = ctx.counts;
  switch (request.capability) {
    case 'nudge.send': {
      const max = grant.limits.nudge!.maxPerHour;
      if (c.senderToTargetLastHour.count >= max) return deny('rate_limited_sender', retryAfter(c.senderToTargetLastHour.oldestAt, now, 3600));
      break;
    }
    case 'alarm.request':
      if (c.senderToTargetToday >= grant.limits.alarm!.maxPerDay) return deny('daily_limit_reached');
      break;
    case 'focus.request':
      if (c.senderToTargetToday >= grant.limits.focus!.maxPerDay) return deny('daily_limit_reached');
      break;
    case 'safety.checkin.request':
      if (c.senderToTargetToday >= (grant.limits.checkin?.maxPerDay ?? 1)) return deny('daily_limit_reached');
      break;
    case 'location.request':
      if (c.senderToTargetLastHour.count >= GLOBAL_LIMITS.locationRequestsPerHour) {
        return deny('rate_limited_sender', retryAfter(c.senderToTargetLastHour.oldestAt, now, 3600));
      }
      break;
  }
  if (AUDIBLE.has(capability) && c.targetAudibleLastHour.count >= settings.soundBudgetPerHour) {
    return deny('rate_limited_recipient', retryAfter(c.targetAudibleLastHour.oldestAt, now, 3600));
  }
  if (c.senderAllLastHour.count >= GLOBAL_LIMITS.senderAllTargetsPerHour) {
    return deny('rate_limited_global', retryAfter(c.senderAllLastHour.oldestAt, now, 3600));
  }

  if (!ctx.device.pushable) return deny('recipient_not_ready');
  if (capability === 'alarm.request' && !ctx.device.alarmCapable) return deny('recipient_not_ready');

  return {
    allowed: true,
    deliveryMode,
    expiresAt: toIsoSeconds(new Date(now.getTime() + request.expiresInSeconds * 1000)),
  };
}

// ---------------------------------------------------------------------------
// Recipient-device re-validation. Runs on the phone that would ring, against
// its locally cached grants, so a revoked or edited grant is honoured even if
// the server dispatched just before the change. No network is required.

export interface IncomingCommand {
  id: string;
  capability: CommandCapability;
  actorId: string;
  grantVersion: number;
  expiresAt: string;
  soundId?: string;
  ringSeconds?: number;
}

export interface LocalExecutionContext {
  now: Date;
  command: IncomingCommand;
  localGrant: GrantSnapshot | null;
  timeZone: string;
  settings: RecipientSettings;
  alreadyProcessed: boolean;
}

export type LocalDecision =
  | { execute: true; mode: 'ring' | 'notify' | 'silent' }
  | {
      execute: false;
      reason: 'duplicate' | 'expired' | 'not_authorized_locally' | 'needs_sync' | 'outside_allowed_window' | 'quiet_hours' | 'recipient_paused';
    };

export function evaluateLocalExecution(ctx: LocalExecutionContext): LocalDecision {
  const { now, command: cmd, localGrant: grant } = ctx;
  if (ctx.alreadyProcessed) return { execute: false, reason: 'duplicate' };
  if (now.getTime() > Date.parse(cmd.expiresAt) + GLOBAL_LIMITS.clockSkewToleranceSeconds * 1000) {
    return { execute: false, reason: 'expired' };
  }
  if (!grant || grant.granteeId !== cmd.actorId) return { execute: false, reason: 'not_authorized_locally' };
  if (grant.version < cmd.grantVersion) return { execute: false, reason: 'needs_sync' };
  if (!grantAllows(grant, cmd.capability, now)) return { execute: false, reason: 'not_authorized_locally' };

  const isSafety = SAFETY.has(cmd.capability);
  if (ctx.settings.pausedUntil && Date.parse(ctx.settings.pausedUntil) > now.getTime() && !isSafety) {
    return { execute: false, reason: 'recipient_paused' };
  }

  let windows: LocalWindow[] | null = null;
  let quietOverride = false;
  if (cmd.capability === 'nudge.send') {
    const l = grant.limits.nudge;
    if (!l || !cmd.soundId || !l.sounds.includes(cmd.soundId)) return { execute: false, reason: 'not_authorized_locally' };
    windows = l.windows;
    quietOverride = l.overridesQuietHours;
  } else if (cmd.capability === 'alarm.request') {
    const l = grant.limits.alarm;
    if (!l || !cmd.soundId || !l.sounds.includes(cmd.soundId)) return { execute: false, reason: 'not_authorized_locally' };
    if (cmd.ringSeconds === undefined || cmd.ringSeconds > l.maxAlarmSeconds) return { execute: false, reason: 'not_authorized_locally' };
    windows = l.windows;
    quietOverride = true;
  } else if (cmd.capability === 'focus.request') {
    windows = grant.limits.focus?.windows ?? null;
  }

  // Allow the skew tolerance at window edges so a command accepted at 08:59:59
  // server time is not refused at 09:00:05 device time.
  if (windows) {
    const skewed = new Date(now.getTime() - GLOBAL_LIMITS.clockSkewToleranceSeconds * 1000);
    if (!isWithinAnyWindow(now, ctx.timeZone, windows) && !isWithinAnyWindow(skewed, ctx.timeZone, windows)) {
      return { execute: false, reason: 'outside_allowed_window' };
    }
  }
  if (isQuietTime(now, ctx.timeZone, ctx.settings.quietHours)) {
    if (isSafety) return { execute: true, mode: 'silent' };
    if (!quietOverride) return { execute: false, reason: 'quiet_hours' };
  }
  if (cmd.capability === 'alarm.request') return { execute: true, mode: 'ring' };
  return { execute: true, mode: 'notify' };
}
