# Design contract and review log (doc 03 §9)

## 1. Product truth (what every screen must stay true to)

- Headline: **Alert your friends. Wake each other up.** Supporting line: "From "where are you?" to
  "we've got this."" The first screen makes contacting a trusted friend obvious.
- Two behaviours share one set of friends and permissions: **I need you now** (quack, wake
  request) and **Help me become better** (shared wake-ups, focus, personal challenges).
- **The recipient owns every permission.** Friendship and circles grant nothing; grants are
  directional, bounded, versioned and revocable; consent is part of the value ("You decide what
  each friend can do"), not a legal settings page.
- **Literal status.** Sent ≠ phone received ≠ heard ≠ awake. "Ready on this phone" only after the
  phone reported what the OS accepted. "Challenge completed" is not "guaranteed awake".
- **Always a way out.** Stop, End focus, the accessible check-in and revocation never wait for the
  network, a friend, AI or a subscription.

## 2. Visual direction: Bloom

Source of truth: `packages/design-tokens/tokens.json` v3 and `packages/design-tokens/icons`, the
**Bloom** system ([system.md](system.md), [research](research-2026-10-21-top-apps.md), ADR 0021;
visual study [bloom.html](bloom.html)). It replaced Daybreak's cream and yellow (ADR 0020,
[directions.html](directions.html)). Generated into both apps; 43 pairs and 8 palette families pass
WCAG in light, dark and both increased-contrast modes, see `contrast-report.md`.

