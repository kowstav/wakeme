import type { Queryable } from '../../db/db.ts';
import type { RecipientSettings } from '@wakeme/policy';

export interface UserRow {
  id: string;
  auth_subject: string;
  display_name: string;
  locale: string;
  time_zone: string;
  account_state: 'active' | 'suspended' | 'deletion_pending';
  age_attested_at: Date;
  quiet_enabled: boolean;
  quiet_start: string;
  quiet_end: string;
  paused_until: Date | null;
  sound_budget_per_hour: number;
  share_bypass: boolean;
  primary_device_id: string | null;
  deletion_requested_at: Date | null;
  created_at: Date;
  updated_at: Date;
}

export interface DeviceRow {
  id: string;
  user_id: string;
  install_id: string;
  platform: 'ios' | 'android';
  os_version: string;
  app_build: string;
  public_key: string;
  push_provider: 'apns' | 'fcm' | null;
  push_token: string | null;
  push_environment: 'sandbox' | 'production' | null;
  push_token_invalid_at: Date | null;
  capabilities: DeviceCapabilities;
  last_seen_at: Date;
  revoked_at: Date | null;
  created_at: Date;
}

/** Capability snapshot reported by the app; informs readiness, never grants authority. */
export interface DeviceCapabilities {
  notificationsAuthorized?: boolean;
  timeSensitiveAuthorized?: boolean;
  alarmAuthorized?: boolean;
  exactAlarmPermitted?: boolean;
  fullScreenIntentPermitted?: boolean;
  backgroundRefreshAvailable?: boolean;
  batteryOptimizationExempt?: boolean;
  speakerPreferenceSupported?: boolean;
  focusAuthorized?: boolean;
  locationAuthorized?: 'none' | 'when_in_use' | 'always';
}

export async function userBySubject(q: Queryable, subject: string): Promise<UserRow | null> {
  const r = await q.query<UserRow>('select * from users where auth_subject = $1', [subject]);
  return r.rows[0] ?? null;
}

export async function userById(q: Queryable, id: string, lock = false): Promise<UserRow | null> {
  const r = await q.query<UserRow>(`select * from users where id = $1${lock ? ' for update' : ''}`, [id]);
  return r.rows[0] ?? null;
}

export function recipientSettings(u: UserRow): RecipientSettings {
  return {
    quietHours: { enabled: u.quiet_enabled, start: u.quiet_start, end: u.quiet_end },
    pausedUntil: u.paused_until ? u.paused_until.toISOString() : null,
    soundBudgetPerHour: u.sound_budget_per_hour,
  };
}

export function isActive(u: UserRow | null | undefined): u is UserRow {
  return !!u && u.account_state === 'active';
}

export async function primaryDevice(q: Queryable, user: UserRow): Promise<DeviceRow | null> {
  if (!user.primary_device_id) return null;
  const r = await q.query<DeviceRow>('select * from devices where id = $1 and user_id = $2 and revoked_at is null', [user.primary_device_id, user.id]);
  return r.rows[0] ?? null;
}

/** Device readiness used by the policy engine. Capability flags are self-reported hints. */
export function deviceReadiness(device: DeviceRow | null): { pushable: boolean; alarmCapable: boolean } {
  if (!device || device.revoked_at) return { pushable: false, alarmCapable: false };
  const pushable = !!device.push_token && !device.push_token_invalid_at;
  const caps = device.capabilities ?? {};
  const alarmCapable = pushable && (device.platform === 'ios' ? caps.alarmAuthorized === true : caps.exactAlarmPermitted === true);
  return { pushable, alarmCapable };
}

export async function appendSync(q: Queryable, userIds: readonly string[], entity: string, entityId: string, op: 'upsert' | 'delete' = 'upsert'): Promise<void> {
  const unique = [...new Set(userIds)];
  if (unique.length === 0) return;
  await q.query(
    `insert into sync_events (user_id, entity, entity_id, op) select u, $2, $3, $4 from unnest($1::uuid[]) as u`,
    [unique, entity, entityId, op],
  );
}

export async function audit(
  q: Queryable,
  entry: { actorUserId: string | null; actorKind: 'user' | 'admin' | 'system'; action: string; targetKind?: string; targetId?: string; data?: Record<string, unknown> },
): Promise<void> {
  await q.query('insert into audit_events (actor_user_id, actor_kind, action, target_kind, target_id, data) values ($1, $2, $3, $4, $5, $6::jsonb)', [
    entry.actorUserId,
    entry.actorKind,
    entry.action,
    entry.targetKind ?? null,
    entry.targetId ?? null,
    JSON.stringify(entry.data ?? {}),
  ]);
}

export async function enqueue(
  q: Queryable,
  job: { kind: string; dedupeKey: string; payload: Record<string, unknown>; dueAt?: Date; maxAttempts?: number },
): Promise<void> {
  await q.query(
    `insert into outbox (kind, dedupe_key, payload, due_at, max_attempts) values ($1, $2, $3::jsonb, coalesce($4::timestamptz, now()), $5)
     on conflict (dedupe_key) do nothing`,
    [job.kind, job.dedupeKey, JSON.stringify(job.payload), job.dueAt ?? null, job.maxAttempts ?? 8],
  );
  await q.query(`select pg_notify('outbox', '')`);
}
