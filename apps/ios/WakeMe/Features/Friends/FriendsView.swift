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

    @State private var nextAlarm: (fireAt: Date, label: String)?
    @State private var pendingInvite: InvitePreviewDTO?
    @Environment(\.scenePhase) private var scenePhase
    /// The friend's row zooms into their page (system zoom transition; standard push with Reduce Motion).
    @Namespace private var friendZoom

    var body: some View {
        List {
            // Doc 03 §3: home shows the next alarm on this phone and, at most, one readiness issue.
            if let nextAlarm {
                Section {
                    VStack(alignment: .leading, spacing: WMSpacing.xs) {
                        Text("Next alarm on this phone").font(.subheadline.weight(.medium)).foregroundStyle(WMColor.textSecondary)
                        Text(nextAlarmText(nextAlarm.fireAt, label: nextAlarm.label)).font(.title2.weight(.semibold)).monospacedDigit().foregroundStyle(WMColor.textPrimary)
                        if WakeAlarms.shared.authorization != .authorized {
                            StatusPill(text: "Alarm access is off, so it can't ring.", tone: .warning)
                            SecondaryButton(title: "Open Settings") {
                                if let url = URL(string: UIApplication.openSettingsURLString) { UIApplication.shared.open(url) }
                            }
                        }
                    }
                    .accessibilityElement(children: .contain)
                }
            }
            if loaded && friends.isEmpty {
                Section {
                    EmptyState(title: "Invite someone you trust",
                               message: "Send a private link. They choose what you can do on their phone, and you choose what they can do on yours.")
                }
            }
            if !friends.isEmpty {
                Section("Friends") {
                    ForEach(friends) { f in
                        NavigationLink(value: f.friendshipId) {
                            HStack(spacing: WMSpacing.sm) {
                                FriendAvatar(name: f.user.displayName, key: f.user.id)
                                VStack(alignment: .leading, spacing: 2) {
                                    Text(f.user.displayName).font(WMFont.cardTitle).foregroundStyle(WMColor.textPrimary)
                                    Text(summary(f)).font(WMFont.supporting).foregroundStyle(WMColor.textSecondary)
                                }
                            }
                            .frame(minHeight: WMSize.minTouchTarget)
                        }
                        .matchedTransitionSource(id: f.friendshipId, in: friendZoom)
                    }
                }
            }
            CirclesSection()
            Section {
                PrimaryButton(title: "Invite a friend") { Task { await createInvite() } }
                HStack {
                    TextField("Have a code?", text: $code)
                        .textInputAutocapitalization(.characters).autocorrectionDisabled()
                        .onChange(of: code) { _, v in if v.count > 11 { code = String(v.prefix(11)) } }
                    Button("Join") { Task { await preview() } }.disabled(code.count < 9)
                }
            }
            if app.offline { Section { StatusPill(text: "Offline · showing nothing new. Your alarms still work.", tone: .warning) } }
        }
        .scrollContentBackground(.hidden)
        .background(WMColor.surface)
        .navigationTitle("Friends")
        .navigationDestination(for: String.self) { id in
            FriendDetailView(friendshipId: id).wmZoomTransition(id: id, in: friendZoom)
        }
        .refreshable { await load() }
        .task { await load() }
        .onChange(of: scenePhase) { _, phase in if phase == .active { nextAlarm = WakeAlarms.shared.nextMainAlarm() } }
        .sheet(item: $invite) { inv in InviteSheet(invite: inv) }
        .alert(inviteTitle, isPresented: Binding(get: { pendingInvite != nil }, set: { if !$0 { pendingInvite = nil } })) {
            if let p = pendingInvite, !p.ownInvite, !p.alreadyConnected {
                Button("Accept") { pendingInvite = nil; Task { await redeem() } }
                Button("Not now", role: .cancel) { pendingInvite = nil }
            } else {
                Button("OK") { pendingInvite = nil; code = "" }
            }
        } message: {
            Text(inviteMessage)
        }
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
        nextAlarm = WakeAlarms.shared.nextMainAlarm()
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

    private var inviteTitle: String {
        guard let p = pendingInvite else { return "" }
        return p.kind == "circle" ? "\(p.inviter.displayName) invited you to \(p.circle?.name ?? "a circle")" : "\(p.inviter.displayName) wants to quack you"
    }

    private var inviteMessage: String {
        guard let p = pendingInvite else { return "" }
        if p.ownInvite { return "This is your own invite. Share it with a friend." }
        if p.alreadyConnected { return "You're already connected." }
        return p.kind == "circle"
            ? "Joining doesn't give anyone in it any permissions on your phone."
            : "Accepting doesn't give \(p.inviter.displayName) any permissions. Next you choose exactly what they can do, if anything."
    }

    /// Doc 03 §5 step 4: say who invited you, and what accepting means, before anything happens.
    private func preview() async {
        do {
            pendingInvite = try await app.api.previewInvite(token: nil, code: code)
        } catch {
            message = (error as? APIError)?.message ?? "That code didn't work."
        }
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
                    .accessibilityIdentifier("inviteCode")
                Text(invite.kind == "circle"
                     ? "It expires soon. Joining the circle doesn't make anyone friends or give any permissions."
                     : "It works once and expires soon. Accepting makes you friends and nothing more: each of you chooses what the other can do.").foregroundStyle(WMColor.textSecondary)
                ShareLink(item: "Let's wake each other up on Wake Me: \(invite.url) (code \(invite.code))") {
                    Text("Share").font(.headline).frame(maxWidth: .infinity, minHeight: WMSize.minTouchTarget)
                }
                .buttonStyle(.borderedProminent).tint(WMColor.accent).foregroundStyle(WMColor.textOnAccent)
                Spacer()
            }
            .padding(.horizontal, WMLayout.screenMargin).padding(.vertical, WMSpacing.md)
            .toolbar { ToolbarItem(placement: .cancellationAction) { Button("Close") { dismiss() } } }
        }
    }
}

