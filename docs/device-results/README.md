# Device results — Stage 0 capability matrix

Doc 04 §2 (Stage 0) and doc 06 ("Resolve uncertainty with experiments") require each headline
capability to have one demonstrated, supported implementation on real phones, or a precise
fallback and product label. Simulator and emulator runs (ADR 0009) do not count here.

**Status (2026-10-20): no physical-device result exists yet.** Every row below is open. Each
run gets its own file, `YYYY-MM-DD-<platform>-<device>.md`, using the record template at the end.

## Matrix

| # | Experiment (doc 06 / doc 04 §2) | Pass condition | Fallback if it fails | iOS 27.0.1 | iOS 26.x | Android 17 Pixel | Android 17 Samsung | Aggressive OEM | Android 12–16 |
|---|---|---|---|---|---|---|---|---|---|
| 1 | Installed routine rings locked, idle, silent/Focus on, after 8 h screen-off | Audible at the scheduled minute (external recording); "Ready on this phone" was shown first | Label the device as not reliable; readiness screen names the setting | open | open | open | open | open | open |
| 2 | Routine left untouched for 3+ days keeps ringing (arming window, ADR 0011) | Rings every scheduled day; window topped up after each ring | Shorter promise; "open Wake Me weekly" hint | open | open | open | open | open | open |
| 3 | Immediate remote quack: app foreground / background / terminated / after reboot | Time from send to audible sound, per state; never a late sound after expiry | Status stays "Sent"/"No confirmation yet"; no claim of ringing | open | open | open | open | open | open |
| 4 | Immediate wake request (alarm path) in each state | Native alarm presented promptly when the OS allows; otherwise visible alert | Short sound nudge + recipient-confirmed scheduling | open | open | open | open | open | open |
| 5 | Android 17 alarm service: exact alarm + systemExempted FGS + USAGE_ALARM, target 37 | Audio focus granted, route reported, audible; no silent failure | Notification fallback while permissions are repaired | n/a | n/a | open | open | open | open |
| 6 | Speaker preference with earbuds / wired / car connected, and during a call | Actual audible route matches what the app reports | Label route limitation in readiness; no "disconnects earbuds" claim | open | open | open | open | open | open |
| 7 | Challenge action from the alarm (iOS App Intent cold launch; Android full-screen activity) | Correct wake session opens from a cold start; Stop still works | Open the challenge from the notification / app | open | open | open | open | open | open |
| 8 | Main alarm + one follow-up; challenge completion cancels the follow-up; Stop does not | Follow-up rings only when the challenge wasn't finished | Drop the follow-up promise on that platform | open | open | open | open | open | open |
| 9 | Offline edit / pause / end of a routine | Local cancellation at once; server reconciles later; no ghost alarms | Show "pending" and keep the last confirmed schedule | open | open | open | open | open | open |
| 10 | Guest wake-up, then sign-in on a flaky network | Local copy keeps ringing until the server copy is installed; never two rings | Keep local only until confirmed | open | open | open | open | open | open |
| 11 | Android 17 location button for an SOS snapshot; timed sharing separately (ADR 0018) | Alert sent first; one fresh position if available; no continuous service for SOS; denial/expiry observed; timed-share service stops on Stop | SOS without location; explicit settings recovery | n/a | n/a | open | open | open | open (fallback button) |
| 12 | iOS shield → app transition (`openParentalControlsApp`) for focus (Stage 6) | Shield opens Wake Me; bounded unlock after challenge | Shield explains "open Wake Me" manually | open | open | n/a | n/a | n/a | n/a |
| 13 | iOS 27 separate alarm volume vs AlarmKit audio | Recorded level at min / mid / max alarm volume | Readiness guidance only | open | n/a | n/a | n/a | n/a | n/a |
| 14 | Reboot before first unlock, time-zone change, DST transition, app update with armed routines | Mirrored alarm rings before unlock; local stop/escape cancel follow-up; cancellation stays after unlock; remaining occurrences re-arm | Readiness warns after reboot until opened | open | open | open | open | open | open |
| 15 | Android voice capture, private upload/scan and recipient approval | Denial/revocation safe; recording stops on background/30 s; only clean approved sound rings; deletion/removal and corrupt-cache fallback observed | Built-in sounds | n/a | n/a | open | open | open | open |

## How to run (summary)

1. Build: iOS needs the owner's Apple developer team, signing and the AlarmKit/APNs
   entitlements; Android uses the debug or a signed release APK. Record the exact build (git SHA).
2. Backend: the dev backend (`npm run dev -w @wakeme/api`) on a reachable host, or staging once it
   exists. Pushes need real APNs/FCM credentials for rows 3–4 (the CI relay is not evidence).
3. Evidence: screen recording plus an external audio/time recording (a second phone recording
   the room is enough), the app's own "observations" (Android logcat tag `WakeMeEvidence`), and
   the server's command and receipt events for the request ids involved.
4. One variable at a time: note Focus/DND, silent switch, battery saver, earbuds, network, and
   whether the app was swiped away or force-stopped.

## Record template

```markdown
# <date> — <platform> <device model> — experiment <#>

- Device / OS / build: e.g. Pixel 9, Android 17 (BP2A.…), security patch 2026-10-05
- App build: git <sha>, debug|release, target SDK
- Settings: silent/Focus, battery optimisation, exact alarms, notification permission,
  full-screen permission, alarm volume, accessories connected, network
- Steps: …
- Expected: …
- Observed: … (times from the external recording, route reported vs heard)
- Server events: command id …, receipts …
- Result: pass | fail | inconclusive
- Follow-up: product label / fallback / bug
```
