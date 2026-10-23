# Restore and rollback

## 1. Backups (prerequisites, not yet provisioned)

- Production PostgreSQL must have point-in-time recovery (PITR) enabled. Keep backup retention
  no longer than the data-retention policy needs (doc 02 §11: "consistent backup deletion");
  proposal: 7 days of PITR.
- Object storage (custom sounds) needs versioning or a replicated bucket with the same
  retention.
- Secrets (signing and encryption keys) are backed up by the secret manager, separately from
  data. A database backup without the location key holds only unreadable coordinates.

## 2. Restoring the database

Phones keep ringing installed alarms during the whole procedure; they do not need the server.

1. Declare an incident ([incident-response.md](incident-response.md)). Pick the restore point
   (just before the corruption or bad write). Note what will be lost: everything written after
   that point (grants, routines, messages, reports, deletions requested).
2. Stop writes: scale the API and the worker to zero. Keep the damaged database for forensics;
   restore into a **new** instance.
3. Apply migrations with the build you will run: `npm run migrate -w @wakeme/api`
   (`MIGRATION_DATABASE_URL` pointing at the restored instance).
4. Run once: `npm run post-restore -w @wakeme/api`. It moves the sync feed past every cursor
   phones were given before the incident, so each phone re-fetches its lists on its next sync
   (ADR 0010). Without it, phones can silently skip changes made after the restore.
5. Re-apply deletions lost by the restore. Anyone whose identity no longer exists in Supabase
   Auth, or who asked for deletion after the restore point (audit `account.deletion_requested`
   in the damaged database), is deleted again, with two operators reviewing the list:
   ```sql
   update users set account_state = 'deletion_pending', deletion_requested_at = now() where id = any($1);
   insert into outbox (kind, dedupe_key, payload)
     select 'delete_account', 'delete_account:' || id || ':restore-<date>', jsonb_build_object('userId', id)
     from users where id = any($1);
   ```
   (These jobs run as soon as the worker starts; no new grace period.)
6. Point the API and worker at the restored instance and start the worker first. Its first
   tick runs retention (purging anything past retention) and expires stale requests; expired
   attention requests are never sent late. Notifications that were already sent after the
   restore point may be sent once more.
7. Start the API. Verify: `GET /readyz`; sign in with a test account and confirm `GET /v1/sync`
   with its old cursor answers `resetRequired: true` once; send a test quack between two test
   accounts.
8. Tell people what was lost (by time window) and that their phones refresh automatically.

What phones do: on `resetRequired` they re-fetch grants and routines. Android marks grants the
server no longer lists as not live and removes routines the server no longer lists (alarms
cancelled). iOS cancels alarms for routines the server no longer lists when the Routines list
loads.

Tests: `after a database restore every phone re-fetches once, then receives new changes`,
`a cursor past the head (a restore without the post-restore step) re-fetches instead of
waiting`, `never skips a change that commits after a later one` (PostgreSQL 17.11).

## 3. Restoring custom sounds

Restore the bucket to the same point. Sound rows whose object is missing fail to download and
the app falls back to a built-in sound; no alarm goes silent because a file is missing.

## 4. Rolling back a release

- API and worker images are immutable and tagged by commit. Roll back by deploying the
  previous tag.
- Migrations are forward-only and checksummed; never edit an applied one. Each migration must
  work with the previous release's code (expand, then contract in a later release): add
  nullable columns or new objects; drop or rename only after no running version uses them.
  Migrations 0002 and 0003 replace a function and add a trigger, both compatible with the
  previous release.
- To undo a migration, write a new one. Each migration runs in its own transaction, so a
  failure leaves the schema unchanged.
- Mobile apps cannot be rolled back on people's phones. Keep the API backward compatible with
  every app version still allowed to connect.

## Drill log

Not yet exercised. Drill: in staging, take a PITR restore to 30 minutes ago into a new
instance, run steps 3–7 against two test phones (one Android, one iPhone), measure the time
to recovery and confirm both phones converge.
