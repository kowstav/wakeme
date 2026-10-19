import SwiftUI
import WakeMeAPI
import WakeMeCore

@main
struct WakeMeApp: App {
    @State private var app = AppModel()

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
        WindowGroup {
            RootView()
                .environment(app)
                .tint(WMColor.accentText)
                .task { await app.start() }
        }
    }
}

/// Routes by gate. Mirrors the Android WakeMeRoot.
struct RootView: View {
    @Environment(AppModel.self) private var app

    var body: some View {
        Group {
            switch app.gate {
            case .loading:
                ProgressView().frame(maxWidth: .infinity, maxHeight: .infinity).background(WMColor.surface)
            case .welcome:
                WelcomeView(onInvite: { app.chooseInvite() }, onSelfSetup: { app.chooseSelfSetup() })
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
        .alert(app.error ?? "", isPresented: Binding(get: { app.error != nil }, set: { if !$0 { app.error = nil } })) {
            Button("OK") { app.error = nil }
        }
        // The alarm's "Start challenge" button lands here, over whatever is on screen.
        .fullScreenCover(item: Binding(get: { WakeSessionRouter.shared.pending }, set: { WakeSessionRouter.shared.pending = $0 })) { pending in
            WakeSessionView(pending: pending).environment(app)
        }
    }
}

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
