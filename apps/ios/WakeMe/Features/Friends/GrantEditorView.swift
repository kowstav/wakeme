import SwiftUI
import UserNotifications
import WakeMeAPI
import WakeMeCore

/// What a friend may do on YOUR phone (doc 03 §6). Grants are recipient-owned, directional,
/// bounded, versioned and revocable; friendship and circles grant nothing. Mirrors the Android
/// GrantEditorScreen. Check-ins and location requests are not offered on iPhone until this
/// app can answer them; Focus buddy stays hidden (Stage 6).
struct GrantEditorView: View {
    @Environment(AppModel.self) private var app
    @Environment(\.dismiss) private var dismiss
    let friend: FriendDTO
    let onSaved: () async -> Void

    @State private var caps: Set<String> = []
    @State private var nudge = NudgeLimits(maxPerHour: 3, windows: [LocalWindow(days: [1, 2, 3, 4, 5, 6, 7], start: "08:00", end: "22:00")], sounds: ["bundled:quack.classic", "bundled:quack.double"], overridesQuietHours: false)
    @State private var alarm = AlarmLimits(maxAlarmSeconds: 120, maxPerDay: 1, windows: [LocalWindow(days: [1, 2, 3, 4, 5], start: "06:00", end: "10:00")], sounds: ["bundled:rise.gentle", "bundled:bell.bright"], followUp: FollowUp(allowed: true, maxDelaySeconds: 300))
    @State private var challenge = ChallengeLimits(types: ["multiple_choice", "short_answer", "math", "memory_order", "chess"], maxDurationSeconds: 90, maxDifficulty: "medium")
    @State private var validDays = 90
    @State private var presets: [WakeMeAPI.PresetDTO] = []
    @State private var saving = false
    @State private var reauth = false
    @State private var confirmRemove = false
    @State private var message: String?
    @State private var askToHear = false

