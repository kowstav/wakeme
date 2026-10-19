import {
  difficultyRank,
  grantAllows,
  isWithinAnyWindow,
  isoWeekday,
  nextOccurrences,
  parseLocalDate,
  type GrantSnapshot,
  type Recurrence,
} from '@wakeme/policy';
import type { ChallengeDefinition } from '@wakeme/challenges';
import { deriveFacts } from '@wakeme/challenges';
import { unprocessable } from '../../http/errors.ts';
import type { Queryable } from '../../db/db.ts';

export interface PlanRow {
  id: string;
  owner_id: string;
  creator_id: string;
  state: 'awaiting_acceptance' | 'active' | 'paused' | 'declined' | 'cancelled';
  accepted_version: number | null;
  pending_version: number | null;
  latest_version: number;
  cancelled_reason: string | null;
  created_at: Date;
  updated_at: Date;
}

export interface PlanVersionRow {
  plan_id: string;
  version: number;
  label: string;
  local_time: string;
  time_zone: string;
  time_zone_policy: 'fixed' | 'device';
  recurrence: Recurrence;
  sound_id: string;
  ring_seconds: number;
  challenge_revision_id: string | null;
  follow_up_delay_seconds: number | null;
  created_by: string;
  grant_id: string | null;
  grant_version: number | null;
  created_at: Date;
}

export interface PlanTerms {
  label: string;
  localTime: string;
  timeZone: string;
  timeZonePolicy: 'fixed' | 'device';
  recurrence: Recurrence;
  soundId: string;
  ringSeconds: number;
  challengeRevisionId: string | null;
  followUpDelaySeconds: number | null;
}

export function validateRecurrence(r: Recurrence): void {
  if (r.kind === 'weekly') {
    if (!Array.isArray(r.days) || r.days.length === 0 || r.days.length > 7 || new Set(r.days).size !== r.days.length || r.days.some((d) => !Number.isInteger(d) || d < 1 || d > 7)) {
      throw unprocessable('invalid_recurrence', 'Choose the days this alarm repeats.');
    }
  } else {
    try {
      parseLocalDate(r.date);
    } catch {
      throw unprocessable('invalid_recurrence', 'Choose a valid date.');
    }
  }
}

/** ISO weekdays on which the alarm rings. */
export function ringDays(r: Recurrence): number[] {
  return r.kind === 'weekly' ? r.days : [isoWeekday(parseLocalDate(r.date))];
}

/**
 * A friend may only propose routines inside what the owner granted: sound,
 * duration, follow-up, challenge, wake windows and the daily wake limit.
 */