/// A friend: send what they allowed (a quack, a wake alarm, a suggested wake-up), see literal
/// delivery status, message them, and decide what they can do on my phone.
struct FriendDetailView: View {
    @Environment(AppModel.self) private var app
    let friendshipId: String
    @State private var friend: FriendDTO?
    @State private var suggested: [PlanDTO] = []
    @State private var lastCommand: CommandDTO?
    @State private var quackSound: String?
    @State private var sending: String?
    @State private var message: String?
    @State private var askNotifications = false
    @State private var editing = false
    @State private var suggesting = false
    @State private var confirmBlock = false
    @State private var confirmRemove = false
    /// Bumped on each send result: haptics (doc 03 §2) and the quack animation follow them.
    @State private var sentTick = 0
    @State private var failTick = 0
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        ScrollView {
            if let f = friend {
                let name = f.user.displayName
                VStack(alignment: .leading, spacing: WMSpacing.md) {
                    HStack(spacing: WMSpacing.md) {
                        FriendAvatar(name: name, key: f.user.id, size: WMLayout.avatarHeader)
                        VStack(alignment: .leading, spacing: WMSpacing.xxs) {
                            Text(name).font(.largeTitle.bold()).foregroundStyle(WMColor.textPrimary).accessibilityAddTraits(.isHeader)
                            if f.actions?.first(where: { $0.capability == Capability.nudge })?.availableNow == true {
                                StatusPill(text: "Quack available now", tone: .success)
                            }
                        }
                    }
                    quackCard(f)
                    wakeCard(f)
                    if let c = lastCommand {
                        WMCard {
                            Text("Last request").font(.headline).foregroundStyle(WMColor.textPrimary)
                            StatusPill(text: statusText(c.displayStatus), tone: statusTone(c.displayStatus), announce: true)
                            if c.capability == Capability.nudge || c.capability == Capability.alarmRequest {
                                DeliveryTrailView(displayStatus: c.displayStatus, alarm: c.capability == Capability.alarmRequest)
                            }
                        }
                    }
                    if f.theyAllowMe?.capabilities.contains(Capability.alarmSchedule) == true || !suggested.isEmpty { suggestCard(f) }
                    if let conversation = f.conversationId {
                        NavigationLink {
                            ConversationView(conversationId: conversation, friendName: name)
                        } label: {
                            Label("Messages", systemImage: "bubble.left.and.bubble.right")
                                .frame(maxWidth: .infinity, minHeight: WMSize.minTouchTarget, alignment: .leading)
                        }
                    }
                    permissionsCard(f)
                    Text("What \(name) can do on your phone is up to you. Circle membership never changes it.")
                        .foregroundStyle(WMColor.textSecondary)
                    SecondaryButton(title: "Remove \(name) as a friend") { confirmRemove = true }
                    SecondaryButton(title: "Block or report \(name)") { confirmBlock = true }
                }
                .padding(.horizontal, WMLayout.screenMargin).padding(.vertical, WMSpacing.md).frame(maxWidth: WMLayout.readableWidth).frame(maxWidth: .infinity)
                .sheet(isPresented: $suggesting) { RoutineEditorView(friend: f, onSaved: { await load() }) }
            } else {
                ProgressView().padding()
            }
        }
        .background(WMColor.surface)
        .task { await load() }
        // While a request is on its way, show what their phone reports, in words.
        .task(id: lastCommand?.id) { await followLastCommand() }
        .sensoryFeedback(WMHaptic.sent, trigger: sentTick)
        .sensoryFeedback(WMHaptic.failed, trigger: failTick)
        .sheet(isPresented: $editing) {
            if let f = friend { GrantEditorView(friend: f, onSaved: { await load() }) }
        }
        .confirmationDialog("Block \(friend?.user.displayName ?? "this friend")?", isPresented: $confirmBlock, titleVisibility: .visible) {
            Button("Block and report", role: .destructive) { Task { await block(report: true) } }
            Button("Block only", role: .destructive) { Task { await block(report: false) } }
        } message: {
            Text("Every permission between you ends at once, in both directions, and your conversation closes. They aren't told that you reported them.")
        }
        .alert("Remove \(friend?.user.displayName ?? "this friend")?", isPresented: $confirmRemove) {
            Button("Remove", role: .destructive) { Task { await remove() } }
            Button("Cancel", role: .cancel) {}
        } message: {
            Text("Permissions between you end, and routines they suggested stop. You can invite each other again later.")
        }
        .alert("Turn on notifications?", isPresented: $askNotifications) {
            Button("Continue") { Task { await requestNotificationsThenSend() } }
            Button("Not now", role: .cancel) { Task { await sendQuack() } }
        } message: {
            Text("So you can see replies and hear quacks you've allowed. You choose which friends can make a sound, and when.")
        }
        .alert(message ?? "", isPresented: Binding(get: { message != nil }, set: { if !$0 { message = nil } })) { Button("OK") { message = nil } }
    }

    @ViewBuilder private func quackCard(_ f: FriendDTO) -> some View {
        let quack = f.actions?.first { $0.capability == Capability.nudge }
        let sounds = f.theyAllowMe?.limits.nudge?.sounds ?? []
        let quackTick = lastCommand?.capability == Capability.nudge ? sentTick : 0
        WMCard {
            HStack(spacing: WMSpacing.sm) {
                // Replays only after a send the server accepted; says nothing about delivery.
                QuackBurst(tick: quackTick, height: 48)
                VStack(alignment: .leading, spacing: 2) {
                    Text("Quack").font(WMFont.cardTitle).foregroundStyle(WMColor.textPrimary).accessibilityAddTraits(.isHeader)
                    if !sounds.isEmpty {
                        Text("Only sounds \(f.user.displayName) allowed.").font(.subheadline).foregroundStyle(WMColor.textSecondary)
                    }
                }
            }
            if sounds.count > 1 {
                Picker("Sound", selection: Binding(get: { quackSound ?? sounds[0] }, set: { quackSound = $0 })) {
                    ForEach(sounds, id: \.self) { Text(soundName($0)).tag($0) }
                }
                .pickerStyle(.menu)
            }
            SecondaryButton(title: "Preview on my phone") { QuackPreview.shared.play() }
            if let quack, quack.granted, quack.availableNow {
                PrimaryButton(title: sending == Capability.nudge ? "Sending…" : "Send quack") { Task { await maybeAskThenSend() } }
                    .disabled(sending != nil)
                if quackTick > 0 {
                    Text("Sent to \(f.user.displayName)").font(.headline).foregroundStyle(WMColor.textPrimary)
                        .transition(.opacity)
                }
            } else {
                Text(reasonText(f.user.displayName, quack?.reason ?? "capability_not_granted")).foregroundStyle(WMColor.textSecondary)
            }
        }
    }

    /// Escalation only if they enabled it; never automatic.
    @ViewBuilder private func wakeCard(_ f: FriendDTO) -> some View {
        let name = f.user.displayName
        if let wake = f.actions?.first(where: { $0.capability == Capability.alarmRequest }), wake.granted {
            WMCard {
                Text("Wake alarm").font(WMFont.cardTitle).foregroundStyle(WMColor.textPrimary).accessibilityAddTraits(.isHeader)
                Text("Rings \(name)'s phone for up to \(f.theyAllowMe?.limits.alarm?.maxAlarmSeconds ?? 60) seconds, inside the wake hours they chose.")
                    .foregroundStyle(WMColor.textSecondary)
                if wake.availableNow {
                    PrimaryButton(title: sending == Capability.alarmRequest ? "Sending…" : "Send wake alarm") { Task { await sendWakeAlarm() } }
                        .disabled(sending != nil)
                } else {
                    Text(reasonText(name, wake.reason)).foregroundStyle(WMColor.textSecondary)
                }
            }
        } else {
            Text("\(name) hasn't turned on wake alarms for you. You can ask them in a message.").foregroundStyle(WMColor.textSecondary)
        }
    }

    /// Routines suggested for this friend; nothing rings until they accept.
    @ViewBuilder private func suggestCard(_ f: FriendDTO) -> some View {
        let name = f.user.displayName
        WMCard {
            Text("Wake-ups for \(name)").font(WMFont.cardTitle).foregroundStyle(WMColor.textPrimary).accessibilityAddTraits(.isHeader)
            ForEach(suggested) { p in
                if let v = p.pending ?? p.accepted {
                    VStack(alignment: .leading, spacing: WMSpacing.xxs) {
                        Text("\(v.localTime) · \(daysText(v.recurrence))").foregroundStyle(WMColor.textPrimary)
                        let r = readinessText(p.readiness, owner: name, mine: false)
                        StatusPill(text: r.0, tone: r.1)
                    }
                }
            }
            if f.theyAllowMe?.capabilities.contains(Capability.alarmSchedule) == true {
                SecondaryButton(title: "Suggest a wake-up routine") { suggesting = true }
                Text("It's a suggestion: nothing changes on \(name)'s phone until they accept it.").font(.footnote).foregroundStyle(WMColor.textSecondary)
            }
        }
    }

    private func load() async {
        guard let f = try? await app.api.friend(friendshipId) else { return }
        friend = f
        let helping = (try? await app.api.plans().helping) ?? []
        suggested = helping.filter { $0.owner.id == f.user.id && !["cancelled", "declined"].contains($0.state) }
    }

    private func remove() async {
        do {
            try await app.api.removeFriend(friendshipId)
            dismiss()
        } catch {
            message = (error as? APIError)?.message ?? "You're offline. Nothing was changed."
        }
    }

    /// Refreshes the last request's status until it settles (about two minutes at most).
    private func followLastCommand() async {
        guard let id = lastCommand?.id else { return }
        for _ in 0..<40 {
            if let c = lastCommand, settledStatuses.contains(c.displayStatus) { return }
            try? await Task.sleep(for: .seconds(3))
            if Task.isCancelled { return }
            if let fresh = try? await app.api.command(id), fresh.id == lastCommand?.id { lastCommand = fresh }
        }
    }

    /// What this friend may do on MY phone: recipient-owned, so only I change it.
    @ViewBuilder private func permissionsCard(_ f: FriendDTO) -> some View {
        let name = f.user.displayName
        let mine = f.iAllowThem.flatMap { $0.revokedAt == nil ? $0 : nil }
        WMCard {
            Text("What \(name) can do on your phone").font(WMFont.cardTitle).foregroundStyle(WMColor.textPrimary).accessibilityAddTraits(.isHeader)
            if let g = mine {
                Text("\(name) can \(g.capabilities.map(capabilityText).sorted().joined(separator: ", ")).").foregroundStyle(WMColor.textPrimary)
                if g.paused { StatusPill(text: "Paused", tone: .neutral) }
                SecondaryButton(title: "Change permissions") { editing = true }
                SecondaryButton(title: g.paused ? "Resume \(name)'s permissions" : "Pause \(name)'s permissions") { Task { await pause(g, !g.paused) } }
            } else {
                Text("Nothing yet. \(name) can't make a sound or set an alarm on your phone.").foregroundStyle(WMColor.textSecondary)
                PrimaryButton(title: "Choose what \(name) can do") { editing = true }
            }
        }
    }

    private func pause(_ g: GrantDTO, _ paused: Bool) async {
        do {
            _ = try await app.api.pauseGrant(g.id, paused: paused)
            await load()
        } catch {
            message = (error as? APIError)?.message ?? "You're offline. Nothing was changed."
        }
    }

    private func block(report: Bool) async {
        do {
            try await app.api.block(friendshipId, reportReason: report ? "unwanted_alerts" : nil)
            dismiss()
        } catch {
            message = (error as? APIError)?.message ?? "You're offline. Nothing was changed."
        }
    }

    /// Doc 03 §5 step 6: explain before the system prompt, at the first nudge. Declining never blocks sending.
    private func maybeAskThenSend() async {
        let settings = await UNUserNotificationCenter.current().notificationSettings()
        if settings.authorizationStatus == .notDetermined { askNotifications = true } else { await sendQuack() }
    }

    private func requestNotificationsThenSend() async {
        _ = try? await UNUserNotificationCenter.current().requestAuthorization(options: [.alert, .sound, .badge])
        await app.reportCapabilities()
        await sendQuack()
    }

    private func sendQuack() async {
        guard let f = friend else { return }
        let sounds = f.theyAllowMe?.limits.nudge?.sounds ?? []
        let sound = quackSound.flatMap { sounds.contains($0) ? $0 : nil } ?? sounds.first ?? "bundled:quack.classic"
        await send(.init(targetUserId: f.user.id, capability: Capability.nudge, expectedGrantVersion: f.theyAllowMe?.version, soundId: sound))
    }

    private func sendWakeAlarm() async {
        guard let f = friend, let alarm = f.theyAllowMe?.limits.alarm else { return }
        await send(.init(targetUserId: f.user.id, capability: Capability.alarmRequest, expectedGrantVersion: f.theyAllowMe?.version,
                         soundId: alarm.sounds.first ?? "bundled:rise.gentle", ringSeconds: min(60, alarm.maxAlarmSeconds)))
    }

    private func send(_ request: WakeMeAPI.CommandRequest) async {
        sending = request.capability
        defer { sending = nil }
        do {
            lastCommand = try await app.api.send(request)
            sentTick += 1
        } catch let e as APIError {
            failTick += 1
            message = e.message
            await load() // availability may have changed (their hours, a pause, a limit)
        } catch {
            failTick += 1
            message = "You're offline. Nothing was sent."
        }
    }
}

/// Statuses that won't change any more for a request.
let settledStatuses: Set<String> = ["acknowledged", "challenge_completed", "not_delivered", "expired", "not_played", "stopped", "ended_without_challenge", "cancelled"]

func statusTone(_ displayStatus: String) -> Tone {
    switch displayStatus {
    case "acknowledged", "challenge_completed": .success
    case "not_delivered", "expired", "not_played": .warning
    default: .info
    }
}

/// "Today 07:00 · Gym", "Tomorrow 06:30", "Tue 07:00" in this phone's time zone.
func nextAlarmText(_ fireAt: Date, label: String, now: Date = Date(), calendar: Calendar = .current) -> String {
    let clock = DateFormatter()
    clock.dateFormat = "HH:mm" // as routines are written (07:00), same as Android
    clock.timeZone = calendar.timeZone
    let time = clock.string(from: fireAt)
    let days = calendar.dateComponents([.day], from: calendar.startOfDay(for: now), to: calendar.startOfDay(for: fireAt)).day ?? 0
    let day = days == 0 ? "Today" : days == 1 ? "Tomorrow" : fireAt.formatted(.dateTime.weekday(.abbreviated))
    return "\(day) \(time)" + (label.isEmpty ? "" : " · \(label)")
}
