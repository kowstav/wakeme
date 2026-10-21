# Android: what the owner sets up, and how to check it

Workstreams 3–8 in [android-remaining-work.md](android-remaining-work.md) need accounts, keys,
phones or approvals that only the owner can create. The app code for each is written; none of it
has run against a real provider. This page lists, per workstream, what to create, where each value
goes, and the check that turns "implemented" into "verified". Record every check in
`docs/device-results/` (template there) and in `PROGRESS.md`, naming the build and the phone.

Where values go:

| Kind | Where | Never |
|---|---|---|
| Public client config (Supabase URL and anon key, Firebase app ids, RevenueCat public key, Google web client id, API URL) | `apps/android/gradle.properties` per environment, or `-P` / `~/.gradle/gradle.properties` | — |
| Server secrets (service-role keys, FCM service account, S3 keys, webhook auth, signing keys) | the platform secret manager, read as the env vars in `.env.example` | in source, the app, logs or prompts |
| Upload keystore | CI's protected release environment: `WAKEME_UPLOAD_KEYSTORE`, `…_PASSWORD`, `WAKEME_UPLOAD_KEY_ALIAS`, `WAKEME_UPLOAD_KEY_PASSWORD` | in source |

Staging first: every check below runs against a staging API (`WAKEME_ENV=staging`) with its own
Supabase, Firebase and RevenueCat projects before anything touches production.

## 3. Real sign-in (Supabase + Google)

Create:
1. A Supabase project for staging. Enable the **Email** provider (the app signs in with a one-time
   code: `/auth/v1/otp`) and the **Google** provider.
2. In Google Cloud: a **Web** OAuth client (its id and secret go into Supabase's Google provider)
   and an **Android** OAuth client for `app.wakeme.android` with the SHA-1 of each signing key you
   use (debug, upload key, and Play App Signing's key once Play manages signing). Debug builds are
   `app.wakeme.android.debug`.

Set:
- App: `wakeme.supabaseUrl`, `wakeme.supabaseAnonKey`, `wakeme.googleServerClientId` (the **Web**
  client id; Credential Manager asks for an ID token for it), `wakeme.apiBaseUrl` (staging HTTPS).
- Server: `AUTH_JWKS_URL`, `AUTH_ISSUER`, `AUTH_AUDIENCE`, and for the worker `SUPABASE_URL`,
  `SUPABASE_SERVICE_ROLE_KEY` (account deletion).

With Supabase configured, debug builds stop using the dev backend's local test identities
(`AppConfig.useDevAuth`).

Check (two phones or a phone and an emulator): email code sign-in; Google sign-in; sign out on one
phone leaves the other signed in; account switch; re-authentication before deletion and before a
high-impact grant; deletion removes the Supabase identity. Expected copy and edge cases: doc 03 §5.

## 4. Real push (FCM)

Create a Firebase project, register Android apps `app.wakeme.android` and
`app.wakeme.android.debug`, and a service account with only the **Firebase Cloud Messaging API
Admin** role. No `google-services.json` is committed: the app initialises Firebase from properties
(`DeviceRegistrar`).

Set:
- App: `wakeme.firebaseProjectId`, `wakeme.firebaseAppId`, `wakeme.firebaseApiKey`,
  `wakeme.firebaseSenderId`.
- Server: `FCM_PROJECT_ID`, `FCM_CLIENT_EMAIL`, `FCM_PRIVATE_KEY`.

Check (two phones, device-results matrix rows 3 and 4): quack and wake request with the recipient
app foreground, background, swiped away and after reboot; token refresh after reinstall; an expired
nudge is never played after the phone reconnects; the sender sees only what the recipient's phone
reported (provider acceptance is not "rang").

## 5. Play billing (RevenueCat)

Create the app in Play Console (needs the upload key, section 8), the subscription products and base
plans, a RevenueCat project with the Play app linked through a Play service account, the entitlement
`plus`, an offering, and license testers.

Set:
- App: `wakeme.revenueCatPublicKey` (Google public SDK key).
- Server: `REVENUECAT_API_KEY`, `REVENUECAT_WEBHOOK_AUTH` (the exact `Authorization` value set on
  the RevenueCat webhook), `REVENUECAT_ENTITLEMENT_ID=plus`.

Check with a license tester on an internal-testing build installed from Play: purchase, restore on
a second install, cancel and expiry, grace period, refund, "Manage subscription". During all of
them: Stop on a ringing alarm, revoking a grant and ending focus still work without Plus.
Runbook: `docs/runbooks/billing-reconciliation.md`.

## 6. Private voice sounds (storage)

Create a private S3-compatible bucket (Cloudflare R2 or Supabase Storage S3) with no public access,
and a key limited to that bucket.

Set (server): `S3_ENDPOINT`, `S3_REGION`, `S3_BUCKET`, `S3_ACCESS_KEY_ID`, `S3_SECRET_ACCESS_KEY`,
`S3_FORCE_PATH_STYLE`. Run the worker. The `media_uploads` feature flag is on in the seed data.

What "scanned" means today: the worker's `scan_media` job checks size, SHA-256, audio format and
duration and rejects anything else. It is **not** a malware or content scanner. Decide whether
reviewed uploads need one before launch.

Check (matrix row 15): record → preview → rights confirmation → upload → "clean" → offer to a
friend → the friend approves → it appears in alarm choices only for that friend; rejection of a
too-long or corrupt file; removal; offline and corrupt-cache fallback to Gentle rise; another
account can't fetch the object.

## 7. Physical phones

Run [docs/device-results/README.md](device-results/README.md) on Android 17 (Pixel and Samsung),
one aggressive-battery OEM and one Android 12–16 phone, with earbuds, wired and car audio. Install a
debug build against the dev API with `adb reverse` (see `apps/android/README.md`) for rows that
don't need push; the rest need sections 3–4. Also from the design pass: haptics, press feel, quack
timing and frame pacing (`docs/design/system.md` §11 item 2).

## 8. Release

Play Console developer account and app; Play App Signing with an upload key held in CI's protected
release environment; the accessibility declaration for Focus (release builds keep the service off
until it is approved, ADR 0014); the full-screen-intent declaration and the foreground-service
declarations with videos (system-exempted alarm, location), and the `SCHEDULE_EXACT_ALARM` /
`USE_EXACT_ALARM` decision (ADR 0006), all listed in `docs/store/google-play.md` with the Data
safety answers; legal review of `docs/legal`; real-phone store screenshots; reviewer access. Drills
in `docs/runbooks` need the staging environment above.
