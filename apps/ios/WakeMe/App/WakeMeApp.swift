import SwiftUI
import WakeMeAPI
import WakeMeCore

@main
struct WakeMeApp: App {
    @UIApplicationDelegateAdaptor(PushCenter.self) private var pushCenter
    @State private var app = AppModel()
    @Environment(\.scenePhase) private var scenePhase

    init() {
        #if DEBUG
        // UI tests start from a clean install state.
        if ProcessInfo.processInfo.arguments.contains("-uiTestReset") {
            for key in ["auth.refresh", "auth.subject", "device.id"] { Keychain.delete(key) }
            if let id = Bundle.main.bundleIdentifier { UserDefaults.standard.removePersistentDomain(forName: id) }
        }
        #endif
    }

    var body: some Scene {
        let model = app
        WindowGroup {
            RootView()
                .environment(app)
                .tint(WMColor.accentText)
                .task { await app.start() }
        }
        .onChange(of: scenePhase) { _, phase in
            switch phase {
            // Catch up on changes from elsewhere and top up the armed window.
            case .active where model.gate == .main: Task { await SyncEngine.shared.sync(model) }
            case .background: SyncEngine.scheduleRefresh()
            default: break
            }
        }
        .backgroundTask(.appRefresh(SyncEngine.refreshTaskId)) {
            await SyncEngine.shared.backgroundRefresh(model)
        }
    }
}

/// Routes by gate. Mirrors the Android WakeMeRoot.
struct RootView: View {
    @Environment(AppModel.self) private var app

    var body: some View {
        Group {
            #if DEBUG
            if ProcessInfo.processInfo.arguments.contains("-uiTestChallengeDemo") {
                ChallengeDemoView()
            } else {
                gated
            }
            #else
            gated
            #endif
        }
        .alert(app.error ?? "", isPresented: Binding(get: { app.error != nil }, set: { if !$0 { app.error = nil } })) {
            Button("OK") { app.error = nil }
        }
        // The alarm's "Start challenge" button lands here, over whatever is on screen.
        .fullScreenCover(item: Binding(get: { WakeSessionRouter.shared.pending }, set: { WakeSessionRouter.shared.pending = $0 })) { pending in
            WakeSessionView(pending: pending).environment(app)
        }
    }

    @ViewBuilder private var gated: some View {
        Group {
            switch app.gate {
            case .loading:
                ProgressView().frame(maxWidth: .infinity, maxHeight: .infinity).background(WMColor.surface)
            case .welcome:
                WelcomeView(onInvite: { app.chooseInvite() }, onSelfSetup: { app.chooseSelfSetup() })
            case .guest:
                GuestView()
            case .signIn:
                SignInView()
            case .profile:
                ProfileView()
            case .ready:
                NavigationStack {
                    ReadyView()
                        .toolbar {
                            ToolbarItem(placement: .confirmationAction) { Button("Continue") { app.finishSelfSetup() } }
                        }
                }
            case .main:
                switch app.me?.accountState ?? "active" {
                case "deletion_pending": DeletionPendingView()
                case "suspended": SuspendedView()
                default: MainTabs()
                }
            }
        }
    }
}

#if DEBUG
/// UI tests only (`-uiTestChallengeDemo`): the challenge runner with a fixed two-step challenge,
/// so its on-phone checking can be tested without a backend or an alarm.
struct ChallengeDemoView: View {
    @State private var done = false
    private static let definition: ChallengeDefinition? = try? JSONDecoder().decode(ChallengeDefinition.self, from: Data(#"""
    {"schemaVersion":1,"title":"Morning warm-up","kind":"sequence","difficulty":"easy","maxDurationSeconds":90,
     "steps":[{"type":"multiple_choice","id":"q1","prompt":"What do we call our Thursday dinner?","options":["Chaos curry","Brunch"],"correctIndex":0},
              {"type":"math","id":"q2","operands":[7,8],"operator":"+","answer":15}],
     "verification":"local_deterministic","fallback":{"type":"accessible_checkin"}}
    """#.utf8))

    var body: some View {
        if done {
            Text("Challenge complete").font(.largeTitle.bold()).frame(maxWidth: .infinity, maxHeight: .infinity).background(WMColor.surface)
        } else if let definition = Self.definition {
            ChallengeRunnerView(definition: definition, escapeLabel: "End this wake session", onComplete: { _ in done = true }, onEscape: { done = true })
        }
    }
}
#endif

/// Four tabs (doc 03 §3): Friends, Routines, Challenges, You.
struct MainTabs: View {
    var body: some View {
        TabView {
            Tab("Friends", systemImage: "person.2") { NavigationStack { FriendsView() } }
            Tab("Routines", systemImage: "alarm") { NavigationStack { RoutinesView() } }
            Tab("Challenges", systemImage: "trophy") { NavigationStack { ChallengesView() } }
            Tab("You", systemImage: "person.crop.circle") { NavigationStack { YouView() } }
        }
    }
}

/// Signed in during the deletion grace period: the account is deactivated, so the only
/// choices are keeping it (permissions stay revoked) or signing out.
struct DeletionPendingView: View {
    @Environment(AppModel.self) private var app
    @State private var working = false

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: WMSpacing.md) {
                Text("Your account is set to be deleted").font(.largeTitle.bold()).foregroundStyle(WMColor.textPrimary).accessibilityAddTraits(.isHeader)
                if let when = app.me?.deletionScheduledFor.flatMap({ try? Instant.parse($0) }) {
                    Text("It will be permanently deleted on \(when.formatted(date: .long, time: .omitted)).").foregroundStyle(WMColor.textSecondary)
                }
                Text("If you keep it, permissions you gave or received stay removed; friends can grant them again.").foregroundStyle(WMColor.textSecondary)
                PrimaryButton(title: working ? "Keeping…" : "Keep my account") { Task { await keep() } }.disabled(working)
                SecondaryButton(title: "Sign out") { Task { await app.signOut() } }
            }
            .padding(WMSpacing.md).frame(maxWidth: 640).frame(maxWidth: .infinity)
        }
        .background(WMColor.surface)
    }

    private func keep() async {
        working = true
        defer { working = false }
        do {
            app.me = try await app.api.cancelDeletion()
            await app.route()
        } catch {
            app.error = (error as? APIError)?.message ?? "You're offline. Try again when you're connected."
        }
    }
}

/// A suspended account can't send or receive requests (moderation). Alarms already installed
/// on this phone keep ringing; they never depend on the server.
struct SuspendedView: View {
    @Environment(AppModel.self) private var app

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: WMSpacing.md) {
                Text("This account is suspended").font(.largeTitle.bold()).foregroundStyle(WMColor.textPrimary).accessibilityAddTraits(.isHeader)
                Text("Friends can't send you requests and you can't send any. If you think this is a mistake, contact support@wakeme.app.")
                    .foregroundStyle(WMColor.textSecondary)
                SecondaryButton(title: "Sign out") { Task { await app.signOut() } }
            }
            .padding(WMSpacing.md).frame(maxWidth: 640).frame(maxWidth: .infinity)
        }
        .background(WMColor.surface)
    }
}

struct PlaceholderView: View {
    let title: String
    let message: String
    var body: some View {
        VStack(spacing: WMSpacing.md) {
            Text(title).font(.title.bold()).foregroundStyle(WMColor.textPrimary)
            Text(message).foregroundStyle(WMColor.textSecondary).multilineTextAlignment(.center)
        }
        .padding(WMSpacing.md)
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(WMColor.surface)
    }
}
