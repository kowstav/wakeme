# Noisy friend

Someone says a friend is waking them, quacking them or messaging them more than they want.

## 1. The person can stop it themselves, immediately

Walk them through these first; none needs support and all work at once:

| Control | Where | Effect |
|---|---|---|
| Stop the sound | System Stop on the alarm or notification; the in-app escape | Always available, no network needed |
| Pause everyone | You → "Pause all requests" (`PATCH /v1/me` `pausedUntil`) | No remote requests reach the phone until the time passes; own alarms still ring |
| Quiet hours / sound budget | You | Limits for all friends together |
| Pause one friend | Friend → permissions → Pause (`POST /v1/grants/:id/pause`) | That friend's queued requests are cancelled |
| Remove a permission | Friend → permissions → edit or Remove (`DELETE /v1/grants/:id`) | Takes effect before the next request is sent; the phone also re-checks its own copy |
| Block | Friend → "Block and report" or "Block only" (`POST /v1/friendships/:id/block`) | Revokes grants in **both** directions atomically, ends the conversation, cancels queued requests |
| Report | Long-press a message, or Friend → "Block and report" (`POST /v1/reports`) | Opens a moderation case with minimal evidence |

These controls are in the Android app today. The iOS app has quiet hours and pause; its
friend page (permissions, block, report) is not built yet.

Covered by tests: `pausing a friend cancels their queued requests`, `blocking atomically
revokes grants in both directions…`, `client ids make sends idempotent; blocking ends the
conversation`, `revocation racing a queued command: the worker re-checks and never sends`.

## 2. Moderation queue (moderators with MFA)

```sh
curl -sS -H "Authorization: Bearer $TOKEN" "$API/v1/admin/reports?state=open&limit=50"
```

Each report shows the reason, the reporter's note and only the evidence captured when it was
filed (for a message: its text and time; for a request: capability and time). Viewing the
queue is audited. Do not look up other content.

Decide with `POST /v1/admin/reports/:id/resolve` and a note (required):

| Action | Use when | Effect |
|---|---|---|
| `dismiss` | No policy breach | Report closed |
| `remove_content` | A message, challenge or sound breaks policy | Message hidden for everyone; challenge archived; sound rejected and its file deleted |
| `suspend_user` | Harassment, repeated unwanted alerts, unsafe challenges, impersonation | Account suspended; **every grant it gave or holds is revoked**; its queued requests cancelled |

```sh
curl -sS -X POST -H "Authorization: Bearer $TOKEN" -H 'content-type: application/json' \
  -d '{"action":"suspend_user","note":"Repeated unwanted alarms after a block request (report <id>)"}' \
  "$API/v1/admin/reports/<report-id>/resolve"
```

Lifting a suspension (`POST /v1/admin/users/:id/reinstate` with a note) does **not** restore
revoked permissions; friends must grant them again. That is deliberate.

## 3. Patterns that need escalation

- Several reports against one account from different people within a day: suspend first, then review.
- Reports of a threatening message, stalking or coercion: SEV1 under
  [incident-response.md](incident-response.md); consider the `location` kill switch if location is involved.
- A sender getting past limits they should not be able to exceed: treat as a security incident
  (cross-user control), capture request IDs and command IDs, and escalate to engineering.

## Drill log

Not yet exercised. Drill: two test accounts, one sends quacks up to its limit; the recipient
pauses, then blocks; a third account reports; a moderator suspends and reinstates; verify the
grants stay revoked and the audit trail is complete.
