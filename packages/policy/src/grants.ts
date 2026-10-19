import { validateWindow, type LocalWindow, type WindowIssue } from './windows.ts';

/**
 * Directional, recipient-owned capabilities. A grant from A (grantor) to B
 * (grantee) lets B ask A's phone to do these things; it never works in reverse.
 * Friendship or circle membership alone grants none of them.
 */
export const CAPABILITIES = [
  'nudge.send',
  'alarm.request',
  'alarm.schedule',
  'challenge.assign',
  'focus.request',
  'safety.checkin.request',
  'location.request',
] as const;
export type Capability = (typeof CAPABILITIES)[number];

export const CHALLENGE_STEP_TYPES = [
  'multiple_choice',
  'short_answer',
  'math',
  'memory_order',
  'chess',
  'timer',
  'qr_code',
  'self_attested',
] as const;
export type ChallengeStepType = (typeof CHALLENGE_STEP_TYPES)[number];

export const DIFFICULTIES = ['easy', 'medium', 'hard'] as const;
export type Difficulty = (typeof DIFFICULTIES)[number];

export function difficultyRank(d: Difficulty): number {
  return DIFFICULTIES.indexOf(d);
}

export interface NudgeLimits {
  maxPerHour: number;
  windows: LocalWindow[];
  sounds: string[];
  /** Recipient explicitly allows quacks during their quiet hours (default false). */
  overridesQuietHours: boolean;
}

export interface AlarmLimits {
  maxAlarmSeconds: number;
  maxPerDay: number;
  /** Wake windows. Explicit wake consent: they override Wake Me quiet hours. */
  windows: LocalWindow[];
  sounds: string[];
  followUp: { allowed: boolean; maxDelaySeconds: number };
}

export interface ChallengeLimits {
  types: ChallengeStepType[];
  maxDurationSeconds: number;
  maxDifficulty: Difficulty;
}

export interface FocusLimits {
  maxSessionMinutes: number;
  maxPerDay: number;
  windows: LocalWindow[];
}

export interface CheckinLimits {
  maxPerDay: number;
}

export interface GrantLimits {
  nudge?: NudgeLimits;
  alarm?: AlarmLimits;
  challenge?: ChallengeLimits;
  focus?: FocusLimits;
  checkin?: CheckinLimits;
}

export interface GrantSnapshot {
  id: string;
  grantorId: string;
  granteeId: string;
  version: number;
  capabilities: Capability[];
  limits: GrantLimits;
  validFrom: string; // ISO instant
  validUntil: string; // ISO instant
  paused: boolean;
  revokedAt: string | null;
}

export const SOUND_ID_PATTERN = /^(bundled:[a-z0-9][a-z0-9._-]{0,39}|media:[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})$/;

export const BUNDLED_SOUNDS = [
  'bundled:quack.classic',
  'bundled:quack.double',
  'bundled:chime.soft',
  'bundled:bell.bright',
  'bundled:rise.gentle',
  'bundled:pulse.wake',
] as const;

export const GRANT_BOUNDS = {
  maxValidityDays: 366,
  nudge: { maxPerHour: [1, 12], windows: [1, 7], sounds: [1, 20] },
  alarm: { maxAlarmSeconds: [10, 300], maxPerDay: [1, 3], windows: [1, 7], sounds: [1, 20], followUpDelay: [60, 600] },
  challenge: { types: [1, CHALLENGE_STEP_TYPES.length], maxDurationSeconds: [10, 900] },
  focus: { maxSessionMinutes: [5, 120], maxPerDay: [1, 6], windows: [1, 7] },
  checkin: { maxPerDay: [1, 6] },
} as const;

/** Which limits block each capability requires. */
const REQUIRED_LIMITS: Record<Capability, keyof GrantLimits | null> = {
  'nudge.send': 'nudge',
  'alarm.request': 'alarm',
  'alarm.schedule': 'alarm',
  'challenge.assign': 'challenge',
  'focus.request': 'focus',
  'safety.checkin.request': 'checkin',
  'location.request': null,
};

export interface GrantDraft {
  capabilities: string[];
  limits: GrantLimits;
  validUntil: string;
}

function intIn(v: unknown, [min, max]: readonly [number, number]): boolean {
  return Number.isInteger(v) && (v as number) >= min && (v as number) <= max;
}

function checkWindows(windows: unknown, bounds: readonly [number, number], path: string, issues: WindowIssue[]): void {
  if (!Array.isArray(windows) || windows.length < bounds[0] || windows.length > bounds[1]) {
    issues.push({ path, message: `must contain ${bounds[0]}-${bounds[1]} windows` });
    return;
  }
  windows.forEach((w, i) => issues.push(...validateWindow(w, `${path}[${i}]`)));
}

