-- Wake Me initial schema.
-- Invariants are enforced by constraints and indexes as well as application
-- code: canonical friend pairs, one live grant per direction, immutable
-- published challenge revisions, idempotent commands and delivery keys.

-- ---------------------------------------------------------------- identity
create table users (
  id uuid primary key default gen_random_uuid(),
  auth_subject text not null unique,
  display_name text not null check (char_length(display_name) between 1 and 40),
  locale text not null default 'en' check (char_length(locale) between 2 and 16),
  time_zone text not null default 'UTC' check (char_length(time_zone) between 1 and 64),
  account_state text not null default 'active' check (account_state in ('active', 'suspended', 'deletion_pending')),
  age_attested_at timestamptz not null,
  quiet_enabled boolean not null default true,
  quiet_start text not null default '22:00' check (quiet_start ~ '^([01][0-9]|2[0-3]):[0-5][0-9]$'),
  quiet_end text not null default '07:00' check (quiet_end ~ '^([01][0-9]|2[0-3]):[0-5][0-9]$'),
  paused_until timestamptz,
  sound_budget_per_hour integer not null default 6 check (sound_budget_per_hour between 1 and 20),
  share_bypass boolean not null default false,
  primary_device_id uuid,
  deletion_requested_at timestamptz,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now()
);

create table devices (
  id uuid primary key default gen_random_uuid(),
  user_id uuid not null references users(id) on delete cascade,
  install_id text not null check (char_length(install_id) between 8 and 64),
  platform text not null check (platform in ('ios', 'android')),
  os_version text not null check (char_length(os_version) <= 32),
  app_build text not null check (char_length(app_build) <= 32),
  public_key text not null check (char_length(public_key) <= 512),
  push_provider text check (push_provider in ('apns', 'fcm')),
  push_token text check (char_length(push_token) <= 4096),
  push_environment text check (push_environment in ('sandbox', 'production')),
  push_token_invalid_at timestamptz,
  capabilities jsonb not null default '{}'::jsonb,
  last_seen_at timestamptz not null default now(),
  revoked_at timestamptz,
  created_at timestamptz not null default now(),
  unique (user_id, install_id)
);
create index devices_user on devices (user_id) where revoked_at is null;

alter table users add constraint users_primary_device_fk
  foreign key (primary_device_id) references devices(id) on delete set null;

-- Replay protection for device-signed requests (purged after 10 minutes).
create table device_nonces (
  device_id uuid not null references devices(id) on delete cascade,
  signature_hash text not null,
  seen_at timestamptz not null default now(),
  primary key (device_id, signature_hash)
);

-- ------------------------------------------------------------ relationships
create table circles (
  id uuid primary key default gen_random_uuid(),
  name text not null check (char_length(name) between 1 and 40),
  created_by uuid not null references users(id) on delete cascade,
  archived_at timestamptz,
  created_at timestamptz not null default now()
);

create table circle_members (
  circle_id uuid not null references circles(id) on delete cascade,
  user_id uuid not null references users(id) on delete cascade,
  role text not null check (role in ('owner', 'admin', 'member')),
  joined_at timestamptz not null default now(),
  left_at timestamptz,
  primary key (circle_id, user_id)
);
create index circle_members_user on circle_members (user_id) where left_at is null;

create table invites (
  id uuid primary key default gen_random_uuid(),
  creator_id uuid not null references users(id) on delete cascade,
  kind text not null check (kind in ('friend', 'circle')),
  circle_id uuid references circles(id) on delete cascade,
  token_hash text not null unique,
  code_hash text not null unique,
  expires_at timestamptz not null,
  max_uses integer not null default 1 check (max_uses between 1 and 20),
  uses integer not null default 0 check (uses >= 0),
  revoked_at timestamptz,
  created_at timestamptz not null default now(),
  check ((kind = 'circle') = (circle_id is not null)),
  check (uses <= max_uses)
);
create index invites_creator on invites (creator_id, created_at desc);

