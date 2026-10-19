# Claude skills and implementation playbook

Researched **18 October 2026**. This is a focused shortlist for Wake Me, not a claim of a universal “best skills” benchmark. Repositories and current documentation were inspected; no package was installed or executed for this report.

Anthropic's official page confirms **Claude Opus 5.5** and the API identifier `claude-opus-5-5`. Use the selected model through your actual Claude environment; verify availability, quotas and current pricing before automation. The development model does not need to be the model used inside the consumer app. [Official Opus page](https://www.anthropic.com/claude/opus)

## 1. Recommended skill set

Choose based on the adopted stack. Load only the skills relevant to the current task. No skill grants a mobile permission, proves security or replaces a running build.

| Skill/repository | Recommendation and verified scope | Limitation / when to use |
|---|---|---|
| **[android/skills](https://github.com/android/skills)** | First choice for current Android-specific agent guidance. Official Android organization; current repository includes Compose, identity, security, system, testing and build/tooling areas. | It deliberately focuses on tasks where agent evaluations reveal weaknesses; it is not a complete replacement for Android architecture docs. Use with API 37 references. |
| **[AvdLee/SwiftUI-Agent-Skill](https://github.com/AvdLee/SwiftUI-Agent-Skill)** | Strong focused SwiftUI choice. Current README covers state, composition, navigation, accessibility, performance and SDK 27 changes. | Explicitly non-opinionated about whole-app architecture. Pair with Wake Me's ADRs and contracts. |
| **[AvdLee/Swift-Concurrency-Agent-Skill](https://github.com/AvdLee/Swift-Concurrency-Agent-Skill)** | Recommended for async lifecycle, actors, cancellation and concurrency review. | Invoke around alarm synchronization, audio ownership, persistence and background tasks; do not load indiscriminately for copy changes. |
| **[justinwetch/HIGAgentSkills](https://github.com/justinwetch/HIGAgentSkills)** | Native iOS design/accessibility reference. Inspected README identifies a September 27, 2026 release and September Apple-source snapshot. | Independent community project, not Apple-endorsed. Verify critical rules against Apple HIG and observe actual native screens. |
| **[anthropics/skills — frontend-design](https://github.com/anthropics/skills/tree/main/skills/frontend-design)** | Useful for distinctive visual direction, intentional typography and avoiding generic output. First-party Anthropic source. | Broad/front-end oriented; does not teach AlarmKit, Compose or Screen Time. Use for design direction and web/admin surfaces, not as sole native UI guidance. |
| **[pbakaus/impeccable](https://github.com/pbakaus/impeccable)** | Useful design critique/polish workflow and durable product/design context. Current upstream has an `impeccable` entry point with audit/critique/polish commands. | Browser detectors target front-end output, not native SwiftUI/Compose semantics. Apply critique principles to screenshots; use native tooling for verification. |
| **[trailofbits/skills](https://github.com/trailofbits/skills)** | Preferred security workflow source: audit-context-building, differential-review, sharp-edges, insecure-defaults, static-analysis and supply-chain-risk-auditor are directly useful. | Select relevant plugins, not blockchain/malware tools unrelated to the app. Findings need reproduction; this is not a penetration-test certificate. |
| **[obra/superpowers](https://github.com/obra/superpowers)** | Useful optional framework for planning, disciplined tests, debugging and completion verification. | It is a workflow framework, not a mobile architecture authority. Avoid redundant orchestration/checklist overhead; adapt to this stage plan. |
| **[expo/skills](https://github.com/expo/skills)** | Official Expo skills if the React Native route is chosen. Current collection includes project structure, design systems, native UI, animation, modules, data fetching and EAS workflows. | Not needed for a purely native build. Native modules/extensions remain required for Wake Me's deep OS features. [Official docs](https://docs.expo.dev/skills/) |
| **[software-mansion-labs/skills](https://github.com/software-mansion-labs/skills)** | Maintainer-authored React Native guidance for current architecture, gestures, animation, audio and debugging tools. | Optional for React Native. Do not add video/streaming SDKs just because the collection includes them. |
| **[rcosteira79/android-skills](https://github.com/rcosteira79/android-skills)** | Secondary community Android/KMP reference to assess for missing architecture/testing guidance. | Prefer official sources when they conflict. Not audited or benchmarked here; inspect individual skill contents before use. |
| **[getsentry/MobileBuildMCP](https://github.com/getsentry/MobileBuildMCP)** | Useful build/test/simulator tool integration. The previously known XcodeBuildMCP GitHub URL currently redirects here. | **Tool/MCP, not an architecture skill.** Verify current setup documentation; requires appropriate Apple development host for iOS workflows. |

Recommended native starting set: official Android skills + SwiftUI Expert + Swift Concurrency + Apple HIG references + a few Trail of Bits review workflows. Add one design-direction workflow and optional Superpowers. For React Native, add Expo and Software Mansion; do not replace native capability knowledge with them.

## 2. What the community research actually supports

**Reddit:** An August 2026 React Native discussion reports generic results despite Expo/Software Mansion skills, with responses emphasizing references, hierarchy, a real design system and focused screen iteration. A June discussion critiques vague “you are an expert” persona files. These support task-specific procedures and visual verification, not installing the largest skill bundle. [React Native design discussion](https://www.reddit.com/r/ClaudeAI/comments/1vzmpkf/how_can_i_get_better_ui_designs_for_react_native/), [skill-quality discussion](https://www.reddit.com/r/claudeskills/comments/1uhpndu/why_are_all_the_claude_code_skill_files_i_see/)

**Reddit discovery:** A March iOS skills thread discusses broad iOS coverage and compares a focused SwiftUI skill with larger collections. It is an author/community discussion, not an independent quality ranking. An October Android thread points to the official Android repository; official provenance is more useful here than its star count. [iOS thread](https://www.reddit.com/r/SwiftUI/comments/1rnf25c/23_agent_skills_for_ios_26_development_swiftui/), [Android discovery](https://www.reddit.com/r/BestGitHubRepos/comments/1x7bbsv/android_skills_aioptimized_modular_instructions/)

**Medium:** The June 30 article “5 Claude Skills Every Modern Android Developer Must Know” surfaced mobile design/architecture candidates. Direct full-page retrieval failed, so only accessible indexed content was used. Its list was not treated as proof of quality. [Article](https://medium.com/@shibinjoseph01999/5-claude-skills-every-modern-android-developer-must-know-and-advanced-integrations-46c98c961a05)

Other accessed Medium search excerpts included a SwiftUI/Xcode workflow showing incremental feature review and a March skills article pointing to Trail of Bits. These are useful discovery trails; technical recommendations above were checked against the maintainers' repositories instead. Claims about replacing staff or dramatic productivity gains were not adopted. [Xcode workflow](https://medium.com/@chinthaka01/agentic-coding-in-xcode-26-3-f5de9e0f93ad), [skills setup article](https://medium.com/@ayobamitunmise99/i-use-claude-skills-daily-heres-the-exact-setup-that-replaced-3-freelancers-2026-f138befa5a5c), [iOS workflow discussion](https://medium.com/@matej-kokosinek/ai-ask-and-you-shall-receive-part-4-038e70895026)

**GitHub:** Actual repository structure, maintainer identity, scope and current documentation are the primary evidence for the shortlist. Star/install counts were intentionally not used to score skill quality. Many directories aggregate the same skill under different names; prefer original upstream repositories over mirrors.

## 3. Installation guidance

The following commands were present in the relevant upstream documentation at research time. They are instructions for a future setup step, not commands run during this task. Recheck the repository README and Claude version first. Record installed revision/tag and inspect executable hooks/scripts before granting tool access.

In Claude Code:

```text
/plugin install android-skills --marketplace android/skills

/plugin marketplace add AvdLee/SwiftUI-Agent-Skill
/plugin install swiftui-expert@swiftui-expert-skill

/plugin marketplace add AvdLee/Swift-Concurrency-Agent-Skill
/plugin install swift-concurrency@swift-concurrency-agent-skill

/plugin marketplace add trailofbits/skills
/plugin menu
```

Choose the relevant Trail of Bits plugins in the menu; do not guess plugin identifiers from blog posts. [Android README](https://github.com/android/skills), [SwiftUI README](https://github.com/AvdLee/SwiftUI-Agent-Skill), [Concurrency README](https://github.com/AvdLee/Swift-Concurrency-Agent-Skill), [Trail of Bits README](https://github.com/trailofbits/skills)

Only for the React Native route:

```text
/plugin install expo@claude-plugins-official

/plugin marketplace add software-mansion-labs/skills
/plugin install skills@swmansion
/reload-plugins
```

These commands are documented by [Expo](https://github.com/expo/skills) and [Software Mansion](https://github.com/software-mansion-labs/skills). Confirm whether your installed Claude version needs a reload.

For a manually installed skill, preserve its complete folder and referenced files under a supported project path such as `.claude/skills/<skill-name>/SKILL.md`. Do not copy only the main markdown file and lose references/scripts. Use the original package's installation method when it is a plugin rather than a single skill. [Claude skills documentation](https://code.claude.com/docs/en/skills)

For each adopted package, create a record containing upstream URL, author, license, commit/tag, reviewed scripts/hooks, tool permissions and reason for inclusion. This report has not pinned commits or audited all code. Pin during project setup; review updates rather than automatically executing new remote instructions with production secrets.

## 4. How to evaluate a skill before trusting it

Run the same bounded task with and without the candidate, using the same model and acceptance criteria. Example tasks:

- Build an accessible SwiftUI grant editor with large text and a denied state.
- Implement an Android 17 alarm service that correctly handles permission denial and failed audio focus.
- Find a seeded cross-user grant bug and show a failing regression test.
- Produce a valid offline challenge schema that rejects arbitrary code and excessive physical tasks.
- Fix a confusing delivery-status screen from actual screenshots.

Score correctness, visual quality, maintainability, security findings reproduced, native API validity, number of repair cycles and token/time cost. A useful skill should improve a recurring failure mode. “More impressive explanation” is not a passing result.

An architecture skill cannot know Wake Me's tradeoffs by itself. Keep product-specific invariants in `CLAUDE.md`, data/API contracts in versioned files, and design decisions in `DESIGN.md`. Use skills for specialized procedures; avoid contradictory instructions spread across many packages.

## 5. Master kickoff prompt for Claude Opus 5.5

Copy this after giving Claude access to the complete folder:

```text
You are implementing Wake Me from this repository's research and planning pack.
Read README.md, CLAUDE.md and the numbered documents before choosing a stack.

Product headline: Alert your friends. Wake each other up.
Two behaviors drive the product:
1. I need you now: consensual quacks, attention nudges and wake requests.
2. Help me become better: shared wake/focus routines and personalized challenges.
Preserve this positioning; existing competitors do not invalidate the headline.

Begin Stage 0. Inspect the environment, installed SDKs, devices and current docs.
Verify latest released iOS/Android capability paths rather than relying on older
restrictions. Specifically validate AlarmKit, current stop/secondary actions,
Screen Time shield-to-app flow and Android 17 alarm-audio/FGS requirements.

Create a capability ledger: feature, public API, minimum OS, permissions,
entitlements, lifecycle states, demonstrated behavior, unresolved cases and fallback.
Build the smallest native proof for each critical path. Record reproducible commands,
device/OS/build/settings, logs and screen/audio evidence. Do not claim hardware
success when only compilation or simulator tests ran.

Then write ADRs for the stack, delivery semantics, consent model and challenge engine.
Use SwiftUI + Kotlin/Compose + a TypeScript/PostgreSQL modular backend by default,
unless measured constraints justify the documented React Native/native-module option.

Keep the scope staged. Finish and verify one vertical slice before broadening it.
Generate challenges as constrained data for existing native renderers, never downloaded
executable code. Local alarm/challenge/escape paths must not depend on the server.
Show truthful delivery states. Apply recipient-owned grants at every authority boundary.

Use only relevant reviewed skills. For every stage return changed files, build/test
evidence, unresolved risks and the next gate. Maintain a durable progress file.
Ask for missing accounts/devices only when needed, while progressing independent work.
Do not purchase services or publish a release without the owner's explicit instruction.
```

## 6. Stage prompts and required outputs

### Platform experiment prompt

“Implement minimal AlarmKit and Android AlarmManager/audio prototypes. Test installed versus newly remote-requested alarms separately. On Android API 37 verify the exact-alarm/USAGE_ALARM exception and legitimate FGS start/type. On iOS use current SDK initializers and inspect OS Stop behavior. Produce the capability ledger and hardware matrix; mark untested states explicitly.”

Required output: runnable experiments, no full product scaffold yet, dependency versions, raw observed outcomes and a decision for each feature.

### Architecture prompt

“Create ADRs, the data model, OpenAPI, grant policy and event state machine from document 2. Identify every trust boundary. Model local authority versus server authority, pending schedule edits, offline revocation and push delivery races. Include shared policy fixtures and define transaction boundaries before routes.”

Required output: contracts, schema/migrations, state diagrams, positive/negative authorization tests, error vocabulary and versioning strategy.

### Design prompt

“Use document 3. Show three visual directions for Friends and Send Quack with real content and native controls. Choose a coherent token system. Implement the complete send/receipt journey including denied, waiting, expired and offline states. Capture small/large, light/dark and large-text screenshots. Critique and repair the implemented result.”

Required output: design rationale, tokens, native components, screenshot evidence and usability questions. Do not accept a web mockup as proof that the native build matches.

### Consent/security prompt

“Threat-model a malicious permitted friend, a stranger with a guessed object ID, a compromised device, an abusive circle and a replayed push. Attempt grant escalation and cross-user reads. Verify database role behavior including secret-key/RLS bypass. Reproduce each finding with a test, fix it and rerun. Inspect queued-command revocation and OS-rendered sound races.”

Required output: prioritized findings with evidence, regression tests, residual risks and reviewed controls. No unsupported “secure” summary.

### AI challenge prompt

“Implement a bounded declarative schema and native renderers. Add authoring from supplied group facts, deterministic math and verified chess. Enforce semantic safety for manual and AI input. Add preview/edit, immutable versions, recipient acceptance, offline caching and accessible fallback. Reject arbitrary code, unsupported sensors and dangerous quantities. Measure all generation/retry costs.”

Required output: schema, fixtures, native demos, adversarial eval results, cost per accepted challenge and honest unsupported-feature copy.

### Subscription prompt

“Implement server-verified entitlements and credit accounting. Test renewal, cancellation, refund, grace, restore, replay and account-link conflicts. Paid expiry must not disable personal alarm escape or trap a user in focus. Keep send/receive basics usable. Update the model with real provider usage and regional store fees.”

Required output: lifecycle test evidence, billing reconciliation runbook, quota handling and revised unit economics.

### Launch prompt

“Audit against the launch gates in document 4. Build distribution candidates, verify signatures/entitlements and privacy disclosures, test real devices and run deletion/restore/rollback drills. Produce reviewer instructions for two accounts/devices. List every unmet gate. Do not mark launch-ready while required evidence is absent.”

Required output: release checklist with evidence links, store metadata, support/incident runbooks and a go/no-go decision for each capability.

## 7. Working discipline for Claude

At each stage, read the relevant documents rather than loading every skill/reference into context. Keep `PROGRESS.md` with completed gates, current branch/build, decisions and blockers. Update ADRs when changing architecture so later sessions do not revert to abandoned choices.

Each implementation task should be small enough for a reviewable diff and end-to-end verification. Prefer meaningful tests for authorization, state transitions, native lifecycle and data integrity over snapshot tests that merely repeat generated implementation. Do not fabricate device results or leave mock APIs behind a production-looking UI.

Keep a feature acceptance ledger with four statuses: **specified**, **implemented**, **verified in named conditions**, **released to a named cohort**. These are different achievements. A successful local demo cannot establish approval, production delivery rate or product-market fit.

Before calling a stage complete, Claude must answer:

1. What real user behavior now works?
2. Which devices/states were tested, and which were not?
3. What can an unauthorized or formerly authorized user still attempt?
4. What happens offline, after a crash and after a permission change?
5. Does the visible status match the evidence?
6. What does the feature cost at expected usage?
7. Is the UI legible, accessible and consistent with the chosen direction?
8. What remains before the next stage or launch?

This is the route to a strong app: current platform knowledge, focused tools, clear contracts, careful product decisions and repeated evidence from actual devices.
