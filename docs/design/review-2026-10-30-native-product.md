# Native product review — 30 October 2026

## Findings from the owner's Android 13 report

The owner confirmed that the installed app shows only **Your wake-ups** and a sign-in button. This is `GuestHome`, not the four-tab signed-in application. The product allowed account-free alarm creation but failed to communicate the boundary clearly. The missing navigation is a discovery problem; it must not be described as evidence that every promised feature is shipped and working.

The second-alarm defect is a lifecycle error. `GuestHome` conditionally composed `RoutineEditorScreen`, whose default Hilt ViewModel belonged to the enclosing activity. Saving set `EditorState.done = true`. Leaving composition did not clear that activity-owned instance, so a subsequent editor observed `done` and immediately called `onDone`. The guest flow now uses the same Navigation 3 entry decorators as the main app: each editor owns its ViewModel and saved UI state, and popping it clears the completed draft. Permission behavior and save assertions are retained.

The existing visual system has several measurable problems: 30–44 sp heavy headings compete with large time numerals; almost every action is a large card; long capability paragraphs repeat status pills; a complete readiness checklist shares space with the alarm list; and the editor exposes templates, duration, time-zone behavior and optional settings before the person has made the common time/day/sound choices. More rounded cards or decorative glass would not resolve these issues.

## Research and selection

This is research performed on 30 October 2026, not a claim that every reference was published in October or that its live app was installed and tested.

