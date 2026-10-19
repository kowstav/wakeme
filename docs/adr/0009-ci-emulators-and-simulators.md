# 0009 — Device CI on GitHub-hosted emulators and simulators

- Status: Accepted (2026-10-19)

## Context

The development environment is a Linux container without KVM (no Android emulator) and
without macOS (no Xcode). The owner approved using the repository's GitHub Actions minutes
for an Android emulator and macOS builds (macOS minutes bill at 10× on private repositories).

## Decision

- `android-device.yml`: Android 17 (API 37) emulator on a KVM-enabled Ubuntu runner; installs
  the debug APK, runs instrumented tests, cold-launches, captures screenshots and logcat.
- `ios.yml`: `macos-26` runner (Xcode 26.6); runs `WakeMeCore` tests, starts the dev backend
  (embedded database), builds the app, runs UI journeys on an iOS Simulator, captures screenshots.
- Both run only on changes to their app paths on the working branch, or by hand, to limit cost.
- Screenshots are also printed into the job log (downscaled, base64) because artifact storage
  hosts are not reachable from the development environment.

## Consequences

Emulator and simulator results show that code paths run on the platform version. They are
**not** device evidence: alarm audibility, audio routes, lock-screen behaviour, Doze/OEM
restrictions and AlarmKit timing still require the physical-device soak matrix (doc 04 §10).
