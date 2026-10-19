# Wake Me — progress

Last updated: 2026-10-19. Status words: **specified**, **implemented**, **verified (named conditions)**, **released**. Nothing is released. Nothing has run on a real phone.

## Verified

Conditions: Linux cloud container (Node 24.19.0; PostgreSQL 17.11 in Docker; JDK 17.0.20 + Gradle 9.8.1; Swift 6.2.4 in Docker); GitHub-hosted `macos-26` runner (Xcode 26.6, iOS 26.x Simulator, iPhone 17 Pro) and Ubuntu 24.04 runners with KVM (Android emulators). Earlier runs: Windows 11, Node 24.19.0, embedded PostgreSQL 17 via PGlite 0.5.8. Simulator and emulator results are not device evidence (ADR 0009).

| Area | Status | Evidence |
|---|---|---|
| Shared policy engine (`packages/policy`) | verified | 119 tests: windows, quiet hours, DST gap/fold, command decisions, local re-validation, delivery-state reduction. Golden fixtures were hand-computed. |
| Challenge engine (`packages/challenges`) | verified | 206 tests: schema, semantic checks, 59-case safety eval set, 15 chess puzzles checked by move generation, signing, re-signing under a new key, tamper detection, answer-free preview, shared envelope vectors. |
| Backend API + worker (`services/api`) | verified on PostgreSQL 17.11 and on PGlite | 102 integration tests, all passing against real PostgreSQL 17.11 (`TEST_DATABASE_URL`); 101 on PGlite (the commit-order test needs concurrent server connections). Includes the concurrent-sender budget race, sync feed (bootstrap, paging, retention reset, restore, commit order), key rotation, retention. TypeScript type-check clean; OpenAPI drift check clean. |
| Android `:core` (pure Kotlin) | verified (JVM unit tests) | 11 tests: every case in time-windows, occurrences, local-execution, delivery-state and challenge-verification fixtures, all 7 signed-envelope vectors, primary-phone rule (ADR 0007). `./gradlew -p tools/core-only :core:test` from `apps/android`. |
| iOS `WakeMeCore` + `WakeMeAPI` (Swift package) | verified on Linux and macOS | 28 XCTest tests on Linux, all passing with the dev backend running: fixtures, envelopes, plan scheduling, challenge progress and chess moves, API client, and the client-server contract test (own routine → signed installation receipt → "ready" → occurrence outcome). The contract test also passes on the macOS runner. |
| iOS app (`apps/ios/WakeMe`) | verified on the iOS Simulator (CI) | Builds with Xcode 26.6 against the real AlarmKit SDK. UI journey on iPhone 17 Pro passes end to end against the dev backend: welcome → sign-in (local test account) → profile (18+) → alarm readiness → Friends → invite → Routines → "Set a wake-up" → AlarmKit permission (Allow) → **"Ready on this phone"** (alarm scheduled by AlarmKit, signed installation receipt accepted) → You → export and delete entries → deletion alert cancelled → a second account accepts the invite → Sam chooses "Gentle nudges" for Alex → **"Alex can send quacks."** Client-server contract test passes on macOS. Screenshots reviewed. |
| Android app (`apps/android/app`) | **runs on the Android 16 emulator (CI)**; journey not yet passing | Android 16 (API 36): APK installs; cold launch 5.6 s to the welcome screen (screenshots reviewed: welcome, dark, 1.6× font); **alarm audio instrumented test passes** (alarm stream started, audio focus granted, route: speaker). UI tests were blocked by the emulator's "System UI isn't responding" dialog on a 320×640 screen (fixed in CI). Android 17 (API 37): SurfaceFlinger aborted in the emulator's virtual GPU mapper and the system crash-looped; a feature flag workaround is in the next run. Compiles; lint has no errors. |
| Shared envelope vectors | verified on 3 platforms | `packages/policy-fixtures/fixtures/signed-envelopes.json`, made by the server's `signEnvelope` with a throwaway key. |
| SigV4 storage signer | verified | Matches AWS's published presigned-URL example. |
| APNs / FCM clients | verified against local fake servers | **Not tested against Apple or Google.** |
| Design tokens | verified | All 14 WCAG contrast pairs pass in light and dark. |
| Bundled sounds | generated | Six original WAVs parse; **not reviewed by ear**. |

Commands: `npm test`, `npm run typecheck`, `npm run check:generated`; API on a real server: `TEST_DATABASE_URL=postgres://… npm test -w @wakeme/api`; Android core: `cd apps/android && ./gradlew -p tools/core-only :core:test`; iOS core: `cd apps/ios/WakeMeCore && swift test` (contract test: `WAKEME_DEV_BACKEND=http://127.0.0.1:8787 swift test --filter DevBackendTests` with `npm run dev -w @wakeme/api` running).

