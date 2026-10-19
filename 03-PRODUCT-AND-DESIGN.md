# Product, design and onboarding brief

## 1. Product identity

Headline: **Alert your friends. Wake each other up.**

Supporting line: **From “where are you?” to “we've got this.”**

Wake Me connects two motivations: **I need you now** and **Help me become better**. The first creates an immediately understandable invitation. The second turns that invitation into a repeated group ritual. Both should feel personal, playful and voluntary.

Do not bury the remote alert under a generic wellness dashboard. The first screen should make contacting a trusted friend obvious. Do not portray consent as a legal settings page; it is part of the product's value: “You decide what each friend can do.”

“Wake Me” is a working name. Before public branding, check app-store naming conflicts, domains and relevant trademarks in launch regions. Existing similarly named products are not automatically a legal conflict, but availability must be verified.

## 2. Design direction

Aim for the interaction clarity of a mature messaging app, the emotional warmth of a close-friends product and the calm legibility of a good alarm. Learn from leading products' clarity and feedback without copying their branding or screen layouts.

Proposed visual direction: **warm daylight + quiet night**. Soft off-white backgrounds, dark ink typography, a distinctive duck-yellow accent and a restrained blue for informational states. Use strong contrast; reserve red for destructive actions/SOS. A small duck motif can make the quack memorable, but it should not dominate every screen or make urgent care feel frivolous.

Initial token proposal, subject to contrast testing:

| Token family | Direction |
|---|---|
| Colors | Semantic `surface`, `surfaceRaised`, `textPrimary`, `textSecondary`, `accent`, `warning`, `danger`, `success`; separate tested light/dark values |
| Typography | Native system text for readability and language coverage; expressive numerals only where legible; Dynamic Type/font scaling |
| Spacing | Base 4-point scale; 8/12/16/24/32 for rhythm; avoid arbitrary per-screen spacing |
| Shape | Consistent rounded controls; fewer nested cards; clear grouped settings |
| Motion | Short acknowledgment transitions; one signature quack animation; Reduced Motion alternative |
| Haptics | Subtle send confirmation, distinct but calm warning; respect system settings |
| Sound | Original, recognizable short sound set with preview, intensity labels and recipient choice |

Do not equate premium design with glass everywhere, gradients, tiny gray text or elaborate animations. Use platform-native navigation and controls. Apply current iOS materials selectively and test tinted/high-contrast variants. Compose should feel intentional on Android rather than imitate every iOS detail.

## 3. Information architecture

Proposed four tabs:

1. **Friends:** trusted people and immediate actions; fastest route to a quack.
2. **Routines:** personal/shared alarms, study sessions and habits.
3. **Challenges:** saved group creations, authoring and assigned tasks.
4. **You:** readiness, permissions, account, quiet hours and subscription.

Messages live in friend/circle detail so they remain attached to the relationship. Safety sessions are accessible from trusted-friend detail and a clearly labeled action; they do not need a full tracking tab at launch.

Home should show the next personal alarm and one concise readiness issue when relevant. Avoid an infinite feed, public follower counts or engagement streaks that penalize recovery days. The app should help users leave the phone after acting.

## 4. Primary journeys

### A. Send a quack

Open Friends → choose Alex → tap the quack → see “Sent to Alex” and available receipt status. Preview is a different control from Send. The same button must not accidentally send while browsing sounds.

The action surface shows only permitted actions: Quack, Wake, Message or Focus request. A disabled Wake control explains “Alex hasn't enabled wake alarms for you,” with a request path. Avoid displaying private capability details such as exact battery/location without separate permission.

If Alex does not respond, offer a bounded, authorized escalation: “Send wake alarm” only if Alex enabled it. Do not automatically escalate based on a sender's impatience. Do not trigger repeated phone calls.

### B. Set a buddy wake routine

Choose time → timezone/travel behavior → days → choose sound → choose optional challenge → invite helper → recipient previews and accepts → phone installs alarm → status becomes Ready.

