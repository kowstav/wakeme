# 0007 — Only the primary phone installs and rings routines

- Status: Accepted (2026-10-19)

## Context

A person may be signed in on two phones. The server already sends wake commands to the
primary device; the first Android sync implementation nevertheless installed routines on
every signed-in phone, so two phones could both ring.

## Decision

- Each account has one primary device (chosen in You; the first registered device by default).
- Only the primary device installs routine alarms and reports installation receipts.
  Other devices remove any local copy on sync and receive passive updates.
- With no primary recorded yet, the registering phone counts as primary, so a single phone
  never skips its alarms.
- Changing the primary moves alarms: the new phone installs on its next sync; the old one
  removes them on its next sync (a window where both may hold them is accepted over a window
  where neither does).

## Verification

Android `SyncEngine` and iOS `AppModel.isPrimaryPhone`; unit tests for the rule are still to add.
