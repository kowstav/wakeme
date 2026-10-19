# Delivery, launch and business plan

All schedules, budgets, prices and conversion rates below are **planning assumptions** unless linked as observed vendor facts. The goal is a launch-ready product with measurable behavior, not a claim that a generated codebase is automatically production-ready.

## 1. Team and sequencing

Practical team: one senior iOS engineer, one senior Android engineer, one backend/product engineer, a part-time product designer, and dedicated QA/security help around milestones. Claude Opus 5.5 can accelerate implementation, contract generation, debugging and review; it does not replace device access, signing authority, user research or an accountable security reviewer.

A strong team could aim for a core private beta in **12–16 weeks**, a polished core public release in **16–22 weeks**, and the expanded focus/safety/sensor feature set in **24–36 weeks**. This is an estimate with overlapping work; entitlement delays and device bugs can extend it. A solo builder should expect substantially longer or reduce platform/scope.

## 2. Stage 0 — prove platform behavior (weeks 1–2)

Build tiny native experiments before a full branded app. Use iOS 27.0.1 plus a supported iOS 26 build, and Android 17/API 37 plus earlier/OEM variants. Begin entitlement requests now.

Deliverables:

- Native scheduled alarm with custom local sound, permissions and state reconciliation.
- Immediate remote quack on real devices; measured locked/background/terminated behavior.
- Android 17 exact-alarm + foreground service + `USAGE_ALARM` demonstration.
- Speaker-route experiment with earbuds, calls and route changes.
- iOS alarm action into a cached quiz, with current system Stop behavior documented.
- Optional preinstalled main-plus-follow-up alarm sequence, cancelled by local challenge completion, with bounded and visible recipient consent.
- Screen Time/app shield experiment and a compliant Android focus prototype.
- Capability matrix with exact phone/OS/build/settings, video/audio evidence and failures.
- ADR choosing native or cross-platform product codebase.

Exit gate: each headline capability has one demonstrated supported implementation or a precise fallback and product label. Do not reject a feature from an old blog post; do not approve it from a simulator-only demo. Public release need not wait for every expansion experiment, but the results determine promises and priorities.

## 3. Stage 1 — product contract and foundation (weeks 2–4)

Deliverables: product/design direction, clickable or native flow prototype, threat model, consent vocabulary, data map, versioned API/schema, repository, CI, environments, managed auth and database migrations.

Build personal alarm setup and local sound test. Recruit adult pilot pairs. Create separate development/staging/production credentials and app IDs. Establish a Mac build path if development otherwise occurs on Windows.

Exit gate: five usability participants can understand the two headline behaviors, and the team can explain every sensitive data flow. CI builds both app shells. Account creation/deletion and basic authorization are exercised end to end.

## 4. Stage 2 — complete attention flow (weeks 4–8)

Implement invitation, friendship, directional grants, approved quack library, command API, outbox delivery, expiry, sender receipts, block/revoke and minimal friend chat/reactions.

Build all denial/offline/rate-limit states. Add the app's distinctive sound/animation and polish the primary journey now; do not leave design until launch.

Exit gate: two phones across platforms can pair, grant access, send a sound and report honest status. Unauthorized users cannot send commands. Duplicate/expired requests do not produce late surprise sounds. Revocation behavior is measured and clear.

## 5. Stage 3 — dependable wake routines (weeks 8–12)

Implement recurring schedules, timezone policy, recipient installation acknowledgments, local media cache, native alarm lifecycle, primary device selection and deterministic math/quiz challenges.

Offline alarm/challenge execution must work after prior installation. Add bounded escalation and friend acknowledgment. Test alarm edits, travel, daylight-saving transitions, reboot, permission loss and app update.

Exit gate: a documented physical-device soak matrix, all critical failures understood/fixed, no misleading Ready status, and no network dependency in the stop/escape path. Establish failure handling and support diagnostics.

## 6. Stage 4 — private-group challenge creation (weeks 12–16)

