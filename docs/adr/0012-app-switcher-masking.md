# 0012 — Sensitive screens are masked in the app switcher, not screenshot-blocked

- Status: Accepted (2026-10-20)

## Context

Doc 02 §10 asks to mask sensitive screens in app-switcher snapshots and to choose Android's
`FLAG_SECURE` only "after evaluating accessibility/support tradeoffs". The sensitive screens are
Safety (where friends are, who is told, SOS) and private Messages. Wake Me has no screen that
shows secrets such as passwords or payment details.

`FLAG_SECURE` blocks the owner's own screenshots and screen recording, blanks the window for
some remote-assistance and accessibility tools, and would blank the CI screenshots used as
design and behaviour evidence. Its benefit over masking the Recents thumbnail is protection
against screen capture by other apps, which on current Android already needs a visible,
user-approved MediaProjection session.

## Decision

- Android: Safety and Conversation call `Activity.setRecentsScreenshotEnabled(false)` while
  shown (`HideFromRecents()`), Android 13+. Android 12 shows the thumbnail as before.
- iOS: Safety and Conversation cover themselves while the scene isn't active
  (`appSwitcherCover()`), so the snapshot iOS takes for the app switcher shows a plain surface.
- No `FLAG_SECURE` and no screenshot prevention on either platform.

## Consequences

- People can still screenshot their own conversations or a friend's shared location; the
  consent copy already says revocation can't recall a copy a viewer made (doc 02 §10).
- Revisit if a screen ever shows credentials, recovery codes or payment data, or if a security
  review finds a realistic capture threat that masking doesn't address.

## Evidence

Code only. Not yet observed in the Recents/app-switcher view on an emulator, simulator or phone.
