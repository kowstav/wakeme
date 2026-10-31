# Runbooks

Operational procedures for the Wake Me backend. Required before launch by doc 02 §13 and
doc 04 §10 ("restore, rollback, incident response, lost-device and account-deletion drills
completed").

**Drill status: none of these has been exercised.** No staging or production environment
exists yet (no Supabase project, push credentials or hosting have been provisioned; doing so
needs the owner's authorization). Each runbook names the automated tests that cover its
server behaviour; the drill itself is still a launch gate. Record each drill (date, people,
environment, timings, what went wrong) at the end of the runbook.

| Runbook | Use when |
|---|---|
| [incident-response.md](incident-response.md) | Anything user-affecting: outage, abuse wave, data exposure, a bad release |
| [noisy-friend.md](noisy-friend.md) | Someone reports unwanted sounds, alarms, messages or requests from a friend |
| [key-rotation.md](key-rotation.md) | Scheduled rotation, staff departure, or a suspected key or secret leak |
| [restore.md](restore.md) | Data loss or corruption; bad migration; rolling back a deploy |
| [billing-reconciliation.md](billing-reconciliation.md) | Plus status wrong, missed store webhooks, refunds |
| [account-deletion-and-export.md](account-deletion-and-export.md) | Deletion/export requests, lost or stolen phones |

## Ground rules (all runbooks)

- **Personal alarms are never the server's to stop.** Kill switches and suspensions stop new
  remote behaviour. Alarms already installed on a phone, the in-app escape and system Stop
  controls keep working with no network (CLAUDE.md invariants).
- **Support sees metadata, not content.** Use request IDs (`error.requestId` in every error
  response, `requestId` in logs). Do not read messages, location or challenge answers to
  resolve a ticket; the moderation queue shows only the minimal evidence a report captured.
- **Every admin action is audited.** Admin routes require the user ID in `ADMIN_USER_IDS`,
  `app_metadata.wakeme_role` of `moderator` or `operator`, and an MFA (`aal2`) session. Each
  call writes an `audit_events` row with the actor and a note.
- **Never paste secrets** into tickets, chat, logs, prompts or this repository.

## Calling admin routes

```sh
API=https://<api-host>             # not yet decided
TOKEN=<operator access token>      # from an aal2 (MFA) session; expires quickly
curl -sS -H "Authorization: Bearer $TOKEN" "$API/v1/admin/feature-flags"
```

## Operator commands (run with the deployment's environment)

| Command | What it does |
|---|---|
| `npm run migrate -w @wakeme/api` | Applies pending migrations with `MIGRATION_DATABASE_URL` (forward-only, checksummed) |
| `npm run resign-challenges -w @wakeme/api` | Re-signs published challenge revisions with the current signing key ([key-rotation.md](key-rotation.md)) |
| `npm run post-restore -w @wakeme/api` | After a database restore: makes every phone re-fetch its lists once ([restore.md](restore.md)) |
