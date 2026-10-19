import Type from 'typebox';
import { COMMAND_CAPABILITIES, EXPIRY_BOUNDS, evaluateCommand, type CommandCapability, type CommandRequest } from '@wakeme/policy';
import type { Queryable } from '../../db/db.ts';
import { Nullable } from '../../http/route.ts';
import { deviceReadiness, primaryDevice, recipientSettings, type UserRow } from '../identity/repo.ts';
import { toSnapshot, type GrantRow } from '../grants/repo.ts';

export const FriendAction = Type.Object({
  capability: Type.String(),
  granted: Type.Boolean(),
  availableNow: Type.Boolean(),
  /** Policy reason when not available (window, quiet hours, pause, readiness). Rate limits are checked on send. */
  reason: Nullable(Type.String()),
});

function sampleRequest(capability: CommandCapability, grant: GrantRow): CommandRequest {
  const expiresInSeconds = EXPIRY_BOUNDS[capability].default;
  switch (capability) {
    case 'nudge.send':
      return { capability, soundId: grant.limits.nudge?.sounds[0] ?? 'bundled:quack.classic', expiresInSeconds };
    case 'alarm.request':
      return { capability, soundId: grant.limits.alarm?.sounds[0] ?? 'bundled:rise.gentle', ringSeconds: Math.min(60, grant.limits.alarm?.maxAlarmSeconds ?? 60), expiresInSeconds };
    case 'focus.request':
      return { capability, minutes: Math.min(25, grant.limits.focus?.maxSessionMinutes ?? 25), expiresInSeconds };
    case 'safety.checkin.request':
    case 'location.request':
      return { capability, expiresInSeconds };
  }
}

/**
 * What the sender can do right now, for the friend screen. Only permitted
 * actions are presented as available; disabled ones carry a literal reason.
 */
export async function friendActions(q: Queryable, me: UserRow, friend: UserRow, theirGrant: GrantRow | null, now: Date, disabled: ReadonlySet<string>) {
  const device = deviceReadiness(await primaryDevice(q, friend));
  const zero = { count: 0, oldestAt: null };
  return COMMAND_CAPABILITIES.map((capability) => {
    const granted = !!theirGrant && theirGrant.capabilities.includes(capability);
    if (!granted || !theirGrant) return { capability, granted: false, availableNow: false, reason: 'capability_not_granted' };
    const d = evaluateCommand({
      now,
      actor: { id: me.id, active: me.account_state === 'active' },
      target: { id: friend.id, active: friend.account_state === 'active', timeZone: friend.time_zone, settings: recipientSettings(friend) },
      friendship: { state: 'accepted' },
      grant: toSnapshot(theirGrant),
      request: sampleRequest(capability, theirGrant),
      counts: { senderToTargetLastHour: zero, senderToTargetToday: 0, targetAudibleLastHour: zero, senderAllLastHour: zero },
      disabledFeatures: disabled,
      device,
    });
    return d.allowed ? { capability, granted: true, availableNow: true, reason: null } : { capability, granted: true, availableNow: false, reason: d.reason };
  });
}
