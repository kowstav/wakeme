# ADR 0020: Daybreak design system, motion tokens and the delivery trail

Date: 2026-10-21. Status: accepted (implemented; native builds and screenshots pending CI).

## Context

The 2026-10-20 audit ([docs/design/audit-2026-10-20.md](../design/audit-2026-10-20.md)) found
accessibility failures (control outlines at 1.29:1, the iOS challenge escape able to scroll off
screen, no status announcements), an inconsistent identity (cool night vs warm day; the 🦆 emoji
rendered differently on every platform) and almost no motion beyond Navigation 3's 700 ms default.
Doc 03 §9 asked for three directions before choosing one.

## Decision

1. **Direction C, Daybreak** ([directions](../design/directions.html)): B's calm structure with A's
   warmth spent only on the primary action, selection, the quack and success.
2. **Tokens v2** in `packages/design-tokens/tokens.json`: an `outline` role ≥ 3:1, accent/info/
   success/warning/danger containers, `surfaceOverlay`, `focus`, a warm night palette, a more
   orange `warning`, increased-contrast variants for secondary text, outline and accent text,
   avatar and illustration colours, and motion/elevation/opacity tokens. The generator checks
   every pair in four modes and reports OKLCH.
3. **Own motion tokens**, not Material 3 Expressive's `MotionScheme` (internal in pinned
   Material3 1.4.0): four springs and Material's emphasized/standard easing.
4. **An original duck** drawn from one geometry on both platforms, with the quack as the single
   signature animation.
5. **The delivery trail is a shared rule**, not UI logic: `packages/policy-fixtures/fixtures/
   delivery-trail.json` maps each literal display status to steps reached, implemented as
   `DeliveryTrail` in Android `:core` and iOS `WakeMeCore` and tested against the fixture.
6. **Red is for destructive actions and SOS only**; readiness and validation use warning.

## Consequences

- Visual changes touch most screens, but public component APIs stay source-compatible and test-
  visible copy is unchanged.
- Increased contrast now does something on both platforms.
- Nothing native was compiled or rendered for this change on the authoring machine; CI and
  physical devices must confirm it (docs/design/system.md §10).
- Android card → detail stays a shared-axis transition until shared bounds can be verified with
  list-detail scenes and predictive back.
