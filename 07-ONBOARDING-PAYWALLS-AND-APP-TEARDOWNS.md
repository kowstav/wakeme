# Wake Me: onboarding, subscription offers and public app teardowns

Research cutoff: **18 October 2026**. This supplements documents 3 and 4. It is a research and implementation brief, not a report of Wake Me conversion results.

## 1. What was missing, and the recommendation

The earlier pack proposed onboarding and pricing but did not provide a detailed visual competitive audit. This supplement adds publicly accessible screen references, observed layouts, source dates, subscription mechanics, original copy and an experiment plan.

**Recommendation:** build two onboarding routes. A friend invitation should quickly lead to a working, consented quack. Someone arriving to improve a habit can receive a longer, genuinely personalized setup that produces a useful routine. Offer Plus after a relevant demonstration. Keep the basic social connection free.

Test a **seven-day free trial on annual Plus** against a supported **paid introductory offer**, after establishing activation. Do not assume a longer questionnaire or a cheaper first payment automatically produces more profitable customers.

“Personal” should mean the product changes because of an answer. It should not mean repeatedly inserting someone's name or collecting intimate information unnecessarily.

## 2. Evidence: does longer onboarding increase purchases?

There is no defensible universal rule that more screens cause more purchases. The effect depends on intent, question relevance, product complexity, acquisition channel and what happens afterward.

### What current evidence actually supports

