import Combine
import FamilyControls
import SwiftUI
import WakeMeAPI
import WakeMeCore

/// Focus (doc 03 §4E, ADR 0014; same flows as Android's FocusScreen): choose apps to keep closed,
/// start a finite session yourself or when a friend asks, take a short break by finishing a
/// challenge, and end it whenever you want. The chosen apps never leave this phone.
struct FocusView: View {
    @Environment(AppModel.self) private var app
    @Environment(\.scenePhase) private var scenePhase
    @State private var focus = FocusController.shared
    @State private var picking = false
    @State private var minutes = 25
    @State private var earningBreak = false
    @State private var now = FocusController.nowMs()
    private let ticker = Timer.publish(every: 1, on: .main, in: .common).autoconnect()

    var body: some View {
        List {
            Group {
                Section {
                    Text("Choose apps to keep closed during a focus session. Your choice stays on this phone; friends never see which apps.")
                        .foregroundStyle(WMColor.textSecondary)
                }
                if !focus.authorized {
                    Section {
                        Text("Focus uses Screen Time on your own phone to keep the apps you choose closed. It isn't parental supervision, and you can turn it off in Settings.")
                            .foregroundStyle(WMColor.textPrimary)
                        PrimaryButton(title: "Allow Screen Time access") { Task { await focus.requestAuthorization() } }
                    }
                }
                if let r = focus.request {
                    Section {
                        Text("\(r.friendName) asks you to focus for \(r.minutes) min").font(.headline).foregroundStyle(WMColor.textPrimary)
                        Text("You choose. Nothing changes unless you start it, and you can end it anytime.").foregroundStyle(WMColor.textSecondary)
                        PrimaryButton(title: "Start focus") { Task { await accept(r) } }
                            .disabled(!focus.authorized || !focus.hasSelection)
                        Button("Not now") { focus.offer(nil) }.frame(minHeight: WMSize.minTouchTarget)
                    }
                }
                switch focus.state(now: now) ?? .finished(reason: "none") {
                case let .active(remaining):
                    Section {
                        Text(activeTitle(remaining)).font(.headline).foregroundStyle(WMColor.textPrimary)
                        if let s = focus.session, s.breaksTaken < focusMaxBreaks {
                            SecondaryButton(title: "Take a \(focusBreakMinutes)-minute break") { earningBreak = true }
                        }
                        Button("End focus", role: .destructive) { focus.end() }.frame(minHeight: WMSize.minTouchTarget)
                    }
                case let .onBreak(breakLeft, sessionLeft):
                    Section {
                        Text(activeTitle(sessionLeft)).font(.headline).foregroundStyle(WMColor.textPrimary)
                        StatusPill(text: "On a break for \(Self.minutes(breakLeft)) min", tone: .info)
                        Button("End focus", role: .destructive) { focus.end() }.frame(minHeight: WMSize.minTouchTarget)
                    }
                default:
                    Section("Start a focus session") {
                        Picker("Length", selection: $minutes) {
                            ForEach([15, 25, 45, 60], id: \.self) { Text("\($0) min").tag($0) }
                        }
                        .pickerStyle(.segmented)
                        PrimaryButton(title: "Start focus") { focus.start(minutes: minutes) }
                            .disabled(!focus.authorized || !focus.hasSelection)
                        if !focus.hasSelection { Text("Choose at least one app below.").foregroundStyle(WMColor.textSecondary) }
                    }
                }
                Section("Apps to keep closed") {
                    Button(focus.hasSelection ? "Change apps (\(focus.selection.applicationTokens.count + focus.selection.categoryTokens.count) chosen)" : "Choose apps") { picking = true }
                        .frame(minHeight: WMSize.minTouchTarget)
                        .disabled(!focus.authorized)
                }
            }
            .cardRows()
        }
        .scrollContentBackground(.hidden)
        .background(WMColor.surface)
        .navigationTitle("Focus")
        .familyActivityPicker(isPresented: $picking, selection: $focus.selection)
        .onChange(of: picking) { _, open in if !open { focus.saveSelection() } }
        .onReceive(ticker) { _ in now = FocusController.nowMs() }
        .onChange(of: scenePhase) { _, phase in if phase == .active { focus.refresh() } }
        .task { focus.refresh() }
        .fullScreenCover(isPresented: $earningBreak) {
            if let definition = Self.breakChallenge() {
                ChallengeRunnerView(definition: definition, escapeLabel: "Back", onComplete: { _ in
                    focus.takeBreak()
                    earningBreak = false
                }, onEscape: { earningBreak = false })
            }
        }
        .alert(focus.lastError ?? "", isPresented: Binding(get: { focus.lastError != nil }, set: { if !$0 { focus.lastError = nil } })) { Button("OK") { focus.lastError = nil } }
    }

    private func activeTitle(_ remaining: Int64) -> String {
        "Focusing" + (focus.session?.friendName.map { " with \($0)" } ?? "") + " · \(Self.minutes(remaining)) min left"
    }

    private static func minutes(_ ms: Int64) -> Int64 { (ms + 59_999) / 60_000 }

    /// Starts within the limit this person gave the friend who asked, and tells the friend it started.
    private func accept(_ r: FocusRequest) async {
        let limits = await GrantStore.shared.grant(from: app.me?.id ?? "", to: r.friendId)?.limits.focus
        if focus.start(minutes: r.minutes, limits: limits, friendId: r.friendId, friendName: r.friendName, commandId: r.commandId) {
            await IncomingRequests.report(r.commandId, kind: "acknowledged")
            focus.offer(nil)
        }
    }

    /// Two small sums, checked on the phone; the runner always offers the accessible check-in instead.
    private static func breakChallenge() -> ChallengeDefinition? {
        let a = Int.random(in: 2...19), b = Int.random(in: 2...19), c = Int.random(in: 2...19), d = Int.random(in: 2...19)
        let json = #"{"schemaVersion":1,"title":"Earn a short break","kind":"sequence","difficulty":"easy","maxDurationSeconds":60,"steps":[{"type":"math","id":"b1","operands":[\#(a),\#(b)],"operator":"+","answer":\#(a + b)},{"type":"math","id":"b2","operands":[\#(c),\#(d)],"operator":"+","answer":\#(c + d)}],"verification":"local_deterministic","fallback":{"type":"accessible_checkin"}}"#
        return try? JSONDecoder().decode(ChallengeDefinition.self, from: Data(json.utf8))
    }
}