    private var name: String { friend.user.displayName }
    private var wakeOn: Bool { caps.contains(Capability.alarmRequest) || caps.contains(Capability.alarmSchedule) }
    private var problem: String? {
        if caps.isEmpty { return "Choose at least one thing, or remove all permissions instead." }
        if caps.contains(Capability.nudge), let p = windowProblem(nudge.windows) { return p }
        if wakeOn, let p = windowProblem(alarm.windows) { return p }
        return nil
    }

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    Text("You're giving \(name) permission to help you, within limits you set. You can change or remove this anytime. It only works one way: it doesn't give you anything on \(name)'s phone.")
                        .foregroundStyle(WMColor.textPrimary)
                    Text("Being in a circle together doesn't change these settings. Only you can change them.")
                        .foregroundStyle(WMColor.textSecondary)
                    if let g = friend.iAllowThem { Text(usageText(g)).foregroundStyle(WMColor.textSecondary) }
                }
                if !presets.isEmpty {
                    Section("Start from") {
                        ForEach(presets.filter { $0.id != "focus_buddy" }, id: \.id) { p in
                            Button(p.id == "gentle_nudges" ? "Gentle nudges" : "Wake buddy") { apply(p) }
                        }
                    }
                }
                Section {
                    Toggle("Send short sounds", isOn: capability(Capability.nudge))
                    if caps.contains(Capability.nudge) {
                        Stepper("Up to \(nudge.maxPerHour) per hour", value: $nudge.maxPerHour, in: 1...12)
                        WindowRows(window: first($nudge.windows))
                        SoundToggles(sounds: $nudge.sounds)
                        Toggle("Allow during my quiet hours", isOn: $nudge.overridesQuietHours)
                    }
                } header: {
                    Text("Quacks")
                } footer: {
                    Text("A quack or chime, a few times an hour at most. With quiet hours not allowed, quacks wait for daytime.")
                }
                Section {
                    Toggle("Set wake-up routines for me", isOn: capability(Capability.alarmSchedule))
                    Toggle("Wake me now", isOn: capability(Capability.alarmRequest))
                    if wakeOn {
                        WindowRows(window: first($alarm.windows))
                        Picker("Ring for at most", selection: $alarm.maxAlarmSeconds) {
                            ForEach([30, 60, 120, 300], id: \.self) { Text("\($0) seconds").tag($0) }
                        }
                        Stepper("Up to \(alarm.maxPerDay) wake-up\(alarm.maxPerDay > 1 ? "s" : "") a day", value: $alarm.maxPerDay, in: 1...3)
                        SoundToggles(sounds: $alarm.sounds)
                        Toggle("Allow one follow-up alarm", isOn: $alarm.followUp.allowed)
                    }
                } header: {
                    Text("Help wake me")
                } footer: {
                    Text("You see and accept every routine before it's active. Wake hours are your explicit OK to be woken, even during quiet hours.")
                }
                Section {
                    Toggle("Assign challenges", isOn: capability(Capability.challengeAssign))
                    if caps.contains(Capability.challengeAssign) {
                        Picker("At most", selection: $challenge.maxDurationSeconds) {
                            ForEach([30, 60, 90, 180], id: \.self) { Text("\($0) seconds").tag($0) }
                        }
                        Picker("Hardest", selection: $challenge.maxDifficulty) {
                            ForEach(["easy", "medium", "hard"], id: \.self) { Text($0.capitalized).tag($0) }
                        }
                    }
                } footer: {
                    Text("Quizzes and puzzles to finish your wake-up. New versions always need your OK, and the escape always works.")
                }
                Section {
                    Picker("Keep these permissions for", selection: $validDays) {
                        ForEach([30, 90, 180], id: \.self) { Text("\($0) days").tag($0) }
                    }
                }
                if let problem {
                    Section { Text(problem).foregroundStyle(WMColor.warning) }
                }
                if friend.iAllowThem != nil {
                    Section {
                        Button("Remove all of \(name)'s permissions", role: .destructive) { confirmRemove = true }
                    }
                }
            }
            .scrollContentBackground(.hidden)
            .background(WMColor.surface)
            .navigationTitle("What can \(name) do?")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Cancel") { dismiss() } }
                ToolbarItem(placement: .confirmationAction) {
                    Button(saving ? "Saving…" : "Save") { Task { await save() } }.disabled(saving || problem != nil)
                }
            }
            .task { await load() }
            .sheet(isPresented: $reauth) { ConfirmIdentityView { Task { await save() } } }
            .alert("Hear \(name)'s quacks?", isPresented: $askToHear) {
                Button("Allow notifications") {
                    Task {
                        _ = try? await UNUserNotificationCenter.current().requestAuthorization(options: [.alert, .sound, .badge])
                        await app.reportCapabilities()
                        dismiss()
                    }
                }
                Button("Not now", role: .cancel) { dismiss() }
            } message: {
                Text("Wake Me uses notifications to play the sounds you just allowed. You can change this in Settings at any time.")
            }
            .confirmationDialog("Remove all of \(name)'s permissions?", isPresented: $confirmRemove, titleVisibility: .visible) {
                Button("Remove", role: .destructive) { Task { await removeAll() } }
            } message: {
                Text("\(name) can't do anything on your phone until you choose again. Queued requests are cancelled.")
            }
            .alert(message ?? "", isPresented: Binding(get: { message != nil }, set: { if !$0 { message = nil } })) { Button("OK") { message = nil } }
        }
    }

    private func capability(_ cap: String) -> Binding<Bool> {
        Binding(get: { caps.contains(cap) }, set: { on in if on { caps.insert(cap) } else { caps.remove(cap) } })
    }

    private func first(_ windows: Binding<[LocalWindow]>) -> Binding<LocalWindow> {
        Binding(get: { windows.wrappedValue.first ?? LocalWindow(days: [1, 2, 3, 4, 5, 6, 7], start: "08:00", end: "22:00") }, set: { windows.wrappedValue = [$0] })
    }

    private func windowProblem(_ windows: [LocalWindow]) -> String? {
        guard let w = windows.first else { return "Choose the hours they can use." }
        if w.days.isEmpty { return "Choose at least one day for each permission's hours." }
        if w.start == w.end { return "Hours need different start and end times." }
        return nil
    }

    private func usageText(_ g: GrantDTO) -> String {
        let used = g.lastUsedAt.flatMap { try? Instant.parse($0) }.map { "Last used \($0.formatted(date: .abbreviated, time: .shortened))." } ?? "\(name) hasn't used these yet."
        return "\(used) Version \(g.version)\(g.paused ? ", paused" : "")."
    }

    private func load() async {
        if let g = friend.iAllowThem, g.revokedAt == nil {
            caps = Set(g.capabilities).intersection(Self.offered)
            if let n = g.limits.nudge { nudge = n }
            if let a = g.limits.alarm { alarm = a }
            if let c = g.limits.challenge { challenge = c }
        }
        presets = (try? await app.api.presets()) ?? []
    }

    /// Capabilities this iPhone app can act on today.
    private static let offered: Set<String> = [Capability.nudge, Capability.alarmRequest, Capability.alarmSchedule, Capability.challengeAssign]

    private func apply(_ p: WakeMeAPI.PresetDTO) {
        caps = Set(p.capabilities).intersection(Self.offered)
        if let n = p.limits.nudge { nudge = n }
        if let a = p.limits.alarm { alarm = a }
        if let c = p.limits.challenge { challenge = c }
    }

    private func save() async {
        guard problem == nil else { return }
        saving = true
        defer { saving = false }
        let until = Date().addingTimeInterval(TimeInterval(validDays) * 86_400)
        let limits = GrantLimits(nudge: caps.contains(Capability.nudge) ? nudge : nil, alarm: wakeOn ? alarm : nil, challenge: caps.contains(Capability.challengeAssign) ? challenge : nil)
        do {
            if let g = friend.iAllowThem, g.revokedAt == nil {
                _ = try await app.api.updateGrant(g.id, expectedVersion: g.version, paused: g.paused, capabilities: caps.sorted(), limits: limits, validUntil: until)
            } else {
                _ = try await app.api.createGrant(granteeId: friend.user.id, capabilities: caps.sorted(), limits: limits, validUntil: until)
            }
            await onSaved()
            // The phone re-validates requests against its own copy of these rules.
            await GrantStore.shared.refresh(api: app.api)
            // Doc 03 §5 step 6: the sounds just allowed need notifications; explain before asking.
            let status = await UNUserNotificationCenter.current().notificationSettings().authorizationStatus
            if (caps.contains(Capability.nudge) || caps.contains(Capability.alarmRequest)) && status == .notDetermined {
                askToHear = true
            } else {
                dismiss()
            }
        } catch let e as APIError where e.code == "reauth_required" {
            reauth = true // wake permissions need a sign-in within the last 24 hours
        } catch {
            message = (error as? APIError)?.message ?? "You're offline. Nothing was changed."
        }
    }

    private func removeAll() async {
        guard let g = friend.iAllowThem else { return }
        // Local first: this phone stops honouring the old rules before the server hears.
        await GrantStore.shared.markRevoked(granteeId: friend.user.id)
        do {
            try await app.api.revokeGrant(g.id)
            await onSaved()
            dismiss()
        } catch {
            message = (error as? APIError)?.message ?? "You're offline. Nothing was changed."
        }
    }
}

