import SwiftUI
import WakeMeAPI
import WakeMeCore

/// A new wake-up of your own (doc 03 §5 step 7), or one suggested for a friend who allowed it.
/// Your own routines are active as soon as they are saved; the primary phone installs them with
/// AlarmKit and reports what the OS accepted. A suggestion stays inside the limits the friend
/// chose (wake hours, sounds, ring length, follow-up), rings in their time zone, and changes
/// nothing until they accept it. Mirrors the Android RoutineEditorScreen.
struct RoutineEditorView: View {
    @Environment(AppModel.self) private var app
    @Environment(\.dismiss) private var dismiss
    var friend: FriendDTO? = nil
    /// Guest use: saved and armed on this phone only (no account, no server).
    var guest = false
    let onSaved: () async -> Void

    @State private var time = Calendar.current.date(bySettingHour: 7, minute: 0, second: 0, of: Date()) ?? Date()
    @State private var days: Set<Int> = [1, 2, 3, 4, 5]
    @State private var label = ""
    @State private var sound = bundledSounds[0]
    @State private var ring = 90
    @State private var followTravel = false
    @State private var challenges: [WakeMeAPI.ChallengeSummary] = []
    @State private var challengeRevision: String?
    @State private var followUp = false
    @State private var saving = false
    @State private var prepared = false
    @State private var message: String?

    private var limits: AlarmLimits? { friend?.theyAllowMe?.limits.alarm }
    private var sounds: [String] { friend == nil ? bundledSounds : (limits?.sounds ?? []) }
    private var ringChoices: [Int] {
        guard let max = limits?.maxAlarmSeconds, friend != nil else { return [30, 60, 90, 120] }
        let choices = [30, 60, 90, 120].filter { $0 <= max }
        return choices.isEmpty ? [max] : choices
    }
    private var canAssignChallenges: Bool { friend == nil || friend?.theyAllowMe?.capabilities.contains(Capability.challengeAssign) == true }
    private var canFollowUp: Bool { friend == nil || limits?.followUp.allowed == true }
    private var followUpDelay: Int { min(180, friend == nil ? 180 : (limits?.followUp.maxDelaySeconds ?? 180)) }
    private var localTime: String {
        let c = Calendar.current.dateComponents([.hour, .minute], from: time)
        return String(format: "%02d:%02d", c.hour ?? 7, c.minute ?? 0)
    }
    /// A suggestion must ring inside the friend's wake hours on every chosen day.
    private var outsideWakeHours: Bool {
        guard friend != nil else { return false }
        let windows = limits?.windows ?? []
        return !days.allSatisfy { (try? isWithinAnyWindow(localTime: localTime, weekday: $0, windows)) == true }
    }