Bugs found and fixed by tests and review: invite brute-force throttle never counted failures; PGlite adapter `rowCount = 0` for SELECTs; outbox mixed clocks; an AI-retry off-by-one; one illegal chess position; friend-proposed routines now use the owner's time zone; an idle PostgreSQL connection terminated by the server crashed the process; every signed-in Android phone installed the owner's routines although only the primary phone should ring. **2026-10-19:** two overlapping transactions could commit sync changes out of order and a phone skipped the slower one forever (reproduced on PostgreSQL 17.11; ADR 0010); after a database restore phones could skip changes or keep routines the server no longer knew; retired challenge keys lost their base64 padding when parsed; Android never refreshed its trusted challenge keys; a location sample under a rotated key returned a server error; sign-out on one phone ended the person's sessions on every phone (Supabase `logout` default scope, Android and iOS); re-authenticating with another account's email code could have switched accounts before a deletion (iOS, never shipped); AI draft prompts were kept until account deletion; the iOS Simulator build signed receipts with a different key each time (Keychain unavailable when unsigned).

## Implemented but not yet verified

- **Android app**: compiles and builds but has never been observed running. Screens: onboarding (welcome with quack preview, sign-in, profile with age attestation, invite acceptance, self-setup readiness), Friends, Friend detail, Grant editor (presets, rows per doc 03 §6, last used, version, circle note), Routines, Challenges, You (readiness, sound test, primary phone, quiet hours, pause, sound budget, bypass sharing, subscription, export, deletion), Safety (check-in, timed location share, SOS), Conversation, Paywall. Platform: `setAlarmClock`, systemExempted alarm service with USAGE_ALARM and audio-focus/route reporting, direct-boot mirror, full-screen alarm with an always-available escape, location-typed foreground service, just-in-time notification permission, Keystore device key, push re-validation, receipts, sync (primary phone only; re-fetch removes routines and grants the server no longer lists), daily trusted-key refresh, Supabase auth, RevenueCat. Library-added permissions to declare in store forms: `USE_BIOMETRIC`, `USE_FINGERPRINT`, `BILLING`.
- **iOS app**, beyond what the journey covers: permission editor wake-up and challenge rows, pause/resume, block or report; routine pause/resume/end; data export through the file exporter; account deletion with identity confirmation (fresh email code, same account only); deletion-pending and suspended screens; **challenge runner** (all eight step types, calm retry, check-in fallback, always-visible escape), wake session from the alarm's "Start challenge" button (cancels the follow-up, reports the occurrence), signed challenge store, Challenges tab for practice, challenges in own wake-ups. **Not yet on iOS:** APNs (needs signing and entitlements), a background sync engine (routines install when the Routines list loads), a durable queue for occurrence receipts, messages, safety, paywall, making challenges.
- **Operations**: runbooks in `docs/runbooks` (incident and kill switches, noisy friend, key rotation, restore and rollback, billing reconciliation, deletion/export/lost phone). Tools: `npm run resign-challenges -w @wakeme/api`, `npm run post-restore -w @wakeme/api`. **No drill has been exercised** (no staging environment).
- **CI**: `ios.yml` (macOS; dev backend for UI journeys and the contract test) and `android-device.yml` (Android 17 and 16 emulators; dev backend for the journey test) run on pushes to the working branch; screenshots are printed into job logs. `ci.yml` runs on `main`/PRs. Action tags still need pinning to commit SHAs.
- **Infra**: Dockerfile, compose file, role split and `.env.example` written, not built.

## Specified only (not yet written)

Store metadata and reviewer instructions, privacy policy and terms drafts, focus supervision (Stage 6; server flag off by default; Focus buddy preset hidden in the apps), the Android 17 session-scoped location button, Stage 0 physical-device experiments and capability matrix.

## Decisions

Recorded in `docs/adr` (0001–0010); threat model with control-to-test mapping in `docs/threat-model.md`.

## Open decisions and deviations

- `message.send` is not a grant: accepted friends can message each other; block ends the conversation.
- `location.last_known.read` became `location.request`: no standing location read; every share is owner-started and time-boxed.
- Re-authentication window for high-impact grants is 24 h by default.
- Android uses `SCHEDULE_EXACT_ALARM` (user-granted), not `USE_EXACT_ALARM`, pending a Play policy decision.
- Only the primary device installs and rings routines; other devices remove local copies on sync.
- Retention values (30 days request metadata and AI prompts, 45 days sync feed, 365 days audit) are proposals pending legal review.

## Remaining launch gates (doc 04 §10), none met yet

Physical-device soak matrix (iOS 26/27, Android 17 on Pixel/Samsung/one aggressive OEM); APNs/FCM and Supabase projects; Apple and Google developer accounts, signing and entitlements (AlarmKit, Time Sensitive, Family Controls); restore, rollback, incident, lost-device and deletion drills; external security review; legal/privacy review; store submission. Spotify is excluded until written approval exists.

## Next step

1. Read the Android 16/17 emulator run and the iOS run; fix what they find.
2. iOS: background sync and challenge runner; APNs once an Apple developer account is available (owner action).
3. Install the APK on a real Android 17 phone and run the Stage 0 alarm experiment (exact alarm + systemExempted service + USAGE_ALARM, locked and idle, with and without earbuds), recording device/OS/build/settings.
4. Privacy policy, terms and store listing drafts for legal review.