The preview summarizes: “Maya can help wake you Mon–Fri, 7–8 am, using these sounds. Up to one wake session a day. You can change this anytime.” Show proposed changes separately until synchronized.

During an alarm, optimize for a sleepy user: very large clear controls, no tiny text, no paywall, no distracting feed. The OS Stop and the app's “I'm up” challenge completion are separate outcomes. After completion, offer a light reaction or “Thanks, Maya”; do not force social posting.

### C. Inside-joke challenge

Creator supplies the facts: “Ask which cafe we call HQ; correct answer is Bean House. Then ask what song we play before exams.” AI drafts the quiz, creator edits and tries it, and recipients can preview its type/difficulty before accepting the routine.

Do not leak a quiz's answer through a preview that should preserve surprise; show sample difficulty or an alternate practice question. Make revision history clear: a friend cannot silently replace a gentle quiz with a long physical task after acceptance.

### D. Describe a unique challenge

Prompt examples:

- “Make a three-question quiz from our group's camping story.”
- “Give me one verified easy chess puzzle before Instagram.”
- “Create a ten-minute guitar-practice ritual with an optional timer and self-check.”
- “Remind me to take a comfortable movement break; offer a seated alternative.”

AI returns a **preview card**, not an immediately active restriction. The card names the steps, duration, permissions, verification strength and escape route. Unsupported requests get a useful alternative: “I can build a timer and checklist; automatic chord recognition isn't available yet.”

### E. Friend-supervised focus

Recipient selects distracting apps locally → chooses a friend and limits → schedules or accepts a session. Before an app opens, a shield explains the shared goal and how to earn a short break or use an escape. Challenge completion grants a bounded access period. The friend sees only the opted-in outcome, not a hidden full browsing history.

Avoid “Your friend owns your phone now.” Preferred copy: “You're giving Sam permission to help you focus for up to 30 minutes.”

### F. Safety check-in

Recipient starts “Check in with me at 10 pm” or “Share for my walk home.” Explicitly list viewers, expiry and what happens if they miss the check-in. If sharing location, show the live sharing indicator and a prominent Stop.

Viewer labels: “Captured 12 minutes ago · accuracy about 80 m,” “New location requested,” or “No location available.” A map must never quietly interpolate a stale sample into a live position. A missed alarm alone is not an emergency declaration.

## 5. Onboarding sequence

Target: first solo sound test within about 60 seconds, first successful paired nudge within about 3 minutes when both people are present. These are usability targets to test, not promises.

1. Explain the headline with a short interactive quack preview.
2. Let the user set a personal alarm or accept a friend invitation.
3. For social use, sign in with minimal information and age eligibility.
4. Identify the inviter clearly and require recipient acceptance; no automatic permission from opening a link.
5. Offer permission presets: **Gentle nudges**, **Wake buddy**, **Focus buddy**. Each expands into editable controls; no “allow everything” default.
6. Ask for notifications when sending/receiving the first nudge. Explain the benefit before the OS prompt.
7. Ask for alarm authorization/exact-alarm access only when setting up wake functionality. Ask for full-screen permission only where appropriate.
8. Run a real local test with the user's chosen sound and intended route. Let them test with headphones connected.
9. Display Ready or the exact next step. Permission denial still leaves messages/personal features usable where possible.
10. Offer a first shared routine after the successful social interaction, not before proving value.

Screen Time, camera, microphone and location requests are just in time. Do not demand all at signup. User intent and platform permission are separate: accepting a friend does not approve microphone use or sharing location.

Invite links carry an opaque expiring token, not profile data. If deferred linking is unreliable, provide a short recovery code instead of fingerprinting the user. Avoid mandatory contact sync.

## 6. Permission screen specification

For each friend, group capabilities into understandable rows:

- Send short sounds: sound set, windows and rate limit.
- Help wake me: schedule requests, immediate wake eligibility, maximum duration and escalation.
- Assign challenges: allowed types, difficulty/duration ceiling and whether a new revision needs acceptance.
- Help me focus: recipient-selected app set, allowed times, maximum session and break policy.
- Check in on me: requested check-ins and separately authorized last-known location.