-- Attempts to preview/redeem invites, for brute-force throttling of short codes.
create table invite_attempts (
  id bigint generated always as identity primary key,
  user_id uuid not null references users(id) on delete cascade,
  success boolean not null,
  attempted_at timestamptz not null default now()
);
create index invite_attempts_user on invite_attempts (user_id, attempted_at desc);

create table friendships (
  id uuid primary key default gen_random_uuid(),
  user_low uuid not null references users(id) on delete cascade,
  user_high uuid not null references users(id) on delete cascade,
  state text not null check (state in ('accepted', 'blocked')),
  blocked_by uuid references users(id) on delete cascade,
  created_via_invite uuid references invites(id) on delete set null,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  check (user_low < user_high),
  check ((state = 'blocked') = (blocked_by is not null)),
  unique (user_low, user_high)
);
create index friendships_high on friendships (user_high);

-- ------------------------------------------------------------------- grants
create table grants (
  id uuid primary key default gen_random_uuid(),
  grantor_id uuid not null references users(id) on delete cascade,
  grantee_id uuid not null references users(id) on delete cascade,
  version integer not null default 1 check (version >= 1),
  capabilities text[] not null check (cardinality(capabilities) between 1 and 7),
  limits jsonb not null,
  valid_from timestamptz not null,
  valid_until timestamptz not null,
  paused boolean not null default false,
  revoked_at timestamptz,
  revoked_reason text check (revoked_reason in ('grantor_revoked', 'blocked', 'friend_removed', 'account_deleted', 'moderation')),
  last_used_at timestamptz,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  check (grantor_id <> grantee_id),
  check (valid_until > valid_from)
);
-- At most one live grant per direction. A revoked grant is never revived.
create unique index grants_one_live_per_direction on grants (grantor_id, grantee_id) where revoked_at is null;
create index grants_grantee on grants (grantee_id) where revoked_at is null;

create table grant_versions (
  grant_id uuid not null references grants(id) on delete cascade,
  version integer not null,
  capabilities text[] not null,
  limits jsonb not null,
  valid_from timestamptz not null,
  valid_until timestamptz not null,
  paused boolean not null,
  revoked boolean not null default false,
  changed_by uuid references users(id) on delete set null,
  created_at timestamptz not null default now(),
  primary key (grant_id, version)
);

-- --------------------------------------------------------------- challenges
create table challenge_definitions (
  id uuid primary key default gen_random_uuid(),
  creator_id uuid not null references users(id) on delete cascade,
  circle_id uuid references circles(id) on delete set null,
  title text not null check (char_length(title) between 1 and 60),
  latest_revision integer not null default 0,
  published_revision integer,
  archived_at timestamptz,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now()
);
create index challenge_definitions_creator on challenge_definitions (creator_id);
create index challenge_definitions_circle on challenge_definitions (circle_id) where circle_id is not null;

create table challenge_revisions (
  id uuid primary key default gen_random_uuid(),
  definition_id uuid not null references challenge_definitions(id) on delete cascade,
  revision integer not null check (revision >= 1),
  state text not null check (state in ('draft', 'published')),
  body jsonb not null,
  origin text not null check (origin in ('manual', 'template', 'ai')),
  generation_id uuid,
  previewed_at timestamptz,
  payload text,
  digest text,
  signature text,
  key_id text,
  created_at timestamptz not null default now(),
  published_at timestamptz,
  unique (definition_id, revision),
  check (state = 'draft' or (payload is not null and digest is not null and signature is not null and published_at is not null))
);

-- A published revision can never change; edits create a new revision.
create function forbid_published_revision_update() returns trigger language plpgsql as $$
begin
  if old.state = 'published' then
    raise exception 'published challenge revisions are immutable' using errcode = 'check_violation';
  end if;
  return new;
end;
$$;
create trigger challenge_revisions_immutable before update on challenge_revisions
  for each row execute function forbid_published_revision_update();

