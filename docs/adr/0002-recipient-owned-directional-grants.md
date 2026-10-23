# 0002 — Friendship grants nothing; grants are recipient-owned and directional

- Status: Accepted (2026-10-19)
- Context documents: CLAUDE.md invariants, 02-ENGINEERING-BLUEPRINT.md §6, 03-PRODUCT-AND-DESIGN.md §6

## Decision

- Accepting a friend or joining a circle grants no phone-affecting capability.
- A grant is owned by the person whose phone is affected (grantor), names one grantee,
  lists capabilities with limits (windows, sounds, rate, duration, difficulty), has an
  expiry, and is versioned. Editing creates a new version; revoking is immediate.
- Every command is authorized on the server against the current grant (identity, object
  access, grant, time, quota, expiry, device eligibility) and re-validated on the phone that
  would ring against its cached copy (`evaluateLocalExecution`); a revoked or narrowed grant
  wins even if the server dispatched first.

## Consequences

- One unique grant per (grantor, grantee) direction (database constraint); "they allow me" and
  "I allow them" are separate objects in every UI.
- Local re-validation means a phone with a stale cache skips (`needs_sync`) rather than rings.

## Verification

`packages/policy` (command decisions, local execution fixtures), API authorization tests,
and the same local-execution fixtures on Android `:core` and iOS `WakeMeCore`.