    var body: some View {
        let name = friend?.user.displayName ?? ""
        NavigationStack {
            Form {
                Group {
                    Section {
                        DatePicker("Time", selection: $time, displayedComponents: .hourAndMinute)
                            .font(.title3.weight(.semibold)).monospacedDigit()
                        DayPicker(days: $days)
                        Text(days.isEmpty ? "Choose at least one day." : daysText(.weekly(days.sorted())))
                            .foregroundStyle(days.isEmpty ? WMColor.warning : WMColor.textSecondary)
                        if outsideWakeHours {
                            Text("That's outside the wake hours \(name) chose.").foregroundStyle(WMColor.warning)
                        }
                    } header: {
                        Text("Time and days")
                    } footer: {
                        if friend != nil {
                            Text("\(name) allows wake-ups: " + (limits?.windows ?? []).map { "\(daysText(.weekly($0.days.sorted()))) \($0.start)–\($0.end)" }.joined(separator: "; ") + ". It rings at this time in \(name)'s time zone.")
                        }
                    }
                    Section {
                        TextField("Label (optional), e.g. 9am class", text: $label)
                            .onChange(of: label) { _, v in if v.count > 40 { label = String(v.prefix(40)) } }
                    }
                    Section {
                        Picker("Sound", selection: $sound) {
                            ForEach(sounds, id: \.self) { Text(soundName($0)).tag($0) }
                        }
                        Picker("Ring for", selection: $ring) {
                            ForEach(ringChoices, id: \.self) { Text("\($0) seconds").tag($0) }
                        }
                    } header: {
                        Text("Sound")
                    } footer: {
                        if friend != nil { Text("Only the sounds and ring length \(name) approved.") }
                    }
                    if canAssignChallenges, challenges.contains(where: { $0.publishedRevisionId != nil }) {
                        Section {
                            Picker("Challenge", selection: $challengeRevision) {
                                Text("None").tag(String?.none)
                                ForEach(challenges.filter { $0.publishedRevisionId != nil }) { c in Text(c.title).tag(c.publishedRevisionId) }
                            }
                            if challengeRevision != nil, canFollowUp {
                                Toggle("One follow-up alarm", isOn: $followUp)
                            }
                        } header: {
                            Text("Challenge (optional)")
                        } footer: {
                            Text("A follow-up rings \(followUpDelay / 60) minutes later unless the challenge is finished. The wake session can always be ended.")
                        }
                    }
                    if friend == nil {
                        Section {
                            Toggle("Follow my phone when I travel", isOn: $followTravel)
                        } footer: {
                            Text(followTravel ? "Rings at this time wherever the phone is." : "Keeps this time in \(TimeZone.current.identifier) when you travel.")
                        }
                    }
                }
                .cardRows()
            }
            .scrollContentBackground(.hidden)
            .background(WMColor.surface)
            .navigationTitle(friend == nil ? "New wake-up" : "Suggest a wake-up")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Cancel") { dismiss() } }
                ToolbarItem(placement: .confirmationAction) {
                    Button(saving ? "Saving…" : (friend == nil ? "Save" : "Suggest")) { Task { await save() } }
                        .disabled(days.isEmpty || saving || outsideWakeHours || !sounds.contains(sound))
                }
            }
            .alert(message ?? "", isPresented: Binding(get: { message != nil }, set: { if !$0 { message = nil } })) { Button("OK") { message = nil } }
            .task {
                prepare()
                if !guest { challenges = (try? await app.api.challenges()) ?? [] }
            }
        }
    }

    /// For a friend, start from choices inside what they allowed.
    private func prepare() {
        guard !prepared else { return }
        prepared = true
        guard friend != nil, let limits else { return }
        if let first = limits.sounds.first { sound = first }
        ring = ringChoices.contains(ring) ? ring : (ringChoices.last ?? ring)
        if let window = limits.windows.first {
            days = days.intersection(window.days)
            if days.isEmpty { days = Set(window.days) }
            if outsideWakeHours, let start = try? minuteOfDay(window.start) {
                time = Calendar.current.date(bySettingHour: start / 60, minute: start % 60, second: 0, of: Date()) ?? time
            }
        }
    }

    private func save() async {
        saving = true
        defer { saving = false }
        let terms = WakeMeAPI.PlanTerms(
            ownerId: friend?.user.id,
            label: label.trimmingCharacters(in: .whitespaces),
            localTime: localTime,
            // A friend's suggestion rings in their own zone; the server applies it (it stays private).
            timeZone: TimeZone.current.identifier,
            timeZonePolicy: friend == nil && followTravel ? "device" : "fixed",
            recurrence: .weekly(days.sorted()),
            soundId: sound,
            ringSeconds: ring,
            challengeRevisionId: challengeRevision,
            followUpDelaySeconds: challengeRevision != nil && followUp && canFollowUp ? followUpDelay : nil
        )
        if guest {
            // Alarm access is needed to arm it on this phone; asked for here, at wake setup.
            if WakeAlarms.shared.authorization == .notDetermined { _ = await WakeAlarms.shared.requestAuthorization() }
            _ = await LocalRoutines.shared.save(terms)
            await onSaved()
            dismiss()
            return
        }
        do {
            _ = try await app.api.createPlan(terms)
        } catch {
            message = (error as? APIError)?.message ?? (friend == nil ? "You're offline. The wake-up wasn't saved." : "You're offline. Nothing was sent.")
            return
        }
        // Alarm access is asked for here, when it is needed (doc 03 §5 step 7); a suggestion rings on their phone.
        if friend == nil, WakeAlarms.shared.authorization == .notDetermined { _ = await WakeAlarms.shared.requestAuthorization() }
        await onSaved()
        dismiss()
    }
}

/// Bundled sounds offered for wake-ups (same IDs and order as Android).
let bundledSounds = ["bundled:rise.gentle", "bundled:bell.bright", "bundled:pulse.wake", "bundled:quack.double"]

func soundName(_ id: String) -> String {
    switch id {
    case "bundled:rise.gentle": "Gentle rise"
    case "bundled:bell.bright": "Bright bell"
    case "bundled:pulse.wake": "Wake pulse"
    case "bundled:quack.double": "Double quack"
    default: id.replacingOccurrences(of: "bundled:", with: "").replacingOccurrences(of: ".", with: " ")
    }
}

/// Seven day toggles, Monday first (ISO weekdays 1–7), each a 44 pt target. The selection is
/// also spelled out in words below the row.
struct DayPicker: View {
    @Binding var days: Set<Int>
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    private let names = Calendar.current.weekdaySymbols // Sunday first
    private let short = Calendar.current.veryShortStandaloneWeekdaySymbols

    var body: some View {
        HStack(spacing: WMSpacing.xxs) {
            ForEach(1...7, id: \.self) { iso in
                let on = days.contains(iso)
                let i = iso % 7 // ISO 7 (Sunday) is index 0 in Calendar symbols
                Button {
                    withAnimation(WMMotion.respecting(reduceMotion, WMMotion.snappy)) {
                        if on { days.remove(iso) } else { days.insert(iso) }
                    }
                } label: {
                    // A chosen day is lime with a bold forest letter, like every selection in
                    // Bloom; the others keep a visible outline (≥ 3:1).
                    Text(short[i])
                        .font(.headline.weight(on ? .bold : .regular))
                        .frame(maxWidth: .infinity, minHeight: WMSize.minTouchTarget)
                        .foregroundStyle(on ? WMColor.textOnAccent : WMColor.textPrimary)
                        .background(on ? WMColor.accent : WMColor.surfaceRaised, in: Circle().scale(0.92))
                        .overlay(Circle().scale(0.92).stroke(on ? Color.clear : WMColor.outline, lineWidth: 1))
                }
                .sensoryFeedback(WMHaptic.selection, trigger: on)
                .buttonStyle(.plain)
                .accessibilityLabel(names[i])
                .accessibilityAddTraits(on ? .isSelected : [])
            }
        }
        .padding(.vertical, WMSpacing.xxs)
    }
}
