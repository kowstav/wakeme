# Research: today's top US apps, and what Wake Me takes from them (2026-10-21)

Why: the owner found the Daybreak UI bland and text-heavy (buttons carried long sentences, almost
no icons) and asked for a comparison with today's top US apps, using the colours of two reference
images: a Wise app board and a painted flower. This note records what was looked at, what was
measured, and where each finding landed in the apps. The decision record is
[ADR 0021](../adr/0021-bloom-visual-refresh.md); the system itself is [system.md](system.md); the
visual study is [bloom.html](bloom.html).

## 1. What was looked at

- **Apple's US Top Free iPhone chart**, 2026-10-21 ([apps.apple.com/us/iphone/charts/36](https://apps.apple.com/us/iphone/charts/36?chart=top-free)):
  1 Capital One Mobile, 2 Muse from Meta, 3 Vinted, 4 ChatGPT, 5 TikTok Pro, 6 Claude, 7 Google
  Gemini, 8 Threads, 9 McDonald's, 10 PineDrama, 11 CapCut, 12 Kalshi, then WhatsApp, Google,
  TikTok, Gmail, Instagram, Temu, Facebook, Depop, Google Maps.
- **App Store screenshots** of Capital One, Vinted, ChatGPT, Threads, McDonald's, Kalshi, Google
  Maps, Depop and Muse from that chart, plus apps that set the bar for this product's patterns:
  Duolingo (habit and colour), Alarmy (the closest competitor), Revolut and Cash App (money apps
  with quick actions), Airbnb (icon tabs), Robinhood, Locket (friend widget).
- **The owner's two images.** Colours were sampled pixel by pixel (ffmpeg to raw RGB, then modal
  colour per region and k-means clusters for the ribbon, globe and flower).

These are marketing screenshots, not running apps: they show layout, colour and type, but not
timing or interaction. Nothing here is usability evidence.

## 2. Colours sampled from the reference images

| Where | Sampled | Used as |
|---|---|---|
| Wise "Log in" / "Done" buttons | `#9FE66F` (Wise publishes `#9FE870`; the 2-unit gap is WebP compression) | `accent`: every primary button, selection, the navigation indicator |
| Wise headline and backdrop | `#163300` | `textOnAccent`, `brandInk`, `heroFill`, `accentText` by day |
| Wise cards, "Sign in with Apple" | `#EBEDEA` | `surfaceNeutral`: cards, secondary buttons |
| Wise secondary text | `#494B48` / `#868885` | `textSecondary` `#454745`, `outline` `#767876` (contrast-tuned) |
| Ribbon pink | `#DE82C3` | orchid family fill |
| Ribbon light blue / deep blue | `#95D3E2` / `#2A5E98` | sky family fill / `info` text by day |
| Ribbon violet | `#9386E4` | periwinkle family fill |
| Globe aqua / deep teal | `#A4F2ED` / `#045B49` | aqua family fill / ink |
| Coin gold / coin brown | `#F8C53D` / `#683305` | gold family fill / ink; duck body |
| Coin amber | `#D7961A` | duck wing |
| Petal apricot / petal maroon | `#FAAF5F` / `#6C1C0F` | apricot family fill / ink; `warning` by night |
| Petal orange / vermillion | `#ED7737` / `#D03E20` | duck beak and beak shade |

Where an image had no dark partner that clears 4.5:1 on its fill (orchid, sky, periwinkle), the
ink is derived on the fill's own OKLCH hue. Every pair is checked by the token generator
([contrast-report.md](../../packages/design-tokens/contrast-report.md)).

## 3. Findings and where they landed

