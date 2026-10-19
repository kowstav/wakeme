import SwiftUI
import WakeMeAPI
import WakeMeCore

/// Opened from the alarm's "Start challenge" button. Runs the routine's installed challenge
/// with no network. Finishing it, or "End this wake session", cancels the follow-up alarm at
/// once; then the outcome is queued and reported, later if offline. The system Stop alone never
/// counts as completion.
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
        let api = app.api
        Task { await ReceiptQueue.shared.enqueue(.init(planId: session.planId, occurrenceKey: session.occurrenceKey, version: version, outcome: outcome), api: api) }
    }
}
