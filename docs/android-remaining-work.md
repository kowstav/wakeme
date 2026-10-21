# Android remaining work — 2026-10-21

The audit groups completion into **eight broad workstreams**, not eight small engineering tickets. CI integration for the reviewed fixes is verified; the other seven workstreams remain open. Most
feature code is implemented. Passing emulators does not establish production integrations,
physical alarm reliability or release approval. This inventory follows docs 01–04 and 06.

| # | Workstream | Remaining completion evidence / dependency |
|---|---|---|
| 1 | CI stability and reliability verification | Codex widget fix verified in run 67: four independent real host/tap checks per OS (eight total), with both journeys passing. PR #4 (replacing closed PR #2) merged the Focus binding and End-focus timing fixes. Combined source 85d2ef7 passed every job in general CI 20 and Android run 76: both OS component/Focus/journey checks, eight real widget host/taps, and 38 previews. Reviewed CI integration is complete. Keep real shield, escape and routing assertions; never turn a failed journey into a skip. |
| 2 | Daybreak design/UX acceptance | Claude owns the design pass. Review final renders on small/large, light/dark and large text; accessibility/keyboard navigation and physical motion/haptic checks; approve screenshot baselines only after review. |
| 3 | Real authentication | Owner-configured Supabase/Google providers; actual Google and email sign-in, account switching, sign-out/re-auth/deletion against staging. |
| 4 | Real push delivery | Owner-configured Firebase/FCM and reachable backend; two-phone foreground/background/terminated/reboot tests, TTL/expiry, refresh and honest receipts. |
| 5 | Play billing | Owner Play/RevenueCat projects and sandbox products; purchase, restore, expiry/grace/refund and manage-subscription checks. Alarm stop/escape stays available throughout. |
| 6 | Private voice storage/scanning | Owner-configured private bucket/scanner/worker; upload/scan/offer/recipient approval/removal round trip, rejection, offline/corrupt-cache fallback and account isolation. |
| 7 | Physical-device QA and soak | Execute docs/device-results/README.md on supported Android versions, Pixel/Samsung/aggressive OEMs, with real audio/accessories, locked reboot, offline pause/end, multi-day arming, permissions and timing. No physical results recorded yet. |
| 8 | Release preparation and operations | Signing/Play ownership, policy declarations and approved focus rollout, accurate SDK/data-safety disclosures, legal/privacy/security review, scrubbed telemetry/retention, restore/rollback/lost-device/deletion drills, real store screenshots and reviewer access. |

## Coordination

- Design/UX is owned by Claude. Claude's Focus binding and End-focus timing fixes merged in PR #4 on 2026-10-21; Codex owns combined CI verification.
- PR #4 has merged; preserve its real Focus assertions. Never push or
  dispatch android-device on ci/focus-binding-api36. Independent widget work uses
  codex/android-widget-stability in a managed worktree.
- Rebase shared progress changes on the latest agreed base rather than overwriting another
  contributor's notes. New test evidence must name the exact code/run and conditions.
- The widget and merged Focus fixes passed together on Android 16/17 in run 76.
  Any newly discovered functional defect becomes a concrete fix under workstream 1; this
  table is not a claim that seven individual edits will make the app release-ready.
Conditional later experiments such as guitar/pose feedback and Spotify are outside the current
core release scope. They add engineering work if their feasibility/permission gates are met;
they are not claimed as implemented by this eight-workstream inventory.
