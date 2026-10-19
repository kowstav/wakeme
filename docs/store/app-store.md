# App Store — DRAFT

## App Privacy ("nutrition label")

Tracking: **No** (no data used for tracking; no third-party advertising or analytics SDKs).

Data linked to the user, all for **App Functionality** only:

| Apple category | What | Notes |
|---|---|---|
| Contact Info → Email Address | Sign-in | Via the sign-in provider |
| User Content → Other User Content | Messages, challenges, reports | Messages are not end-to-end encrypted |
| User Content → Audio Data | Custom sounds people upload | Only once uploads are enabled for iOS (Android first) |
| Location → Precise Location | Only during a share the person starts; deleted when it ends | Not collected in the background without an active share |
| Identifiers → User ID, Device ID | Account ID; install ID and device public key | Device ID is not the advertising identifier |
| Purchases → Purchase History | Plus subscription status | Through RevenueCat |
| Usage Data → Product Interaction | Requests sent and received (time, type, delivery status) | Kept 30 days |

Not collected: contacts, browsing/search history, health, financial info beyond purchases,
sensitive info, advertising data, crash or performance diagnostics (no SDK yet; revisit if one
is added).

Privacy manifest (`PrivacyInfo.xcprivacy`): **to add before submission**, declaring the
required-reason APIs actually used (e.g. `UserDefaults` — reason CA92.1) and the data types
above. Not yet in the project.

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
  always available. (The iPhone app has no challenge screen or "End this wake session" yet;
  submit only once it does, so follow-ups can always be ended in the app.)
- Nothing happens on a phone unless its owner allowed it: open a friend → "What Alex can do
  on your phone" to see and change the rules.
- Sign-in uses an email code; no phone number or contacts are requested.
- Location sharing is started by the person sharing, shows who can see it and stops at the
  chosen time.
