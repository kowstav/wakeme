# App Store — DRAFT

## App Privacy ("nutrition label")

Tracking: **No** (no data used for tracking; no third-party advertising or analytics SDKs).

Data linked to the user, all for **App Functionality** only:

| Apple category | What | Notes |
|---|---|---|
| Contact Info → Email Address | Sign-in | Via the sign-in provider |
| User Content → Other User Content | Messages, challenges, reports | Messages are not end-to-end encrypted |
| User Content → Audio Data | Custom sounds people upload | Only once uploads are enabled for iOS (Android first) |
| Location → Precise Location | Only during a check-in, share or SOS the person starts; the server keeps only the latest sample, deleted when it ends | Sent while the app is in the background only during that session, with iOS's location indicator showing |
| Identifiers → User ID, Device ID | Account ID; install ID and device public key | Device ID is not the advertising identifier |
| Purchases → Purchase History | Plus subscription status | Through RevenueCat |
| Usage Data → Product Interaction | Requests sent and received (time, type, delivery status) | Kept 30 days |

Not collected: contacts, browsing/search history, health, financial info beyond purchases,
sensitive info, advertising data, crash or performance diagnostics (no SDK yet; revisit if one
is added).

Privacy manifest: `apps/ios/WakeMe/PrivacyInfo.xcprivacy` declares no tracking, the only
required-reason API used (`UserDefaults`, reason CA92.1, the app's own data) and the data types
above except Audio Data (no uploads from iOS yet). CI checks it is in the app bundle. Keep it in
step with this table and with any SDK added later (SDKs bring their own manifests).

## Age rating

Answer the questionnaire truthfully: user-generated content and messaging between friends,
location sharing between friends, no unrestricted web access, no gambling, no mature content.
Set the minimum age to **18+** to match the pilot policy (age is also confirmed at sign-up).

## Capabilities and entitlements (owner's Apple Developer account required)

- AlarmKit (`NSAlarmKitUsageDescription` present). Permission is asked when the first
  wake-up is saved or from the readiness screen.
- Push Notifications (APNs) and Time Sensitive Notifications: needed for quacks and remote
  wake requests. **Not yet implemented on iOS** (no APNs registration without signing).
- Live Activities (`NSSupportsLiveActivities`, used by AlarmKit presentations).
- Location, "While Using the App" only (`NSLocationWhenInUseUsageDescription`), asked when the
  person first chooses to share. Background mode `location`: a share the person started keeps
  sending while the app is in the background (CoreLocation live updates in a background
  activity session, so iOS shows its location indicator) and stops when the session ends or
  the person taps Stop. No "Always" permission is requested. Explain this in the review notes.
- Background mode `fetch`: background app refresh keeps the next 8 days of wake-ups armed.
- Associated Domains for invite links (`wakeme.app`): not yet implemented on iOS; the code
  path (typing the invite code) works without it.
- Family Controls: Stage 6 only; not requested now.
- Export compliance: `ITSAppUsesNonExemptEncryption = false` (HTTPS and platform
  CryptoKit signing only). Confirm with counsel.

## Account deletion (guideline 5.1.1(v))

In the app: You → Delete account. Explains the 7-day grace period and that subscriptions are
cancelled separately in the App Store.

## Notes for App Review (draft)

- Two demo accounts on the review backend, already friends, with Sam allowing Alex to send
  quacks: [credentials provided in App Store Connect, never in this repository].
- To see a wake-up: Routines → Set a wake-up → choose a time two minutes ahead → Save, and
  allow alarms when asked. The alarm rings through AlarmKit and the system Stop control is
  always available. A wake-up with a challenge opens it from the alarm's "Start challenge"
  button; every challenge has a way out, and finishing or leaving it ends the follow-up.
- Nothing happens on a phone unless its owner allowed it: open a friend → "What Alex can do
  on your phone" to see and change the rules.
- Sign-in uses an email code; no phone number or contacts are requested.
- Location (why the `location` background mode): You → Safety check-ins and location →
  "Share my location for a while" → pick a friend and a duration → Start. iOS asks for
  "While Using" permission; the share shows who can see it and until when, iOS shows its
  location indicator while it runs (also in the background), and it stops at the chosen time
  or on "Stop now". Nothing is sent without an active session the person started. Wake Me is
  not an emergency service, and the app says so.
