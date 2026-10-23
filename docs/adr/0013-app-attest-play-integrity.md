# 0013 — App Attest and Play Integrity: evaluated, not adopted yet

- Status: Proposed (2026-10-20)

## Context

Doc 02 §10 asks to evaluate App Attest (iOS) and Play Integrity (Android) for high-abuse
endpoints as additional risk signals, with server verification, request binding and replay
protection, and a documented fallback. They supplement account and grant authorization; they
do not prove a person is awake or make receipts unforgeable.

What Wake Me already has on those endpoints:

- Every phone-affecting write (commands, receipts, device updates, location samples) is signed
  with a per-installation P-256 key held in the Secure Enclave / Android Keystore and bound to an
  authenticated device registration (WAKEME-V1 canonical request, timestamp skew check, replay
  store). See `services/api/src/http/device-auth.ts`.
- Commands are limited by recipient-owned grants, aggregate sound budgets and rate limits, so a
  modified client gains nothing a friend didn't allow.

The abuse that attestation would add signal for is scripted mass account creation and commands
sent from emulated or modified clients.

## Options

| Option | Benefit | Cost / risk |
|---|---|---|
| App Attest on device registration, assertion on `POST /v1/commands` | Ties the device key to a genuine app on a genuine iPhone | Needs the owner's Apple team and entitlement; DCAppAttestService is unavailable in the Simulator and on some devices; Apple rate-limits attestation; adds a failure mode to registration |
| Play Integrity standard requests on registration and commands | Device and app integrity verdicts | Needs a Play Console project and Cloud project; verdicts are coarse and can block legitimate rooted/custom-ROM users |
| Neither, for now (chosen) | No new failure modes before launch | Relies on device keys, grants and limits |

## Decision

Not adopted until the owner's Apple and Google accounts exist and real abuse data shows a need.
When adopted, the verdict is a risk signal only: a failed or unavailable check never blocks a
personal alarm, stopping, the escape, revocation, blocking or data export; it can lower rate
limits or require a recent sign-in for new grants.

## Revisit when

Store accounts exist (owner), or the noisy-friend / account-farming runbooks see abuse that grants
and rate limits don't contain.
