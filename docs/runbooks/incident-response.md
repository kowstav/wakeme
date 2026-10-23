# Incident response

## 1. Triage (first 15 minutes)

1. Name an incident lead and open an incident channel. Write a timeline as you go (UTC).
2. Classify:
   - **SEV1**: data exposure; cross-user control (someone made a phone ring without a grant);
     location shown to the wrong person; an alarm cannot be stopped; account takeover at scale.
   - **SEV2**: remote requests failing or late for many people; push provider outage; AI or
     moderation producing unsafe content; billing granting or removing Plus wrongly.
   - **SEV3**: degraded single feature with a workaround.
3. Check health: `GET /healthz` (process up), `GET /readyz` (database reachable). Worker
   health: outbox backlog (`select count(*) from outbox where processed_at is null and due_at < now()`).

## 2. Contain with kill switches

Kill switches stop **new** risky remote behaviour within about 5 seconds (each process caches
flags for 5 s). They never touch alarms already installed on phones or escape controls.

```sh
curl -sS -X PUT -H "Authorization: Bearer $TOKEN" -H 'content-type: application/json' \
  -d '{"enabled": false, "note": "INC-123: <reason>"}' "$API/v1/admin/feature-flags/remote_dispatch.nudge"
```

| Flag | When off | Unaffected |
|---|---|---|
| `remote_dispatch.nudge` | New quacks refused (`feature_disabled`, 503); queued ones cancelled at dispatch | Messages, routines already installed |
| `remote_dispatch.alarm` | New remote wake requests refused; queued ones cancelled | Personal and accepted routines on the phone |
| `focus_requests` | Focus requests refused (off by default until Stage 6) | Local focus sessions already running end on their own timer |
| `safety_requests` | Check-in requests refused | Sessions people started themselves |
| `location` | New location sharing refused; location requests cancelled | Check-ins without location |
| `ai_generation` | Challenge drafting returns `ai_unavailable` | Hand-built quizzes, templates, installed challenges |
| `media_uploads` | Custom sound uploads refused | Built-in sounds; already approved sounds |

Apps read the same flags from `GET /v1/config` (`features`) and explain the pause instead of
failing silently. Re-enable with `"enabled": true` and a note once the cause is fixed.

Other containment:

- A single abusive account: suspend via the moderation queue ([noisy-friend.md](noisy-friend.md)).
  Suspension revokes every grant that account holds or gave and cancels its queued requests.
- A leaked key or secret: [key-rotation.md](key-rotation.md).
- A bad release: roll back the API/worker image to the previous version ([restore.md](restore.md) §4).
  Phones keep working offline for installed alarms.

## 3. Investigate

- Logs are structured JSON with `requestId`; tokens, coordinates, answers, prompts and
  message bodies are scrubbed by the logger.
- `audit_events` records grants, revocations, reports, admin actions, deletions and
  billing webhooks (retained 365 days).
- `commands` + `command_events` show request lifecycle and delivery evidence (retained 30
  days). "Provider accepted" is not proof that a phone rang.

## 4. Communicate

- SEV1/SEV2: status update within 1 hour, then at least every 2 hours until resolved.
- Never claim a fix was "verified on devices" without device evidence.
- Personal data breach: involve the privacy lead immediately; regulators may require
  notification within 72 hours (GDPR Art. 33). Legal review owns that decision.

## 5. Close

Write a blameless review within 5 working days: timeline, impact (people, requests,
duration), root cause, what detected it, follow-up actions with owners. Add a regression test
for any code fault.

## Drill log

Not yet exercised.