/// Days and from–until hours for one permission window; an end before the start runs overnight.
struct WindowRows: View {
    @Binding var window: LocalWindow

    var body: some View {
        DayPicker(days: Binding(get: { Set(window.days) }, set: { window.days = $0.sorted() }))
        DatePicker("From", selection: time(\.start), displayedComponents: .hourAndMinute)
        DatePicker("Until", selection: time(\.end), displayedComponents: .hourAndMinute)
        if window.end < window.start { Text("Ends the next morning.").foregroundStyle(WMColor.textSecondary) }
    }

    private func time(_ key: WritableKeyPath<LocalWindow, String>) -> Binding<Date> {
        Binding(
            get: {
                let p = window[keyPath: key].split(separator: ":").compactMap { Int($0) }
                return Calendar.current.date(bySettingHour: p.first ?? 0, minute: p.count > 1 ? p[1] : 0, second: 0, of: Date()) ?? Date()
            },
            set: { d in
                let c = Calendar.current.dateComponents([.hour, .minute], from: d)
                window[keyPath: key] = String(format: "%02d:%02d", c.hour ?? 0, c.minute ?? 0)
            }
        )
    }
}

/// The bundled sounds a friend may use; at least one stays selected.
struct SoundToggles: View {
    @Binding var sounds: [String]
    static let all = ["bundled:quack.classic", "bundled:quack.double", "bundled:chime.soft", "bundled:bell.bright", "bundled:rise.gentle", "bundled:pulse.wake"]

    var body: some View {
        ForEach(Self.all, id: \.self) { id in
            Toggle(sharedSoundName(id), isOn: Binding(
                get: { sounds.contains(id) },
                set: { on in
                    if on { sounds.append(id) } else if sounds.count > 1 { sounds.removeAll { $0 == id } }
                }
            ))
        }
    }
}

func sharedSoundName(_ id: String) -> String {
    switch id {
    case "bundled:quack.classic": "Classic quack"
    case "bundled:chime.soft": "Soft chime"
    default: soundName(id)
    }
}

/// Literal, recipient-facing words for each capability.
func capabilityText(_ cap: String) -> String {
    switch cap {
    case Capability.nudge: "send quacks"
    case Capability.alarmRequest: "wake you now"
    case Capability.alarmSchedule: "suggest wake-up routines"
    case Capability.challengeAssign: "assign challenges"
    case Capability.checkin: "ask you to check in"
    case Capability.location: "ask where you are"
    case Capability.focus: "ask you to focus"
    default: cap
    }
}
