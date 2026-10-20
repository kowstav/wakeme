# Sizing and typography audit — 2026-10-21

Scope: every shared component and the principal screens on both apps. Final values live in
[`tokens.json`](../../packages/design-tokens/tokens.json) (`type.roles`, `layout`, `component`) and
are summarised in [system.md](system.md) §3.

## Evidence

"Before" is real rendered output of the code at `7b50acf`, read from CI artifacts:

- Android 17 emulator journey screenshots and 34 Compose preview renders (layoutlib), android-device
  [run 53](https://github.com/kowstav/wakeme/actions/runs/39448657221). Inspected: welcome, friend
  detail, new wake-up, grant editor, SOS received, large font, Friends/Routines tabs, alarm (normal
  and 2× text), quiz at 2× text.
- iPhone 17 Pro simulator screenshots, ios [run 35](https://github.com/kowstav/wakeme/actions/runs/39401474021)
  (the run failed later at the AlarmKit prompt, a known issue): welcome, friend granted, new wake-up.

Emulator and simulator renders are not device evidence (ADR 0009). "After" renders come from the
next CI run of this branch; this machine cannot build either app.

## Findings, ranked

| # | Sev | Screens | Problem (seen in the renders) | Cause | Fix |
|---|---|---|---|---|---|
| S1 | P1 | Alarm (Android) | Clock face set in monospace: the colon is spaced out ("07 : 00") and it reads as a terminal, not a clock | `FontFamily.Monospace`, 64 sp | System font with tabular figures, 76 sp Bold (`alarmTime`), grows with font size only to 1.3× so it never overflows at 2× |
| S2 | P1 | Alarm (Android) | 72 dp "I'm up"/"Stop sound now" carry a 16 sp label; the label looks lost in the button | `PrimaryButton` used one label size for every height | Buttons ≥ 72 dp use the 22 sp `section` role |
| S3 | P1 | Friend detail, grant editor (Android) | Card titles at 22 sp ("What Alex can do on your phone" wraps to two heavy lines) compete with the 28 sp screen title | `SectionTitle` (titleLarge) used inside cards | New `cardTitle` role, 17/24 SemiBold, the same on iOS (`.headline`); `SectionTitle` reserved for headers between groups |
| S4 | P1 | Friends, Routines lists (Android) | Names render in a *regular* 22 sp weight, and cards are ~120 dp tall for three short lines | Material's titleLarge is weight 400; `WMCard` spaced every child 12 dp apart | Names 17 sp SemiBold beside a 44 dp avatar; title and supporting line grouped 2–4 dp apart, the pill 12 dp below |
| S5 | P1 | Paired actions (both) | Primary and secondary buttons differ in height (Android 48 vs 52; iOS 44 rounded rectangle vs 50 capsule) so stacked pairs look accidental | Separate defaults per component | Both 52 tall capsules with the same 17 semibold label; the fill says which leads |
| S6 | P1 | Welcome (iOS, tall phones) | The two actions sit mid-screen with the bottom third empty; out of easy thumb reach | Top-aligned scroll stack | Actions pinned in the bottom safe area on both apps; the story scrolls above |
| S7 | P2 | Every Android screen with body text | Body copy looks loosely spaced ("This preview plays only on your phone") | Material's 0.5 sp tracking on 16 sp Roboto | Body 0.15 sp (0.009 em), supporting 0.1 sp |
| S8 | P2 | Text buttons (Android) | "Pause Alex's permissions", "Remove friend" start 12 dp right of the text above them | `TextButton`'s 12 dp content padding | `QuietButton` has no side padding (still a 48 dp target) |
| S9 | P2 | New wake-up, grant editor (Android) | Group labels ("Start with", "Sound") sit as far from their chips as from the previous group; "Time and days" used a different style from its siblings | Uniform 16 dp column spacing | `FieldGroup`: label 14 sp SemiBold secondary, 8 dp above its controls; groups 16 dp apart |
| S10 | P2 | Grant editor (Android) | "From 08:00 / Until 22:00" are small indented accent text buttons, the most important values on the card | `TimeOfDayButton` was a `TextButton` | Two equal outlined time fields (52 dp, label 12 sp above a 17 sp time) |
| S11 | P2 | Steppers (Android) | Bare "−" "+" text glyphs in 48 dp outlined circles: heavy rings, glyphs off-centre | Text standing in for icons | Material Add/Remove icons (20 dp) in a 40 dp ring inside the 48 dp target |
| S12 | P2 | New wake-up (Android) | The label text field stops at 280 dp while everything else is full width | Missing `fillMaxWidth` | Full width |
| S13 | P2 | Safety (Android) | "Start" header touches its button; the SOS explanation touches the SOS button | Items without internal spacing | Header 12 dp above its buttons with 8 dp extra above; explanation 8 dp under SOS |
| S14 | P2 | Messages (Android) | A full-width 52 dp yellow "Send" pill under the field is the heaviest element on screen and pushes the composer to two rows | Composer built from screen-level buttons | One row: field + 48 dp accent send button |
| S15 | P2 | iOS custom screens | 16 pt margins on custom screens vs 20 pt on system lists in the same app; edges don't line up between tabs | `WMSpacing.md` used as screen padding | `WMLayout.screenMargin` 20 pt (Android keeps Material's 16 dp) |
| S16 | P3 | Routines (both) | The time and the days have equal weight ("07:00 · Weekdays" in one regular style) | No time role | `timeInline` 32 SemiBold for the time, days quieter in the same text (tests and screen readers still read one string) |
| S17 | P3 | Chess board, day toggles | Covered in the Daybreak pass (board contrast, single-row toggles) | — | — |

Kept deliberately: iOS forms (`Form`, `DatePicker`, `Picker`) are native and well proportioned;
Material chip height (32 dp visual, 48 dp target); the 72 dp alarm controls; 16 dp card padding and
20 dp card radius; Safety's SOS card titles stay at their stronger size.

## Final type scale

| Role | Android (sp, size / line, weight) | iOS (Dynamic Type) | Used for |
|---|---|---|---|
| alarmTime | 76 / 80 Bold, max 1.3× | system 76 bold | Alarm clock face |
| timeDisplay | 56 / 60 SemiBold, max 1.5× | system 56 semibold (scaled where used) | Routine time on detail and editor |
| timeInline | 32 / 36 SemiBold | Large Title semibold | Routine time in lists |
| hero | 32 / 40 Bold | Large Title bold | Welcome headline |
| screenTitle | 28 / 36 Bold | Large Title bold (navigation) | Screen titles |
| prominent | 24 / 32 SemiBold | Title 2 semibold | Alarm label, challenge title, dialog titles |
| section | 22 / 28 SemiBold | Title 2 semibold | Headers between groups; alarm button labels |
| cardTitle | 17 / 24 SemiBold | Headline | Card and row titles, button labels |
| fieldLabel | 14 / 20 SemiBold | Subheadline semibold | Labels above form controls |
| body | 16 / 24 Regular, 0.15 sp | Body | Sentences |
| supporting | 14 / 20 Regular, 0.1 sp | Subheadline | Secondary lines, metadata |
| label | 14 / 20 SemiBold | Subheadline medium | Pills, chips |
| caption | 12 / 16 Medium | Caption | Trail steps, footnotes (the floor) |

Platform differences are deliberate: Android follows Material's 28 sp screen titles and 16 sp body;
iOS follows HIG's 34 pt large titles and 17 pt body.

## Component sizes

| Component | Visible | Hit area | Label |
|---|---|---|---|
| Primary / secondary / destructive button | 52 tall, full width, capsule, 24 side padding | ≥ 52 | cardTitle (17 semibold) |
| Alarm control | 72 tall | ≥ 72 | section (22 semibold) |
| Tertiary (`QuietButton`) | text only, aligned to content | ≥ 48 dp / 44 pt | body medium, accent text |
| Icon button / stepper | 40 ring, 20 icon | 48 dp | spoken label |
| Chip | 32 tall (Material) | 48 dp | label |
| Day toggle | ≤ 44 circle | full row height ≥ 48 dp / 44 pt | cardTitle |
| Time field (Android) | 52 tall, 1 dp outline, radius 14 | whole field | caption over cardTitle |
| Status pill | 12 × 6 padding, capsule | not interactive | label |
| Avatar | 44 (rows), 56 (headers) | decorative | — |
| Card | 16 padding, radius 20, 12 between groups, 2–4 inside a title group | — | — |
| Screen margin | Android 16 dp, iOS 20 pt; readable width 640 | — | — |
| Section header | 8 extra above, 12 below | — | section |
