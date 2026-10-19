import SwiftUI
import UserNotifications
import WakeMeAPI
import WakeMeCore

struct FriendsView: View {
    @Environment(AppModel.self) private var app
    @State private var friends: [FriendDTO] = []
    @State private var loaded = false
    @State private var invite: InviteCreatedDTO?
    @State private var code = ""
    @State private var message: String?

    var body: some View {
        List {
            if loaded && friends.isEmpty {
                Section {
                    VStack(alignment: .leading, spacing: WMSpacing.xs) {
                        Text("Invite someone you trust").font(.headline).foregroundStyle(WMColor.textPrimary)
                        Text("Send a private link. They choose what you can do on their phone, and you choose what they can do on yours.")
                            .foregroundStyle(WMColor.textSecondary)
                    }
                }
            }
            if !friends.isEmpty {
                Section("Friends") {
                    ForEach(friends) { f in
                        NavigationLink(value: f.friendshipId) {
                            VStack(alignment: .leading, spacing: 4) {
                                Text(f.user.displayName).font(.headline).foregroundStyle(WMColor.textPrimary)
                                Text(summary(f)).font(.subheadline).foregroundStyle(WMColor.textSecondary)
                            }
                            .frame(minHeight: WMSize.minTouchTarget)
                        }
                    }
                }
            }
            Section {
                PrimaryButton(title: "Invite a friend") { Task { await createInvite() } }
                HStack {
                    TextField("Have a code?", text: $code)
                        .textInputAutocapitalization(.characters).autocorrectionDisabled()
                        .onChange(of: code) { _, v in if v.count > 11 { code = String(v.prefix(11)) } }
                    Button("Join") { Task { await redeem() } }.disabled(code.count < 9)
                }
            }
            if app.offline { Section { StatusPill(text: "Offline · showing nothing new. Your alarms still work.", tone: .warning) } }
        }
        .navigationTitle("Friends")
        .navigationDestination(for: String.self) { id in FriendDetailView(friendshipId: id) }
        .refreshable { await load() }
        .task { await load() }
        .sheet(item: $invite) { inv in InviteSheet(invite: inv) }
        .alert(message ?? "", isPresented: Binding(get: { message != nil }, set: { if !$0 { message = nil } })) { Button("OK") { message = nil } }
    }

    private func summary(_ f: FriendDTO) -> String {
        if f.state != "accepted" { return f.state == "blocked" ? "Blocked" : "Invite pending" }
        let can = (f.actions ?? []).filter(\.granted).map(\.capability)
        if can.contains(Capability.alarmRequest) { return "Can wake them up" }
        if can.contains(Capability.nudge) { return "Can send quacks" }
        return "No permissions from them yet"
    }

    private func load() async {
        do {
            friends = try await app.api.friends()
            app.offline = false
        } catch is OfflineError {
            app.offline = true
        } catch {
            message = (error as? APIError)?.message
        }
        loaded = true
    }

    private func createInvite() async {
        do { invite = try await app.api.createInvite() } catch { message = (error as? APIError)?.message ?? "Couldn't create an invite." }
    }

    private func redeem() async {
        do {
            _ = try await app.api.redeemInvite(token: nil, code: code)
            code = ""
            await load()
        } catch {
            message = (error as? APIError)?.message ?? "That code didn't work."
        }
    }
}


struct InviteSheet: View {
    let invite: InviteCreatedDTO
    @Environment(\.dismiss) private var dismiss
    var body: some View {
        NavigationStack {
            VStack(alignment: .leading, spacing: WMSpacing.md) {
                Text("Share your invite").font(.title2.bold()).foregroundStyle(WMColor.textPrimary)
                Text("If the link doesn't open the app, they can type this code:").foregroundStyle(WMColor.textSecondary)
                Text(invite.code).font(.system(.title, design: .monospaced).bold()).foregroundStyle(WMColor.textPrimary).textSelection(.enabled)
                Text("It works once and expires soon. Opening it gives you no permissions.").foregroundStyle(WMColor.textSecondary)
                ShareLink(item: "Let's wake each other up on Wake Me: \(invite.url) (code \(invite.code))") {
                    Text("Share").font(.headline).frame(maxWidth: .infinity, minHeight: WMSize.minTouchTarget)
                }
                .buttonStyle(.borderedProminent).tint(WMColor.accent).foregroundStyle(WMColor.textOnAccent)
                Spacer()
            }
            .padding(WMSpacing.md)
            .toolbar { ToolbarItem(placement: .cancellationAction) { Button("Close") { dismiss() } } }
        }
    }
}

