# ADR 0019: an Android routine end is durable offline

Accepted 2026-10-20. Review found that End removed scheduled alarms before calling the API, but
kept the installed routine when that API call failed. A subsequent local arming-window top-up or
server bootstrap could therefore bring it back.

Before network work, End now persists a local tombstone, cancels mirrored pending alarms, stops
the matching active wake session through its escape, and removes local scheduling data. The
scheduler, boot mirror, alarm service and sync installer all honor this local decision, including
an install already in flight. Readiness/home views exclude it. The Routines screen explains when
the account update is still waiting to sync.

The pending server deletion is retried before ordinary sync under the same account ID; switching
accounts cannot send another person's queued mutation. A successful deletion or an authoritative
404 clears the pending mutation. Other errors keep it pending. A minimal ended-plan ID remains
in device-protected storage (excluded from backup) to prevent stale snapshots or a server restore
from reviving that exact routine. It carries no label, sound, schedule, contacts or location.
Pending mutations additionally contain the originating account ID for identity isolation. These
local stop fences are removed by clearing app data/uninstalling; signing out clears armed alarms
but preserves an unconfirmed end for the original account's next sign-in.

This is a one-way end. Creating a new routine uses a new ID. Other phones learn the cancellation
only after the server accepts it; the UI does not claim they already stopped while offline.

Recovery also distinguishes installed future routine occurrences from fresh remote requests:
boot never replays a past occurrence or an immediate remote command. An occurrence missed while
the phone was restarting is not silently turned into a new wake request. It has no completion
evidence. Clock/update recovery drops immediate-command rows instead of re-registering them.

Verification: `BootAlarmsTest` checks persistence, account isolation, no mirrored re-arm and a
retained fence after acknowledgment. `LocalRoutinesTest` checks that an old installed snapshot
cannot be reinstalled or topped up. The native journey checks an End reaching the backend.
Execution evidence belongs in PROGRESS.md; physical soak/restore drills remain open.