create table challenge_generations (
  id uuid primary key default gen_random_uuid(),
  user_id uuid not null references users(id) on delete cascade,
  status text not null check (status in ('queued', 'running', 'succeeded', 'rejected', 'failed')),
  prompt text not null check (char_length(prompt) between 1 and 500),
  facts jsonb not null default '[]'::jsonb,
  difficulty text not null check (difficulty in ('easy', 'medium', 'hard')),
  result_definition_id uuid references challenge_definitions(id) on delete set null,
  rejection jsonb,
  error_code text,
  model text,
  input_tokens integer,
  output_tokens integer,
  cost_micros bigint,
  attempts integer not null default 0,
  created_at timestamptz not null default now(),
  completed_at timestamptz
);
create index challenge_generations_user on challenge_generations (user_id, created_at desc);

create table challenge_sessions (
  id uuid primary key default gen_random_uuid(),
  revision_id uuid not null references challenge_revisions(id) on delete cascade,
  assignee_id uuid not null references users(id) on delete cascade,
  device_id uuid references devices(id) on delete set null,
  context_kind text not null check (context_kind in ('alarm', 'command', 'practice', 'focus')),
  context_id text,
  state text not null check (state in ('started', 'completed', 'bypassed', 'expired')),
  evidence_tier text check (evidence_tier in ('local_deterministic', 'self_report')),
  started_at timestamptz not null default now(),
  ended_at timestamptz
);
create index challenge_sessions_assignee on challenge_sessions (assignee_id, started_at desc);

-- ------------------------------------------------------------------ credits
create table credit_ledger (
  id bigint generated always as identity primary key,
  user_id uuid not null references users(id) on delete cascade,
  delta integer not null check (delta <> 0),
  reason text not null check (reason in ('intro_grant', 'plus_monthly', 'generation', 'refund_system_failure', 'admin_adjustment')),
  ref text not null,
  created_at timestamptz not null default now(),
  unique (user_id, reason, ref)
);

-- -------------------------------------------------------------------- media
create table media_assets (
  id uuid primary key default gen_random_uuid(),
  owner_id uuid not null references users(id) on delete cascade,
  kind text not null check (kind in ('voice', 'alarm_sound')),
  object_key text not null unique,
  mime text not null check (mime in ('audio/wav', 'audio/mp4', 'audio/x-caf')),
  bytes integer not null check (bytes between 1 and 2097152),
  duration_ms integer check (duration_ms between 1 and 30000),
  sha256 text not null check (sha256 ~ '^[0-9a-f]{64}$'),
  state text not null check (state in ('pending_upload', 'uploaded', 'clean', 'rejected', 'deleted')),
  rejection_reason text,
  rights_attested_at timestamptz not null,
  created_at timestamptz not null default now(),
  finalized_at timestamptz,
  deleted_at timestamptz
);
create index media_assets_owner on media_assets (owner_id, created_at desc);

-- A recipient's approved library of friend-supplied sounds.
create table sound_library (
  owner_id uuid not null references users(id) on delete cascade,
  media_id uuid not null references media_assets(id) on delete cascade,
  offered_by uuid not null references users(id) on delete cascade,
  state text not null check (state in ('offered', 'approved', 'declined')),
  created_at timestamptz not null default now(),
  decided_at timestamptz,
  primary key (owner_id, media_id)
);

