# 0014 — Friend-supervised focus: recipient-chosen apps, local shield, always finite

- Status: Proposed (2026-10-20). Stage 6 (doc 04 §8); the server's `focus_requests` kill switch
  stays off until the Stage 0 focus experiment (doc 04 §2, row 12 of docs/device-results) passes.

## Context

Doc 03 §4E: the recipient selects distracting apps locally, chooses a friend and limits, and
schedules or accepts a session. A shield explains the shared goal and how to earn a short break
or escape. The friend sees only the opted-in outcome. Doc 02 §12: policies are local and finite;
a friend can request a session inside an accepted maximum but never chooses apps remotely or
locks the phone; temporary unlocks expire locally without the server. Doc 01 §6: iOS uses
Family Controls / Managed Settings / Device Activity with individual authorization; Android has
no single consumer API — evaluate Usage Access and a narrowly scoped AccessibilityService only
if the function and Play review permit it; rules stay deterministic and user-approved, and an
LLM never drives the service.

The server already has `focus.request` commands (minutes, optional challenge), focus grant
limits (`maxSessionMinutes`, `maxPerDay`, windows), the Focus buddy preset and the kill switch.

## Decision

- **Shared session rules** (`FocusSession`, Kotlin and Swift, same tests): a session has a fixed
  end (at most the friend's `maxSessionMinutes`, never more than 4 hours); "End focus" is always
  available and ends it at once; a challenge earns a 5-minute break, at most 3 per session;
  expiry, breaks and ending need no network. The shield applies only while a session is active
  and not on a break.
- **iOS**: `AuthorizationCenter.requestAuthorization(for: .individual)`; apps picked with
  `FamilyActivityPicker` and kept on the phone; `ManagedSettingsStore.shield` applied at start
  and cleared at end, break or escape; the scheduled end is also registered with
  `DeviceActivityCenter` so a DeviceActivityMonitor extension clears the shield even if the app
  isn't running. The shield's "Open Wake Me" uses `openParentalControlsApp` where available,
  with a manual-open fallback. Needs the Family Controls entitlement (owner; distribution needs
  Apple's approval).
- **Android**: a narrowly scoped AccessibilityService (`typeWindowStateChanged` only, no content
  retrieval, no gestures or global actions) that, while a session is active, brings Wake Me's
  shield to the front when one of the recipient's chosen apps opens. The chosen packages and the
  session live on the phone. Apps are listed from launcher intents (`<queries>`), not
  `QUERY_ALL_PACKAGES`. The service is off unless the person turns it on in system settings,
  with a prominent disclosure first. Play requires the Accessibility API declaration (owner).
- **Requests**: a friend's `focus.request` arrives as a notification ("Alex asks you to focus
  25 min — you choose"); nothing starts until the recipient taps Start. The friend learns
  "acknowledged" (started) and, only if the recipient opted in, whether it was finished.

## Why not Usage Access on Android

Polling `UsageStatsManager` needs a foreground service for the whole session plus a way to show
the shield from the background, which Android 15+ restricts (an overlay permission is the usual
workaround and is broader than this need). A system-bound accessibility service is exempt from
those launch limits, reacts without polling and can be scoped to window changes.

## Consequences / revisit

- Stage 0 must show the shield behaves on real phones (iOS shield → app transition; Android
  service across OEMs, split screen and work profiles) before the kill switch is turned on.
- If Play rejects the accessibility declaration, Android falls back to a self-started session
  with a gentle reminder notification only, labelled as not enforced.
