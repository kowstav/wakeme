# 0006 — Android exact alarms: `SCHEDULE_EXACT_ALARM`, not `USE_EXACT_ALARM`

- Status: Proposed (2026-10-19) — needs a Google Play policy review before release.
- Referenced from: `apps/android/app/src/main/AndroidManifest.xml`

## Context

`USE_EXACT_ALARM` is granted at install but Play restricts it to apps whose core function is
an alarm clock or calendar. `SCHEDULE_EXACT_ALARM` is granted by the user in Settings
("Alarms & reminders") and can be revoked. Android 17's alarm audio exception relies on
exact-alarm permission plus `USAGE_ALARM` (doc 06).

## Decision

Declare `SCHEDULE_EXACT_ALARM`, ask for it only when the person sets up wake-ups, check
`canScheduleExactAlarms()` before installing, report `exactAlarmPermitted` to the server so
senders see "isn't set up for this yet", and re-check on `SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED`.

## Consequences

- Wake features need one extra user step; readiness screens explain it and link to Settings.
- If Play confirms Wake Me qualifies for `USE_EXACT_ALARM`, switch and keep the readiness check.

## Verification still owed

Stage 0 on Android 17 devices: exact alarm + systemExempted service + `USAGE_ALARM`, locked and idle.