function checkSounds(sounds: unknown, bounds: readonly [number, number], path: string, issues: WindowIssue[]): void {
  if (!Array.isArray(sounds) || sounds.length < bounds[0] || sounds.length > bounds[1]) {
    issues.push({ path, message: `must contain ${bounds[0]}-${bounds[1]} sounds` });
    return;
  }
  const seen = new Set<string>();
  for (const s of sounds) {
    if (typeof s !== 'string' || !SOUND_ID_PATTERN.test(s)) issues.push({ path, message: `invalid sound id` });
    else if (seen.has(s)) issues.push({ path, message: 'sounds must be unique' });
    else seen.add(s);
  }
}

/**
 * Semantic validation for a grant the recipient is creating or editing.
 * Structural typing is also enforced by the API's JSON schema; this function is
 * the shared source of truth for bounds and capability/limits consistency.
 */
export function validateGrantDraft(draft: GrantDraft, now: Date): WindowIssue[] {
  const issues: WindowIssue[] = [];
  const caps = draft.capabilities;
  if (!Array.isArray(caps) || caps.length === 0) {
    issues.push({ path: 'capabilities', message: 'at least one capability is required' });
    return issues;
  }
  const seen = new Set<string>();
  for (const c of caps) {
    if (!(CAPABILITIES as readonly string[]).includes(c)) issues.push({ path: 'capabilities', message: `unknown capability ${c}` });
    if (seen.has(c)) issues.push({ path: 'capabilities', message: `duplicate capability ${c}` });
    seen.add(c);
  }
  const capSet = new Set(caps as Capability[]);

  const until = Date.parse(draft.validUntil);
  if (Number.isNaN(until)) issues.push({ path: 'validUntil', message: 'must be an ISO instant' });
  else if (until <= now.getTime()) issues.push({ path: 'validUntil', message: 'must be in the future' });
  else if (until - now.getTime() > GRANT_BOUNDS.maxValidityDays * 86_400_000) {
    issues.push({ path: 'validUntil', message: `must be within ${GRANT_BOUNDS.maxValidityDays} days` });
  }

  const limits = draft.limits ?? {};
  const needed = new Set<keyof GrantLimits>();
  for (const c of capSet) {
    const key = REQUIRED_LIMITS[c];
    if (key) needed.add(key);
  }
  for (const key of Object.keys(limits) as (keyof GrantLimits)[]) {
    if (!needed.has(key)) issues.push({ path: `limits.${key}`, message: 'limits provided for a capability that is not granted' });
  }
  for (const key of needed) {
    if (!limits[key]) issues.push({ path: `limits.${key}`, message: 'required by the granted capabilities' });
  }
  if (limits.nudge) {
    const n = limits.nudge;
    if (!intIn(n.maxPerHour, GRANT_BOUNDS.nudge.maxPerHour)) issues.push({ path: 'limits.nudge.maxPerHour', message: 'must be 1-12' });
    checkWindows(n.windows, GRANT_BOUNDS.nudge.windows, 'limits.nudge.windows', issues);
    checkSounds(n.sounds, GRANT_BOUNDS.nudge.sounds, 'limits.nudge.sounds', issues);
    if (typeof n.overridesQuietHours !== 'boolean') issues.push({ path: 'limits.nudge.overridesQuietHours', message: 'must be boolean' });
  }
  if (limits.alarm) {
    const a = limits.alarm;
    if (!intIn(a.maxAlarmSeconds, GRANT_BOUNDS.alarm.maxAlarmSeconds)) issues.push({ path: 'limits.alarm.maxAlarmSeconds', message: 'must be 10-300' });
    if (!intIn(a.maxPerDay, GRANT_BOUNDS.alarm.maxPerDay)) issues.push({ path: 'limits.alarm.maxPerDay', message: 'must be 1-3' });
    checkWindows(a.windows, GRANT_BOUNDS.alarm.windows, 'limits.alarm.windows', issues);
    checkSounds(a.sounds, GRANT_BOUNDS.alarm.sounds, 'limits.alarm.sounds', issues);
    if (!a.followUp || typeof a.followUp.allowed !== 'boolean') issues.push({ path: 'limits.alarm.followUp', message: 'must be provided' });
    else if (!intIn(a.followUp.maxDelaySeconds, GRANT_BOUNDS.alarm.followUpDelay)) {
      issues.push({ path: 'limits.alarm.followUp.maxDelaySeconds', message: 'must be 60-600' });
    }
  }
  if (limits.challenge) {
    const c = limits.challenge;
    if (!Array.isArray(c.types) || c.types.length < 1 || c.types.length > CHALLENGE_STEP_TYPES.length) {
      issues.push({ path: 'limits.challenge.types', message: 'must list allowed challenge types' });
    } else {
      const uniq = new Set(c.types);
      if (uniq.size !== c.types.length) issues.push({ path: 'limits.challenge.types', message: 'must be unique' });
      for (const t of c.types) {
        if (!(CHALLENGE_STEP_TYPES as readonly string[]).includes(t)) issues.push({ path: 'limits.challenge.types', message: `unknown type ${t}` });
      }
    }
    if (!intIn(c.maxDurationSeconds, GRANT_BOUNDS.challenge.maxDurationSeconds)) {
      issues.push({ path: 'limits.challenge.maxDurationSeconds', message: 'must be 10-900' });
    }
    if (!(DIFFICULTIES as readonly string[]).includes(c.maxDifficulty)) issues.push({ path: 'limits.challenge.maxDifficulty', message: 'invalid' });
  }
  if (limits.focus) {
    const f = limits.focus;
    if (!intIn(f.maxSessionMinutes, GRANT_BOUNDS.focus.maxSessionMinutes)) issues.push({ path: 'limits.focus.maxSessionMinutes', message: 'must be 5-120' });
    if (!intIn(f.maxPerDay, GRANT_BOUNDS.focus.maxPerDay)) issues.push({ path: 'limits.focus.maxPerDay', message: 'must be 1-6' });
    checkWindows(f.windows, GRANT_BOUNDS.focus.windows, 'limits.focus.windows', issues);
  }
  if (limits.checkin) {
    if (!intIn(limits.checkin.maxPerDay, GRANT_BOUNDS.checkin.maxPerDay)) issues.push({ path: 'limits.checkin.maxPerDay', message: 'must be 1-6' });
  }
  return issues;
}

