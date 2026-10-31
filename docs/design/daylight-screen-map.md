# Daylight screen map — 31 October 2026

This replaces the rejected green/3D welcome and Bloom's visual palette throughout the native apps. It is a shared system change plus specific layout changes, not a claim that every screen has been individually approved by the owner.

## References actually inspected

1. [Alarmi: social alarm and morning routine screens, Karina Bondarenko](https://dribbble.com/shots/27155856-Alarmi-Gamified-Social-Alarm-Morning-Routine-App-UI). Visually inspected the original image: lavender/white canvas, dark time-first alarm cards, rounded category controls, a dark profile identity card and colored shortcuts. These structures inform Friends, Routines, personal alarms and You.
2. [Habit Tracker App UI/UX, Husnain Jaleel](https://dribbble.com/shots/22811169-Habit-Tracker-App-UI-UX-Design). Visually inspected the original image: a compact list hierarchy, icon-led category choices, grouped creation controls and one main action. These structures inform challenge galleries, editors and secondary forms.

These are design concepts; their presence on Dribbble does not establish shipped usability or popularity. Mobbin access was not needed and no Mobbin screen was claimed as reviewed. Reference images are research-only and are not bundled in the app.

## Coherent native system

- Lavender-tinted canvas and white cards; ink alarm/profile surfaces; violet primary actions; apricot and muted category colors.
- Manrope variable font, bundled unmodified from google/fonts with OFL licenses on both platforms. Official Google branding keeps its official Google Sans/logo. OS-owned dialogs and navigation retain platform behavior.
- Semantic shared spacing, cards, control sizes, headings, time numerals, status and selection roles. Existing contrast requirements remain enforced in light, dark and increased-contrast modes.
- Original flat vector scenes replace the two oversized 3D bitmap scenes. Shared SVG source, Android vector drawables and iOS vector catalogs are versioned. Person avatars now use real-name initials in stable category colors; sound picker animals remain sound choices.
- No fabricated streaks, charts, photos, achievements, subscription status or fake controls copied from the references. Every production control still uses the existing real state and action.

## Screen coverage

| Screen family | Android / iOS implementation | Design treatment |
|---|---|---|
| Welcome | Onboarding.kt / WelcomeView.swift | New headline-first composition, flat scene, aligned account and local entry group; image yields space to large text |
| Google/email, profile creation, invitation, consent | Onboarding.kt / SignIn and Onboarding features | Shared type, fields, action hierarchy, card surfaces and category colors; existing authentication/consent preserved |
| Local wake-ups | GuestHome.kt / GuestView.swift | Dark time-first alarm cards; empty-state art only when empty; compact readiness/account routes |
| Friends and friend detail | FriendsScreens.kt / FriendsView.swift | Ink next-alarm card, compact invite/join, white person cards, real initials, refreshed quick actions |
| Circles and membership | Friends circle feature files | Shared cards, headings, monograms, fields and selected controls |
| Routines and proposals | Routines.kt / RoutinesView.swift | Dark time-first cards; recurrence and label quieter; truthful readiness remains visible |
| Routine editor/detail | Routine editor feature files | New semantic font/spacing/fields/cards/selection; existing advanced disclosure and real scheduling intact |
| Challenges and template gallery | Challenges.kt / ChallengesView.swift | Short icon-led category tiles, compact gallery, revised type and category colors |
| Challenge authoring/try-out/runner | Challenge feature files | Shared hierarchy, fields, progress, action and escape styling; no escape/assertion changes |
| Profile / You | ProfileOverview.kt, YouScreen.kt / YouView.swift | Dark identity card, colored feature grid, shorter overview; Android full alarm checklist opens its existing readiness route |
| Chat and reactions | ConversationScreen.kt / ConversationView.swift | New type, violet conversation surfaces, field/control palette and status styling |
| Focus and shield | Focus feature files | Shared type, colors, cards, choices and real escape controls; accessibility binding/declaration gates preserved |
| Safety, check-ins, location, SOS | Safety feature files | Refreshed shared hierarchy and cards; explicit consent, duration, stop and emergency meaning retained |
| Private voice | VoiceSoundsScreen.kt / media features | Shared recording/list typography, cards and actions; real storage/provider state retained |
| Subscription | PaywallScreen.kt / PaywallView.swift | New type, surfaces and action hierarchy; actual products/prices/provider state retained |
| Permissions/readiness | ReadyScreen.kt / ReadyView.swift | Shared controls/cards; required OS explanations remain explicit |
| Ringing and wake session | Alarm and wake-session feature files | Ink surfaces, new type and semantic controls; touch sizes, safety and escape behavior retained |
| Completion/thanks, settings, dialogs | Shared components and feature files | Shared font, palette, card spacing, symbols and action hierarchy |

Rows marked shared treatment retain their working feature layout; the direct layout rebuilds are identified above. A full visual acceptance of every state still requires the emulator/simulator journeys and owner review, especially providers that are not configured.

## Accessibility and verification

All token foreground/background checks and category-family contrast checks pass. Android's real alarm journey now verifies time and recurrence on the same merged routine card even though they occupy separate visual lines. iOS reads time and recurrence as one native accessibility phrase, with readiness still separate.

Initial Android source, existing instrumented tests and all preview renders compiled successfully. The final candidate adds compact metadata, actual profile-overview previews, initials, increased-contrast variants and version 0.1.2 (3); its full staging build, unit/lint checks and renders are being verified. No fresh iOS compile or physical acceptance is claimed until recorded.

## Constraints retained

Docs 01–04 and 06 still govern feasibility, trust, scheduling, consent, delivery and OS behavior. A prettier screen does not complete backend provider configuration, physical alarm/audio testing or store policy/release gates. The previously observed API 33 Focus binding flake remains a reliability gate, not a reason to relax the shield assertions.

## Recorded local result

The final 0.1.2 (3) staging APK passed assembleDebug, lintDebug, compileDebugAndroidTestKotlin and updateDebugScreenshotTest (final compact-action build: 4m 41s). The candidate's unit run passed 22 tests with no failures or skips; the final layout-only change keeps the same alarm action and is covered by the updated journey source plus renders. Lint reports 0 errors, 73 warnings and 6 hints, not a warning-free codebase. Token generation/check, contrast requirements and secret scan passed; two tooling tests passed.

49 native previews were generated, including the actual profile-overview component on four window sizes. Inspected representative welcome light/dark/large text, Friends, Routines, Challenges and profile renders. Routines now has a persistent, compact plus control, with its accessible name asserted before the real journey clicks it. Remaining list content reserves space below the control.

The packaged APK's version, debug package and registered signing certificate were verified. Compiled staging API/Supabase/Google configuration and bundled Manrope were verified; superseded native 3D bitmaps are absent. The font's actual PostScript name, Manrope-ExtraLight, was read from its name table and used for iOS registration/SwiftUI font lookup, with semantic weights applied.

The local review bundle contains the APK, a native contact sheet, all 49 renders and a filterable HTML gallery. These are Compose renders with fixtures, not physical-device or iOS screenshots. Fresh emulator/simulator CI is still required for this new source. No store upload or new physical Google-session result is claimed.