Implement private circles, challenge templates, manual quiz authoring, AI-to-schema generation, creator preview/edit, immutable revisions and recipient limits. Add verified chess positions, memory tasks and safe timer/checklist activities.

Create a realistic evaluation set: inside jokes with supplied facts, multilingual names, malformed prompts, impossible chess, wrong answers, abusive text, extreme physical tasks and attempts to inject code/permissions. Include accessibility alternatives and unavailable-model behavior.

Exit gate: all outputs pass schema validation; deterministic answers are checked; unsafe/unsupported tasks fail safely; generated challenges cannot alter permissions. Measure cost per accepted published challenge, not merely per API call.

## 7. Stage 5 — paid core release preparation (weeks 16–22)

Implement subscription lifecycle, restore purchases, entitlements, quotas, privacy export/deletion, reporting/moderation, support tools and store submission assets. Run an external security review focused on cross-user authority and location-readiness boundaries even if location has not launched.

Beta target: 100–300 adult users across 20–40 groups. Run a minimum four-week retention study where feasible. Inspect negative experiences as carefully as sends/completions.

Exit gate: security and operational checklist below passes; value is visible in retained groups; store-required tests/entitlements completed; truthful screenshots and reviewer instructions. Release the useful core even if optional focus/location rollout remains gated.

## 8. Stage 6 — friend-supervised focus (additional 4–6 weeks)

Ship native iOS Screen Time extensions and the approved Android implementation, with local schedules, temporary access, challenge entry, safe expiry, emergency access and revocation.

Recipient chooses the applications. Friend requests must stay within the accepted goal and limits. Record outcome-only sharing preferences. Do not send raw Screen Time reports to friends by default.

Exit gate: entitlements and store declarations accepted, actual distribution builds behave correctly, failed sync cannot leave indefinite restrictions, and accessibility alternatives work. If one platform needs a different intervention, document it without pretending feature parity means identical enforcement.

## 9. Stage 7 — care and advanced activities (additional 4–8 weeks)

Add explicit check-ins/SOS and opt-in location sessions with fine-grained viewer scope, timestamp/accuracy display, retention and audited access. Use Android 17's session location path when applicable. Introduce guitar feedback and pose detection only after on-device accuracy and privacy tests.

Add reusable shared routines for gym, classes, language practice and music practice. Test whether groups use them beyond wake-up rather than assuming a general habit platform is wanted.

Exit gate: location threat assessment and consent tests pass, stale/missing data is clear, background permissions are justified, sensor failures cannot trap users, and support can handle abuse reports promptly.

Spotify remains an independent partnership track: request written approval, document permitted SDK behavior, rights and account requirements, then prototype. Do not hold the core app launch hostage to it.

## 10. Definition of launch-ready

### Product and quality

- Headline flow works between supported iOS/Android devices with honest status.
- Alarms can be installed, edited, cancelled and recovered with visible evidence.
- Core challenges work offline and have bounded difficulty/duration and escape.
- All permission denial, offline, expired and error screens have usable next actions.
- Small/large screens, light/dark and large text pass visual review.
- Device matrix includes Pixel, Samsung and at least one battery-aggressive OEM used by the target audience.
- No known critical/high unresolved security issue; crash/performance gates pass on the release candidate.

### Security and operations

- Cross-account/relationship/grant tests, upload tests and revocation races pass.
- Secrets and signing materials are protected; dependencies/actions pinned; SBOM generated.
- Restore, rollback, incident response, lost-device and account-deletion drills completed.
- Telemetry is scrubbed and retention is configured, including backups.
- Abuse controls, report queue, support contact and reviewer access are ready.
- Feature switches can suspend remote audio/AI/location without disabling personal alarm escape.

### Distribution

- Correct Apple/Google app ownership, certificates, signing and verified business/contact information.
- Entitlements for main app and all relevant extensions confirmed in distribution signatures.
- Apple privacy labels/manifests and Play Data Safety reflect actual SDK behavior.
- Prominent permission explanations and background-location/accessibility declarations where used.
- In-app account deletion, privacy policy, terms, subscription disclosure and restore/manage links.
- Demo accounts and two-device review instructions, including grant/permission setup and feature limitations.
- Production backend available during review; no hidden feature switches that mislead review.

