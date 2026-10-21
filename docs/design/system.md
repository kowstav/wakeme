# Daybreak: the Wake Me design system

Status (2026-10-21): **specified and implemented** on iOS and Android. **Not yet verified**: no
native build, preview render or screenshot ran for this pass (see §10). Source of truth for values:
[`packages/design-tokens/tokens.json`](../../packages/design-tokens/tokens.json) (v2), generated into
`apps/ios/WakeMe/DesignSystem/Tokens.generated.swift` and
`apps/android/app/src/main/java/app/wakeme/designsystem/Tokens.kt` by `npm run generate:tokens`.
The audit that motivated it is [audit-2026-10-20.md](audit-2026-10-20.md); the decision record is
[ADR 0020](../adr/0020-daybreak-design-system-and-motion.md).

## 1. Direction

Three directions were drawn with real Wake Me content on the same two screens
([directions.html](directions.html)): **A Sunrise** (warm, joyful, expressive), **B Linen & Ink**
(refined, premium) and **C Daybreak** (hybrid). **Daybreak is selected** (27/30 against 19 and 24):

- B's structure — calm cream surfaces, ink text, hairlines, legible hierarchy — is what alarms,
  permissions and SOS need to feel trustworthy.
- A's warmth is spent only where emotion belongs: the single yellow primary action, butter
  selection, the duck at the quack, success.
- It refines the existing identity (every earlier contrast and screenshot fix still applies).
- A was rejected for coral colliding with danger/SOS semantics and needing a bundled rounded font
  on Android; B for reading like a bank and losing brand recall.

Personality in one line: **joyful when appropriate, effortless in daily use, calm when trust or
safety matters.**

## 2. Colour