export function isGrantActive(grant: GrantSnapshot | null | undefined, now: Date): grant is GrantSnapshot {
  if (!grant) return false;
  if (grant.revokedAt) return false;
  const t = now.getTime();
  return Date.parse(grant.validFrom) <= t && t < Date.parse(grant.validUntil);
}

export function grantAllows(grant: GrantSnapshot | null | undefined, capability: Capability, now: Date): boolean {
  return isGrantActive(grant, now) && !grant.paused && grant.capabilities.includes(capability);
}

export type PresetId = 'gentle_nudges' | 'wake_buddy' | 'focus_buddy';

const EVERY_DAY = [1, 2, 3, 4, 5, 6, 7];
const WEEKDAYS = [1, 2, 3, 4, 5];

/**
 * Starting points shown in the permission editor. Each expands into editable
 * controls; there is deliberately no "allow everything" preset.
 */
export function presetDraft(preset: PresetId, now: Date, validityDays = 90): GrantDraft {
  const validUntil = new Date(now.getTime() + validityDays * 86_400_000).toISOString();
  switch (preset) {
    case 'gentle_nudges':
      return {
        capabilities: ['nudge.send'],
        validUntil,
        limits: {
          nudge: {
            maxPerHour: 3,
            windows: [{ days: EVERY_DAY, start: '08:00', end: '22:00' }],
            sounds: ['bundled:quack.classic', 'bundled:quack.double', 'bundled:chime.soft'],
            overridesQuietHours: false,
          },
        },
      };
    case 'wake_buddy':
      return {
        capabilities: ['alarm.schedule', 'alarm.request', 'challenge.assign'],
        validUntil,
        limits: {
          alarm: {
            maxAlarmSeconds: 120,
            maxPerDay: 1,
            windows: [{ days: WEEKDAYS, start: '06:00', end: '10:00' }],
            sounds: ['bundled:rise.gentle', 'bundled:bell.bright', 'bundled:quack.classic'],
            followUp: { allowed: true, maxDelaySeconds: 300 },
          },
          challenge: {
            types: ['multiple_choice', 'short_answer', 'math', 'memory_order', 'chess'],
            maxDurationSeconds: 90,
            maxDifficulty: 'medium',
          },
        },
      };
    case 'focus_buddy':
      return {
        capabilities: ['focus.request', 'challenge.assign'],
        validUntil,
        limits: {
          focus: { maxSessionMinutes: 30, maxPerDay: 3, windows: [{ days: WEEKDAYS, start: '09:00', end: '21:00' }] },
          challenge: { types: ['multiple_choice', 'math', 'chess', 'timer'], maxDurationSeconds: 60, maxDifficulty: 'easy' },
        },
      };
  }
}
