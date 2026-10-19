# Wake Me — progress

Last updated: 2026-10-18. Status words: **specified**, **implemented**, **verified (named conditions)**, **released**. Nothing is released. Nothing has run on a real phone.

## Verified on this machine (Windows 11, Node 24.19.0, embedded PostgreSQL 17 via PGlite 0.5.8)

| Area | Status | Evidence |
|---|---|---|
| Shared policy engine (`packages/policy`) | verified | 119 tests: windows, quiet hours, DST gap/fold occurrences, command decisions, local re-validation, delivery-state reduction. Golden fixtures were hand-computed. |
| Challenge engine (`packages/challenges`) | verified | 201 tests: bounded JSON Schema, semantic checks, 59-case safety eval set, 15 chess puzzles checked by move generation, signing and tamper detection, answer-free preview. |
| Backend API + worker (`services/api`) | verified (embedded DB only) | 88 integration tests across 11 suites; TypeScript 7 type-check clean; 101 operations in `packages/contracts/openapi.yaml` (drift check in tests). |
| SigV4 storage signer | verified | Matches AWS's published presigned-URL example. |
| APNs / FCM clients | verified against local fake servers | HTTP/2 headers, payload, expiry, token refresh, invalid tokens. **Not tested against Apple or Google.** |
| Design tokens | verified | All 14 WCAG contrast pairs pass in light and dark (`packages/design-tokens/contrast-report.md`). |
| Bundled sounds | generated | Six original synthesized WAVs parse correctly; **not reviewed by ear**. |
| Local dev backend (`npm run dev -w @wakeme/api`) | verified smoke test | Health, dev token, account creation and config respond. |

Commands: `npm test` (all JS packages), `npm run typecheck`, `npm run check:generated`.

Bugs found and fixed by the tests: invite brute-force throttle never counted failures (rolled back with the 404); the PGlite adapter reported `rowCount = 0` for SELECTs; the outbox mixed the app clock with the database clock; an AI-retry off-by-one; one illegal chess position; friend-proposed routines now use the owner's time zone.

## Implemented but NOT compiled or run

- **Android app (`apps/android`)**: not compiled. No Android SDK here; the disk has under 1 GB free. Written: Gradle build with pinned versions; a pure-Kotlin `:core` module mirroring the policy, with fixture-parity tests; the alarm path (`setAlarmClock`, systemExempted foreground service, USAGE_ALARM with an audio-focus check and speaker-route reporting, direct-boot mirror, full-screen alarm with an always-available escape); push re-validation; receipts; sync; Keystore device key; Supabase auth; RevenueCat; onboarding, Friends, Grant editor, Routines and Challenges screens.
  - **Missing files referenced by `ui/Root.kt`**: `ui/you/YouScreen`, `ui/safety/SafetyScreen` and `LocationShareService`, `ui/messages/ConversationScreen`, `ui/paywall/PaywallScreen`. The build will fail until these exist.
  - No binary Gradle wrapper jar; CI uses Gradle 9.8.1 directly.
- **iOS app (`apps/ios`)**: **not started.** Only the generated `DesignSystem/Tokens.generated.swift` and the sound files exist. Needs a Mac (SwiftUI, AlarmKit, App Intents, notification service extension, WakeMeCore Swift package with fixture parity).
- **CI (`.github/workflows/ci.yml`)**: written, never run. Action tags still need pinning to commit SHAs.
- **Infra**: Dockerfile, compose file, runtime/migration role split and `.env.example` are written but not built (Docker isn't running here).

## Specified only (not yet written)

ADRs (`docs/adr`), threat model, runbooks (incident, noisy friend, key rotation, restore, billing reconciliation, deletion), store metadata and reviewer instructions, privacy policy and terms drafts, focus supervision (Stage 6; the server flag is off by default), and the Android 17 location button.

## Open decisions and deviations (to record as ADRs)

- `message.send` is not a grant: accepted friends can message each other; block ends the conversation.
- `location.last_known.read` became `location.request`: there is no standing read of location; every share is an owner-started, time-boxed session.
- Re-authentication window for high-impact grants is 24 h by default (configurable).
- Android uses `SCHEDULE_EXACT_ALARM` (user-granted), not `USE_EXACT_ALARM`, pending a Play policy decision.

## Remaining launch gates (doc 04 §10), none met yet

Physical-device soak matrix (iOS 26/27, Android 17 on Pixel/Samsung/one aggressive OEM); APNs/FCM and Supabase projects; Apple and Google developer accounts, signing and entitlements (AlarmKit, Time Sensitive, Family Controls); server-PostgreSQL CI run; external security review; legal/privacy review; store submission. Spotify is excluded until written approval exists.

## Next step

Free disk space or use a machine with the Android SDK. Add the four missing Android screens, compile, and run `:core:test` (fixture parity). Then build the iOS app on a Mac, starting with WakeMeCore and its fixture tests.