All values were chosen in OKLCH (the contrast report lists each role's L, C, h). Light-mode
semantic text colours share lightness L ≈ 0.50, dark-mode ones L ≈ 0.76–0.84, so no status shouts
louder than another. 42 foreground/background pairs pass WCAG in light, dark and both
increased-contrast variants ([contrast-report.md](../../packages/design-tokens/contrast-report.md)).

| Role | Use | Never |
|---|---|---|
| `surface` / `surfaceRaised` / `surfaceSunken` / `surfaceOverlay` | Page / cards / wells and pressed states / dialogs and sheets | — |
| `border` | Decorative hairlines (card edges, dividers) | The only boundary of an interactive control (1.29:1) |
| `outline` (new) | Text fields, chips, secondary buttons, steppers, unselected day toggles: ≥ 3:1 on every surface (WCAG 1.4.11) | Large fills |
| `textPrimary` / `textSecondary` | Body and supporting text | `textSecondary` for anything a sleepy person must read during an alarm |
| `accent` / `accentPressed` | The one primary action per screen, selected day toggles, the duck's body/wing | Text on light surfaces |
| `accentContainer` / `onAccentContainer` (new) | Selected chips, your own message bubbles, hero highlights | Status |
| `accentText` | Text buttons, links, the quack's sound marks, focus-adjacent text | Warnings (moved apart, ΔE 0.029 → 0.067) |
| `info` / `infoContainer` | Information, retry guidance, keyboard focus (`focus`) | Errors |
| `success` / `successContainer` | Confirmed evidence (delivery steps, ready, finished segments) | "Awake" claims |
| `warning` / `warningContainer` | Something needs attention: readiness, validation, not delivered | Destructive actions |
| `danger` / `dangerContainer` / `dangerFill` | Destructive actions and SOS only | Validation, readiness, install failures (moved to warning) |
| `alarmBackground` / `alarmText` | The alarm screen, always dark | — |
| `avatar` tints (6) | Friend monograms; equal OKLCH lightness, no red hues | Meaning |
| `illustration` | Duck beak, eye | Anything but the duck |

**Night is warm ink** (h ≈ 75, C ≤ 0.01) instead of cold slate, so day and night read as one
product and the yellow doesn't glare. **Increased contrast**: `textSecondary`, `outline` and
`accentText` have HC variants, used on iOS with Increase Contrast and on Android 14+ when the
system contrast level is medium or high (`UiModeManager.getContrast() ≥ 0.5`).

## 3. Type, shape, spacing, elevation

- **Type**: system fonts (SF Pro / Roboto) on 13 semantic roles (`type.roles`; table in
  [sizing-audit-2026-10-21.md](sizing-audit-2026-10-21.md)): alarm time 76, time display 56, inline
  time 32, hero 32, screen title 28 (iOS 34), prominent 24, section 22, card title 17, field label 14,
  body 16 (iOS 17), supporting 14, label 14, caption 12. Android builds Material's slots from the
  generated `WakeMeType`; iOS uses the generated `WMFont` (Dynamic Type styles). **Tabular
  figures** everywhere; already-large numerals grow with font size only to 1.3–1.5×.
- **Shape**: radius 8 (fields), 14 (chips, medium), 20 (cards), 28 (sheets, dialogs), pill (buttons,
  status). Buttons became capsules on both platforms, matching iOS 26 and Material 3's current
  shape language.
- **Spacing**: 4-point scale (4/8/12/16/24/32/48), unchanged, plus semantic `layout` tokens: screen
  margin (Android 16 dp, iOS 20 pt), title group 4, label-to-controls 8, between controls 12, card
  padding 16, between groups 12–16, section extra 8 above.
- **Controls** (`component`): primary, secondary and destructive buttons 52 tall with a 17 semibold
  label; alarm controls 72 with a 22 label; icon buttons 40 visible in a 48 target; chips 32; every
  target ≥ 44 pt / 48 dp.
- **Elevation**: light cards = hairline + one soft ink shadow (iOS radius 10, y 2, 6 %; Android
  2 dp tinted ink); dark cards = lighter tonal surface, no shadow.

## 4. Components (same names on both platforms)

`PrimaryButton` (yellow capsule, pressed = `accentPressed` + 0.97 scale), `SecondaryButton`
(capsule, `outline` edge), `QuietButton`, `DangerButton` (never animated), `WMCard` (optional whole-card
tap with press feedback on Android), `StatusPill` (tinted container + dot + words; crossfades;
`announce` speaks changes), `ChoiceChip` (Android: butter + check mark), `DayChips`/`DayPicker` (accent
fill + bold + outline when off), `TimeDisplay` (Android), `FriendAvatar`, `DeliveryTrailView`,
`DetailTopBar` (Android), `EmptyState`, `DuckMark`, `QuackBurst`, `SettingRow`, `Stepper`.

## 5. Motion

| Token | Value | Used for |
|---|---|---|
| `press` spring | response 0.20 s, damping 0.90 (Android stiffness 987) | Pressed buttons and cards |
| `snappy` spring | 0.30 s, 0.86 (439) | Day toggles, selection |
| `smooth` spring | 0.45 s, 1.0 (195) | Delivery trail fill, step changes, message insertion |
| `playful` spring | 0.40 s, 0.55 (247) | Reserved for the quack and celebrations |
| `quick` / `standard` / `emphasized` / `exit` | 120 / 220 / 320 / 180 ms | Status crossfades; navigation enter (emphasized decelerate 0.05, 0.7, 0.1, 1) and exit (emphasized accelerate 0.3, 0, 0.8, 0.15) |
| `quack` | 600 ms | The signature quack |
| stagger | 40 ms, at most 5 steps | First appearance of the welcome screen only |

Easing and durations follow Material 3's published motion tokens
([material-components-android Motion.md](https://github.com/material-components/material-components-android/blob/master/docs/theming/Motion.md));
iOS uses SwiftUI springs with the same response/damping.

**Rules**

1. Motion explains a change (where something came from, what changed, that a tap registered).
   Nothing loops, nothing idles, nothing bounces more than once.
2. **Never delay information or input.** New content is interactive immediately; no animation
   gates a button.
3. **Truth over theatre.** Delivery steps fill only when the server reports evidence
   (`DeliveryTrail`, shared fixture `packages/policy-fixtures/fixtures/delivery-trail.json`, tested on
   Android core and iOS core). The quack animation follows a send the server *accepted* and says
   nothing about delivery.
4. **Interruptible.** Springs retarget from current velocity; state-driven animations only.
5. **Reduce Motion**: iOS replaces movement with a ≤ 150 ms crossfade, keeps the zoom transition
   off, and shows the quack as a still duck mid-quack. Android's "Remove animations" makes every
   Compose animation instant; components choose their still variant (quack, entrance).
6. **Critical flows stay still**: the alarm screen, Stop, "I need to stop", the simple check-in,
   SOS and End focus have no entrance or exit animation. Only a press colour change (and on
   primary buttons a 3 % settle that never moves the target) acknowledges a tap.
7. Performance: per-frame values are read in the draw/layer phase on Android
   (`graphicsLayer {}`, `Canvas`), so animations don't recompose; iOS uses `KeyframeAnimator` and
   `Canvas` for the duck. No particles, no blur, no continuously running animation, so idle battery
   cost is zero.

**Where motion is used (after)**

| Moment | iOS | Android | Benefit |
|---|---|---|---|
| Friend row → friend page | System zoom transition (`matchedTransitionSource`) | Shared-axis 320/180 ms (replacing Navigation 3's 700 ms default) | Spatial continuity; faster navigation |
| Setup steps | Crossfade | Fade-through | Continuity without implying a stack |
| Press | Fill + 0.97 settle | Fill + 0.97 settle | Immediate acknowledgement |
| Quack sent | Duck hops once, beak opens, three sound marks fade | Same | A memorable, brand-owned confirmation |
| Delivery evidence | Trail fills to the reported step | Same | Literal progress, readable at a glance |
| Status change | Pill crossfade + announcement | Pill crossfade + live region | Noticed by sight and by screen reader |
| Day toggles, chips | Fill change + selection haptic | Same | Clear, tactile selection |
| Challenge steps | Next step slides 24 pt and fades in; segment fills | Same | Progress is felt; no penalty theatre |
| Wrong answer | Calm info pill, light haptic, no shake | Same | Respectful retry |
| Messages | New bubble rises from below | `animateItem` placement | No layout jumps |
| Lists | — (native `List`) | `animateItem` on friend and routine cards | Followable insertions/removals |
| Welcome | Duck + 5-block stagger (≤ 200 ms) | Same | Warm first impression, then out of the way |
| Loading | Duck + small spinner | Same, centred | Calm, branded wait |

## 6. The duck

An original mark replaces the 🦆 emoji (which differed per OS and OEM and couldn't animate). One
geometry in a 64-unit box, drawn natively on both platforms (`Duck.kt`, `Duck.swift`) and in SVG
in the directions page:

| Part | Geometry | Colour |
|---|---|---|
| Tail | `M12,37 L5,25 Q13,27 19,32 Z` | `accent` |
| Body | ellipse x 9, y 28, 42 × 28 | `accent` |
| Head | circle (42, 22) r 12 | `accent` |
| Wing | `M20,40 Q30,34 40,40 Q32,48 22,46 Z` | `accentPressed` |
| Upper beak | `M51,18.5 Q60,18 62.5,22 Q58,24.5 51,24.5 Z` | `duckBeak` |
| Lower beak | `M51,24.5 Q57,24.5 60.5,26 Q56,28.5 51,28 Z`, hinged at (51, 24.5), opens 24° | `duckBeakShade` |
| Eye | circle (45, 19) r 2.3 + highlight (45.8, 18.2) r 0.75 | `duckEye`, `duckEyeHighlight` |
| Sound marks (quack) | three arcs around (62, 24), r 6 / 10.5 / 15, ±38°, stroke 2.6 | `accentText` |

The quack (600 ms): one hop (−5 units, −7°) over the first 45 %, beak open 8–52 %, sound marks start
at 12 / 22 / 32 % and fade over half the duration. The duck is always hidden from screen readers.

## 7. Haptics

| Event | iOS | Android |
|---|---|---|
| Selection (chip, day, correct step) | `.selection` | `SegmentTick` |
| Toggle | `.selection` | `ToggleOn` / `ToggleOff` |
| Request accepted by the server | `.success` | `Confirm` |
| Send failed | `.warning` | `Reject` |
| Wrong answer | `.impact(weight: .light)` | `Reject` |

The system haptics setting decides whether anything is felt. No extra haptics during an alarm or
SOS. Haptic quality is **not observable in CI** and needs physical phones.

## 8. Screen-by-screen changes

| Screen | Change | Files |
|---|---|---|
| Tokens | v2 roles, HC variants, avatar tints, illustration colours, motion/elevation/opacity tokens; OKLCH report | `packages/design-tokens/*` |
| Theme | New roles mapped into Material (outline 3:1, butter selection, dark `onError` ink), shape scale, tabular type, Android 14 contrast | `designsystem/Theme.kt`; iOS `Tokens.generated.swift` (HC via traits) |
| Welcome | Interactive duck quack (local preview), stagger, tagline on iOS too | `ui/onboarding/Onboarding.kt`, `Features/Onboarding/WelcomeView.swift` |
| Root / gates | Centred duck loading state; fade between setup steps; Android navigation 320/180 ms shared axis, RTL-aware, predictive back | `ui/Root.kt`, `App/WakeMeApp.swift` |
| Friends | Avatars, whole-card tap with label, chevron, empty state with duck, larger next-alarm time | `ui/friends/FriendsScreens.kt`, `Features/Friends/FriendsView.swift` |
| Friend detail | Back button (Android), identity header, quack card with `QuackBurst`, butter sound chips, "Sent to …", announced status + `DeliveryTrailView`, one primary action once permissions exist | same |
| Routines | Large tabular times (same text node), animated readiness, empty state, warning instead of danger for readiness | `ui/routines/Routines.kt`, `Features/Routines/RoutinesView.swift`, `GuestView.swift` |
| Routine editor | Time hero (Android), day toggles with haptics, butter chips, back button; validation in warning (iOS) | `Routines.kt`, `ui/grants/GrantEditor.kt`, `RoutineEditorView.swift` |
| Grant editor | Butter chips with check marks | `ui/grants/GrantEditor.kt`; iOS validation colour |
| Challenge runner | **iOS escape/check-in pinned to the bottom (P0)**; step progress; step transition; calm announced feedback; haptics; outlined answer fields; readable chess board; Dynamic Type timer | `challenge/ChallengeRunner.kt`, `Features/Challenges/ChallengeRunnerView.swift` |
| Messages | Butter bubbles with a tail corner; insertion animation; pressable Send | `ui/messages/ConversationScreen.kt`, `Features/Friends/ConversationView.swift` |
| App-switcher cover | Duck mark instead of emoji | `DesignSystem/Components.swift` |

## 9. Decisions on candidate techniques

| Technique | Decision | Why / where |
|---|---|---|
| Spring transitions | Adopted | All interactive motion; interruptible by construction |
| Shared-element card → detail | iOS adopted (system zoom), Android modified (shared axis) | Nav3 supports a `SharedTransitionScope`, but list-detail scenes swap between one and two panes and nothing could be verified on an emulator here |
| Material 3 Expressive `MotionScheme` | Rejected for now | Factories are internal in pinned Material3 1.4.0 |
| Contextual micro-interactions | Adopted selectively | Press, selection, quack, trail |
| Coordinated choreography / stagger | Adopted once | Welcome only, ≤ 200 ms |
| Tonal colour system in OKLCH | Adopted | §2 |
| Elevation and shadows | Adopted (light only) | Cards; dark uses tonal surfaces |
| Materials / Liquid Glass on content | Rejected | Legibility; system bars already adopt it |
| Gradients | Rejected | Flat tonal fills read better and survive high contrast |
| Illustration | Adopted (one duck) | Empty states, loading, welcome, quack |
| Loading skeletons | Deferred | Most loads are short and local-first; revisit with measured latency |
| Sound design for UI | Rejected | The product's sounds are alarms and quacks; extra UI sounds would blur them |
| Particles / confetti / looping idle | Rejected | Battery, distraction |
| Adaptive layouts | Kept (existing) | Rail, list-detail, 640 dp readable width |
| Skill guidance that conflicted | Platform and safety won | e.g. web-centric suggestions (CSS, Framer Motion) from installed skills were not applied to native screens |

## 10. Verification

| Check | Result |
|---|---|
| Token generation + contrast (42 pairs × 4 modes, 6 avatar tints) | **Passed locally** (`npm run generate:tokens`; `--check` clean) |
| Android `:core` unit tests incl. new `DeliveryTrailTest` | **28/28 passed locally** on JDK 22 via a scratch init script (repo pins JDK 17, not installed here) |
| iOS `WakeMeCore` tests incl. `DeliveryTrailTests` | **Not run** (no Swift toolchain on this Windows machine) |
| Android app compile, lint, unit tests, instrumented journey, previews | **Not run**: no Android SDK installed locally, ~1 GB free RAM |
| iOS app build, UI journey, simulator screenshots | **Not run**: needs macOS |
| New Compose previews (`DesignSystemPreviews.kt`: light, dark, increased contrast, 2× text) | Written; render in CI's `previews` job |
| Frame pacing, haptics, physical devices | **Not measured** |

Copy the UI tests depend on was kept (for example "07:00 · Weekdays" stays one text node on both
platforms; "Not quite. Take your time and try again.", "Change permissions", "Play preview").

## 10b. Sizing pass (2026-10-21)

Audit with ranked findings and the final scale: [sizing-audit-2026-10-21.md](sizing-audit-2026-10-21.md).
Evidence was the CI renders of the previous code (Android run 53, iOS run 35).

## 11. Remaining work, by impact

1. **Run CI** (Android build/lint/unit/journey/previews; iOS build/journey) on this branch and fix
   anything that fails; review the new preview renders and simulator screenshots, then record
   before/after images in the screenshot log.
2. Physical-device pass: haptics, press feel, quack timing, frame pacing (Android GPU rendering /
   Macrobenchmark; Instruments on iOS), Reduce Motion / Remove animations, Increase Contrast.
3. Extend the system to screens not touched in this pass: Safety (keep it still; tokens only),
   You, Paywall, Focus, Circles, Grant editor layout, challenge authoring, voice sounds.
4. Android back affordance on the remaining full-screen destinations (Conversation, Grant editor,
   Safety, Focus, Paywall) via `DetailTopBar`.
5. Android card → detail shared bounds once a device/emulator can verify list-detail pane changes
   and predictive back.
6. Usability rounds (doc 03 §9 step 8) on the quack + delivery trail: do people read "Reached their
   phone" correctly?