-- --------------------------------------------------------------- commands
create table commands (
  id uuid primary key default gen_random_uuid(),
  actor_id uuid not null references users(id) on delete cascade,
  target_id uuid not null references users(id) on delete cascade,
  capability text not null check (capability in ('nudge.send', 'alarm.request', 'focus.request', 'safety.checkin.request', 'location.request')),
  grant_id uuid not null references grants(id) on delete cascade,
  grant_version integer not null,
  payload jsonb not null,
  payload_hash text not null,
  idempotency_key text not null check (char_length(idempotency_key) between 8 and 64),
  delivery_mode text not null check (delivery_mode in ('audible', 'notify', 'silent')),
  target_device_id uuid references devices(id) on delete set null,
  expires_at timestamptz not null,
  lifecycle text not null default 'accepted' check (lifecycle in ('accepted', 'expired', 'cancelled')),
  transport text not null default 'queued' check (transport in ('queued', 'provider_accepted', 'provider_rejected', 'not_sent')),
  device_state text not null default 'unknown' check (device_state in ('unknown', 'received', 'installed', 'execution_reported')),
  human_state text not null default 'none' check (human_state in ('none', 'opened', 'acknowledged', 'challenge_completed', 'bypassed')),
  refused_reason text,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  unique (actor_id, idempotency_key),
  check (actor_id <> target_id)
);
create index commands_pair on commands (actor_id, target_id, capability, created_at desc);
create index commands_target_audible on commands (target_id, created_at desc) where capability in ('nudge.send', 'alarm.request');
create index commands_actor on commands (actor_id, created_at desc);
create index commands_open on commands (expires_at) where lifecycle = 'accepted';

create table command_events (
  seq bigint generated always as identity primary key,
  command_id uuid not null references commands(id) on delete cascade,
  kind text not null,
  actor_user_id uuid references users(id) on delete set null,
  device_id uuid references devices(id) on delete set null,
  reason text,
  client_time timestamptz,
  server_time timestamptz not null default now()
);
create index command_events_command on command_events (command_id, seq);
-- A device reports each receipt kind at most once per command (duplicate pushes are no-ops).
create unique index command_events_device_once on command_events (command_id, kind, device_id) where device_id is not null;

-- -------------------------------------------------------------- alarm plans
create table alarm_plans (
  id uuid primary key default gen_random_uuid(),
  owner_id uuid not null references users(id) on delete cascade,
  creator_id uuid not null references users(id) on delete cascade,
  state text not null check (state in ('awaiting_acceptance', 'active', 'paused', 'declined', 'cancelled')),
  -- Version the owner accepted (what should be installed); a friend's later
  -- edit is a pending version and never replaces it until the owner accepts.
  accepted_version integer,
  pending_version integer,
  latest_version integer not null default 1,
  cancelled_reason text,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now()
);
create index alarm_plans_owner on alarm_plans (owner_id) where state <> 'cancelled';
create index alarm_plans_creator on alarm_plans (creator_id) where state <> 'cancelled';

create table alarm_plan_versions (
  plan_id uuid not null references alarm_plans(id) on delete cascade,
  version integer not null check (version >= 1),
  label text not null default '' check (char_length(label) <= 40),
  local_time text not null check (local_time ~ '^([01][0-9]|2[0-3]):[0-5][0-9]$'),
  time_zone text not null,
  time_zone_policy text not null check (time_zone_policy in ('fixed', 'device')),
  recurrence jsonb not null,
  sound_id text not null,
  ring_seconds integer not null check (ring_seconds between 10 and 300),
  challenge_revision_id uuid references challenge_revisions(id) on delete set null,
  follow_up_delay_seconds integer check (follow_up_delay_seconds between 60 and 600),
  created_by uuid not null references users(id) on delete cascade,
  grant_id uuid references grants(id) on delete set null,
  grant_version integer,
  created_at timestamptz not null default now(),
  primary key (plan_id, version)
);

create table alarm_installations (
  plan_id uuid not null references alarm_plans(id) on delete cascade,
  device_id uuid not null references devices(id) on delete cascade,
  version integer not null,
  result text not null check (result in ('scheduled', 'failed', 'removed')),
  error_code text check (char_length(error_code) <= 64),
  next_fire_at timestamptz,
  scheduled_instants jsonb not null default '[]'::jsonb,
  asset_digest text check (char_length(asset_digest) <= 128),
  reported_at timestamptz not null default now(),
  primary key (plan_id, device_id)
);

