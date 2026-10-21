# Android skills used in the Codex continuation

Reviewed 2026-10-20. Source: [Google / Android skills](https://github.com/android/skills),
revision `ed0a2728ff531174383f910c649694de1eaebcd6` (Apache-2.0).

The owner installed all 30 skills globally with skill-installer after the plugin manifest was
rejected by the local Codex CLI. Only these relevant skills were applied in this continuation:

| Skill | Application |
|---|---|
| android-cli | Official CLI setup, SDK setup and current state-saving documentation |
| adaptive | Readable wake/challenge layouts, form-factor previews; no experimental Grid added |
| edge-to-edge | Safe-drawing/keyboard insets, adjustResize for the alarm and focus activities |
| jetpack-compose-performance | Saved and keyed UI state, formatter caching, off-main decoding, fresh timer callbacks |
| android-permissions-security | Direct Boot storage boundaries/unlock receiver; microphone rationale, denial and foreground recording lifecycle |
| android-intent-security | Protected broadcast allowlist, immutable explicit alarm intents, HTTPS invite/warm Intent validation |
| glance-canonical-layouts | Copied the standard toolbar layout, controls, provider and dimensions from the pinned skill; adapted actions/icons, removed demo code and unused layouts; SizeMode.Exact |

Their installed SKILL.md contents match the pinned snapshot after normalizing line endings.
No additional skill scripts or bundled profiler binaries were run. The emulator QA skill from
the installed test-android-apps plugin was also consulted. CLI task commands used `--no-metrics`.

The copied widget sources retain their Apache-2.0 notices. Glance 1.2.0 was checked against the
[official release notes](https://developer.android.com/jetpack/androidx/releases/glance). Widget
actions open the app and never send a quack or start a safety/focus session automatically.

The resulting changes are described in ADRs 0015–0019 and PROGRESS.md. No performance improvement has
been benchmarked, and no new screenshot reference has been approved or committed. Local emulator
setup failed for insufficient disk space; only test execution actually recorded in PROGRESS.md
counts as verification. Physical-device and distribution gates remain open.
