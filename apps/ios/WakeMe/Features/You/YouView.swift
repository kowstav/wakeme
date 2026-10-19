import SwiftUI
import WakeMeAPI
import WakeMeCore

/// You: readiness, consent settings and account. Every control works without a subscription.
struct YouView: View {
    @Environment(AppModel.self) private var app
    @State private var confirmSignOut = false
    @State private var message: String?

    var body: some View {
        List {
            if let me = app.me {
                Section {
                    Text(me.displayName).font(.title2.bold()).foregroundStyle(WMColor.textPrimary)
                    Text(me.timeZone).foregroundStyle(WMColor.textSecondary)
                }
                Section("This phone") {
                    NavigationLink("Make this phone ready") { ReadyView() }
                }
                Section("When friends can reach you") {
                    Toggle("Quiet hours (\(me.settings.quietHours.start)–\(me.settings.quietHours.end))", isOn: Binding(
                        get: { me.settings.quietHours.enabled },
                        set: { on in Task { await update(.init(quietHours: QuietHoursDTO(enabled: on, start: me.settings.quietHours.start, end: me.settings.quietHours.end))) } }
                    ))
                    if let until = me.settings.pausedUntil, let date = try? Instant.parse(until), date > Date() {
                        StatusPill(text: "Requests paused until \(date.formatted(date: .abbreviated, time: .shortened))", tone: .info)
                        Button("Resume now") { Task { await pause(nil) } }
                    } else {
                        Button("Pause all requests for 8 hours") { Task { await pause(Date().addingTimeInterval(8 * 3600)) } }
                    }
                    Text("Your own alarms still ring while requests are paused.").font(.footnote).foregroundStyle(WMColor.textSecondary)
                }
                Section("Subscription") {
                    Text("AI credits: \(me.billing.credits.available) available").foregroundStyle(WMColor.textPrimary)
                    Text("Routines \(me.limits.activeRoutines.used)/\(me.limits.activeRoutines.max) · Custom sounds \(me.limits.customSounds.used)/\(me.limits.customSounds.max)")
                        .foregroundStyle(WMColor.textSecondary)
                }
            }
            Section { Button("Sign out", role: .destructive) { confirmSignOut = true } }
        }
        .navigationTitle("You")
        .task { await app.refreshMe() }
        .confirmationDialog("Sign out?", isPresented: $confirmSignOut) {
            Button("Sign out", role: .destructive) { Task { await app.signOut() } }
        } message: {
            Text("Alarms installed on this phone are removed when you sign out.")
        }
        .alert(message ?? "", isPresented: Binding(get: { message != nil }, set: { if !$0 { message = nil } })) { Button("OK") { message = nil } }
    }

    private func update(_ patch: WakeMeAPI.MePatch) async {
        do { app.me = try await app.api.updateMe(patch) } catch { message = (error as? APIError)?.message ?? "You're offline. Nothing was changed." }
    }

    private func pause(_ until: Date?) async {
        do { app.me = try await app.api.setPaused(until: until) } catch { message = (error as? APIError)?.message ?? "You're offline. Nothing was changed." }
    }
}
