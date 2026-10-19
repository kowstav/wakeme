import SwiftUI

@main
struct WakeMeApp: App {
    var body: some Scene {
        WindowGroup {
            RootView()
                .tint(WMColor.accentText)
        }
    }
}

/// Top-level routing. Mirrors the Android gates: welcome → sign-in → profile → main.
struct RootView: View {
    enum Route: Hashable { case signIn, selfSetup }
    @State private var path: [Route] = []

    var body: some View {
        NavigationStack(path: $path) {
            WelcomeView(onInvite: { path.append(.signIn) }, onSelfSetup: { path.append(.selfSetup) })
                .navigationDestination(for: Route.self) { route in
                    switch route {
                    case .selfSetup:
                        ReadyView()
                    case .signIn:
                        PlaceholderView(title: "Sign in", message: "Sign-in arrives in the next build.")
                    }
                }
        }
    }
}

struct PlaceholderView: View {
    let title: String
    let message: String
    var body: some View {
        VStack(spacing: WMSpacing.md) {
            Text(title).font(.title.bold()).foregroundStyle(WMColor.textPrimary)
            Text(message).foregroundStyle(WMColor.textSecondary)
        }
        .padding(WMSpacing.md)
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(WMColor.surface)
    }
}