| Rule | Why |
|---|---|
| White page, flat neutral-grey cards, no borders or shadows; deep green-black night | The reviewed top apps; colour separates surfaces |
| Lime (`accent`) is the action colour with forest ink; text on white uses `accentText` (forest by day) | The owner's reference; lime text on white is 1.47:1 |
| Petal families mark things of the same kind (friends, tiles, chips, quick actions), never status | Vibrancy without diluting meaning |
| Blue only for information and focus; red only for destructive actions and SOS (readiness and validation use warning) | Urgency keeps its meaning |
| Fields, switches and open toggles use `outline` (≥ 3:1); filled pills are identified by their words | WCAG 1.4.11 |
| Selection is lime plus a check mark or bold weight | State never by colour alone |
| Icon only for universal compact actions (with a spoken label); icon + one word for tabs and quick actions; words only for consent, dialogs, alarm Stop and escapes | Research §4 |
| Status is words plus a dot, never colour alone (`StatusPill`) | Colour-vision differences |
| One primary action per screen; 44 pt / 48 dp minimum targets | Sleepy and one-handed use |
| Native navigation: tab bar on iPhone; bottom bar on Android phones, rail on tablets | Platform-native, adaptive (doc 06) |
| Dialogs on the raised surface, not Material's default tint | Run 19 review |
| One signature motion (the original duck's quack) with a Reduce Motion still; tokenised springs; nothing animated in alarm, SOS or escape controls | Doc 03 §2; system.md §5 |
| Delivery shown as words plus a trail that only advances on reported evidence | Literal status (shared `delivery-trail.json`) |

## 3. Components (same names on both platforms)

`PrimaryButton` (lime pill, optional icon), `SecondaryButton` (neutral pill; visibly disabled at 38–45 % when
unavailable), `QuietButton`, `DangerButton` (destructive/SOS only, never animated), `WMCard` (flat),
`HeroCard`, `BentoTile`/`BentoRow`, `QuickAction`, `IconChip`, `RoundIconButton`, `PillButton`,
`DisplayText`, the `WMIcon` set, `StatusPill` (tinted, words + dot or icon, optional announcement), `ChoiceChip`, `DayChips`/`DayPicker`,
`TimeDisplay`, `FriendAvatar`, `DeliveryTrailView`, `DetailTopBar` (Android), `EmptyState`,
`DuckMark`, `QuackBurst`, `SettingRow` (whole row toggles), `Stepper` (Android: 48 dp −/+ spoken as
Fewer/More), app-switcher cover / `HideFromRecents` on Safety and Messages (ADR 0012).

## 4. Copy rules

- Literal and kind: "Alex hasn't turned this on for you." "You're offline. Nothing was sent."
- Never "Your friend owns your phone"; say "You're giving Sam permission to help you focus for up
  to 30 minutes."
- Explain before every system prompt (notifications, alarms, Screen Time, accessibility,
  location), at the moment it's needed, never at sign-up.
- No paywall, feed or upsell during an alarm, a focus shield or a safety flow.

## 5. What is not done (owner / people needed)

- Three directions for the two core screens now exist as static HTML studies (directions.html);
  a reference collection and people's reactions to them (doc 03 §9 steps 2–4) are still to do.
- Five-person usability rounds and the 90 % targets (doc 03 §9 step 8): need participants.
- Real-device screenshots (small/large phones, tablets) for store listings.

## 6. Screenshot review log

Screenshots come from CI (printed into job logs; see ADR 0009 — not device evidence).

| Run | Platform | Reviewed | Findings (fixed unless noted) |
|---|---|---|---|
| Android 13 | Android 16 emulator | 2026-10-19 | Yellow text buttons below AA → `accentText`; sound ids shown → names |
| Android 19 | Android 16/17 | 2026-10-19 | Lavender default dialogs; check-in summary cut off |
| iOS 21 | iPhone 17 Pro simulator | 2026-10-19 | Incoming SOS listed below "Start" → friends' shares first |
| Android 20 | Android 16/17 | 2026-10-19 | Disabled "Send quack" looked tappable; switch subtitle under the switch; bare −/+ steppers |
| Android 26+ | Android 16/17 | run 30 large-screen and previews reviewed 2026-10-20; rest pending | Home card, quack hop, guest home, circles, focus shield, tablet-size rail (40-large-screen), list-detail on a tablet (41-large-screen-detail), Compose previews on four form factors (preview-*) |
| iOS 30+ | iPhone simulator | pending | Guest home, push received, circles, Focus permission step, paywall (store unavailable) |
| Android 30 | Compose previews (layoutlib) and Android 16 at 2560×1600 | 2026-10-20 | Full-width grid rows (headings, "Create a challenge", "Invite a friend") stretched to the 1200 dp grid on desktop instead of 640 dp; two-pane list-detail and rail confirmed |
| Android 31 | Compose previews (layoutlib) | 2026-10-20 | Width fix confirmed: full-width rows keep 640 dp on tablet and desktop |
| Android 31 | Android 16 emulator + layoutlib | 2026-10-20 (Codex) | Reviewed `40-large-screen`, `41-large-screen-detail`, `13-focus-shield`, desktop Challenges, foldable Routines and dark-phone Friends: readable controls, rail and two panes confirmed; next installed alarm visible on Friends. No new screenshot references approved or committed. |
| Android 45 | Android 17 emulator | 2026-10-20 (Codex) | Reviewed voice rights/local-preview screen (52), real-host widget toolbar (53), system location button (56) and large-text challenge controls (51). Voice upload is visibly disabled without rights confirmation; Stop/check-in/End remain visible at large text. Widget uses canonical Glance styling; actual launcher/OEM rendering remains a device gate. |
| Daybreak pass | Android runs 54–56 (previews + 16/17 emulators), iOS runs 37–38 (iPhone 17 Pro simulator) | 2026-10-21 (Claude) | Before: run 53 / iOS run 35. After: alarm clock on tabular figures, 52 dp paired buttons, 17 sp card titles, pinned welcome actions, pinned iOS challenge escape above the keyboard. Fixed from the renders: an Em/Sp crash in labelled text fields (run 54), blue tab label, dark-mode disabled/neutral fills, circle card titles, trail labels at 2× text, duck-title gap, welcome cut-off at large text, heavy day discs, missing back bars. List in [sizing-audit](sizing-audit-2026-10-21.md). No screenshot baselines accepted. |
| Bloom pass | HTML study from the same tokens and SVGs ([bloom.html](bloom.html)); native renders pending CI | 2026-10-21 (Claude) | Before: Daybreak renders (Android run 60, iOS run 39). The study was reviewed in a browser (light and dark); icons checked at 24 and 40 units, two redrawn. No native screenshots exist for Bloom yet. |
| Android 76 → 80+ | previews + Android 16/17 emulators | 2026-10-21 (Claude) | Before: run 76 (all green). Fixed: "Use the simple check-in instead" clipped to "Jse … 'nstead" at large text (`QuietButton` kept Material's pill shape with no side padding); selection chips without a check mark on Safety, You, Challenges, Focus and Conversation; no back bar, a smaller heading and an edge-to-edge pill on the challenge try-out/publish step; no back bar on the Paywall and on readiness opened from Routines; the Paywall's selected plan bordered in yellow (< 3:1); "Share precise locatio" on Android 17's system button (sized from a local copy of the label). Test evidence: the Focus shield and location-button screenshots were taken before drawing (blank in runs 56, 64, 76). Not changed: the widget's dynamic colours (system.md §9). No screenshot baselines accepted. |
| Android 49 | Compose previews (layoutlib) | 2026-10-20 (Codex) | All 34 renders pass. Reviewed small-landscape alarm, large-text quiz and tablet chess: readable content with reachable fixed escape controls; chess content scrolls separately. No new baseline images accepted. |
