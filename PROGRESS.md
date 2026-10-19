# Wake Me — progress

Last updated: 2026-10-19. Status words: **specified**, **implemented**, **verified (named conditions)**, **released**. Nothing is released. Nothing has run on a real phone.

## Verified

Conditions: Linux cloud container (Node 24.19.0; PostgreSQL 17.11 in Docker; JDK 17.0.20 + Gradle 9.8.1; Swift 6.2.4). Earlier runs: Windows 11, Node 24.19.0, embedded PostgreSQL 17 via PGlite 0.5.8.

| Area | Status | Evidence |
|---|---|---|
| Shared policy engine (`packages/policy`) | verified | 119 tests (Windows and Linux): windows, quiet hours, DST gap/fold, command decisions, local re-validation, delivery-state reduction. Golden fixtures were hand-computed. |
| Challenge engine (`packages/challenges`) | verified | 205 tests: schema, semantic checks, 59-case safety eval set, 15 chess puzzles checked by move generation, signing and tamper detection, answer-free preview, shared envelope vectors. |
| Backend API + worker (`services/api`) | verified on server PostgreSQL 17.11 and on PGlite | 88 integration tests, run twice against real PostgreSQL 17.11 (`TEST_DATABASE_URL`), including the concurrent-sender budget race. TypeScript 7 type-check clean; OpenAPI drift check clean. |
| Android `:core` (pure Kotlin) | verified (JVM unit tests) | 10 tests: every case in time-windows, occurrences, local-execution, delivery-state and challenge-verification fixtures, plus all 7 signed-envelope vectors. Run with `./gradlew -p tools/core-only :core:test` from `apps/android`. |
| iOS `WakeMeCore` (Swift package) | verified on Linux | 13 XCTest tests: the same five fixture files, all 7 envelope vectors (swift-crypto 5.0.1 on Linux; CryptoKit on Apple), explicit DST gap/fold, plan scheduling, wake-session transitions. **Not yet run on macOS/iOS.** |
| Android app (`apps/android/app`) | **compiles; lint has no errors; debug APK builds** (2026-10-19, AGP 9.4.1, Kotlin 2.4.21, compile/target SDK 37, JDK 17) | `./gradlew :core:test :app:testDebugUnitTest :app:lintDebug :app:assembleDebug` succeeds. APK: `app.wakeme.android.debug` 0.1.0, targetSdk 37. **Never installed or run on a device or emulator** (no KVM here). The app module has no unit tests yet. 52 lint warnings remain (mostly unused resources and KTX style). |
| Shared envelope vectors | verified on 3 platforms | `packages/policy-fixtures/fixtures/signed-envelopes.json`, made by the server's `signEnvelope` with a throwaway key (generator in `packages/challenges/scripts`). |
| SigV4 storage signer | verified | Matches AWS's published presigned-URL example. |
| APNs / FCM clients | verified against local fake servers | **Not tested against Apple or Google.** |
| Design tokens | verified | All 14 WCAG contrast pairs pass in light and dark. |
| Bundled sounds | generated | Six original WAVs parse; **not reviewed by ear**. |

Commands: `npm test`, `npm run typecheck`, `npm run check:generated`; API on a real server: `TEST_DATABASE_URL=postgres://… npm test -w @wakeme/api`; Android core: `cd apps/android && ./gradlew -p tools/core-only :core:test`; iOS core: `cd apps/ios/WakeMeCore && swift test`.

Bugs found and fixed by tests and review: invite brute-force throttle never counted failures; PGlite adapter `rowCount = 0` for SELECTs; outbox mixed clocks; an AI-retry off-by-one; one illegal chess position; friend-proposed routines now use the owner's time zone; **(2026-10-19)** an idle PostgreSQL connection terminated by the server crashed the process (no pool error handler; found on real PostgreSQL); **(2026-10-19)** every signed-in Android phone installed the owner's routines although only the primary phone should ring.

