# 0011 — Routines are armed over a rolling window and topped up

- Status: Accepted (2026-10-19)

## Context

Both apps armed only the next occurrence of a routine. iOS gets no code execution when an
AlarmKit alarm rings, and Android re-armed only after a reboot, a clock change or a change to
the routine. A weekday routine therefore rang once and then stayed silent until the app next
synced a change: the core promise failed on the second morning. Review of the scheduling code
found it; no test covered more than one occurrence, and nothing has shipped.

A second fault sat next to it on Android: reporting "rang" changes the routine on the server,
the change comes back through sync, and sync reinstalled the routine from scratch. Reinstalling
computes occurrences after "now", so a follow-up still pending for the occurrence that had just
rung was cancelled and not re-created.

## Decision

- The shared rule (`scheduleFor`, Kotlin and Swift, same tests): arm every occurrence in the
  next 8 days, at most 8, always including the next one even when it is further away, each with
  its follow-up if the owner accepted one. A daily routine with a follow-up holds 16 alarms.
- Phones top the window up whenever they run, without touching what is already armed:
  - Android: when a routine alarm rings (local data only; skipped before the first unlock after
    a reboot, when that data can't be read yet), before every sync, and after a reboot or app
    update. A clock or time-zone change still recomputes every occurrence.
  - iOS: when the person stops an alarm (AlarmKit `stopIntent`, which also records "stopped"),
    when the app becomes active, during background app refresh, and on every sync. Future alarms
    AlarmKit no longer holds are armed again; follow-ups of finished wake sessions never are.
- Sync applies an unchanged routine by topping it up instead of reinstalling it.
- Every installation receipt lists every armed instant. The server counts a routine as ready
  only while one of them is still ahead, and, for fixed-zone routines, only when one matches the
  next occurrence (2 minutes either way). When the window runs out, friends see "not yet
  confirmed" instead of a stale "ready".
- Signing out stops work already in flight from arming anything afterwards.

## Consequences

- A phone that never runs Wake Me for 8 days stops ringing that routine, and the server says so.
  iOS background refresh is at the system's discretion; APNs (not set up yet) will add a wake-up
  path before the window runs out.
- More alarms per routine (16 for a daily routine with a follow-up). AlarmKit's limit on alarms
  per app is not documented; if it stops accepting alarms part-way, the earlier ones stay armed
  and the receipt reports exactly those. Android's limit (500 per app) is far away.
- Readiness changes meaning slightly: "ready" means the next occurrence is armed on the primary
  phone now, not that it was once.

## Verification

Kotlin `WakePlanTest` and Swift `WakePlanTests` (window, cap, one-off weeks away, device zone);
`ArmingWindowTest` (instrumented, on the emulator's AlarmManager: window armed, topped up after a
ring with the pending follow-up kept, a finished session's follow-up not brought back); server
`alarms.test.ts` (stays ready while armed, stale after the window, wrong instant not ready);
contract test `DevBackendTests` (the app's computed window makes the server say ready). Not yet
verified on a phone: AlarmKit's behaviour with many alarms, `stopIntent` running after Stop on
iOS 26.0 and 26.1+, and background refresh frequency.
