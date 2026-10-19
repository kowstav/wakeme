# Google Play — DRAFT

## Data safety

- Data encrypted in transit: **yes**. Users can request deletion: **yes** (in the app, You →
  Delete account; Play also requires a **web link** for deletion requests — owner to publish
  one, e.g. `https://wakeme.app/delete-account`, before submission).
- Data shared with third parties: **no** (service providers processing on our behalf are not
  "sharing" under Play's definitions; confirm per provider with counsel).
- Collected (all "App functionality", none optional except where noted):

| Play category | Data | Notes |
|---|---|---|
| Personal info → Email address, Name | Sign-in email; display name | Google sign-in or email code |
| Location → Precise location | Only during a share the person starts (optional) | Deleted when the share ends |
| Messages → Other in-app messages | Messages between friends | Not end-to-end encrypted |
| Audio → Other audio files | Custom sounds people upload (optional) | |
| Financial info → Purchase history | Plus subscription status | |
| App activity → Other actions | Requests sent and received, routines | Request records kept 30 days |
| Device or other IDs | Install ID, device public key, push token | Not the advertising ID |

Not collected: contacts, photos/videos, health, files beyond uploaded sounds, web browsing,
crash logs or diagnostics (no SDK yet).

## Permission declarations

| Permission | Why | Play Console action |
|---|---|---|
| `SCHEDULE_EXACT_ALARM` | Wake-ups must ring at the exact minute | Special app access the person grants; explained before asking (ADR 0006). `USE_EXACT_ALARM` not used pending policy decision. |
| `USE_FULL_SCREEN_INTENT` | Show the ringing alarm over the lock screen | Complete the full-screen intent declaration (alarm functionality). |
| `FOREGROUND_SERVICE_SYSTEM_EXEMPTED` | Keep a started alarm playing until stopped (exact-alarm apps) | Foreground service declaration with a short video of an alarm ringing and being stopped. |
| `FOREGROUND_SERVICE_LOCATION`, `ACCESS_FINE_LOCATION`, `ACCESS_COARSE_LOCATION` | Location share the person starts, visible as an ongoing notification with Stop | Foreground service declaration and video; prominent in-app disclosure before the permission prompt. No background location permission is requested. |
| `POST_NOTIFICATIONS` | Quacks, requests and replies | Asked just in time, after an explanation. |
| `RECEIVE_BOOT_COMPLETED`, `WAKE_LOCK`, `VIBRATE` | Re-install alarms after a reboot; ring reliably | Normal permissions. |
| `USE_BIOMETRIC`, `USE_FINGERPRINT`, `BILLING` | Added by libraries (sign-in, billing) | Normal permissions; list in Data safety where relevant. |

No microphone, camera, contacts or SMS permissions.

## Content rating (IARC) and audience

User interaction (messaging between friends), location sharing between friends, no ads, no
gambling. Target audience: **18+ only**. Not designed for children.

## Testing requirement

New personal developer accounts must run a closed test with at least **12 opted-in testers
for 14 consecutive days** before applying for production (verify the account's actual
requirement in Play Console).

## Notes for review (draft)

- Demo accounts as for App Review [provided in Play Console only].
- Alarms: Routines → New wake-up; the app asks for "Alarms & reminders" access before saving
  and explains why. The ringing screen always has Stop and "End this wake session".
- Nothing happens on a phone unless its owner allowed it (friend → "Choose what … can do").
