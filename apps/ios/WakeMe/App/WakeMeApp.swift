import SwiftUI

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
                MainTabs()
            }
        }
        .alert(app.error ?? "", isPresented: Binding(get: { app.error != nil }, set: { if !$0 { app.error = nil } })) {
            Button("OK") { app.error = nil }
        }
    }
}

/// Four tabs (doc 03 §3): Friends, Routines, Challenges, You.
struct MainTabs: View {
    var body: some View {
        TabView {
            Tab("Friends", systemImage: "person.2") { NavigationStack { FriendsView() } }
            Tab("Routines", systemImage: "alarm") { NavigationStack { RoutinesView() } }
            Tab("Challenges", systemImage: "trophy") {
                NavigationStack {
                    PlaceholderView(title: "Challenges", message: "Group challenges come in a later build. Wake-ups and quacks work now.")
                        .navigationTitle("Challenges")
                }
            }
            Tab("You", systemImage: "person.crop.circle") { NavigationStack { YouView() } }
        }
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