| Source | Useful principle | Application to Wake Me |
|---|---|---|
| [Apple HIG](https://developer.apple.com/design/human-interface-guidelines/) and [2026 design principles](https://developer-rno.apple.com/videos/play/wwdc2026/250/) | Hierarchy, clear affordances, user agency, platform familiarity and deliberate simplicity | Sentence-case native typography; put details at their decision point; preserve conspicuous Stop, End and consent controls |
| [Android ViewModel scoping](https://developer.android.com/topic/libraries/architecture/viewmodel/viewmodel-apis) | ViewModel lifetime follows the owning navigation destination | Replace the guest's conditional activity-scoped editor with entry-owned state |
| [Opal's official product presentation](https://opalapp.com/) | A strong primary message; focus tools have distinct purposes rather than a page of equal-weight controls | One primary action, clearly separated alarm and account tasks; no borrowed engagement scores or unearned metrics |
| [RISE's official product](https://www.risescience.com/) | Organize information around the user's next decision | Time and upcoming routine are primary; explanation and configuration are secondary. Do not copy sleep predictions or medical claims |
| [Dribbble alarm concepts](https://dribbble.com/search/alarm-app) and [Vitali Darozhka's concept](https://dribbble.com/shots/15810887-Alarm-App) | Visual exploration of time hierarchy, light/dark surfaces and focused action areas | Concept references only. Indexed pages were available; interactive viewing hit a human-verification gate. No usability or production-readiness claim is inferred from a shot, palette or likes |

The official Opal page was visually inspected. Direct asset viewing was blocked by browser policy; no workaround was used. The current Wake Me Friends phone render was inspected at native scale. New native renders must be reviewed before declaring the refresh accepted.

### GitHub skills

Reviewed and installed [mobile-android-design](https://github.com/wshobson/agents/tree/main/plugins/ui-design/skills/mobile-android-design) and [mobile-ios-design](https://github.com/wshobson/agents/tree/main/plugins/ui-design/skills/mobile-ios-design). They provide directly relevant native Compose/SwiftUI layout, semantic type, accessibility, navigation and dark-mode guidance. Their sample navigation code is guidance, not a reason to replace this project's Navigation 3 stack. Official platform documentation takes precedence for current APIs.

Also reviewed the existing Chris Banes Compose component guidance. Existing `mobile-native` and `apple-design` skills in `.claude/skills` explicitly target websites/CSS: they are not suitable implementation instructions for these native apps. A SwiftUI expert repository was considered, but an unavailable path was not installed or represented as used. No skill is declared universally “best” based on stars or a marketing directory.

The selected native skills are installed in the owner's Codex skill directory and are available on subsequent turns. Their files and referenced details were read for this implementation.

## Implementation decisions

- Keep the identity and semantic color system; change hierarchy rather than adding another decorative skin. System sentence-case display type replaces condensed all-caps. Screen/section headings use semibold; time numerals use medium. Body text remains readable and touch targets retain native minimum sizes.
- Local home has a persistent New wake-up action, scannable time/day/status rows, a dedicated readiness destination and a concise explanation of account features. Deleting an alarm requires confirmation. Local alarms remain offline and account-free.
- The initial alarm form prioritizes time, repeat days, name and sound. Routine templates, ring duration, travel and other uncommon settings live under More options. Friend-granted limits and readiness warnings remain explicit.
- Friends rows have one availability line instead of capability paragraphs plus a repeated status pill. Invite and Join are compact controls rather than two oversized feature tiles. Detailed permissions remain on the friend page.
- Apply shared type changes to Android and iOS; make iOS guest discovery explicit and travel settings expandable. Keep native controls and Dynamic Type rather than imitating a web mockup.
- Add Android 13 to device CI, a second-editor regression in the real native journey, and guest home previews in light, dark and large text. Verification must name the source and conditions; an emulator is not the owner's handset.

## Feature reality and where to find it

| Feature | Entry / implementation | Current completion boundary |
|---|---|---|
| Personal alarms | Guest home; `alarm/LocalRoutines`, `AlarmScheduler`; iOS Routines | Available without account. Second-editor bug being corrected; physical Android 13 re-test required |
| Friends, circles, quacks, messages | Signed-in Friends tab; `ui/friends`, `ui/messages`; API relationships/conversations/commands | Requires sign-in, grants and real FCM/worker for cross-phone delivery. API acceptance is not a ringing receipt |
| Shared routines and suggestions | Signed-in Routines, friend actions; API alarms | Consent, recipient acceptance and installation evidence remain required |
| Quizzes, chess, checklists/timers | Challenges tab and `challenge/ChallengeRunner`; shared challenge engine | Implemented types need account and authored/published revisions where applicable. Not every free-text request is supported |
| AI challenge drafting | Challenges authoring and API challenge worker | Needs configured AI provider and worker; not verified in staging |
| Focus | You → Focus; `focus/FocusScreen`, accessibility service | Android release service remains gated by the Play declaration. Do not silently enable it to make a feature checklist look complete |
| Safety/check-ins/location | You → Safety or trusted-friend actions; `ui/safety`, API safety | Requires consent, permissions and, for remote outcomes, push/worker. Never advertise continuous tracking or guaranteed emergency response |
| Private voice sounds | You → Voice sounds; `media/VoiceSoundsScreen` | Needs private storage and worker checks, approval and account isolation; provider integration still open |
| Subscription | You / paywall; billing modules | Needs RevenueCat/Play products and license testing; no premium claim from a static screen |
| Guitar/pose recognition, Spotify automation and other conditional experiments | Research/feasibility documents | Not core shipped features; require separate technical, platform and rights decisions |

The new design must make the account route clear without fabricating functional previews, bypassing sign-in, weakening recipient consent, or implying unavailable services work. Feature code, configured services and real-device verification are separate completion states.

## Acceptance

Required: open/save/cancel/reopen local editors; save another routine; preserve earlier alarms; verify Android 13 plus the existing 16/17 journeys; review native phone/dark/large-text renders; verify iOS compile and UI journeys. Owner acceptance on the Android 13 handset remains necessary for touch feel, audio and subjective visual quality. Full product completion still includes the provider, physical QA and release gates in docs 01–04 and 06.

## Owner steering and artwork implementation

The owner confirms that Google sign-in succeeds on the Android 13 handset and the four tabs appear. This is an owner-observed sign-in outcome; session refresh and the wider physical delivery matrix remain separate checks. The owner also requests a clear Google entry on the first screen, removal of Play preview, less explanatory copy and more purposeful background imagery. Those instructions supersede the older two-choice welcome hierarchy.

The welcome now launches the existing Credential Manager flow directly through an official-brand Google action, with account-free alarm setup and other sign-in options alongside it. iOS retains its platform-appropriate account entry. Startup tests assert the explicit sign-in action and absence of Play preview. Two original transparent illustrations (morning and connection) are bundled locally on both platforms. Native gradients supply light/dark surfaces; artwork is decorative, with real state, permissions and actions remaining accessible native controls. Long account explanations and repeated Friends availability badges are removed.

The alarm fix passed android-device #107 on APIs 33, 36 and 37, and ios #59 before the final artwork/welcome additions. New-source validation is required for those additions. General CI exposed an existing date-sensitive deletion test: its API fixture was fixed at 19 October while outbox eligibility uses the database calendar. The test now snapshots PostgreSQL time, still asserts exactly seven days and retains both before/after deletion checks. The local account suite passes all five cases.

## Widget reliability integration

Android-device #111 on Android 13 reproduced the widget reset: RescheduleReceiver enabled at 15:11:40.599, PACKAGE_CHANGED arrived at 15:11:41.603 during the one pointer tap (down 41.551, up 41.788), and the host remained foreground with no friends route. Reviewed PR #9 passed ci #40 and android-device #103 and was merged as 31f8447. This branch was rebased onto that base, retaining both progress entries and the strict widget assertions. Final combined-source verification follows.
