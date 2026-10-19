# 0004 — Location only through owner-started, time-boxed sessions

- Status: Accepted (2026-10-19)
- Context: earlier drafts had `location.last_known.read` (a standing read). CLAUDE.md: no stealth location sharing.

## Decision

- No standing location read exists. A friend with `location.request` can only *ask*.
- The owner starts a session (check-in, share, or SOS) with named viewers and a duration
  (5–480 minutes; SOS 120). It ends on its own; the owner can stop it at once.
- While sharing, the phone shows a visible indicator with Stop (Android: location-typed
  foreground service on a silent channel). Only fresh fixes with an accuracy are uploaded;
  the server rejects samples older than 15 minutes and keeps only the latest, encrypted.
- Viewers see capture time and accuracy, never an old sample presented as live.

## Consequences

- "Where is X right now" is not a feature; "X chose to share for 1 hour" is.
- The Android 17 session-scoped location button (doc 06) fits this model and is still to do.
