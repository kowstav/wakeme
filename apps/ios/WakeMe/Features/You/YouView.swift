import SwiftUI
import UniformTypeIdentifiers
import WakeMeAPI
import WakeMeCore

/// You: who you are, the places you go from here as a bento grid (each its own petal colour and
/// icon), consent settings and account. Every control works without a subscription.
struct YouView: View {
    @Environment(AppModel.self) private var app
    @State private var showSafety = false
    @State private var showFocus = false
    @State private var showReady = false
    @State private var confirmSignOut = false
    @State private var confirmDelete = false
    @State private var showPlus = false
    @State private var reauth = false
    @State private var exportFile: JSONFile?
    @State private var working = false
    @State private var message: String?

    var body: some View {
        List {
            if let me = app.me {
                HeroCard {
                HStack(spacing: WMSpacing.md) {
                    FriendAvatar(name: me.displayName, key: me.id, size: 64)
                    VStack(alignment: .leading, spacing: 2) {
                        Text(me.displayName).font(WMFont.section).foregroundStyle(WMColor.onHero)
                        HStack(spacing: WMSpacing.xxs) {
                            WMIconImage(.globe, size: 16)
                            Text(me.timeZone)
                        }
                        .font(WMFont.supporting)
                        .foregroundStyle(WMColor.onHeroSecondary)
                    }
                }
                }
                .bentoRow()
                BentoRow {
                    BentoTile(title: "Safety check-ins and location", icon: .shield, family: WMPalette.orchid) { showSafety = true }
                    BentoTile(title: "Focus", icon: .focus, family: WMPalette.aqua) { showFocus = true }
                }
                .bentoRow()
                BentoRow {
                    BentoTile(title: "Make this phone ready", icon: .phone, family: WMPalette.lime, supporting: "Alarm access and sound") { showReady = true }
                    BentoTile(title: me.billing.plan == "plus" ? "Manage Plus" : "Wake Me Plus", icon: .sparkle, family: WMPalette.gold, supporting: me.billing.plan == "plus" ? "Your subscription" : "See what's included") { showPlus = true }
                }
                .bentoRow()
                Group {
                    Section("When friends can reach you") {
                        Toggle(isOn: Binding(
                            get: { me.settings.quietHours.enabled },
                            set: { on in Task { await update(.init(quietHours: QuietHoursDTO(enabled: on, start: me.settings.quietHours.start, end: me.settings.quietHours.end))) } }
                        )) {
                            Label { Text("Quiet hours (\(me.settings.quietHours.start)–\(me.settings.quietHours.end))") } icon: { IconChip(icon: .moon, family: WMPalette.periwinkle, size: 32) }
                        }
                        if let until = me.settings.pausedUntil, let date = try? Instant.parse(until), date > Date() {
                            StatusPill(text: "Requests paused until \(date.formatted(date: .abbreviated, time: .shortened))", tone: .info, icon: .pause)
                            Button { Task { await pause(nil) } } label: {
                                Label { Text("Resume now") } icon: { IconChip(icon: .play, family: WMPalette.sky, size: 32) }
                            }
                        } else {
                            Button { Task { await pause(Date().addingTimeInterval(8 * 3600)) } } label: {
                                Label { Text("Pause all requests for 8 hours") } icon: { IconChip(icon: .hourglass, family: WMPalette.sky, size: 32) }
                            }
                        }
                    }
                    Section("Subscription") {
                        Text("AI credits: \(me.billing.credits.available) available").foregroundStyle(WMColor.textPrimary)
                        Text("Routines \(me.limits.activeRoutines.used)/\(me.limits.activeRoutines.max) · Custom sounds \(me.limits.customSounds.used)/\(me.limits.customSounds.max)")
                            .foregroundStyle(WMColor.textSecondary)
                    }
                    Section("Your data") {
                        Button { Task { await export() } } label: {
                            Label { Text(working ? "Preparing…" : "Export my data") } icon: { WMIconImage(.download, size: WMLayout.iconSizeSmall) }
                        }
                        .disabled(working)
                        Button(role: .destructive) { confirmDelete = true } label: {
                            Label { Text("Delete account") } icon: { WMIconImage(.trash, size: WMLayout.iconSizeSmall) }
                        }
                    }
                }
                .cardRows()
            }
            Section {
                Button(role: .destructive) { confirmSignOut = true } label: {
                    Label { Text("Sign out") } icon: { WMIconImage(.logout, size: WMLayout.iconSizeSmall) }
                }
            }
            .cardRows()
        }
        .scrollContentBackground(.hidden)
        .background(WMColor.surface)
        .navigationTitle("You")
        .navigationDestination(isPresented: $showSafety) { SafetyView() }
        .navigationDestination(isPresented: $showFocus) { FocusView() }
        .navigationDestination(isPresented: $showReady) { ReadyView() }
        .task { await app.refreshMe() }
        .sheet(isPresented: $showPlus) { PaywallView() }
        .confirmationDialog("Sign out?", isPresented: $confirmSignOut) {
            Button("Sign out", role: .destructive) { Task { await app.signOut() } }
        } message: {
            Text("Alarms installed on this phone are removed when you sign out.")
        }
        .alert("Delete your account?", isPresented: $confirmDelete) {
            Button("Delete account", role: .destructive) { Task { await delete() } }
            Button("Cancel", role: .cancel) {}
        } message: {
            Text("Your account is deactivated now and permanently deleted after 7 days. Every permission you gave friends is revoked straight away, and alarms on this phone are removed. You can change your mind by signing in within 7 days. Cancel a subscription separately in the App Store.")
        }
        .sheet(isPresented: $reauth) {
            ConfirmIdentityView { Task { await delete() } }
        }
        .fileExporter(isPresented: Binding(get: { exportFile != nil }, set: { if !$0 { exportFile = nil } }), document: exportFile, contentType: .json, defaultFilename: "wakeme-export.json") { result in
            if case .failure = result { message = "The file wasn't saved." }
            exportFile = nil
        }
        .alert(message ?? "", isPresented: Binding(get: { message != nil }, set: { if !$0 { message = nil } })) { Button("OK") { message = nil } }
    }

