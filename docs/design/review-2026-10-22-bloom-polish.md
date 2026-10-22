# Bloom polish review (2026-10-22)

Source: the CI screenshots of commit `b9d6ef0`. That is 38 iOS simulator screens (ios run 44), 33 Android 17
emulator screens and 30 Compose previews (android-device run 88). Every screen was read at full size
with two questions in mind: *is this element or sentence needed here?* and *is the space around it right?*

## What works

- Lime actions on forest ink, the per-family bento tiles, the round quick actions and the forest
  "next alarm" hero (Android Friends) read as one system on both platforms.
- The type scale holds up. Small text in the gallery was the 360 px CI downscale, not the app.
- Safety copy (not an emergency service, who is told, no location shown as none) and the
  always-visible challenge escape are correct and stay.

## Findings and decisions

| # | Where | Problem | Decision |
|---|---|---|---|
| 1 | Everywhere | A lone paragraph sits in a grey card (Your wake-ups, Focus ×2, Safety, Challenges, What can Alex do?, Circle). A card says "this is a group of things you can act on"; a paragraph alone is not. | Explanations become a one-line `Note` (small icon + supporting text, no card) or are cut. Cards only hold controls or content. |
| 2 | Everywhere | Explanations restate what the title or button already says. | Each screen keeps at most one sentence of explanation, placed next to the control it explains. |
| 3 | Make this phone ready | Two titles ("Make this phone ready" in the bar, "Let's make sure your alarm rings" below). "Alarms allowed" sits alone in a card. A trailing "Next step" pill repeats the card above it. | One title. The status pill moves into the card it describes. The trailing pill is gone; the test result line is the next step. |
| 4 | Welcome | Headline, tagline, preview pill and a two-line privacy note float in a column with a large dead gap above the buttons. | The tagline goes (the headline already says it). The privacy note moves beside the decision, under the buttons. The story block centres in the space. |
| 5 | Your wake-ups (guest) | An intro card, then an action card mixing a button with a nav row, then a three-part "Wake each other up" card. | No intro card. The friends invitation becomes one row: icon chip, title, one line, and a button. |
| 6 | Friends (empty) | The "Invite someone you trust" card repeats the two tiles above it. The Circles section opens with a paragraph card. | The invite card keeps its title but loses half its words. The circles explanation becomes a `Note`. |
| 7 | Friend detail | The Quack card shows a duck, then a lone speaker circle with no label, then status text. "Hasn't turned this on" appears in two places. The duck is the only capability without an icon chip. | Every capability card gets the same header (chip, title, trailing action). The preview button is labelled. Each status is said once. |
| 8 | Challenges | The primary action is a text link inside an intro card. Templates are text cards with "Try …" links. | One subtitle line, then a lime "Create a challenge" button. Templates become coloured bento tiles with icons; tapping one tries it. |
| 9 | Safety | Intro paragraph card. "You're sharing" is a card holding only a pill. | The intro becomes the emergency `Note`. The sharing status is a plain row. |
| 10 | Focus | Two explanation cards before the first control. | One `Note`. The duration chips and Start lead. |
| 11 | Circle | A stray disclosure chevron between the Messages and Invite quick actions (a `NavigationLink` inside the row). The paragraph sits in a card. | Navigation moves off the row, so no chevron. The paragraph becomes a `Note`. |
| 12 | Invite sheet | Three paragraphs around a code. | The code, one line on how to use it, one line on what accepting means. |
| 13 | Android welcome | The display face renders as regular-weight Roboto Condensed: the brand moment is thin next to iOS's Condensed Black. | Load the condensed device family at ExtraBold, so the weight axis is used instead of a fixed regular typeface. |
| 14 | Android guest home | "Make this phone ready" is a card of three paragraphs and two buttons. | A title, one status, the two actions, and a one-line note. |

## Not changed

- Strings the UI tests look up (for example "Invite someone you trust", "07:00 · Weekdays",
  "No challenges yet. Make one, or start from a template.") keep their exact text. Only their
  surroundings change.
- Legal and safety sentences: account deletion, the emergency-service note, location capture time
  and accuracy, "Your own alarms still ring while requests are paused".
- The widget keeps the wallpaper's dynamic colours (system.md §9).
