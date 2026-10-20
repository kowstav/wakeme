# ADR 0016: Android alarms before first unlock

Accepted 2026-10-20. This corrects an implementation gap; physical reboot evidence is still required.

The old device-protected alarm mirror existed, but none of the alarm components opted into Direct
Boot. Application startup also eagerly constructed account dependencies, and alarm receipts tried
to start WorkManager before credential-encrypted storage was available.

Only the alarm receiver, reschedule receiver, alarm service and alarm activity are Direct Boot
aware. Account UI, network work, Room, custom recordings and quiz answers stay credential encrypted.
`BootAlarms` has no dependency on those systems. It registers immutable, explicit alarm intents from
the existing mirror. Bundled audio works before unlock; custom audio uses the bundled fallback.
Challenge identity is retained so an unavailable quiz cannot be mistaken for a completed quiz.
The sound stop and wake-session escape remain local.

Cancelling a follow-up before unlock records a device-protected cancellation. After unlock, Room is
reconciled before the normal sync starts, preventing a cancelled follow-up from returning. Receipts
contain IDs/outcomes only and wait in a device-protected outbox until WorkManager commits them after
unlock. Unique work keys preserve idempotency if the process dies while transferring an item.

Account startup uses lazy injection and starts once on unlock (including an unlock that races
receiver registration). Protected system actions are allowlisted; private components remain
unexported. The warm MainActivity path replaces its Intent and accepts only HTTPS invite URLs with
the expected host and exactly two path segments.

Reference: [Android Direct Boot guidance](https://developer.android.com/privacy-and-security/direct-boot).
Verification: `BootAlarmsTest` exercises a context that rejects credential files/preferences/Room,
mirror scheduling, cancellation and reconciliation. This does not prove real reboot timing or
audibility. The Stage 0 locked-reboot experiment remains a release gate.
