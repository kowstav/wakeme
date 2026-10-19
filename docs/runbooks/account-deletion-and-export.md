# Account deletion, export and lost phones

## Deletion

People delete their account in the app (Android: You → Delete account; the iOS screen is not
built yet and is required before App Store submission). It calls `DELETE /v1/account`, which
needs a sign-in within the last 15 minutes (`REAUTH_SECONDS_DELETION=900`).

What happens **immediately** (one transaction):

- Account becomes `deletion_pending`; the primary phone is cleared.
- Every grant the person gave or received is revoked; queued requests to or from them are
  cancelled; routines they own or created are cancelled; location sessions end and viewer
  access is revoked; invites are revoked; every device is signed out and its push token removed.
- Friends' phones receive the revocations through sync.

What happens **after 7 days** (`DELETION_GRACE_DAYS`, worker job `delete_account`):

- Custom sound files are deleted from storage; the identity is deleted from Supabase Auth.
- Messages they sent are blanked (others see a "deleted message" placeholder); the user row
  and everything referencing it are deleted; an `account.deleted` audit row (no personal data)
  remains for 365 days.

During the grace period the person can sign in and cancel (`POST /v1/account/deletion/cancel`).
Revoked permissions are **not** restored; friends must grant them again.

Tests (`account.test.ts`): `deletion needs a recent sign-in, deactivates at once, revokes
everything, and deletes after the grace period`; `deletion can be cancelled during the grace
period (permissions stay revoked)`.

### Daily check

```sql
-- Deletions that should have completed (job failed or was lost).
select id, deletion_requested_at from users
 where account_state = 'deletion_pending' and deletion_requested_at < now() - interval '8 days';
```
For each row, read the job's `last_error` in `outbox` (`kind = 'delete_account'`, kept for 7
days after it ends). Fix the cause (for example a rotated Supabase key) and re-enqueue with a
new dedupe key, as in [restore.md](restore.md) step 5.

### Requests by email

Support may receive deletion requests from people who cannot sign in. Verify ownership through
the identity provider (a code sent to the account's email) before acting; never on a
display name or a phone number. Then an operator runs the same deletion as above.

## Export

`GET /v1/account/export` (5 per hour) returns the person's own data as JSON: profile and
settings, devices, friendships, grants given and received, routines with every version,
challenges they made with revisions, challenge sessions, requests sent and received (latest
1,000 each), messages they sent (latest 5,000), sound metadata, location sessions they started
(coordinates are not kept after a session ends) and subscription records. Android: You → Export my data saves a file. iOS:
not built yet.

## Retention (proposals pending legal review, `services/api/src/worker/periodic.ts`)

| Data | Kept |
|---|---|
| Location samples | Only the latest, only until the session ends (at most 8 hours) |
| Request metadata (`commands`), location access log | 30 days |
| Sync feed | 45 days (older cursors re-fetch) |
| Processed background jobs, invite attempts | 7 days |
| Expired invites | 30 days |
| Audit records | 365 days |
| Signed-request nonces | 15 minutes |
| Deleted accounts | 7-day grace, then erased as above |

Backups must not outlive these by more than the PITR window ([restore.md](restore.md)).

## Lost or stolen phone

1. From another signed-in device: You → Phones → sign out the lost phone
   (`DELETE /v1/devices/:id`). It can no longer send requests or receipts, and pushes stop.
   If it was the primary phone, make another phone primary so routines install there.
2. Without another device: sign in on a new phone (email code); it registers a new device key.
   Then sign out the old one from the new phone.
3. Suggest the person sign out other sessions in account security settings, and change the
   email account password if it may be compromised.
4. Alarms already installed on the lost phone keep ringing there until it syncs, is wiped,
   or is signed out locally; the server cannot stop local alarms (by design: alarms work offline).

Test: `device signatures: required, bound to body, single use, clock-checked, revocable`.

## Drill log

Not yet exercised. Drill: delete a test account with friends and routines; confirm friends'
phones drop the permissions; advance the grace period in staging; confirm storage objects and
the auth identity are gone and the export of a friend no longer names the deleted account.
