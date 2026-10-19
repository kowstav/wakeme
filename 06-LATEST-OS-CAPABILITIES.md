# Latest OS capability audit

Checked **18 October 2026**, specifically to avoid designing Wake Me around old restrictions. Read this before implementing native modules. The strategy is to exploit supported capabilities aggressively, then validate their exact lifecycle on hardware.

## Release baseline

- **iOS 27** became generally available on **14 September 2026**. Apple's accessed security-release page identifies **27.0.1** as the latest iOS version, released **28 September 2026**. [Apple release announcement](https://www.apple.com/newsroom/2026/09/major-updates-for-apples-software-platforms-are-now-available/), [Apple security releases](https://support.apple.com/en-sa/100100)
- **Android 17 / API 37** was released on **16 June 2026**, initially on supported Pixel devices. OEM rollout and security patch versions vary. QPR beta features must not be silently treated as universally released behavior. [Google release announcement](https://android-developers.googleblog.com/2026/06/Android-17.html)

Proposed product support: iOS 26+ for the full alarm product, with 27.0.1 as a primary test target; Android 12+ subject to pilot audience coverage, compiling/targeting API 37 after dependency verification. These minimums are business choices, not the minimum availability of every framework. Older iOS support, if added, must have its own clearly tested alarm experience.

## Capability changes that materially help Wake Me

| Capability | Evidence and practical use | Engineering task |
|---|---|---|
| **Native prominent iOS alarms** | AlarmKit, introduced in iOS 26 and available in the current generation, schedules alarms that override silent/Focus with authorization. This is substantially more capable than old notification-only designs. [Apple sample](https://developer.apple.com/documentation/AlarmKit/scheduling-an-alarm-with-alarmkit) | Schedule locally, reconcile OS state, prefetch sounds and challenge content, expose readiness. |
| **Custom alarm experience and sound** | AlarmKit integrates custom metadata, App Intents, ActivityKit presentation and custom sound. [Apple session](https://developer.apple.com/videos/play/wwdc2025/230/) | Build a native wake flow and a custom action into the challenge; test file availability while locked. |
| **Modern iOS alarm presentation** | Current `AlarmPresentation.Alert` documentation supplies a system stop control and optional secondary action; old stop-button initializer is deprecated. [Current API](https://developer.apple.com/documentation/alarmkit/alarmpresentation/alert-swift.struct) | Use current initializers and availability checks, rather than reproducing outdated sample code unchanged. |
| **Separate alarm/timer volume settings in iOS 27** | Apple's update notes list independent volume settings for alarms and timers. [Apple update notes](https://support.apple.com/en-mide/149076) | Add a user-guided readiness test. Verify how the current setting affects third-party AlarmKit audio; do not assume app-programmable volume. |
| **Shield-to-app transition** | Current Managed Settings docs include `openParentalControlsApp`. [Apple API](https://developer.apple.com/documentation/managedsettings/shieldactionresponse/openparentalcontrolsapp) | Verify symbol availability in the installed SDK and `.individual` flow. Use it for “Complete your challenge,” with a manual-open fallback on unsupported versions. |
| **Adult self-control authorization** | Family Controls supports individual device-owner authorization, enabling voluntary focus use beyond parental control. [Apple explanation](https://developer.apple.com/videos/play/wwdc2022/110336/) | User selects apps locally, then chooses which friend can request a bounded focus session. |
| **Android 17 explicitly supported alarm audio exception** | Background audio requires a visible activity or non-`SHORT_SERVICE` foreground service. For API 37 targets, exact-alarm permission plus `USAGE_ALARM` waives the additional while-in-use requirement. [Google background audio specification](https://developer.android.com/about/versions/17/changes/bg-audio) | Use a legitimate alarm service, correct audio attributes and granted exact-alarm permission. Handle audio focus results; never rely on a background coroutine playing media. |
| **Android alarm-capable foreground services** | Google's service-type reference includes exact-alarm permission holders among `systemExempted` eligibility. [Foreground service types](https://developer.android.com/develop/background-work/services/fgs/service-types) | Validate eligibility, manifest permissions, Play declaration and launch condition independently. A service type does not itself grant permission to launch from any background state. |
| **Session-scoped location button on Android 17** | The system location button supports intentional one-session precise location. The documentation specifies a Play policy requirement for qualifying API 37 apps whose location features are all session-based. [Google location button](https://developer.android.com/guide/topics/permissions/private-alternatives/location-button) | Use it for “Share my location now” / SOS when applicable. Separate this from persistent safety sessions. |

## Android 17 integration details

The alarm exception is a positive design route, not a loophole. Build the following chain:

1. Recipient explicitly enables Wake Me's alarm capability and grants a friend bounded alarm permission.
2. An incoming request is authorized by the server and, when the app executes, by the recipient's local grant cache.
3. A valid scheduled alarm is installed through AlarmManager. An immediate request uses only the documented launch/scheduling path demonstrated in the Stage 0 experiment.
4. Alarm execution starts the correctly declared service when permitted, posts the required notification and uses `USAGE_ALARM`.
5. Audio focus success, chosen route and playback lifecycle are inspected. Failed audio calls may be silent in Android 17, so “no exception” is not a passing test.
6. The recipient can stop the sound, complete a challenge, or use a safety escape; the service then releases audio resources.

AlarmManager documentation identifies exact alarms as an exception to foreground-service start restrictions for relevant user-requested actions. Treat that separately from FCM priority, service-type eligibility and permission approval. All must line up. [AlarmManager scheduling](https://developer.android.com/develop/background-work/services/alarms)

API 37 also brings networking/security changes: ECH when the network stack and server support it, certificate transparency by default for targets, local-network runtime permission, and more granular background activity launch controls. Keep internet push/API transport as the primary network architecture; a future LAN feature needs its own permission handling. Use updated SDK lint and real-device tests for PendingIntent/background launch behavior. [Android 17 target changes](https://developer.android.com/about/versions/17/behavior-changes-17)

Large-screen layout and resizability are also part of API 37 readiness. The quiz keyboard, chess board, grant editor and alarm screen must work on split screens and foldables; a portrait-only lock is not a sufficient solution. [Android 17 behavior changes](https://developer.android.com/about/versions/17/behavior-changes-17)

## Current iOS implementation opportunities

Use AlarmKit for true alarms, not a loop of notification sounds. Pair its secondary/custom action with the in-app challenge session. Use App Intents and widgets to reduce interaction cost for the sender, while retaining server authorization on every remote action. A recipient can pre-authorize scheduled routines so later occurrences do not require manual approval every morning.

A particularly useful experiment is a preapproved main alarm plus one locally installed follow-up: challenge completion cancels the follow-up. Scheduling both in advance avoids needing a fresh remote command at wake time. Make the full sequence visible and bounded, and offer a way to end the complete session. This is a concrete way to strengthen challenge accountability while respecting the system's own stop behavior; verify the actual sequence on current devices before shipping.

For a fresh remote request, attempt the supported background/update path and send an ordinary visible alert fallback. A future or existing local alarm is a different case from a newly received command. Do not use repeated fake activity events, silent audio playback or pretend VoIP calls as process-keepalive mechanisms. [Background delivery](https://developer.apple.com/documentation/usernotifications/pushing-background-updates-to-your-app), [App Review software requirements](https://developer.apple.com/app-store/review/guidelines/)

The latest Apple release notes promote improved parental controls and Screen Time schedules. Those consumer features alone do not prove a new public API for arbitrary friend remote control. The inspected public APIs do provide individual authorization, shielding, scheduling and a documented return-to-app response. Build against those concrete surfaces. [iOS 27 update notes](https://support.apple.com/en-mide/149076), [Managed Settings response](https://developer.apple.com/documentation/managedsettings/shieldactionresponse)

## Resolve uncertainty with experiments

| Experiment | Positive result that unlocks shipping | Fallback if result fails |
|---|---|---|
| Immediate alarm on a locked iPhone | Logged supported request path, compliant native scheduling and measured prompt alarm presentation across states | Short sound nudge plus recipient-confirmed scheduling; explain device readiness |
| iOS challenge action from alarm | Correct app session restored from current App Intent, including cold launch | Open challenge through normal notification/app navigation |
| Speaker with earbuds connected | Actual audible speaker route under each intended lifecycle | Label route limitation and require readiness setup; retain supported alarm behavior |
| Android 17 alarm service | Correct exact-alarm permission, foreground lifecycle, focus and audio work with target 37 | Notification fallback while permissions/configuration are repaired |
| iOS shield challenge transition | Current API opens controlling app and safely grants bounded access after completion | User opens Wake Me manually; shield explains that action |
| Offline schedule edit/revocation | Local cancellation works immediately and server reconciles later | Pending remote edits remain visibly unconfirmed; preserve last confirmed schedule |

An Apple developer forum report described delayed AlarmKit alarms on iOS 26/27. This is an unverified developer report, not an Apple-confirmed universal defect. Include long locked-screen soak tests rather than extrapolating it into either “AlarmKit is unreliable” or “AlarmKit is perfect.” [Developer report](https://developer.apple.com/forums/thread/846063)

## Audit confidence

High confidence: released OS families, documented AlarmKit support, system stop control, Android 17 alarm-audio exception, session location button, individual Screen Time authorization.

Requires SDK/hardware confirmation: exact introduction version and behavior of `openParentalControlsApp` for the chosen deployment targets; routing of OS-owned alarms while accessories are connected; immediate remote alarm latency across iOS lifecycle states; OEM-specific Android behavior.

Not established by this research: a public general-purpose server API that directly rings any iPhone regardless of process/network state; Spotify permission for Wake Me; irreversible app blocking on a consenting adult's unmanaged personal phone. These unknowns do not invalidate the product. They define the experiments and precise promises required for each feature.
