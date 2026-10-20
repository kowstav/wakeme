# Android development and verification

Kotlin/Compose app, minimum API 31, target/compile API 37. See [PROGRESS](../../PROGRESS.md)
for current evidence and [the physical-device matrix](../../docs/device-results/README.md)
for release gates. The app has not been released.

## Build

Use the checked-in Gradle wrapper. CI uses JDK 21 with a JDK 17 toolchain for `:core`.
Install Android SDK platform 37 and the build tools requested by AGP. Configure `ANDROID_HOME`
or a local, ignored `local.properties` SDK path.

```sh
./gradlew :core:test :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest
./gradlew :app:lintRelease :app:minifyReleaseWithR8
```

The debug APK is `app/build/outputs/apk/debug/app-debug.apk`. GitHub's `ci` workflow includes it
in the `android-reports` artifact, alongside test/lint reports. A debug build is for development;
release minification without signing does not produce a Play-ready signed release.

## Backend and configuration

Start the repository's development API from the repository root:

```sh
npm ci
npm run dev -w @wakeme/api
```

The default debug URL, `http://10.0.2.2:8787`, reaches the host from an Android emulator.
For an attached development phone, reverse the port and rebuild for loopback:

```sh
adb reverse tcp:8787 tcp:8787
./gradlew -Pwakeme.apiBaseUrl=http://127.0.0.1:8787 :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Android 17 local-network access is declared only in debug. Use the normal onboarding controls
for notifications, exact alarms and full-screen access. Accessibility focus remains disabled in
release until the owner's Play declaration is approved (ADR 0014).

For staging/production, supply a public HTTPS API, Supabase URL/publishable key and configured
Google sign-in provider, Firebase project/app/sender configuration and RevenueCat public key/products.
The corresponding Gradle properties are listed in `gradle.properties`; keep private server and
signing credentials outside source. Real voice upload/scanning needs configured private storage,
the media feature flag and worker. Account keys alone do not verify push delivery or store billing.

## Native feature checks

- Wake: stop sound, solve, accessible check-in, escape, follow-up cancellation, later occurrence,
  rotation, locked reboot before first unlock, offline operation and accessory routing.
- Voice sounds: You → Voice sounds; microphone denial/revocation, bounded capture, preview/discard,
  rights confirmation, upload/scan state, friend offer, recipient approval and removal. Approving
  a sound grants no permission to ring. Only approved recordings enter wake-alarm choices;
  notification quacks use bundled sounds. Missing/corrupt recordings use Gentle rise.
- Routines: study/gym/practice presets, acceptance of a friend's proposal, “Use this routine again”,
  saved form state and explicit schedule/sound review. No attendance verification or penalties.
- Widget: add “Wake Me quick actions” from the launcher; resize it. Actions open the app and
  still require the person to send/start. Check small size and TalkBack labels.
- Safety: SOS sends first and may attach one current position. Timed location sharing is a
  separate, visible foreground service. Verify denial, unavailable fixes, capture time/accuracy,
  stop and expiry; it is not an emergency service.
- Thanks: after a completed wake, optionally open the buddy preview and choose Send thanks.
  Opening or rotating the screen must not send a message.

`android-device.yml` runs instrumented tests, a local-backend journey, large-screen captures and
Compose preview renders on Android 16/17. Focus runs in a separate instrumentation process so
Compose/Espresso's UiAutomation connection cannot suppress the service being tested. New preview
reference images require review before acceptance. Emulator results do not establish real-device
audibility, delivery reliability, location quality, OEM battery behavior or store approval.
