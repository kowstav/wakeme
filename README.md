# Wake Me — research and build plan

Research date: **18 October 2026**. Prepared as a development brief for **Claude Opus 5.5**, a human founder, and the engineers who will verify and ship the app.

**Recommendation:** Lead with **“Alert your friends. Wake each other up.”** Build the product around two reinforcing behaviors: **“I need you now”** and **“Help me become better.”** A quack or remote wake brings a group in; shared routines, inside jokes and reusable challenges give it lasting value. Add focus supervision and safety check-ins after the alarm and permission systems pass device testing. Competition validates the headline use case; it is not a reason to hide it.

The complete brief is split into practical handoff documents:

1. [Research, competitors, market and feasibility](./01-RESEARCH-AND-FEASIBILITY.md)
2. [Architecture, stack, APIs, networking, authentication and security](./02-ENGINEERING-BLUEPRINT.md)
3. [Product design, onboarding and challenge experience](./03-PRODUCT-AND-DESIGN.md)
4. [Delivery stages, launch, pricing and unit economics](./04-DELIVERY-AND-BUSINESS.md)
5. [Claude skills shortlist and implementation instructions](./05-CLAUDE-SKILLS-AND-PLAYBOOK.md)
6. [Repository instructions for Claude](./CLAUDE.md)
7. [Latest OS capability audit: iOS 27 and Android 17](./06-LATEST-OS-CAPABILITIES.md)
8. [Onboarding screens, copy, subscription offers and app teardowns](./07-ONBOARDING-PAYWALLS-AND-APP-TEARDOWNS.md)

The pricing model is editable in [cost-model.json](./cost-model.json). Run [cost-model.py](./cost-model.py) with Python 3 to recalculate it. It uses only the standard library, has no network access and writes no files.

[SOURCES.md](./SOURCES.md) collects the report's source links. Each numbered document keeps citations beside the claims they support.

Read these in order before implementing. Start Claude with the Stage 0 prompt in document 5. These files are a plan, not an implemented or certified app. No third-party skills were installed, no services purchased, and no deployments made.

## Decisions that matter most

- **Core alarm functionality is feasible.** Native scheduling is substantially different from receiving an immediate internet command. An already-installed alarm can work offline; a new remote command cannot reach a phone with no connectivity.
- **Competition is real.** Galarm is established. PingPal already advertises friend alarms, custom media and proof missions; Alarmee has shared alarms and permissions. The opportunity is excellent execution and the richer challenge-authoring system, not a claim to have invented social alarms.
- **Use native SwiftUI and Kotlin/Compose by default.** Alarm, audio and Screen Time behavior are the central product, so native capability work outweighs the benefit of one UI codebase. React Native with custom native modules is a budget alternative, with an explicit validation gate.
- **Spotify is approval-dependent.** Its published policy prohibits alarm functionality without written approval. Existing apps advertising Spotify do not transfer permission to a new app.
- **“Any challenge” means creative combinations of supported building blocks.** AI outputs validated declarative data. It does not install arbitrary code, control accessibility autonomously, or judge dangerous physical tasks.
- **User control is the foundation.** Grants are directional, capability-specific, time-limited and revocable. A friend cannot grant themselves new permissions. Every challenge has an accessible escape route.
- **Do not promise an emergency service.** Explicit SOS and optional last-known-location sharing can be useful; timestamp, uncertainty, consent and delivery status must remain visible.

## Research method and evidence limits

Reddit and Medium were searched for discovery, user frustrations and skill recommendations. Official GitHub repositories were then inspected for skill provenance and current contents. Apple, Google, Firebase, Spotify and vendor documentation support technical and pricing conclusions. Store listings support advertised features and public traction indicators; they do not prove implementation quality or every claimed behavior.

Numbers are snapshots from accessible pages, predominantly the US App Store and English Google Play. Search indexes can lag live pages; do not interpret rounded downloads as active users, app ratings as revenue, or a founder interview as an audited financial statement. Medium access was partial for one named article. No paid Sensor Tower/data.ai dataset, representative survey, competitor binary audit or device benchmark was performed.

Dates after 18 October 2026 are outside this report's intended cutoff. Repository recommendations are a suitability shortlist, not a measured universal ranking. Before implementation, pin exact dependency/skill versions and recheck SDK and store requirements.

All monetary forecasts, conversion rates, budgets, schedules and quality gates explicitly described as **assumptions**, **proposals** or **targets** are planning inputs, not measured Wake Me results.