| # | What the reviewed apps do | Evidence | In Wake Me now |
|---|---|---|---|
| 1 | White page, flat grey cards, **no borders or shadows** | Wise, Capital One, Cash App, McDonald's | `surface` white, `surfaceNeutral` cards, `WMCard` flat on both platforms; iOS grouped lists take the grey via `cardRows()` |
| 2 | **One saturated action colour**, dark ink on it; secondary actions are grey pills, not outlines | Wise (lime + forest), Cash App (green/black), Threads (black pills) | `PrimaryButton` lime + forest; `SecondaryButton` neutral pill; outlines only on fields and toggles |
| 3 | **The same kind of thing in different colours** | Duolingo courses, Capital One cards, Airbnb category art, the flower's petals | Seven petal families for avatars, bento tiles, icon chips, quick actions, routine time of day |
| 4 | **Bento tiles**: icon top-left, title or value bottom-left, two per row | Wise accounts, Revolut "Earned" tiles, Cash App savings | `BentoTile`/`BentoRow`: Friends (Invite, Join), You (Safety, Focus, Voice sounds, Plus, and on iOS Make this phone ready) |
| 5 | **A dark hero card** for the one number that matters | Wise balance, Capital One card art, Revolut balance | `HeroCard` (forest, lime numerals): next alarm on Friends, routine time, the editor's time |
| 6 | **Quick actions**: a row of round icons with one word each | Revolut (Request/Send), Wise, Cash App | `QuickAction`: Messages, Check in, Location, Suggest, Safety on a friend (iOS: Messages, Suggest, Safety, the actions it already had); Messages, Invite, Rename on a circle |
| 7 | **Icon-only** for back, close, more, share, send; **icon + label** in tab bars | Airbnb, Threads, Wise, McDonald's | `RoundIconButton` (back, more, preview), icon-only Send in chat, labelled tabs with Bloom icons |
| 8 | Order and delivery **trackers with icons at each stop** | McDonald's delivery, Robinhood steps | `DeliveryTrailView` stops: send → phone → seen/rang |
| 9 | Big **heavy condensed display type** only for hero moments; regular sans elsewhere | Wise ("MAKE MONEY GO ROUND"), Kalshi ("FOOTBALL IS BACK.") | `display` role: SF Pro Condensed Black / Roboto Condensed, capitals, welcome only |
| 10 | Bold, tight screen titles (28–34) and **tabular big numbers** | Wise "Account", Alarmy clock list, Cash App balance | `screenTitle` 30/700 −0.02 em, `timeInline` 34/700, `timeDisplay` 60/700 |
| 11 | Full-width buttons ~**56 pt**, pills fully round; cards **20–24** radius; 16–20 margins; 12 gaps | All of the above; Material 3 medium button 56 dp | `buttonHeight` 56, radius lg 24 / xl 32, margins 16 (Android) / 20 (iOS), `cardGap` 12 |
| 12 | Danger kept rare; destructive actions moved into a **More** menu | Threads, Airbnb, Wise | Remove/Block in a More menu on the friend page (both platforms) |
| 13 | Many reviewed apps hide tab labels or shout in every colour | Alarmy (icon-only tabs), Duolingo (saturated everywhere) | **Not adopted**: labels stay (clarity, tests, guidance), alarm/SOS/escape stay calm and text-led |

## 4. Where icons, words, or both

| Use | Where in Wake Me | Why |
|---|---|---|
| Icon only (with a spoken label) | Back, More, Play preview, Send in chat, steppers | Universally recognised, compact; the label is still read aloud |
| Icon + one word | Tabs, quick actions | Scannable rows; the word removes doubt |
| Words with a leading icon | Primary/secondary/danger buttons where an icon helps (Send quack, Invite, New wake-up, End this routine) | Recognition without losing precision |
| Words only | Consent and permission choices, dialog buttons, alarm Stop, every escape and check-in | Nothing may be ambiguous when sleepy, upset or deciding what others may do |

## 5. Icon set

57 icons drawn for Wake Me on a 24-unit grid with 2-unit rounded strokes (the weight of the Wise
and Airbnb outline icons), in [`packages/design-tokens/icons`](../../packages/design-tokens/icons).
Each was rendered and checked at 24 and 40 units before generation; the quack (a rubber-duck
silhouette) and the book were redrawn after the first check. `npm run generate:tokens` writes them
as Android vector drawables (`res/drawable/wm_*.xml`, `WMIcon` enum) and iOS template vectors
(`Resources/Icons.xcassets`, `WMIcon` enum). Back, chevron, send, the outward arrow and sign-out
flip in right-to-left languages. The Material icons library is no longer needed on Android and
was removed. SF Symbols remain only where the system requires them (AlarmKit buttons, App
Shortcuts, the paywall's radio marks).

## 6. Motion

The reviewed screenshots show no motion; the existing motion system (springs, the quack, the
trail fill, Reduce Motion rules) is kept. New surfaces reuse it: tiles, quick actions and icon
buttons settle on the press spring; nothing new loops or idles.