export async function checkFriendPlanAgainstGrant(
  q: Queryable,
  grant: GrantSnapshot,
  owner: { id: string; time_zone: string },
  creatorId: string,
  terms: PlanTerms,
  challenge: ChallengeDefinition | null,
  now: Date,
  excludePlanId: string | null,
): Promise<void> {
  if (!grantAllows(grant, 'alarm.schedule', now)) throw unprocessable('capability_not_granted', "They haven't allowed you to schedule wake-ups.");
  const limits = grant.limits.alarm;
  if (!limits) throw unprocessable('capability_not_granted', "They haven't allowed you to schedule wake-ups.");
  if (terms.timeZone !== owner.time_zone || terms.timeZonePolicy !== 'fixed') {
    throw unprocessable('time_zone_mismatch', "Routines you set for a friend use their time zone.");
  }
  if (!limits.sounds.includes(terms.soundId)) throw unprocessable('sound_not_allowed', "That sound isn't in the set they approved.");
  if (terms.ringSeconds > limits.maxAlarmSeconds) throw unprocessable('duration_exceeds_limit', "That's longer than they allow.");
  if (terms.followUpDelaySeconds !== null) {
    if (!limits.followUp.allowed || terms.followUpDelaySeconds > limits.followUp.maxDelaySeconds) throw unprocessable('follow_up_not_allowed', "They haven't allowed a follow-up alarm like that.");
  }
  if (challenge) {
    if (!grantAllows(grant, 'challenge.assign', now) || !grant.limits.challenge) throw unprocessable('challenge_not_allowed', "They haven't allowed you to assign challenges.");
    const cl = grant.limits.challenge;
    const facts = deriveFacts(challenge);
    if (challenge.maxDurationSeconds > cl.maxDurationSeconds || difficultyRank(challenge.difficulty) > difficultyRank(cl.maxDifficulty) || !facts.stepTypes.every((t) => cl.types.includes(t))) {
      throw unprocessable('challenge_not_allowed', "That challenge isn't within what they accepted.");
    }
  }
  // Every ring time must fall inside a wake window on its day.
  for (const day of ringDays(terms.recurrence)) {
    // Probe the next real date with that weekday in the owner's zone.
    const probe = nextOccurrences({ localTime: terms.localTime, timeZone: owner.time_zone, recurrence: { kind: 'weekly', days: [day] } }, now, 1)[0]!;
    if (!isWithinAnyWindow(probe.instant, owner.time_zone, limits.windows)) throw unprocessable('outside_allowed_window', 'That time is outside the wake hours they chose.');
  }
  // At most maxPerDay friend wake routines on any day from this helper.
  const others = await q.query<{ recurrence: Recurrence }>(
    `select v.recurrence from alarm_plans p join alarm_plan_versions v on v.plan_id = p.id and v.version = coalesce(p.pending_version, p.accepted_version, p.latest_version)
      where p.owner_id = $1 and p.creator_id = $2 and p.state in ('active', 'paused', 'awaiting_acceptance') and ($3::uuid is null or p.id <> $3)`,
    [owner.id, creatorId, excludePlanId],
  );
  for (const day of ringDays(terms.recurrence)) {
    const n = others.rows.filter((o) => ringDays(o.recurrence).includes(day)).length;
    if (n + 1 > limits.maxPerDay) throw unprocessable('daily_limit_reached', "You've reached the number of wake-ups they allow on that day.");
  }
}

export type Readiness =
  | 'ready'
  | 'ready_edit_pending'
  | 'awaiting_acceptance'
  | 'awaiting_installation'
  | 'install_failed'
  | 'paused'
  | 'permission_lost'
  | 'finished'
  | 'declined'
  | 'cancelled';

export interface InstallationRow {
  plan_id: string;
  device_id: string;
  version: number;
  result: 'scheduled' | 'failed' | 'removed';
  error_code: string | null;
  next_fire_at: Date | null;
  scheduled_instants: string[];
  asset_digest: string | null;
  reported_at: Date;
}

/**
 * "Ready" only when the owner's current primary phone reported the accepted
 * version as scheduled with the expected next fire time. Saving on the server
 * is never presented as "scheduled".
 */
export function planReadiness(plan: PlanRow, accepted: PlanVersionRow | null, install: InstallationRow | null, primaryDeviceId: string | null, grantOk: boolean, now: Date): Readiness {
  if (plan.state === 'cancelled') return 'cancelled';
  if (plan.state === 'declined') return 'declined';
  if (plan.state === 'paused') return 'paused';
  if (!accepted) return 'awaiting_acceptance';
  if (!grantOk) return 'permission_lost';
  const next = nextOccurrences({ localTime: accepted.local_time, timeZone: accepted.time_zone, recurrence: accepted.recurrence }, now, 1)[0];
  if (!next) return 'finished';
  if (!install || !primaryDeviceId || install.device_id !== primaryDeviceId || install.version !== accepted.version) return 'awaiting_installation';
  if (install.result === 'failed') return 'install_failed';
  if (install.result !== 'scheduled') return 'awaiting_installation';
  if (accepted.time_zone_policy === 'fixed') {
    if (!install.next_fire_at || Math.abs(install.next_fire_at.getTime() - next.instant.getTime()) > 120_000) {
      // The phone's schedule is stale (e.g. it last synced before an occurrence passed).
      if (!install.next_fire_at || install.next_fire_at.getTime() < now.getTime()) return 'awaiting_installation';
    }
  }
  return plan.pending_version ? 'ready_edit_pending' : 'ready';
}
