# 0001 — Native SwiftUI + Compose apps over shared contracts

- Status: Accepted (2026-10-19)
- Context documents: 02-ENGINEERING-BLUEPRINT.md §1–2, 06-LATEST-OS-CAPABILITIES.md

## Context

The headline capabilities depend on new, platform-specific APIs: AlarmKit, App Intents
and Live Activities on iOS; exact alarms, `systemExempted` foreground services, `USAGE_ALARM`
audio and direct boot on Android 17. Doc 02 recommends native apps when both platforms and
full capability depth matter, and allows a React Native ADR if Stage 0 evidence or team
constraints favour it.

## Decision

Build native apps (SwiftUI on iOS, Kotlin/Compose on Android) and share everything that is
not lifecycle code:

- the HTTP contract (`packages/contracts/openapi.yaml`, generated from the server and checked for drift);
- policy and verification rules, re-implemented per platform and held equal by golden fixtures
  (`packages/policy-fixtures`): Android `:core` and iOS `WakeMeCore` run the same cases as the server;
- signed challenge vectors, design tokens (generated Swift/Kotlin) and bundled sounds.

## Consequences

- Two UI codebases; parity is enforced by fixtures, not by shared runtime code.
- iOS builds need macOS. Until a Mac is available, iOS is built and tested on GitHub-hosted
  `macos-26` runners (ADR 0009); `WakeMeCore` also runs on Linux.

## Revisit when

Stage 0 device results show the native paths give no advantage for the headline features,
or the team cannot sustain two UI codebases.