- RevenueCat's 2026 aggregate report shows median trial-to-paid conversion of **37.4% for 5–9-day trials**, versus **25.5% for trials of four days or less** and **42.5% for 17–32 days**. These are observational cohorts, not randomized evidence that extending Wake Me's trial will cause those results. Trial duration and onboarding length are different variables. [RevenueCat report](https://www.revenuecat.com/state-of-subscription-apps?from=google)
- A Reddit developer describes five onboarding iterations: a lengthy flow improved conversion among people reaching the paywall while losing many people before that point. Treat this as a useful warning about denominators, not audited research. [Developer account](https://www.reddit.com/r/iOSAppsMarketing/comments/1sldhkc/we_iterated_onboarding_5_times_heres_what/)
- A recent alarm-app developer reports over 70% completion of an 18-screen quiz but poor paywall conversion at its worst. Long onboarding can retain attention without establishing willingness to pay. Sample sizes and controlled attribution remain uncertain. [Rizen discussion](https://www.reddit.com/r/appledevelopers/comments/1wt7ylq/my_apps_onboarding_kept_70_of_users_the_paywall/)
- Another founder reports increasing onboarding from six to 26 steps, with a skip option. The post is a description and discussion, not reliable proof of uplift. [Reddit discussion](https://www.reddit.com/r/SaaS/comments/1qd2vkv/i_took_my_apps_onboarding_from_6_steps_to_26/)
- A Medium analysis advocates continuing onboarding through repeated useful experiences, with progressive disclosure. It is design commentary, not a controlled experiment; do not import its secondary numerical claims into forecasts. [Maulana Saputra, December 2024](https://medium.com/design-den/redesigning-user-onboarding-insights-from-figma-duolingo-and-more-31493632deed)

### The denominator trap

Illustrative arithmetic, not industry benchmarks:

| Route | Starts | Reach offer | Buy among offer viewers | Purchases per start |
|---|---:|---:|---:|---:|
| Short | 1,000 | 800 | 5% | 4.0% |
| Long | 1,000 | 400 | 8% | 3.2% |

The longer flow “wins” paywall conversion but produces fewer buyers. Even purchases per start are insufficient if refunds rise or the friend's invite fails.

Wake Me must track **retained, activated pairs and net revenue per onboarding start**, alongside individual conversion. An invited person is helping create value for two users; blocking that person behind a questionnaire or paywall can damage the whole circle.

## 3. Public screenshot sources: what is actually accessible

No Mobbin subscription is needed for the references below. Access can change. Some libraries expose previews and gate the rest; this research did not create accounts, bypass gates or retrieve hidden assets.

| Reference | Date/version visible | Access and verification | Best use |
|---|---|---|---|
| [Alarmy on PaywallPro](https://www.paywallpro.app/apps/alarmy-app) | v26.11.0; page labels release January 8, 2026 | Visually inspected public screenshot grid and pricing panel | Trial hierarchy, plan cards, promotion, alarm setup |
| [Opal on Botsi](https://www.botsi.com/resources/flows/apps/opal) | Recording June 2026 | Visually inspected opening screenshots; further images have an email gate. Later flow details below are public text descriptions, not a visual inspection of gated frames | Emotional opening, personalization, offer sequence |
| [Opal historical flow](https://www.botsi.com/resources/flows/apps/opal-2024-04) | April 2024 | Historical text reference | Compare evolution rather than call it current |
| [one sec on Adapty](https://adapty.io/paywall-library/one-sec-screen-time-focus/) | Captures July 10, 2024 and October 31, 2022 | Visually inspected public previews | Functional free/pro comparison |
| [Duolingo study](https://uidiscovery.com/studies/duolingo-onboarding) | January 2026 recording; reviewed October 7, 2026 | Read detailed annotations; screenshot frames did not render in this browser session | Answer-to-recommendation flow and visible free route |
| [Jomo on Appllama](https://appllama.io/apps/1609960918/jomo-screen-time-blocker) | Page advertises v2.0.7 | Welcome preview; deeper screenshots locked. Not treated as a full visual audit | Discovery reference only |
| [one sec on ScreensDesign](https://screensdesign.com/apps/one-sec-screen-time-focus/) | Capture date not established | Public flow chapter descriptions; deeper access may require Pro | Locate intervention and scheduling sequences |
| [Opal on Uiland](https://uiland.design/screens/opal/screens/aae10c1c-f8c7-4eeb-9474-9035e89d3962/flows/onboarding) | Capture date not established | Public indexed flow reference; not independently audited in full | Secondary reference library |

**Interpretation rule:** capture date, app release date, article date and search crawl date are different. A page crawled yesterday can display a 2022 screen. Screen counts can include intermediate recording frames and alternative routes; “113 screens” does not mean 113 required questions.

These libraries also show estimated revenue. Those estimates are not used here as evidence of business success or of a particular screen's effectiveness.

## 4. Competitive screen and business-model findings

### Alarmy: sell the morning outcome, then distinguish plans

**Visually observed:** dark navy/black surfaces, a vivid pink primary button, vertically stacked plan choices, selected annual option, monthly and lifetime alternatives, and a separate purple promotional card. Introductory screens show social-proof symbols and a before/after alarm comparison. Setup previews include a time-selection step and wallpaper choice.

The captured paywall shows annual **$69.99**, monthly **$8.99**, lifetime **$167.99**, and a separate annual promotion at **$34.99**. These are historical captured offers, not guaranteed current prices or universally available products. The annual trial is visually prominent; the separate promotion is presented without a trial. [Screens and captured pricing](https://www.paywallpro.app/apps/alarmy-app)

Alarmy's official help describes a one-time seven-day premium trial. [Official trial help](https://alarmy-ios.zendesk.com/hc/en-us/articles/360038208414--Trial-Premium-features-look-good-Can-I-give-them-a-try-Free-trial)

**Wake Me application, our inference:** show the concrete morning routine before offering upgrades. Use one clear primary action and an unmistakable selected plan. Avoid importing Alarmy's small renewal text, ranking claims or lifetime economics. A lifetime product is especially risky when AI and media create ongoing costs.

### Opal: personalization and a narrated trial

**Visually observed opening:** black backgrounds, luminous gemstone imagery, sparse centered text, a name input and early account sheet. The interface builds an emotional focus narrative.

The public teardown describes later questions, permission education, a tailored summary, trial-reminder choice and offer timeline. Its June 2026 capture lists **$99.99/year**, either after seven free days or after **$4.99 for 30 days**. These terms are source-reported; later gated screenshots were not visually audited. [Opal capture](https://www.botsi.com/resources/flows/apps/opal)

**Wake Me application, our inference:** reflect a user's selected goal and actual routine back to them. Explain when an offer charges. Do not invent “years of life saved,” guaranteed results, or artificial computation delays. For invited users, the friend and shared quack are a stronger opening than an introspective focus narrative.

### one sec: a useful free unit and a clear expansion upgrade

**Visually observed:** light native-looking cards, lavender accents, explanatory setup cards and a free/pro feature matrix with checks and crosses. The available screenshots are historical, so they are useful structural references rather than current price evidence. [Public captures](https://adapty.io/paywall-library/one-sec-screen-time-focus/)

Official documentation says the free product targets one app, with broader capabilities in Pro; it also distinguishes supported features across platforms. [Official comparison, edited June 2026](https://tutorials.one-sec.app/en/articles/3036418)

**Wake Me application, our inference:** free users should complete a real social interaction. Sell richer creation and recurring use after that experience. Show platform-specific capabilities before purchase, especially focus supervision. A paid iPhone feature should not imply identical Android behavior.

### Jomo: short annual trial and transparent support

Official help describes a **three-day trial on the annual plan during onboarding**, followed by **$29.99**, with regional variation. It includes subscription management and reminder guidance. This is a business-model reference; the locked screenshot collection was not audited. [Official subscription help](https://help.jomo.so/en/article/how-to-manage-or-cancel-jomo-plus-subscription-rt2qj5/)

**Wake Me application, our inference:** provide an easy subscription-status page and a working manage-subscription action. A three-day trial may be too short for a pair that needs to coordinate weekdays; test against seven days rather than assume urgency helps.

### Duolingo: make each answer affect the starting experience

The January 2026 recorded-path annotations describe a stated question count, language and proficiency choices, multiple motivations, daily effort, a free route and a recommended starting level. This was one path, not an experiment. [Recent study](https://uidiscovery.com/studies/duolingo-onboarding)

A 2020 Medium teardown describes an older lesson-before-profile strategy. It is useful historical contrast, not evidence of today's exact flow. [Historical Medium case study](https://medium.com/usabilitygeek/ux-case-study-duolingo-ade986ec7450)

**Wake Me application, our inference:** the questionnaire must produce an editable routine, challenge or permission recommendation. A user choosing “guitar practice” should see a guitar routine, not the same generic morning dashboard as everyone else.

## 5. Proposed Wake Me onboarding: two routes

All copy below is original proposed copy. Screen counts and completion times are design hypotheses. Keep back navigation, persist progress locally and make optional questions skippable.

### Route A: invited by a friend

Target first successful paired demonstration within roughly three minutes when both people are available. Do not count time waiting for the friend as UI completion time.

| Step | Heading and body | Action | Behavior |
|---|---|---|---|
| 1 | **Maya wants to quack you.** “Connect so you can get each other's attention—with rules you choose.” | “See invitation” | Use a real verified invitation. Show inviter identity before accepting |
| 2 | **Hear what a quack sounds like.** “This preview plays only on your phone.” | “Play preview” | Explicit tap; bounded volume; no remote command |
| 3 | **Save your connection.** “Sign in to accept Maya's invitation and keep your settings.” | Native sign-in choices | Preserve invitation through authentication |
| 4 | **What can Maya do?** “Start with a short quack. Add wake-ups or challenges whenever you want.” | “Allow short quacks” and “Not now” | Directional consent; other capabilities off |
| 5 | **When are quacks welcome?** “Choose your hours and how often Maya can send one.” | “Save my rules” | Recipient chooses schedule, cooldown and expiry |
| 6 | **Make this phone ready.** Explain each relevant system permission immediately before its native prompt | Contextual permission action | Show actual capability status; no focus/location prompts here |
| 7 | **You're connected. Try a quack.** “Maya can send a short alert during your allowed hours.” | “Send a test request” | Distinguish requested, delivered and acknowledged |

Do not send an unsolicited alert simply because onboarding finished. The preview must not be confused with successful remote delivery.

If the friend is unavailable, offer a local practice challenge and a pending setup card. Do not manufacture a friend response. If the invitation is invalid, explain and offer a fresh link request or solo setup.

**Paywall placement:** after a successful demonstration or when the user explicitly opens a premium creator feature. Accepting a friend's basic invitation should not require a trial.

### Route B: self-directed habit or wake-up setup

Suggested 10–12 short steps, with optional detail after the first result:

1. **“What would you like a hand with?”** Wake up / Get my attention / Focus / Practice something / Something else. Allow multiple goals but choose one to configure first.
2. **“What would a good week look like?”** A concrete selection such as “Make my 9 am class.” Optional free text remains private by default.
3. **“When should we start?”** Time, days and timezone.
4. **“What usually gets in the way?”** Snoozing / Losing track of time / Forgetting / Something else / Skip. Use the answer to change a suggestion, not diagnose the user.
5. **“Who could help?”** Invite a person or start solo. No address-book upload requirement.
6. **“How should your friend help?”** A short alert / Scheduled wake-up / A check-in / A challenge. Explain each capability.
7. **“Make it sound like your group.”** Pick a quack, permitted sound or recorded phrase; preview explicitly.
8. **“What challenge would feel like you?”** Inside-joke quiz / Maths / Chess / Practice check-in / Make my own.
9. **“Try your first challenge.”** A real sample with an accessible alternative.
10. **“Your plan, your rules.”** Show exact schedule, friend permissions, quiet hours and challenge. Every field editable.
11. **“Make this phone ready.”** Ask only for permissions needed for that chosen plan; show fallback if declined.
12. **“Your first routine is ready.”** Save a genuinely usable free routine; optionally present the relevant Plus offer.

Optional AI branch: **“Describe a challenge only your group would get.”** Example placeholder: “Ask which café we call headquarters. Use the three choices I provide.” Require an answer key supplied by the user; AI must not invent personal facts. Show a preview and difficulty controls before saving.

For guitar or physical activity, state whether completion is a self-check, friend confirmation or supported sensor check. Do not suggest that a checkbox proves an activity happened. Avoid unsafe requirements such as rapidly drinking five glasses of water.

### Questions that earn their place

| Input | Concrete product consequence |
|---|---|
| Wake time and days | Local schedule |
| Goal | Appropriate routine and home card |
| Difficulty preference | Challenge parameters |
| Accessibility preference | Equivalent challenge alternatives |
| Friend selection | Invitation and scoped grant |
| Preferred sound | Preview and permitted sound selection |
| Quiet hours | Enforced delivery policy |

Remove a question if the team cannot identify a useful consequence. Do not collect mental-health history, relationship conflict or location to make a paywall feel personal.

## 6. Subscription and offer choices

Keep the earlier proposed US base prices as hypotheses: **$4.99/month or $34.99/year**. Regional prices require local research. Neither price is validated willingness to pay.

| Option | Why test it | Main risk | Recommendation |
|---|---|---|---|
| Free core, optional Plus | Preserves friend network growth | Free operating costs | Default foundation |
| Seven-day free trial, then annual | Enough time to try several mornings | Forgotten renewal; low-intent trials | First trial candidate |
| Three-day free trial | Faster measurement | Misses coordinated usage/weekends | Later comparison |
| $0.99 first week | Small paid commitment | Store-specific setup; renewal confusion | Conditional experiment, not universal default |
| $0.99 first month, then annual or monthly | Longer evaluation with paid commitment | Revenue delay and cheap-offer cohorts | Practical cross-platform candidate |
| Discounted first year | Upfront cash and long evaluation | Anchors low willingness to pay | Later acquisition campaign |
| Monthly without trial | Low total commitment | Lower upfront cash | Keep visible |
| Circle membership | One buyer supports several participants | Seat ownership and cancellation complexity | After pair retention is demonstrated |
| Lifetime | Attractive one-time payment | Unfunded AI/server obligations | Avoid for uncapped cloud features |
| Non-renewing access pass | Clear bounded commitment | Separate entitlement/product work | Later, if users ask for it |

### Important platform difference: the $1 week

Apple's published duration table permits a one-week discounted **pay-as-you-go offer on a weekly subscription**. It does **not** list a one-week paid upfront introductory period for an annual or monthly subscription. Its pay-upfront durations begin at one month. A seven-day **free** trial on annual is supported. Each person can redeem only one introductory offer per subscription group. [Apple offer-duration table](https://developer.apple.com/help/app-store-connect/manage-subscriptions/set-up-introductory-offers-for-auto-renewable-subscriptions/)

Therefore, do not specify an iOS annual product as “$0.99 for seven days, then $34.99/year” using ordinary introductory pricing. Feasible directions include a seven-day free annual trial, a one-month paid introductory offer, or a weekly product that clearly renews weekly. Moving from weekly to annual requires the appropriate user-selected subscription change; it is not an invisible billing switch.

Google Play supports single-payment introductory phases lasting a specified number of days, weeks or months before base-plan renewal. A paid week can therefore be evaluated against a monthly or annual base plan, subject to regional pricing, eligibility and console validation. Developer-determined eligibility is the developer's responsibility. [Google offer phases](https://support.google.com/googleplay/android-developer/answer/12154973)

Validate each proposed configuration in the actual store consoles and sandbox purchase sheets before approving design copy. Do not represent cross-platform pricing parity that does not exist.

### Is $1 a massive discount?

Not necessarily. Against $4.99/month, the rough weekly equivalent is $1.15; $0.99/week is only a modest reduction. Against $34.99/year, the weekly equivalent is about $0.67. Different commitment lengths have different value, but “massive discount” requires a genuine, clearly identified comparison.

At the proposed base prices, annual savings against 12 monthly payments are approximately **42%**: 1 - 34.99 / (4.99 × 12). Display the annual charge prominently; a monthly equivalent is secondary.

Do not invent an inflated crossed-out price or a countdown that resets. A paid intro is a paid offer, not a free trial.

## 7. Original paywall designs and copy

### A. Routine-led annual trial

Visual order: close/continue-free action → small routine preview → outcome headline → three benefits → plan selector → billing timeline → primary action → renewal terms → restore/manage/legal links.

**Headline:** “Make your routine a team effort.”

**Personalized card:** “Your plan: weekdays at 7:30 · Maya can send a wake-up · café quiz.”

Only show that card if the user actually configured those details.

**Benefits:** “Build challenges from your group's ideas.” “Save reusable routines.” “Create with 10 AI credits each month.” Final benefits must match the shipping entitlement.

**Annual choice:** “$34.99/year after 7 days free.”
**Monthly choice:** “$4.99/month. Starts today.”
**Annual CTA:** “Start 7-day free trial.”
**Terms:** “Then $34.99/year, automatically renewed until canceled. Manage or cancel in your store subscription settings.”
**Alternative:** “Continue free.”

All currency, billing period, trial eligibility and prices come from store product data. These dollar amounts are design examples, not hardcoded production strings.

### B. Supported paid-intro variant

**Headline:** “Try your routine for a month.”
**Price:** “$0.99 for your first month, then $34.99/year.”
**CTA:** “Start for $0.99.”
**Supporting text:** “Automatic renewal. Manage or cancel in your store subscription settings.”

Use only where that exact product is configured and the person is eligible. Do not display “No payment today.” For the Google-only paid-week variant, replace the duration with the actual seven-day phase and show the actual renewal term equally clearly.

### C. Contextual AI-creator upgrade

Show a real preview before monetizing if free credits remain.

**Headline:** “Turn your group's ideas into challenges.”
**Body:** “Plus includes 10 AI creations each month. Reuse saved challenges without spending another credit.”
**Secondary path:** “Build a quiz myself.”

A retry caused by a service failure should not spend another credit. Explain credit refresh and whether unused credits carry over. Do not call finite quotas unlimited.

### D. Trial and subscription status

The account page should show the current product, entitlement status, next charge/expiry, localized amount and management link.

If offering reminders, let the user opt in and show permission status. Do not guarantee a push notification will arrive. A reminder is assistance; it must not be required to find or cancel a subscription.

For “canceled but active,” show the remaining access period returned by the billing system. Do not revoke early or promise access until a date the store does not report. Google documents distinct trial, cancellation, grace-period and account-hold behavior. [Billing lifecycle](https://developer.android.com/google/play/billing/subscriptions)

## 8. Visual direction for Wake Me

These are original design recommendations inferred from the research, not claims of measured superiority.

- Give playful alerts a warm, expressive visual identity, with one distinctive quack illustration and restrained motion. Use a quieter visual mode for permissions, billing and location.
- Build around a real friend card and one primary next action. Avoid a feed of unrelated tools on first launch.
- Use generous type, short questions, a bottom action reachable by thumb and explicit selected states. Honor Dynamic Type and Android font scaling.
- Keep permissions readable as capabilities: “Short alerts: allowed until 10 pm”; “Wake alarms: off”; “Location: off.” Never reduce this to one “trust friend” switch.
- Show delivery states in words as well as colors. “Sent” is different from “Played”; “Played” is different from “I'm awake.”
- Use brief animation to explain an action, not to stall the user. Respect reduced motion and never rely on audio alone.
- Support light and dark themes. Do not copy Opal's gemstone, Alarmy's brand, Duolingo's mascot or competitors' exact compositions.
- Make the paywall's renewal terms legible at normal text size. A beautiful screen still fails if people misunderstand the bill.

Required design states: first launch, returning unfinished setup, expired invite, unavailable friend, rejected permission, offline preview, sound test failure, unsupported capability, generation failure, purchase pending, canceled purchase, ineligible offer, restore success/failure, trial active, subscription expired and retained free functionality.

## 9. GitHub and Reddit: useful code versus claimed reverse engineering

A clone is not the proprietary app's source code. A screenshot reconstruction can teach interaction structure without revealing the production backend, experiments, security model or native reliability engineering.

| Resource | What was verified | How to use it | Limits |
|---|---|---|---|
| [Mindful](https://github.com/akaMrNagar/Mindful) | Public Flutter/Android project, GPL-2.0, focus/screen-time features | Study capability boundaries, local settings and Android implementation organization | Not Opal's code, not iOS proof, no line-by-line audit performed |
| [Mindful Reddit launch](https://www.reddit.com/r/androidapps/comments/1fn59zs) | Public developer/user discussion linked to repository | Mine device-specific friction and permission expectations | Marketing claims and comments need reproduction |
| [Loop Habit Tracker](https://github.com/iSoron/uhabits) | Public habit-tracking project | Study recurring habit model and offline-first product behavior | Not a social-alarm or subscription implementation; review its license before reuse |
| [RevenueCat iOS SDK](https://github.com/RevenueCat/purchases-ios) and [examples](https://github.com/RevenueCat/purchases-ios/tree/main/Examples) | Official public SDK and example directory | Prefer vendor examples for purchase, restore and entitlement integration | SDK integration does not replace backend authorization |
| [rihaans/Duolingo](https://github.com/rihaans/duolingo) | Educational React/TypeScript/Node/MongoDB reconstruction; README lists tests as future work | Explore how quiz screens and progress can be organized | Not production Duolingo; “educational” wording is not a verified commercial-use license |
| [bdnetworks/duolingo-clone](https://github.com/bdnetworks/duolingo-clone) | Public repository with MIT license label and minimal traction | Optional learning reference only | No security or maintainability endorsement |
| [Public Duolingo UX notes](https://github.com/sanklp-dev/cram/blob/main/duolingo-ux-research.md) | Indexed research document | Find additional UX references | Secondary notes, not proprietary internals |

This search did **not** establish a trustworthy, complete reverse-engineered implementation of the latest Alarmy or Opal with their backend and onboarding experiments. Do not claim that it did.

Before adopting any repository: inspect the actual license and assets, pin a commit, read dependencies and CI, identify abandoned APIs, and write an independent architecture decision. GPL code should not be copied into a closed-source app without resolving its obligations. A source code license does not automatically license trademarks, screenshots or bundled media.

For Claude, prioritize **official billing samples + original native implementation + public UX references**. Do not run unknown teardown scripts, install intercepted certificates or incorporate leaked proprietary code merely to imitate a top app.

## 10. Experiment plan and unit economics

### Stage 1: qualitative prototype tests

Recruit invited recipients and self-directed habit users separately. Proposed starting sample: 6–8 people in each route, used to discover confusion rather than estimate conversion.

Ask participants to:
- Explain what their friend is allowed to do.
- Complete a local demonstration and accept/reject a grant.
- Find the free path.
- Explain exactly when and how much an offer charges.
- Change an answer, recover from denial and find subscription management.

Revise any screen where users misunderstand permissions or billing, regardless of visual preference.

### Stage 2: measure the baseline

Instrument the current route before experiments. Events should include route assignment, step viewed/completed/skipped, invite accepted, grant approved, permission result, local preview, paired acknowledgement, offer viewed, purchase started, purchase verified, renewal, refund and expiry.

Do not send quiz answers, friend messages, precise location or sensitive free text to analytics. Use pseudonymous identifiers, purpose-limited retention and a documented consent approach.

Report:
1. First-value completion per onboarding start.
2. Activated pair rate and seven-/30-day pair retention.
3. Offer reach, trial starts and paid conversions with explicit denominators.
4. Net revenue and contribution per start at 30/60/90 days.
5. Refunds, early cancellations, abuse reports and permission revocations.

### Stage 3: test personalization first

Compare short goal setup with an expanded, adaptive goal setup at the **same price and offer**. Keep invitation onboarding separate. Randomize consistently; circle-level assignment may be needed where friends influence one another. Account for clustering in analysis.

Choose sample size using baseline rates, desired minimum effect and power. Do not declare a winner from a handful of buyers or stop the first time a dashboard turns green.

### Stage 4: test the offer

Compare seven-day free trial against a supported paid-month intro, keeping onboarding fixed. Analyze separately by store, acquisition source and region. Only then test a Google paid-week variant if useful. Do not combine unrelated store cohorts into one conversion claim.

Wait until cohorts have reached comparable renewal opportunities. The paid-month arm cannot fairly be judged against a seven-day trial on day eight.

### Stage 5: test circle pricing

Only after users retain as pairs, test one payer sponsoring a bounded number of members. Receiving ordinary alerts stays free. Specify ownership, seat removal, personal versus circle benefits, overlapping subscriptions and cancellation behavior before exposing an offer.

### Incremental economics

Use the existing cost model for total operations, but add an offer-cohort worksheet:

**Contribution per onboarding start = net collected receipts − refunds − variable service costs − allocated acquisition costs**, divided by starts.

Illustration: a $0.99 intro yields approximately $0.84 after an assumed 15% store fee, before taxes/refunds. Ten AI generations at the existing model's $0.0384 each cost $0.384, leaving about $0.46 before every other cost. This does not establish profitability and fee assumptions differ by eligibility and store.

Track cash collection separately from recognized annual revenue. Annual prepayment is not evidence of annual retention. Measure customer acquisition at the pair/circle level as well as the individual level.

## 11. Implementation brief for Claude

Read this with the existing architecture and security documents. Do not replace native alarm feasibility work with onboarding work.

1. Create a versioned onboarding state machine with explicit entry routes, resumable progress and deep-link recovery. Keep permission decisions separate from marketing preferences.
2. Define each question with its purpose, allowed values, skip behavior and downstream setting. Keep a traceability table proving every answer is used.
3. Build a reusable native screen system: heading, supporting text, selection list, preview, progress, back/skip and bottom action. Use platform accessibility semantics.
4. Implement the free paired demonstration before monetization. Treat offline, pending delivery and unavailable friend as normal states.
5. Build a typed offer adapter from store products: localized price, period, introductory mode, eligibility and renewal terms. Reject configurations missing required fields.
6. Configure products in the stores before final copy. Do not invent an annual iOS paid-week offer. Never silently substitute full-price checkout when an introductory offer disappears.
7. Use verified purchase state for entitlements and quota enforcement. Make webhook handling idempotent and reconcile missed events. Keep billing provider outages from disabling free alarms.
8. Keep onboarding and paywall configuration data-only, validated and versioned. A remote experiment must not grant new friend capabilities or execute downloaded code.
9. Add privacy-conscious funnel events and a stable experiment assignment. Record exposure, not just assignment.
10. Produce screenshots of every route and offer state on both platforms, including large fonts and light/dark themes. Explain which competitor pattern informed each original design.
11. Verify purchase cancellation, pending payment, restore, ineligible intro, expiry, refunds, offline launch and account switching in the appropriate sandbox.
12. Ship behind a controlled rollout. Review activation, reliability, complaints and billing comprehension before optimizing purchase rate.

### Acceptance gates

- An invited recipient can complete a useful free connection without subscribing.
- No remote action is enabled by answering a personalization question.
- A declined permission leads to an honest fallback, not a fake success state.
- Every displayed price and duration matches the selected store product.
- Users can state today's charge and renewal charge after viewing the paywall.
- Restoring purchases does not duplicate AI credits.
- Finishing or skipping onboarding preserves all intentional choices.
- Paid expiry preserves free functionality and does not trap an active challenge.
- No premium promise depends on an unimplemented or unapproved platform capability.
- Research references are inspiration; production assets and copy are original.

## 12. Decisions to carry into the existing plan

Adopt the two-route structure now. Keep the existing short invited-user activation goal, while adding a longer optional habit setup. Use the annual seven-day trial as the first offer hypothesis, retain a visible monthly choice and free path, and evaluate paid-month introductory pricing before a paid-week campaign.

The app's strongest onboarding demonstration remains exactly the original idea: **a friend gets your attention with a quack, and your group turns its own jokes and goals into challenges.** Personalization should make that experience real before asking the user to pay.