/// A friend: send a quack (only what they allowed), see literal delivery status.
struct FriendDetailView: View {
    @Environment(AppModel.self) private var app
    let friendshipId: String
    @State private var friend: FriendDTO?
    @State private var lastCommand: CommandDTO?
    @State private var sending = false
    @State private var message: String?
    @State private var askNotifications = false

    var body: some View {
        ScrollView {
            if let f = friend {
                VStack(alignment: .leading, spacing: WMSpacing.md) {
                    Text(f.user.displayName).font(.largeTitle.bold()).foregroundStyle(WMColor.textPrimary)
                    let quack = f.actions?.first { $0.capability == Capability.nudge }
                    WMCard {
                        Text("Quack").font(.title3.bold()).foregroundStyle(WMColor.textPrimary)
                        SecondaryButton(title: "Preview on my phone") { QuackPreview.shared.play() }
                        if let quack, quack.granted, quack.availableNow {
                            PrimaryButton(title: sending ? "Sending…" : "Send quack") { Task { await maybeAskThenSend(f) } }
                                .disabled(sending)
                        } else {
                            Text(reasonText(f.user.displayName, quack?.reason ?? "capability_not_granted")).foregroundStyle(WMColor.textSecondary)
                        }
                        if let c = lastCommand { StatusPill(text: statusText(c.displayStatus), tone: .info) }
                    }
                    Text("What \(f.user.displayName) can do on your phone is up to you. Circle membership never changes it.")
                        .foregroundStyle(WMColor.textSecondary)
                }
                .padding(WMSpacing.md).frame(maxWidth: 640).frame(maxWidth: .infinity)
            } else {
                ProgressView().padding()
            }
        }
        .background(WMColor.surface)
        .task { await load() }
        .alert("Turn on notifications?", isPresented: $askNotifications) {
            Button("Continue") { Task { await requestNotificationsThenSend() } }
            Button("Not now", role: .cancel) { Task { await send() } }
        } message: {
            Text("So you can see replies and hear quacks you've allowed. You choose which friends can make a sound, and when.")
        }
        .alert(message ?? "", isPresented: Binding(get: { message != nil }, set: { if !$0 { message = nil } })) { Button("OK") { message = nil } }
    }

    private func load() async {
        friend = try? await app.api.friend(friendshipId)
    }

    /// Doc 03 §5 step 6: explain before the system prompt, at the first nudge. Declining never blocks sending.
    private func maybeAskThenSend(_ f: FriendDTO) async {
        let settings = await UNUserNotificationCenter.current().notificationSettings()
        if settings.authorizationStatus == .notDetermined { askNotifications = true } else { await send() }
    }

    private func requestNotificationsThenSend() async {
        _ = try? await UNUserNotificationCenter.current().requestAuthorization(options: [.alert, .sound, .badge])
        await app.reportCapabilities()
        await send()
    }

    private func send() async {
        guard let f = friend else { return }
        sending = true
        defer { sending = false }
        let sound = f.theyAllowMe?.limits.nudge?.sounds.first ?? "bundled:quack.classic"
        do {
            lastCommand = try await app.api.send(.init(targetUserId: f.user.id, capability: Capability.nudge, expectedGrantVersion: f.theyAllowMe?.version, soundId: sound))
        } catch let e as APIError {
            message = e.message
        } catch {
            message = "You're offline. Nothing was sent."
        }
    }
}