## Implemented but NOT compiled or run

- **Android app (`apps/android/app`)**: compiles and builds (see above) but has never run. The first build passed Kotlin compilation; lint then found two real crashes, now fixed: alarm vibration used an Android 13 API on Android 12, and the alarm service passed the Android 14 `systemExempted` type on Android 12–13. Google sign-in also failed silently when the phone had no Google account; it now explains and points to the email code. Remaining deprecation: `hiltViewModel` moved to `androidx.hilt.lifecycle.viewmodel.compose`. Library-added permissions to declare in store privacy forms: `USE_BIOMETRIC`, `USE_FINGERPRINT`, `BILLING`.
  - Screens: onboarding (welcome with quack preview, sign-in, profile with age attestation, invite acceptance, **self-setup readiness flow**), Friends, Friend detail, Grant editor (presets, rows per doc 03 §6, last used, version, circle note), Routines, Challenges, **You** (readiness, sound test with measured route, primary phone, quiet hours, pause, sound budget, bypass sharing, subscription, export, deletion), **Safety** (check-in, timed location share, SOS, viewer labels with capture time and accuracy), **Conversation** (reactions, report), **Paywall** (store data only, Continue free always available).
  - Platform: `setAlarmClock`, systemExempted alarm service with USAGE_ALARM and audio-focus/route reporting, direct-boot mirror, full-screen alarm with an always-available escape, location-typed foreground service for safety sessions, just-in-time notification permission, Keystore device key, push re-validation, receipts, sync (installs only on the primary phone), Supabase auth, RevenueCat.
  - Gradle wrapper 9.8.1 committed; `distributionSha256Sum` not pinned (the published checksum host was blocked here).
- **iOS app (`apps/ios`)**: only `WakeMeCore` exists. The SwiftUI app, AlarmKit, App Intents and the notification service extension need a Mac with Xcode.
- **CI (`.github/workflows/ci.yml`)**: written, never run. Now uses `./gradlew` and installs JDK 17 + 21. Action tags still need pinning to commit SHAs.
- **Infra**: Dockerfile, compose file, role split and `.env.example` written, not built.

## Specified only (not yet written)

ADRs (`docs/adr`), threat model, runbooks (incident, noisy friend, key rotation, restore, billing reconciliation, deletion), store metadata and reviewer instructions, privacy policy and terms drafts, focus supervision (Stage 6; server flag off by default; Focus buddy preset hidden in the app), the Android 17 session-scoped location button, Stage 0 physical-device experiments and capability matrix.

## Open decisions and deviations (to record as ADRs)

- `message.send` is not a grant: accepted friends can message each other; block ends the conversation.
- `location.last_known.read` became `location.request`: no standing location read; every share is owner-started and time-boxed.
- Re-authentication window for high-impact grants is 24 h by default.
- Android uses `SCHEDULE_EXACT_ALARM` (user-granted), not `USE_EXACT_ALARM`, pending a Play policy decision.
- Only the primary device installs and rings routines; other devices remove local copies on sync.

## Remaining launch gates (doc 04 §10), none met yet

Physical-device soak matrix (iOS 26/27, Android 17 on Pixel/Samsung/one aggressive OEM); APNs/FCM and Supabase projects; Apple and Google developer accounts, signing and entitlements (AlarmKit, Time Sensitive, Family Controls); CI run; external security review; legal/privacy review; store submission. Spotify is excluded until written approval exists.

## Next step

1. Add app-module tests (Robolectric is pinned): sync installs only on the primary phone, local re-validation on push, alarm scheduling results, paywall offer rendering.
2. Install the APK on a real Android 17 phone and run the Stage 0 alarm experiment (exact alarm + systemExempted service + USAGE_ALARM, locked and idle, with and without earbuds), recording device/OS/build/settings.
3. On a Mac: run `swift test` for WakeMeCore, then build the SwiftUI/AlarmKit app.
4. Write the ADRs and the threat model listed above.
