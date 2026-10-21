# Installed skills — review record (doc 05 §3)

Project skills in this folder are loaded by Claude Code sessions opened in this repository. Only
skills relevant to Wake Me are installed (CLAUDE.md: "use only relevant skills, not an
indiscriminate global collection").

## android/skills (Google) — 8 of 30 skills

- Source: https://github.com/android/skills (GitHub organisation `android`, Google / Android)
- Revision: `ed0a2728ff531174383f910c649694de1eaebcd6` (2026-10-19, plugin version 1.0.15)
- Licence: Apache-2.0 (`LICENSE.txt` in each skill folder)
- Requested by the owner on 2026-10-20 (`/plugin install android-skills --marketplace android/skills`);
  copied from the pinned revision because `/plugin` runs only in an interactive Claude Code session.
- Reviewed 2026-10-20:
  - The plugin manifest declares skills only: no hooks, MCP servers, commands or agents.
  - The installed skills are Markdown only (`SKILL.md` + `references/*.md`); nothing executable.
  - Not installed: the other 22 skills (TV, Wear, Cars, XR, CameraX, Media3 Cast, Engage SDK,
    Navigation 3 migration, XML-to-Compose migration, Glance, on-device AI, …: not used by Wake
    Me), including `play-policy-insights` (Python scripts that scrape the Play Store) and
    `android-profiler` (ships a prebuilt `trace_processor` binary).
- Permissions: none beyond what a session already has; the skills are instructions.

| Skill | Why it's here |
|---|---|
| `android-intent-security` | Exported activity/receiver, notification PendingIntents, deep links |
| `android-permissions-security` | Runtime permissions (notifications, exact alarms, location), denial handling |
| `edge-to-edge` | Target SDK 37 edge-to-edge, keyboard insets in editors and chat |
| `adaptive` | Android 17 large screens/foldables (doc 06): navigation rail, readable layouts |
| `testing-setup` | Unit, instrumented and screenshot test structure |
| `jetpack-compose-performance` | Compose recomposition and list performance |
| `agp-9-upgrade` | Android Gradle Plugin upgrades |
| `r8-analyzer` | Release minification and keep rules |

To update: re-check the repository, review the diff of the installed skills, copy the new revision,
and update the revision line above.

### Codex continuation, 2026-10-20

The owner installed all 30 Android skills globally for Codex using the skill-installer helper
after `codex plugin add android-skills@android-skills` rejected the plugin manifest. This
continuation applied only `android-cli`, `adaptive`, `edge-to-edge` and
`jetpack-compose-performance`. Their installed SKILL.md contents were compared with the
snapshot at `ed0a2728ff531174383f910c649694de1eaebcd6` (line endings normalized), and match.
No additional skill scripts or bundled profiler binaries were executed. The official Android
CLI was installed and used for current documentation and SDK setup; its metrics flag was
disabled for task commands. The test-android-apps emulator QA instructions were also consulted.

Applied to the wake/challenge slice (ADR 0015): readable width, separate scrolling task and
escape controls, keyboard insets/adjustResize, saved UI state and stable session identity,
cached formatters, off-main JSON parsing and fresh callbacks in timer effects. New previews
do not approve or update any committed screenshot baseline. Runtime performance is unmeasured.

## Applied so far (2026-10-20)

- `android-intent-security`: `RescheduleReceiver` (exported for system broadcasts) now ignores any
  other action; `MainActivity` (exported) follows only notification routes it creates
  (`NotificationRoutes`, unit-tested). All PendingIntents were already immutable; no providers or
  bound services are exported.
- `android-permissions-security`: notification asks already explain first, never loop and send
  people to settings afterwards; a denied location in the Safety dialog now offers the app's
  settings. No custom permissions, providers or bound services.
- `edge-to-edge`: `AlarmActivity` calls `enableEdgeToEdge()`; editors, readiness, circle, paywall
  and chat screens apply safe-drawing (bars + keyboard) padding; the guest list pads itself.
- `adaptive`: the app shell is a `NavigationSuiteScaffold` (bottom bar on phones, rail on tablets
  and unfolded foldables) with per-screen insets. Multi-pane layouts need Navigation 3 and are
  not adopted (no migration).
