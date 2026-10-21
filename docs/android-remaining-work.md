# Android remaining work — 2026-10-21

There are **eight broad completion workstreams**, not eight small engineering tickets. Most
feature code is implemented. Passing emulators does not establish production integrations,
physical alarm reliability or release approval. This inventory follows docs 01–04 and 06.

| # | Workstream | Remaining completion evidence / dependency |
|---|---|---|
| 1 | CI stability and reliability verification | Codex: QuickActionsWidgetTest live-view lookup and repeated real host/tap checks on Android 16/17. Claude: Focus binding in PR #2. The Android 17 End-focus failure belongs to the Focus follow-up after PR #2 merges. Keep real shield, escape and routing assertions; never turn a failed journey into a skip. |
| 2 | Daybreak design/UX acceptance | Claude owns the design pass. Review final renders on small/large, light/dark and large text; accessibility/keyboard navigation and physical motion/haptic checks; approve screenshot baselines only after review. |
| 3 | Real authentication | Owner-configured Supabase/Google providers; actual Google and email sign-in, account switching, sign-out/re-auth/deletion against staging. |
| 4 | Real push delivery | Owner-configured Firebase/FCM and reachable backend; two-phone foreground/background/terminated/reboot tests, TTL/expiry, refresh and honest receipts. |
| 5 | Play billing | Owner Play/RevenueCat projects and sandbox products; purchase, restore, expiry/grace/refund and manage-subscription checks. Alarm stop/escape stays available throughout. |
| 6 | Private voice storage/scanning | Owner-configured private bucket/scanner/worker; upload/scan/offer/recipient approval/removal round trip, rejection, offline/corrupt-cache fallback and account isolation. |
| 7 | Physical-device QA and soak | Execute docs/device-results/README.md on supported Android versions, Pixel/Samsung/aggressive OEMs, with real audio/accessories, locked reboot, offline pause/end, multi-day arming, permissions and timing. No physical results recorded yet. |
| 8 | Release preparation and operations | Signing/Play ownership, policy declarations and approved focus rollout, accurate SDK/data-safety disclosures, legal/privacy/security review, scrubbed telemetry/retention, restore/rollback/lost-device/deletion drills, real store screenshots and reviewer access. |

## Coordination

- Design/UX and the in-progress Focus binding fix are owned by Claude.
- Until PR #2 merges, do not edit FocusShieldTest.kt or its workflow step, and do not push or
  dispatch android-device on ci/focus-binding-api36. Independent widget work uses
  codex/android-widget-stability in a managed worktree.
- Rebase shared progress changes on the latest agreed base rather than overwriting another
  contributor's notes. New test evidence must name the exact code/run and conditions.
- CI stability is still open until the widget fix and the separately owned Focus work pass.
  Any newly discovered functional defect becomes a concrete fix under workstream 1; this
  table is not a claim that eight individual edits will make the app release-ready.