Apple requires moderation/report/block/contact safeguards for user-generated content, and intended use of public APIs/background modes. These apply even to friend-group content. [App Review Guidelines](https://developer.apple.com/app-store/review/guidelines/)

For applicable new personal Google developer accounts, the currently documented requirement is at least **12 opted-in testers for 14 consecutive days** before applying for production access. Verify the account's actual requirements and allow review time. [Google testing requirements](https://support.google.com/googleplay/android-developer/answer/14151465)

## 11. Business model

**Recommended: useful free social core + paid creation/routine convenience.** Charging both ends of a first quack would damage the invitation loop. Do not monetize alarm intensity, emergency access, basic blocking/revocation, or deleting private data.

| Tier proposal | Included |
|---|---|
| Free | Personal alarms; friend invitations; practical nudge/wake usage within abuse limits; manual quizzes; basic shared routines; messages; complete safety controls; two lifetime introductory AI generations |
| Plus | More active routines, larger private sound/template library, advanced personalization, history and **10 AI generation credits/month** with bounded complexity |
| Circle, later | One payer sponsors a small group, shared templates and pooled capped credits; clear seat limits and ownership transfer |

Manual and previously generated challenges should remain usable when credits run out. Avoid “unlimited AI” until costs and abuse are measured. Refund credits for system failures; do not reward attempts to generate harmful content with repeated free processing.

Suggested price experiment: **US $4.99 monthly / $34.99 yearly** for Plus. Test against a simpler $2.99 monthly entry point and higher-value bundles; do not launch many confusing plans. For India, test localized willingness to pay around **₹99–199 monthly / ₹799–1,499 yearly**, not an automatic USD conversion. These are hypotheses, not researched price acceptance.

A later circle plan might test $7.99 monthly or $59.99 yearly for up to five people with a pooled AI budget. Do not include it in the base forecast until seat utilization and payer demand are known. Avoid lifetime unlimited cloud/AI promises. Ads, if ever introduced, must not interrupt waking/focus and should not depend on selling sensitive habit/location data.

Show pricing after an experienced value moment or when opening a premium creation feature, never before the first successful paired interaction. Trials should display duration, renewal date and price clearly. Test the impact on retained paying groups, not only trial starts.

## 12. Vendor cost facts checked

| Item | Observed published pricing / fact | Source |
|---|---|---|
| Supabase Pro | From **$25/month**, includes 100K MAU; overage shown as **$0.00325/MAU**; compute/storage/egress limits still matter | [Pricing](https://supabase.com/pricing) |
| R2 standard storage | **$0.015/GB-month**, Class A **$4.50/million**, Class B **$0.36/million**; internet egress listed free | [Pricing](https://developers.cloudflare.com/r2/pricing/) |
| RevenueCat | Free through **$2,500 monthly tracked revenue**, then **1% of tracked revenue** | [Pricing](https://www.revenuecat.com/pricing) |
| Claude Opus 5.5 standard tokens | **$4/million input, $20/million output** at the accessed official page; separate caching/fast-mode terms | [Anthropic](https://www.anthropic.com/claude/opus) |
| FCM | Listed among no-cost Firebase products; workers/network/analytics still cost money | [Firebase pricing](https://firebase.google.com/pricing) |
| Apple commission | Eligible Small Business Program: **15%**; do not assume all developers/regions qualify | [Program](https://developer.apple.com/app-store/small-business-program/) |
| Google fees | Depend on region/program/transaction; current page includes changed regional terms and subscription treatment | [Service fees](https://support.google.com/googleplay/android-developer/answer/112622) |
| Google registration | **US $25 one-time**, before applicable taxes/other charges | [Play Console setup](https://support.google.com/googleplay/android-developer/answer/6112435) |

Do not call a $25 database plan the total operating budget. Extra production/staging compute, backups, email delivery, worker hosting, monitoring, media processing, moderation, support and engineering all matter. Store commissions also change by jurisdiction; the model below uses an explicit 15% assumption, not a universal current rate.

## 13. AI cost per user

Conservative model using Opus 5.5 even though a cheaper evaluated runtime model may suffice:

```text
Generation = 3,000 input tokens + 1,000 output tokens
Raw cost = (3,000 × $4 + 1,000 × $20) / 1,000,000 = $0.032
Planning overhead for retries/validation = 20%
Budget per generation = $0.0384
10 generations per paying user/month = $0.384
```

The 20% overhead is an allowance, not a measurement. Large private-group context, image/video verification, long outputs or multiple critic models can exceed it. Count all attempts and provider charges. Generating once and replaying a local challenge many times does not incur another generation charge.

Separate development spend from runtime spend. Example development usage of 20M input + 5M output standard Opus tokens is **$180** before caches, retries, tools and other billing. A Claude subscription and API billing are different products; a fixed subscription is not a promise of unlimited development capacity.

## 14. Per-user operating model

Assumptions: 5% of MAU pay; half pay $4.99 monthly, half $34.99 annually. Recognized monthly gross revenue per payer is **$3.9529**. This is not cash collected in a month. Each paid user consumes 10 generations/month. Free users average **0.2 generations/month** across the cohort from the two lifetime introductory credits; this depends on new-user growth. No video feed, continuous tracking or SMS login.

Base infrastructure includes database/API/workers/storage/email/observability allowances. Support/moderation is a separate planning allowance, not included engineer payroll. RevenueCat threshold is approximated using recognized monthly revenue here; actual tracked revenue and annual billing cash flows can cross its threshold differently, so reconcile against real invoices.

| Monthly metric | 1,000 MAU | 10,000 MAU | 100,000 MAU |
|---|---:|---:|---:|
| Paying users at 5% | 50 | 500 | 5,000 |
| Gross recognized revenue | $197.65 | $1,976.46 | $19,764.58 |
| Assumed store fee, 15% | $29.65 | $296.47 | $2,964.69 |
| Modeled RevenueCat fee | $0.00 | $0.00 | $197.65 |
| AI generations | 690 | 6,900 | 69,000 |
| AI cost | $26.50 | $264.96 | $2,649.60 |
| Base infrastructure allowance | $150 | $400 | $2,500 |
| Support/moderation allowance | $100 | $500 | $5,000 |
| Total service operating cost, before store/RevenueCat | $276.50 | $1,164.96 | $10,149.60 |
| Service cost per MAU | **$0.2765** | **$0.1165** | **$0.1015** |
| Contribution after modeled fees/service cost | **−$108.50** | **$515.03** | **$6,452.65** |

This contribution excludes salaries, acquisition, tax, refunds, chargebacks, legal, insurance, equipment and general overhead. **100K MAU at these assumptions is not enough to comfortably fund a full team.** Paid conversion, pricing, retention and cost controls are core business work.

Marginal AI cost is about $0.00768/free MAU and $0.384/paid user under these assumptions. Infrastructure/support allocations explain the rest; they are not vendor quotes. Media-heavy guitar/video proof or persistent GPS must get separate budgets before release.

Sensitivity:

- Doubling paid AI usage from 10 to 20 increases the 100K-MAU monthly AI bill by **$1,920**.
- Increasing free usage from 0.2 to 1 generation/month adds **$2,918.40** at 100K MAU.
- A 30% rather than 15% store assumption reduces 100K-MAU contribution by about **$2,964.69**.
- If every payer buys the annual plan, recognized ARPPU becomes **$2.9158**, materially lowering margin.
- A cheaper model can help, but only after the same safety/correctness eval passes. Savings are not worth broken puzzles or unsafe tasks.

Use an editable model with variables for MAU, paid share, annual mix, generations, tokens, retries, storage, uploads, delivery events, retention, support tickets and regional fees. Reforecast weekly during beta.

## 15. Development budget

Estimate effort before vendor quotes. **Illustrative** core build: 12–18 engineering person-months plus 2–4 design/QA person-months and external review. At an assumed blended engineering rate of $4K–10K/person-month, engineering alone is approximately **$48K–180K**. The expanded platform can require 18–27 engineering person-months: **$72K–270K** at the same assumed rate.

Add actual quotes for design/QA, security review, legal/privacy, physical test phones, a Mac/build runners, audio rights, support and developer memberships. A founder building personally may spend much less cash but still incurs time and specialist-review needs. AI token spend is unlikely to be the largest cost in a serious two-platform launch.

## 16. Subscription implementation details

Use store-approved in-app billing for digital features unless a specifically applicable regional program is intentionally adopted. Verify entitlement server-side, deduplicate webhook events, reconcile periodically, and handle refund, cancellation, billing retry, grace period, renewal, upgrade/downgrade and account transfer conflicts.

Keep free basics functional if billing is unavailable. Subscription expiry should not cancel tomorrow's already accepted personal alarm or trap a user in focus. Downgrade future premium creation gracefully; explain retained content and limits. Restore purchases must work after reinstall and across supported devices tied to the correct app account.

A receipt is not interchangeable with an app identity. Prevent the same purchase being attached to many unrelated accounts without a supported transfer policy. Circle subscription changes must not silently expose other members' private content to the payer.

## 17. Growth loop and metrics

Acquisition loop: roommate sends an invite → recipient permits a quack → group experiences a useful wake/attention moment → one member creates a shared routine → friends personalize a challenge → members invite their own close circles.

Measure this as **activated pairs and retained circles**, not just downloads. North-star proposal: weekly mutually accepted routines or attention requests that receive a voluntary acknowledgment and do not generate an abuse complaint. Keep wake and focus outcomes separately visible.

Pilot targets, to validate rather than treat as industry benchmarks:

- 50%+ invite acceptance among personally invited pilot friends.
- 60%+ of accepted pairs complete a first useful interaction in 24 hours.
- 30%+ of activated pairs still use a core function in week four.
- 20%+ of active circles adopt a repeated routine beyond novelty sounds.
- 3–7% paid conversion among retained adults in price tests.
- Low and declining nuisance reports; no unresolved severe safety/permission incident.

Use cohort retention, routine success, receiver satisfaction and permission revocations as guardrails. A high send count combined with receiver blocking is failure. Ask users whether the app helped and whether they would invite another friend.

Start with 3–5 campus communities and specific recurring groups, then expand. Campus ambassadors demonstrate consensual paired use, not pranks. Shareable challenge templates can omit private names/answers. Reward invitations with capped creation credits rather than giving unlimited ability to interrupt others.

Do not forecast virality without measuring it. Illustrative invitation coefficient: 2 invites × 40% acceptance × 60% activation = **0.48 activated users per inviter**, insufficient for self-sustaining growth. Improve actual usefulness before buying traffic. Track acquisition cost per activated retained pair and payback from contribution, not gross subscription revenue.

## 18. Privacy, legal and launch-region decisions

Default pilot assumption: adults 18+, one primary launch region, invite-only groups. Select the region before legal work; this report does not assume the user's location determines the business jurisdiction.

Prepare a data map and privacy impact assessment covering social authority, location, audio, sensor evidence, AI providers and support access. Obtain advice on GDPR/UK GDPR where applicable, Indian DPDP phased commencement if serving India, US state privacy rules and child privacy obligations if expanding to minors. India's 2025 commencement notification phases substantive provisions over time; do not label everything already effective or irrelevant without checking launch-date obligations. [Indian commencement gazette](https://www.meity.gov.in/static/uploads/2025/11/c56ceae6c383460ca69577428d36828b.pdf)

Review music/upload rights, takedown handling, recurring-billing disclosures, cancellation/refund rules, consumer claims and emergency-feature wording. Do not sell the app as medical treatment for sleep disorders, addiction treatment, guaranteed wake-up or guaranteed emergency response.

The operational commitment is straightforward: user-owned consent, documented limits, useful fallbacks, prompt abuse response and evidence-backed product claims. These support growth because users can safely invite people they care about.