create table alarm_occurrences (
  plan_id uuid not null references alarm_plans(id) on delete cascade,
  occurrence_key text not null check (occurrence_key ~ '^\d{4}-\d{2}-\d{2}$'),
  version integer not null,
  outcome text not null check (outcome in ('rang', 'stopped', 'challenge_completed', 'bypassed', 'follow_up_rang', 'session_ended', 'missed_unknown')),
  device_id uuid references devices(id) on delete set null,
  reported_at timestamptz not null default now(),
  primary key (plan_id, occurrence_key)
);

-- ------------------------------------------------------------ conversations
create table conversations (
  id uuid primary key default gen_random_uuid(),
  kind text not null check (kind in ('direct', 'circle')),
  user_low uuid references users(id) on delete cascade,
  user_high uuid references users(id) on delete cascade,
  circle_id uuid unique references circles(id) on delete cascade,
  created_at timestamptz not null default now(),
  unique (user_low, user_high),
  check ((kind = 'direct' and user_low is not null and user_high is not null and circle_id is null)
      or (kind = 'circle' and circle_id is not null and user_low is null and user_high is null))
);

create table messages (
  id uuid primary key default gen_random_uuid(),
  conversation_id uuid not null references conversations(id) on delete cascade,
  sender_id uuid references users(id) on delete set null,
  body text check (char_length(body) between 1 and 2000),
  client_id text not null check (char_length(client_id) between 8 and 64),
  moderation_state text not null default 'visible' check (moderation_state in ('visible', 'removed')),
  created_at timestamptz not null default now(),
  deleted_at timestamptz,
  unique (conversation_id, sender_id, client_id)
);
create index messages_conversation on messages (conversation_id, created_at desc);

create table message_reactions (
  message_id uuid not null references messages(id) on delete cascade,
  user_id uuid not null references users(id) on delete cascade,
  emoji text not null check (emoji in ('👍', '❤️', '😂', '🦆', '🙌', '😴', '☀️')),
  created_at timestamptz not null default now(),
  primary key (message_id, user_id)
);

create table conversation_reads (
  conversation_id uuid not null references conversations(id) on delete cascade,
  user_id uuid not null references users(id) on delete cascade,
  read_at timestamptz not null,
  primary key (conversation_id, user_id)
);

-- ------------------------------------------------------------------ safety
create table location_sessions (
  id uuid primary key default gen_random_uuid(),
  owner_id uuid not null references users(id) on delete cascade,
  purpose text not null check (purpose in ('share', 'sos', 'checkin')),
  share_location boolean not null,
  message text check (char_length(message) <= 200),
  consent_version text not null,
  started_at timestamptz not null default now(),
  ends_at timestamptz not null,
  ended_at timestamptz,
  checkin_due_at timestamptz,
  checked_in_at timestamptz,
  missed_notified_at timestamptz,
  check (ends_at > started_at),
  check (ends_at <= started_at + interval '8 hours')
);
create index location_sessions_owner on location_sessions (owner_id, started_at desc);
create index location_sessions_checkin on location_sessions (checkin_due_at) where checked_in_at is null and ended_at is null and missed_notified_at is null;

create table location_session_viewers (
  session_id uuid not null references location_sessions(id) on delete cascade,
  viewer_id uuid not null references users(id) on delete cascade,
  acknowledged_at timestamptz,
  revoked_at timestamptz,
  primary key (session_id, viewer_id)
);
create index location_session_viewers_viewer on location_session_viewers (viewer_id) where revoked_at is null;

-- Latest sample only (overwritten); coordinates encrypted at the application layer.
create table location_samples (
  session_id uuid primary key references location_sessions(id) on delete cascade,
  captured_at timestamptz not null,
  received_at timestamptz not null default now(),
  accuracy_m integer not null check (accuracy_m between 0 and 100000),
  ciphertext text not null,
  nonce text not null,
  key_id text not null,
  expires_at timestamptz not null
);

