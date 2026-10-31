# ADR 0023: First light, a refinement of Daylight

Date: 2026-10-31. Status: accepted (Android rendered locally; iOS compile and owner review pending).
Refines [ADR 0022](0022-daylight-native-redesign.md). It keeps Daylight's palette, type and structure.

## Context

The owner asked for the whole app to be taken through the unslop skill. The rendered app was competent but could have belonged to any habit tracker: the welcome headline had drifted from the product's, the same sun chip sat on every card, every status was a pill and pastel tiles competed with the one thing that matters on a screen. Three full repaints in ten days also argued against a fourth.

## Decision

1. **Keep the palette and give it a reason.** Lavender, ink and violet become the sky before sunrise, and apricot is the sun. Apricot appears only inside the moments, never in the chrome.
2. **Adopt one signature, the dawn arc.** A horizon and a dotted path with the sun or moon placed by the alarm's real time, on every time surface. It is derived from data, decorative for assistive technology, and never animates.
3. **Draw the scene in code** (`DawnScene`), so it fills any window, follows the theme and needs no image asset or paid generation.
4. **Restore the product's headline** on the welcome, per `CLAUDE.md`: "Alert your friends. Wake each other up."
5. **Status is quiet unless it needs action.** Only warnings and problems get a tinted block.
6. **Colour on tiles moves to the icon chip.** One bright thing per screen.
7. **Controls stop being capsules.** 52 tall with a 14 radius, chips 10, the alarm's 72 tall controls 20.
8. **The escape is not a failure.** "End focus" becomes a neutral button; `DangerButton` is for destructive actions and SOS.
9. **On the alarm, finishing leads and silencing is a separate control** of the same size, both in thumb reach. Neither behaviour changes.

## Consequences

- Both apps change through the shared tokens plus matching component edits. iOS was edited without a compiler on this machine, so its CI run is the check.
- The welcome copy changes on both platforms. `.local-tools/package-daylight.ps1` asserts the old headline and needs updating if it is reused.
- `daylight.svg` and `orbit.svg` remain in `packages/design-assets` as provenance; neither app ships them.
- This pass used an installed third-party skill under CC BY 4.0, so its credit stays in the design record.
