# ADR 0021: Bloom, a vibrant visual system with icons and bento layouts

Date: 2026-10-21. Status: accepted (implemented; native builds, previews and screenshots pending CI).
Supersedes the visual parts of [ADR 0020](0020-daybreak-design-system-and-motion.md) (colour,
shape, cards, buttons); its motion, haptics, duck geometry, delivery-trail rule and
increased-contrast approach stay.

## Context

The owner found Daybreak bland and text-heavy: cream surfaces, one yellow, long sentences on
buttons (the friend page stacked nine full-width text buttons) and about ten icons across both
apps. They asked for a comparison with today's top US apps and for the colours of two reference
images (a Wise app board, a painted flower), keeping the green for buttons. The research is in
[research-2026-10-21-top-apps.md](../design/research-2026-10-21-top-apps.md).

## Decision

1. **Palette from the references.** White page, flat `surfaceNeutral` cards (`#EBEDEA`), the Wise
   lime `#9FE870` for every action and selection with forest `#163300` ink, a forest hero surface,
   and eight **petal families** (lime, orchid, sky, apricot, gold, aqua, periwinkle, forest) whose
   fills are sampled colours. Families mark things of the same kind and never carry status;
   semantic info/success/warning/danger stay separate and checked.
2. **Cards are flat** (no border, no shadow) on both platforms; only overlays cast shadows. Quiet
   controls inside cards switch to white (`LocalOnCard` / `wmOnCard`).
3. **Secondary buttons are filled neutral pills**, not outlines: their words identify them, so
   WCAG 1.4.11 does not require a 3:1 boundary. Outlines stay on fields, toggles and open days.
4. **An owned icon set** (57 SVGs, 24 grid, 2-unit round strokes) generated into Android vector
   drawables and an iOS asset catalog by the token generator. Android drops
   `material-icons-extended`. iOS uses the same drawings instead of SF Symbols in app UI, for one
   brand language across platforms; SF Symbols stay where the system requires them.
5. **New components on both platforms**: `HeroCard`, `BentoTile`/`BentoRow`, `QuickAction`,
   `IconChip`, `RoundIconButton`, `PillButton`, `DisplayText`, icon parameters on buttons and
   status pills, `ListRow` (Android), `cardRows()`/`bentoRow()` (iOS lists).
6. **Icons vs words rule** (research §4): icon-only for universal compact actions with a spoken
   label; icon + one word for tabs and quick actions; words with a leading icon for most buttons;
   words only for consent, dialogs, alarm Stop and escapes.
7. **Type**: system fonts; a condensed, heavy, capitalised `display` role for the welcome only;
   bolder screen titles and numerals; 56-high full-width buttons; radius 24 cards, 32 sheets.
8. **Layout**: the Friends home leads with the next alarm as a forest hero and two bento tiles
   (Invite, Join with a code); the friend page has a row of quick actions and moves Remove/Block
   into a More menu; the You tab is a bento grid of destinations; routines show a time-of-day icon
   in its own colour.

## Consequences

- Test-visible words are kept; where a label became a short quick-action word, the spoken label
  keeps the old phrase (iOS `accessibilityLabel`, Android `onClickLabel`). The Android journey taps
  "Invite" on a circle (was "Invite to this circle"); iOS needs no test change.
- Avatar colours change (seven families instead of six muted tints); the mapping stays shared and
  stable across platforms.
- Vibrant fills differ in lightness, so tiles and avatars no longer have equal visual weight; this
  is intended (the owner asked for vibrancy), and the alarm, SOS and escape flows stay calm.
- Dark mode keeps the hue of every family with a deep fill and the bright colour as ink.
- Nothing native was compiled or rendered on the authoring machine (no Android SDK or macOS);
  `npm run generate:tokens`, its `--check` mode and the contrast checks passed. CI must build, run the
  journeys and render the previews before any of this counts as verified.