    private func update(_ patch: WakeMeAPI.MePatch) async {
        do { app.me = try await app.api.updateMe(patch) } catch { message = (error as? APIError)?.message ?? "You're offline. Nothing was changed." }
    }

    private func pause(_ until: Date?) async {
        do { app.me = try await app.api.setPaused(until: until) } catch { message = (error as? APIError)?.message ?? "You're offline. Nothing was changed." }
    }

    private func export() async {
        working = true
        defer { working = false }
        do {
            exportFile = JSONFile(data: try await app.api.exportAccount())
        } catch {
            message = (error as? APIError)?.message ?? "You're offline. Try the export again when you're connected."
        }
    }

    /// Deletion needs a sign-in within the last 15 minutes; otherwise confirm identity first.
    private func delete() async {
        do {
            let r = try await app.api.deleteAccount()
            let date = (try? Instant.parse(r.deletionScheduledFor))?.formatted(date: .long, time: .omitted) ?? "in 7 days"
            await app.signOut()
            app.error = "Your account is deactivated and will be deleted on \(date). Sign in before then to keep it."
        } catch let e as APIError where e.code == "reauth_required" {
            reauth = true
        } catch {
            message = (error as? APIError)?.message ?? "You're offline. Nothing was deleted."
        }
    }
}

/// A JSON file for the system file exporter (the export is saved where the person chooses).
struct JSONFile: FileDocument {
    static let readableContentTypes: [UTType] = [.json]
    let data: Data
    init(data: Data) { self.data = data }
    init(configuration: ReadConfiguration) throws { data = configuration.file.regularFileContents ?? Data() }
    func fileWrapper(configuration: WriteConfiguration) throws -> FileWrapper { FileWrapper(regularFileWithContents: data) }
}

/// Confirms it's really you before a high-impact action: a fresh email code (or, in debug
/// builds without Supabase, a fresh local test token), then runs `onConfirmed`.
struct ConfirmIdentityView: View {
    @Environment(\.dismiss) private var dismiss
    let onConfirmed: () -> Void
    @State private var email = ""
    @State private var code = ""
    @State private var sent = false
    @State private var working = false
    @State private var message: String?

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: WMSpacing.md) {
                    Text("Confirm it's you.")
                        .foregroundStyle(WMColor.textSecondary)
                    if AppConfig.supabaseConfigured {
                        TextField("Email", text: $email)
                            .textContentType(.emailAddress).keyboardType(.emailAddress).textInputAutocapitalization(.never).autocorrectionDisabled()
                            .padding(WMSpacing.sm).background(WMColor.surfaceSunken, in: RoundedRectangle(cornerRadius: WMRadius.sm))
                        if !sent {
                            SecondaryButton(title: working ? "Sending…" : "Send code") { Task { await send() } }.disabled(!email.contains("@") || working)
                        } else {
                            TextField("Code from the email", text: $code)
                                .textContentType(.oneTimeCode).keyboardType(.numberPad)
                                .padding(WMSpacing.sm).background(WMColor.surfaceSunken, in: RoundedRectangle(cornerRadius: WMRadius.sm))
                            PrimaryButton(title: "Confirm") { Task { await verify() } }.disabled(code.count < 6 || working)
                        }
                    } else if AppConfig.useDevAuth {
                        SecondaryButton(title: "Confirm with the local test account (debug build)") { Task { await devConfirm() } }
                    } else {
                        Text("Sign-in isn't configured for this build.").foregroundStyle(WMColor.textSecondary)
                    }
                }
                .padding(WMSpacing.md)
            }
            .background(WMColor.surface)
            .navigationTitle("Confirm it's you")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .cancellationAction) { Button("Cancel") { dismiss() } } }
            .alert(message ?? "", isPresented: Binding(get: { message != nil }, set: { if !$0 { message = nil } })) { Button("OK") { message = nil } }
        }
    }

    private func send() async {
        working = true
        defer { working = false }
        do {
            try await Session.shared.requestEmailCode(email)
            sent = true
        } catch {
            message = error.localizedDescription
        }
    }

    private func verify() async {
        working = true
        defer { working = false }
        do {
            try await Session.shared.confirmEmailCode(email, code: code)
            dismiss()
            onConfirmed()
        } catch {
            message = error.localizedDescription
        }
    }

    private func devConfirm() async {
        do {
            try await Session.shared.devSignIn()
            dismiss()
            onConfirmed()
        } catch {
            message = error.localizedDescription
        }
    }
}
