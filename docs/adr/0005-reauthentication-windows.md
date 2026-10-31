# 0005 — Recent sign-in for high-impact grants and account deletion

- Status: Accepted (2026-10-19)

## Decision

- Granting a capability that can affect the phone strongly — `alarm.request`,
  `alarm.schedule`, `focus.request`, `location.request` — for the first time requires a
  sign-in within the last 24 hours (`REAUTH_SECONDS_HIGH_IMPACT_GRANTS`, default 86 400,
  bounded 60 s–30 days). Re-saving a grant that already had the capability does not.
- Deleting the account requires a sign-in within 15 minutes (`REAUTH_SECONDS_DELETION`, default 900).

## Rationale

A borrowed or stolen unlocked phone should not be able to hand someone wake or location
powers. A daily window keeps normal editing friction low.

## Revisit when

Pilot users report the 24-hour window as confusing, or a stronger factor (passkey) is added.