create table location_access_log (
  id bigint generated always as identity primary key,
  session_id uuid not null references location_sessions(id) on delete cascade,
  viewer_id uuid not null references users(id) on delete cascade,
  accessed_at timestamptz not null default now()
);

-- -------------------------------------------------------------- moderation
create table reports (
  id uuid primary key default gen_random_uuid(),
  reporter_id uuid references users(id) on delete set null,
  target_user_id uuid references users(id) on delete set null,
  target_kind text not null check (target_kind in ('user', 'message', 'challenge', 'media', 'command')),
  target_id text not null,
  reason text not null check (reason in ('spam', 'harassment', 'unwanted_alerts', 'unsafe_challenge', 'impersonation', 'inappropriate_content', 'other')),
  note text check (char_length(note) <= 500),
  evidence jsonb not null default '{}'::jsonb,
  state text not null default 'open' check (state in ('open', 'actioned', 'dismissed')),
  resolution text,
  resolved_by uuid references users(id) on delete set null,
  created_at timestamptz not null default now(),
  resolved_at timestamptz
);
create index reports_open on reports (created_at) where state = 'open';

-- ----------------------------------------------------------------- billing
create table entitlements (
  user_id uuid not null references users(id) on delete cascade,
  entitlement text not null,
  product_id text,
  store text,
  period_type text,
  purchased_at timestamptz,
  expires_at timestamptz,
  grace_expires_at timestamptz,
  will_renew boolean,
  billing_issue_at timestamptz,
  unsubscribe_at timestamptz,
  refunded_at timestamptz,
  sandbox boolean not null default false,
  synced_at timestamptz not null default now(),
  primary key (user_id, entitlement)
);

create table billing_events (
  provider text not null,
  event_id text not null,
  user_id uuid references users(id) on delete set null,
  event_type text not null,
  received_at timestamptz not null default now(),
  processed_at timestamptz,
  primary key (provider, event_id)
);

-- -------------------------------------------------------------- operations
create table feature_flags (
  key text primary key,
  enabled boolean not null,
  updated_by uuid references users(id) on delete set null,
  updated_at timestamptz not null default now()
);
insert into feature_flags (key, enabled) values
  ('remote_dispatch.nudge', true),
  ('remote_dispatch.alarm', true),
  ('focus_requests', false),
  ('safety_requests', true),
  ('location', true),
  ('ai_generation', true),
  ('media_uploads', true);

create table audit_events (
  id bigint generated always as identity primary key,
  actor_user_id uuid references users(id) on delete set null,
  actor_kind text not null check (actor_kind in ('user', 'admin', 'system')),
  action text not null,
  target_kind text,
  target_id text,
  data jsonb not null default '{}'::jsonb,
  created_at timestamptz not null default now()
);
create index audit_events_target on audit_events (target_kind, target_id);

-- Per-user change feed backing GET /v1/sync. Push is only a hint; this is the source of truth.
create table sync_events (
  seq bigint generated always as identity primary key,
  user_id uuid not null references users(id) on delete cascade,
  entity text not null,
  entity_id text not null,
  op text not null check (op in ('upsert', 'delete')),
  created_at timestamptz not null default now()
);
create index sync_events_user_seq on sync_events (user_id, seq);

-- Highest purged sync sequence per user; a cursor below it must re-bootstrap.
create table sync_floor (
  user_id uuid primary key references users(id) on delete cascade,
  floor_seq bigint not null
);

create table outbox (
  id bigint generated always as identity primary key,
  kind text not null,
  dedupe_key text not null unique,
  payload jsonb not null,
  due_at timestamptz not null default now(),
  attempts integer not null default 0,
  max_attempts integer not null default 8,
  lease_until timestamptz,
  last_error text,
  processed_at timestamptz,
  created_at timestamptz not null default now()
);
create index outbox_ready on outbox (due_at) where processed_at is null;
