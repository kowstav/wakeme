import SwiftUI
import UniformTypeIdentifiers
import WakeMeAPI
import WakeMeCore

/// You: readiness, consent settings and account. Every control works without a subscription.
struct YouView: View {
    @Environment(AppModel.self) private var app
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
                Section {
                    Text(me.displayName).font(.title2.bold()).foregroundStyle(WMColor.textPrimary)
                    Text(me.timeZone).foregroundStyle(WMColor.textSecondary)
                }
                Section("This phone") {
                    NavigationLink("Make this phone ready") { ReadyView() }
                }
                Section("Safety") {
                    NavigationLink("Safety check-ins and location") { SafetyView() }
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
                    Button(me.billing.plan == "plus" ? "Manage Plus" : "See Plus") { showPlus = true }
                        .frame(minHeight: WMSize.minTouchTarget)
                }
                Section("Your data") {
                    Button(working ? "Preparing…" : "Export my data") { Task { await export() } }.disabled(working)
                    Button("Delete account", role: .destructive) { confirmDelete = true }
                }
            }
            Section { Button("Sign out", role: .destructive) { confirmSignOut = true } }
        }
        .scrollContentBackground(.hidden)
        .background(WMColor.surface)
        .navigationTitle("You")
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
                    Text("For your safety, confirm it's you. This keeps someone holding your unlocked phone from deleting your account.")
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
