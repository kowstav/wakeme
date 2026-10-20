import SwiftUI
import WakeMeCore

/// Personal wake-ups without an account (doc 02 §9, doc 03 §5 step 2), mirroring Android's
/// GuestHome: make this phone ready, set wake-ups that live only here, and sign in when friends
/// should help. Nothing here needs the network.
struct GuestView: View {
    @Environment(AppModel.self) private var app
    @Environment(\.scenePhase) private var scenePhase
    @State private var routines: [InstalledRoutine] = []
    @State private var creating = false

    var body: some View {
        NavigationStack {
            List {
                Section {
                    Text("They live on this phone only and ring without a connection. Sign in when you want friends to help.")
                        .foregroundStyle(WMColor.textSecondary)
                }
                if !routines.isEmpty {
                    Section("On this phone") {
                        ForEach(routines, id: \.planId) { r in
                            VStack(alignment: .leading, spacing: WMSpacing.xs) {
                                Text("\(r.localTime) · \(daysText(r.recurrence))").font(.title3).foregroundStyle(WMColor.textPrimary)
                                if !r.label.isEmpty { Text(r.label).foregroundStyle(WMColor.textSecondary) }
                                if WakeAlarms.shared.authorization == .authorized && WakeAlarms.shared.armedRoutine(planId: r.planId) != nil {
                                    StatusPill(text: "Ready on this phone", tone: .success)
                                } else {
                                    StatusPill(text: "Not scheduled: allow alarms first", tone: .warning)
                                }
                            }
                            .swipeActions { Button("Delete", role: .destructive) { LocalRoutines.shared.delete(r.planId); reload() } }
                        }
                    }
                }
                Section {
                    PrimaryButton(title: "New wake-up") { creating = true }
                    NavigationLink("Make this phone ready") { ReadyView() }
                        .frame(minHeight: WMSize.minTouchTarget)
                }
                Section {
                    Text("Wake each other up").font(.headline).foregroundStyle(WMColor.textPrimary)
                    Text("Sign in to invite friends. Your wake-ups here move to your account and keep ringing on this phone.")
                        .foregroundStyle(WMColor.textSecondary)
                    SecondaryButton(title: "Sign in to add friends") { app.signInFromGuest() }
                }
            }
            .scrollContentBackground(.hidden)
            .background(WMColor.surface)
            .navigationTitle("Your wake-ups")
            .sheet(isPresented: $creating) {
                // Arms anything the first install missed (alarm access granted while it was saving).
                RoutineEditorView(guest: true) { Task { await armAndReload() } }
            }
            .task { await armAndReload() }
            .onChange(of: scenePhase) { _, phase in if phase == .active { Task { await armAndReload() } } }
        }
    }

    private func reload() {
        routines = LocalRoutines.shared.all().sorted { $0.localTime < $1.localTime }
    }

    /// Alarm access may have been granted in Settings since a wake-up was saved.
    private func armAndReload() async {
        await LocalRoutines.shared.armMissing()
        reload()
    }
}
