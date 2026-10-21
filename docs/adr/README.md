# Architecture decision records

One file per material decision (CLAUDE.md: "Write ADRs for material decisions").
Status is one of: **Proposed**, **Accepted**, **Superseded by NNNN**. Each record
says what evidence the decision rests on and what would make us revisit it.

| ADR | Decision | Status |
|---|---|---|
| [0001](0001-native-apps-shared-contracts.md) | Native SwiftUI + Compose apps over shared contracts, fixtures and tokens | Accepted |
| [0002](0002-recipient-owned-directional-grants.md) | Friendship grants nothing; recipient-owned, directional, versioned grants | Accepted |
| [0003](0003-messaging-is-not-a-grant.md) | Messaging between accepted friends is not a grant | Accepted |
| [0004](0004-location-sessions-not-standing-reads.md) | Location only through owner-started, time-boxed sessions | Accepted |
| [0005](0005-reauthentication-windows.md) | Recent sign-in for high-impact grants (24 h) and deletion (15 min) | Accepted |
| [0006](0006-android-exact-alarm-permission.md) | Android uses user-granted `SCHEDULE_EXACT_ALARM`, not `USE_EXACT_ALARM` | Proposed |
| [0007](0007-primary-device-rings.md) | Only the primary phone installs and rings routines | Accepted |
| [0008](0008-signed-challenge-envelopes.md) | Published challenges are ES256-signed and verified on the phone | Accepted |
| [0009](0009-ci-emulators-and-simulators.md) | Device CI on GitHub-hosted emulators/simulators; not device evidence | Accepted |
| [0010](0010-sync-feed-commit-order.md) | Sync feed positions follow commit order; restores force one re-fetch | Accepted |
| [0011](0011-rolling-arming-window.md) | Routines are armed 8 days ahead and topped up whenever the app runs | Accepted |
| [0012](0012-app-switcher-masking.md) | Safety and Messages are masked in the app switcher; no FLAG_SECURE | Accepted |
| [0013](0013-app-attest-play-integrity.md) | App Attest / Play Integrity evaluated; not adopted until store accounts exist | Proposed |
| [0014](0014-focus-supervision.md) | Focus: recipient-chosen apps, local shield (iOS Family Controls, Android scoped accessibility service), always finite | Proposed |
| [0020](0020-daybreak-design-system-and-motion.md) | Daybreak design system: outline/container roles, increased contrast, motion tokens, original duck, shared delivery-trail rule | Accepted |
