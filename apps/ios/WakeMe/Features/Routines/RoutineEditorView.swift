import SwiftUI
import WakeMeAPI
import WakeMeCore

/// A new wake-up of your own (doc 03 §5 step 7). Your own routines are active as soon as they
/// are saved; the primary phone installs them with AlarmKit and reports what the OS accepted.
/// Mirrors the Android RoutineEditorScreen for own routines.
struct RoutineEditorView: View {
    @Environment(AppModel.self) private var app
    @Environment(\.dismiss) private var dismiss
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
    @State private var message: String?

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    DatePicker("Time", selection: $time, displayedComponents: .hourAndMinute)
                    DayPicker(days: $days)
                    Text(days.isEmpty ? "Choose at least one day." : daysText(.weekly(days.sorted())))
                        .foregroundStyle(days.isEmpty ? WMColor.danger : WMColor.textSecondary)
                } header: {
                    Text("Time and days")
                }
                Section {
                    TextField("Label (optional), e.g. 9am class", text: $label)
                        .onChange(of: label) { _, v in if v.count > 40 { label = String(v.prefix(40)) } }
                }
                Section {
                    Picker("Sound", selection: $sound) {
                        ForEach(bundledSounds, id: \.self) { Text(soundName($0)).tag($0) }
                    }
                    Picker("Ring for", selection: $ring) {
                        ForEach([30, 60, 90, 120], id: \.self) { Text("\($0) seconds").tag($0) }
                    }
                } header: {
                    Text("Sound")
                }
                if challenges.contains(where: { $0.publishedRevisionId != nil }) {
                    Section {
                        Picker("Challenge", selection: $challengeRevision) {
                            Text("None").tag(String?.none)
                            ForEach(challenges.filter { $0.publishedRevisionId != nil }) { c in Text(c.title).tag(c.publishedRevisionId) }
                        }
                        if challengeRevision != nil {
                            Toggle("One follow-up alarm", isOn: $followUp)
                        }
                    } header: {
                        Text("Challenge (optional)")
                    } footer: {
                        Text("A follow-up rings 3 minutes later unless you finish the challenge. You can always end the session.")
                    }
                }
                Section {
                    Toggle("Follow my phone when I travel", isOn: $followTravel)
                } footer: {
                    Text(followTravel ? "Rings at this time wherever the phone is." : "Keeps this time in \(TimeZone.current.identifier) when you travel.")
                }
            }
            .scrollContentBackground(.hidden)
            .background(WMColor.surface)
            .navigationTitle("New wake-up")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Cancel") { dismiss() } }
                ToolbarItem(placement: .confirmationAction) {
                    Button(saving ? "Saving…" : "Save") { Task { await save() } }.disabled(days.isEmpty || saving)
                }
            }
            .alert(message ?? "", isPresented: Binding(get: { message != nil }, set: { if !$0 { message = nil } })) { Button("OK") { message = nil } }
            .task { challenges = (try? await app.api.challenges()) ?? [] }
        }
    }

    private func save() async {
        saving = true
        defer { saving = false }
        let c = Calendar.current.dateComponents([.hour, .minute], from: time)
        let terms = WakeMeAPI.PlanTerms(
            label: label.trimmingCharacters(in: .whitespaces),
            localTime: String(format: "%02d:%02d", c.hour ?? 7, c.minute ?? 0),
            timeZone: TimeZone.current.identifier,
            timeZonePolicy: followTravel ? "device" : "fixed",
            recurrence: .weekly(days.sorted()),
            soundId: sound,
            ringSeconds: ring,
            challengeRevisionId: challengeRevision,
            followUpDelaySeconds: challengeRevision != nil && followUp ? 180 : nil
        )
        do {
            _ = try await app.api.createPlan(terms)
        } catch {
            message = (error as? APIError)?.message ?? "You're offline. The wake-up wasn't saved."
            return
        }
        // Alarm access is asked for here, when it is needed (doc 03 §5 step 7).
        if WakeAlarms.shared.authorization == .notDetermined { _ = await WakeAlarms.shared.requestAuthorization() }
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
    private let names = Calendar.current.weekdaySymbols // Sunday first
    private let short = Calendar.current.veryShortStandaloneWeekdaySymbols

    var body: some View {
        HStack(spacing: WMSpacing.xxs) {
            ForEach(1...7, id: \.self) { iso in
                let on = days.contains(iso)
                let i = iso % 7 // ISO 7 (Sunday) is index 0 in Calendar symbols
                Button {
                    if on { days.remove(iso) } else { days.insert(iso) }
                } label: {
                    Text(short[i])
                        .font(.headline)
                        .frame(maxWidth: .infinity, minHeight: WMSize.minTouchTarget)
                        .foregroundStyle(on ? WMColor.textOnAccent : WMColor.textPrimary)
                        .background(on ? WMColor.accent : WMColor.surfaceSunken, in: RoundedRectangle(cornerRadius: WMRadius.md))
                }
                .buttonStyle(.plain)
                .accessibilityLabel(names[i])
                .accessibilityAddTraits(on ? .isSelected : [])
            }
        }
        .padding(.vertical, WMSpacing.xxs)
    }
}
