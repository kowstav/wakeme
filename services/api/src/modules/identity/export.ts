import type { Queryable } from '../../db/db.ts';
import type { UserRow } from './repo.ts';

/**
 * Personal data export. Contains the user's own data and the minimum needed
 * to make it meaningful (friends' display names). Push tokens, device keys
 * and other users' private settings are excluded. Location coordinates are
 * not retained long enough to export (latest sample only, short expiry).
 */
export async function exportAccount(q: Queryable, user: UserRow, now: Date): Promise<Record<string, unknown>> {
  const one = async (sql: string) => (await q.query(sql, [user.id])).rows;
  return {
    exportedAt: now.toISOString(),
    format: 'wakeme-export-v1',
    profile: {
      id: user.id,
      displayName: user.display_name,
      locale: user.locale,
      timeZone: user.time_zone,
      createdAt: user.created_at,
      ageAttestedAt: user.age_attested_at,
      settings: { quietEnabled: user.quiet_enabled, quietStart: user.quiet_start, quietEnd: user.quiet_end, pausedUntil: user.paused_until, soundBudgetPerHour: user.sound_budget_per_hour, shareBypass: user.share_bypass },
    },
    devices: await one(`select id, platform, os_version, app_build, created_at, last_seen_at, revoked_at from devices where user_id = $1`),
    friendships: await one(
      `select f.id, f.state, f.created_at, (case when f.blocked_by = $1 then true else false end) as blocked_by_me,
              u.display_name as friend_display_name
         from friendships f join users u on u.id = case when f.user_low = $1 then f.user_high else f.user_low end
        where (f.user_low = $1 or f.user_high = $1) and (f.state = 'accepted' or f.blocked_by = $1)`,
    ),
    grantsGiven: await one(`select id, grantee_id, version, capabilities, limits, valid_from, valid_until, paused, revoked_at, created_at from grants where grantor_id = $1`),
    grantsReceived: await one(`select id, grantor_id, version, capabilities, limits, valid_from, valid_until, paused, revoked_at, created_at from grants where grantee_id = $1`),
    alarmPlans: await one(
      `select p.id, p.owner_id, p.creator_id, p.state, p.accepted_version, p.latest_version, p.created_at,
              (select json_agg(v order by v.version) from alarm_plan_versions v where v.plan_id = p.id) as versions
         from alarm_plans p where p.owner_id = $1 or p.creator_id = $1`,
    ),
    challenges: await one(
      `select d.id, d.title, d.circle_id, d.created_at,
              (select json_agg(json_build_object('revision', r.revision, 'state', r.state, 'body', r.body, 'origin', r.origin, 'createdAt', r.created_at) order by r.revision)
                 from challenge_revisions r where r.definition_id = d.id) as revisions
         from challenge_definitions d where d.creator_id = $1`,
    ),
    challengeSessions: await one(`select id, revision_id, context_kind, state, evidence_tier, started_at, ended_at from challenge_sessions where assignee_id = $1`),
    commandsSent: await one(`select id, target_id, capability, delivery_mode, lifecycle, transport, device_state, human_state, created_at from commands where actor_id = $1 order by created_at desc limit 1000`),
    commandsReceived: await one(`select id, actor_id, capability, delivery_mode, lifecycle, transport, device_state, human_state, created_at from commands where target_id = $1 order by created_at desc limit 1000`),
    messagesSent: await one(`select id, conversation_id, body, created_at, deleted_at from messages where sender_id = $1 order by created_at desc limit 5000`),
    media: await one(`select id, kind, mime, bytes, duration_ms, state, rights_attested_at, created_at from media_assets where owner_id = $1`),
    locationSessions: await one(`select id, purpose, share_location, started_at, ends_at, ended_at, checkin_due_at, checked_in_at from location_sessions where owner_id = $1`),
    entitlements: await one(`select entitlement, product_id, store, period_type, purchased_at, expires_at, will_renew from entitlements where user_id = $1`),
    credits: await one(`select delta, reason, created_at from credit_ledger where user_id = $1 order by created_at`),
    reportsFiled: await one(`select id, target_kind, reason, state, created_at from reports where reporter_id = $1`),
    circles: await one(`select c.id, c.name, m.role, m.joined_at, m.left_at from circle_members m join circles c on c.id = m.circle_id where m.user_id = $1`),
  };
}
