import SwiftUI
import WakeMeAPI
import WakeMeCore

/// Opened from the alarm's "Start challenge" button. Runs the routine's installed challenge
/// with no network. Finishing it, or "End this wake session", cancels the follow-up alarm at
/// once; then the outcome is reported (best effort). The system Stop alone never counts as
/// completion.
struct WakeSessionView: View {
    @Environment(AppModel.self) private var app
    let pending: WakeSessionRouter.Pending
    @State private var definition: ChallengeDefinition?
    @State private var loaded = false

    var body: some View {
        Group {
            if let definition {
                ChallengeRunnerView(definition: definition, escapeLabel: "End this wake session",
                                    onComplete: { _ in finish("challenge_completed") },
                                    onEscape: { finish("bypassed") })
            } else if loaded {
                ScrollView {
                    VStack(alignment: .leading, spacing: WMSpacing.md) {
                        Text("Wake-up").font(.largeTitle.bold()).foregroundStyle(WMColor.textPrimary)
                        Text("This wake-up's challenge isn't on this phone, so there's nothing more to do.").foregroundStyle(WMColor.textSecondary)
                        PrimaryButton(title: "End this wake session") { finish("session_ended") }
                    }
                    .padding(WMSpacing.lg)
                }
                .background(WMColor.surface)
            } else {
                ProgressView().frame(maxWidth: .infinity, maxHeight: .infinity).background(WMColor.surface)
            }
        }
        .task {
            if let revision = WakeAlarms.shared.installedRoutine(planId: pending.planId)?.challengeRevisionId {
                definition = await ChallengeStore.shared.definition(revision)
            }
            loaded = true
        }
    }

    private func finish(_ outcome: String) {
        WakeAlarms.shared.cancelFollowUp(planId: pending.planId, occurrenceKey: pending.occurrenceKey)
        let session = pending
        let version = WakeAlarms.shared.installedRoutine(planId: session.planId)?.version
        WakeSessionRouter.shared.pending = nil
        guard let version else { return }
        Task { try? await app.api.reportOccurrence(planId: session.planId, occurrenceKey: session.occurrenceKey, version: version, outcome: outcome) }
    }
}

/// Challenges you made or that are shared in your circles. Try one any time; practice runs
/// are local and change nothing. Making challenges is on Android for now.
struct ChallengesView: View {
    @Environment(AppModel.self) private var app
    @State private var challenges: [WakeMeAPI.ChallengeSummary] = []
    @State private var loaded = false
    @State private var practice: PracticeRun?
    @State private var message: String?

    struct PracticeRun: Identifiable { let id: String; let definition: ChallengeDefinition }

    var body: some View {
        List {
            let ready = challenges.filter { $0.publishedRevisionId != nil }
            if loaded && ready.isEmpty {
                Section {
                    Text("No challenges yet. Quizzes and puzzles made on Android, or shared in your circles, appear here.")
                        .foregroundStyle(WMColor.textSecondary)
                }
            }
            if !ready.isEmpty {
                Section("Try one") {
                    ForEach(ready) { c in
                        Button(c.title) { Task { await open(c) } }.frame(minHeight: WMSize.minTouchTarget)
                    }
                }
            }
        }
        .scrollContentBackground(.hidden)
        .background(WMColor.surface)
        .navigationTitle("Challenges")
        .refreshable { await load() }
        .task { await load() }
        .fullScreenCover(item: $practice) { run in
            ChallengeRunnerView(definition: run.definition, escapeLabel: "Close", onComplete: { _ in practice = nil }, onEscape: { practice = nil })
        }
        .alert(message ?? "", isPresented: Binding(get: { message != nil }, set: { if !$0 { message = nil } })) { Button("OK") { message = nil } }
    }

    private func load() async {
        if let list = try? await app.api.challenges() { challenges = list }
        loaded = true
    }

    private func open(_ c: WakeMeAPI.ChallengeSummary) async {
        guard let revision = c.publishedRevisionId else { return }
        if await ChallengeStore.shared.ensureInstalled(revision, api: app.api), let definition = await ChallengeStore.shared.definition(revision) {
            practice = PracticeRun(id: revision, definition: definition)
        } else {
            message = "This challenge couldn't be checked and installed. Try again when you're connected."
        }
    }
}