Add **Pause this friend's permissions**, **Remove friend**, and **Block and report**. Explain that circle membership does not change these settings. Show last-used permissions and device-local versus synchronized state.

## 7. Essential screens and states

Design and implement these before calling the UI complete:

| Surface | Required states |
|---|---|
| Friends | Empty, invite pending, accepted, blocked, no permitted action |
| Send sound | Preview, permitted, rate limited, quiet window, offline sender, expired request |
| Delivery | Accepted, no phone confirmation, device received, acknowledged, failed/expired |
| Routine | Draft, awaiting acceptance, awaiting device installation, ready, edited pending, permission lost |
| Alarm/challenge | Ringing where observable, stopped, in progress, solved, bypassed, invalid challenge, sensor denied |
| AI authoring | Generating, editable draft, invalid facts, unsupported primitive, safety rejection, quota reached |
| Focus | Scheduled, active, challenge unlock, temporary break, expired, permission revoked |
| Location | Off, session active, stale, unavailable, viewer revoked, SOS sent/no acknowledgment |
| Billing | Trial, active, grace, expired, restore, refunded, store unavailable |
| Account | Guest, signed in, recovery, device change, export, deletion pending |

Copy must remain literal. “Phone received” is not “They heard it.” “Challenge completed” is not “Guaranteed awake.” “Scheduled” must not mean merely saved to the server.

## 8. Accessibility and humane behavior

Test screen readers, text scaling, high contrast, Reduced Motion, color-vision differences, one-handed use and alternate input. Use generous touch targets (project baseline: at least 44 pt iOS / 48 dp Android), visible focus and non-color status cues. Validate current platform guidance rather than treating those dimensions as sufficient accessibility by themselves. [Apple HIG](https://developer.apple.com/design/human-interface-guidelines), [Android accessibility](https://developer.android.com/guide/topics/ui/accessibility)

Every physical/sensor challenge has an equivalent low-demand option. Speech/microphone tasks need typed alternatives, visual challenges need accessible alternatives, and math difficulty should not exclude users with dyscalculia. Never keep escalating volume indefinitely because someone cannot solve a task.

Provide **Stop sound now** / safety escape under the applicable native experience. Track a bypass separately from completion; the recipient chooses whether helpers see bypass outcomes. Do not use humiliating copy or expose sensitive reasons.

No subscriptions during alarms, no upgrade condition to remove a restriction, and no paid tier that gives friends stronger coercive privileges. Paid convenience can expand templates and authoring, not take away user control.

## 9. Process for genuinely polished output from Claude

1. Write a concise product truth file and this design contract before screens.
2. Collect reference images for navigation, typography, states and motion; document what to learn, not what to clone.
3. Produce three distinct directions for the same two core screens using real Wake Me content.
4. Choose one direction against readability, personality, trust and speed. Keep tokens and reusable components explicit.
5. Implement Friends → Quack → receipt → shared routine as the first polished vertical journey.
6. Capture native screenshots on small/large devices, light/dark, large text and denied-permission states.
7. Review visual hierarchy and behavior, fix, recapture and compare. A code review without screenshots is insufficient for design acceptance.
8. Run five-person usability rounds; test whether users understand who controls what and what delivery labels mean.

Design acceptance targets: 90% of pilot participants can send a permitted nudge without help; 90% can explain and revoke a friend's access; all critical text fits at supported accessibility sizes; no unsolved critical accessibility issue; no misleading success state. These targets need measurement.

Skills support this process, but none can guarantee world-class taste. Reddit developers explicitly report generic output even with mobile skills; strong visual references, a consistent design system and single-journey iteration are more actionable than “make it like a top app.” [Mobile design discussion](https://www.reddit.com/r/ClaudeAI/comments/1vzmpkf/how_can_i_get_better_ui_designs_for_react_native/)
