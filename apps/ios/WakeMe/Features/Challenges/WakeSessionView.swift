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
    /// Finishing is the morning's best moment, so it gets an ending of its own (peak-end rule).
    @State private var celebrating = false

    var body: some View {
        Group {
            if celebrating {
                YoureUpView { WakeSessionRouter.shared.pending = nil }
            } else if let definition {
                ChallengeRunnerView(definition: definition, escapeLabel: "End this wake session",
                                    onComplete: { _ in
                                        record("challenge_completed")
                                        celebrating = true
                                    },
                                    onEscape: { finish("bypassed") })
            } else if loaded {
                ScrollView {
                    VStack(alignment: .leading, spacing: WMSpacing.md) {
                        Text("Wake-up").font(WMFont.screenTitle).foregroundStyle(WMColor.textPrimary)
                        Text("Nothing more to do.").foregroundStyle(WMColor.textSecondary)
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
        record(outcome)
        WakeSessionRouter.shared.pending = nil
    }

    /// Cancels the follow-up at once and queues the outcome (sent later if offline).
    private func record(_ outcome: String) {
        WakeAlarms.shared.cancelFollowUp(planId: pending.planId, occurrenceKey: pending.occurrenceKey)
        let session = pending
        guard let version = WakeAlarms.shared.installedRoutine(planId: session.planId)?.version else { return }
        let api = app.api
        Task { await ReceiptQueue.shared.enqueue(.init(planId: session.planId, occurrenceKey: session.occurrenceKey, version: version, outcome: outcome), api: api) }
    }
}

/// The end of a finished wake-up: a happy face that hops and calls once, "You're up.", and Done.
/// Nothing is reported here (the outcome was already queued); with Reduce Motion it doesn't hop.
struct YoureUpView: View {
    let onDone: () -> Void
    @State private var hop = false
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    private let sound = AnimalSounds.all.randomElement() ?? AnimalSounds.all[0]

    var body: some View {
        VStack(spacing: WMSpacing.lg) {
            Spacer()
            sound.face.image
                .resizable()
                .scaledToFit()
                .frame(width: 140, height: 140)
                .offset(y: hop && !reduceMotion ? -16 : 0)
                .animation(reduceMotion ? nil : .spring(response: 0.35, dampingFraction: 0.45), value: hop)
                .accessibilityHidden(true)
            DisplayText("You're up.", alignment: .center)
            Spacer()
            PrimaryButton(title: "Done", action: onDone)
        }
        .padding(WMSpacing.lg)
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(WMColor.surface)
        .sensoryFeedback(.success, trigger: hop)
        .task {
            SoundPreview.shared.play(sound.id)
            hop = true
            try? await Task.sleep(for: .milliseconds(350))
            hop = false
        }
    }
}
