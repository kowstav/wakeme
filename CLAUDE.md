# Wake Me project instructions

This repository currently contains a research and implementation plan dated 18 October 2026. It does not yet contain the app. Do not describe planned capabilities as implemented.

## Product

Headline: **Alert your friends. Wake each other up.**

Two reinforcing behaviors:
1. **I need you now:** send an authorized quack, nudge or wake request.
2. **Help me become better:** shared waking, study/focus and personalized challenges.

Keep the social alert as the headline. Competition informs execution and differentiation; it does not prohibit this positioning. The recipient owns all delegated permissions.

## Read first

- README.md: overview and evidence limits.
- 06-LATEST-OS-CAPABILITIES.md: current platform paths and experiments.
- 01-RESEARCH-AND-FEASIBILITY.md: market and product evidence.
- 02-ENGINEERING-BLUEPRINT.md: stack, contracts, security and delivery.
- 03-PRODUCT-AND-DESIGN.md: design system and complete journeys.
- 04-DELIVERY-AND-BUSINESS.md: stages, launch gates and costs.
- 05-CLAUDE-SKILLS-AND-PLAYBOOK.md: reviewed skill shortlist and prompts.

## Default architecture

SwiftUI iOS + Kotlin/Compose Android; TypeScript modular API/worker backend; managed PostgreSQL and auth; native OS scheduling; versioned OpenAPI and declarative challenge schemas. Revisit with an ADR if the Stage 0 evidence/team constraints favor React Native with custom native modules. No PWA-only substitute for native capabilities.

## Non-negotiable invariants

- Friendship and circle membership never automatically grant phone-control capabilities.
- Grants are recipient-owned, directional, bounded, versioned and revocable.
- Check identity, object access, grant, time, quota, expiry and device eligibility at each relevant boundary.
- Provider acceptance is not proof that a phone rang or a person woke up.
- An installed local alarm and a fresh remote command are different delivery paths.
- Local alarm execution, cached challenge completion and escape do not require network/AI.
- Never replay an expired attention nudge after reconnection.
- Preserve system stop controls; keep challenge completion distinct from silencing.
- Use current supported native APIs. Test new OS capabilities before concluding something is impossible.
- Android 17 audio needs the documented lifecycle/permission/audio-attribute combination; a background task alone is insufficient.
- Speaker preference must be verified for the actual audio path. Do not claim generic Bluetooth disconnection.
- Spotify alarm use requires written approval under the researched policy; use approved local sounds meanwhile.
- AI creates constrained data, never arbitrary code or autonomous accessibility actions.
- Validate manual and AI-created tasks; no dangerous/coercive physical challenges.
- Every challenge and focus restriction has an accessible safe escape and finite duration.
- No stealth location sharing; show capture time, accuracy, scope and expiry.
- No production secrets in apps, source, logs, skills or prompts. Runtime credentials are least privilege.
- Do not claim E2EE, security certification, store approval or device verification without evidence.
- Subscriptions never gate stopping audio, revoking grants or removing restrictions.

## Implementation workflow

Start Stage 0 with small native experiments. Record device/OS/build/settings and observed results. Inspect current official docs and SDK declarations. Pin dependencies and adopted skill revisions after review. Use only relevant skills, not an indiscriminate global collection.

Implement one complete vertical slice at a time. Write ADRs for material decisions. Keep shared policy fixtures and challenge schemas consistent across server/iOS/Android. Use meaningful integration, authorization and native lifecycle tests. Capture actual screenshots for design reviews and actual audio/device evidence for alarm reliability.

Maintain PROGRESS.md with finished gates, open issues, current build and next step. Every completion report must state what changed, tests actually run, untested conditions and remaining gates. Distinguish specified/implemented/verified/released.

Publishing, purchasing services and production credential changes require explicit owner authorization. Make reviewable builds and artifacts first. Continue independent authorized work when account, device or entitlement access is pending.
