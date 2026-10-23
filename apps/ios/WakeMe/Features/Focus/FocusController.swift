import DeviceActivity
import FamilyControls
import Foundation
import ManagedSettings
import Observation
import WakeMeCore

/// A friend's focus request waiting for the recipient's choice; nothing starts until Start.
struct FocusRequest: Codable, Equatable, Identifiable {
    let commandId: String
    let friendId: String
    let friendName: String
    let minutes: Int
    var id: String { commandId }
}

/// Focus on iPhone (ADR 0014), mirroring Android's FocusStore + service: Family Controls with
/// individual authorization, apps chosen with the system picker and kept on this phone, a
/// Managed Settings shield while a session is active, and DeviceActivity schedules so the
/// monitor extension ends the shield on time (and restores it after a break) even when Wake Me
/// isn't running. "End focus" always works at once and needs no network.
@MainActor
@Observable
final class FocusController {
    static let shared = FocusController()

    private let store = ManagedSettingsStore(named: .init("wakeme.focus"))
    private let center = DeviceActivityCenter()
    private let sessionActivity = DeviceActivityName("wakeme.focus.session")
    private let breakActivity = DeviceActivityName("wakeme.focus.break")
    /// Shared with the extensions (App Group).
    private let shared = UserDefaults(suiteName: "group.app.wakeme.ios") ?? .standard

    var selection = FamilyActivitySelection()
    private(set) var session: FocusSession?
    private(set) var request: FocusRequest?
    var lastError: String?

    var authorized: Bool { AuthorizationCenter.shared.authorizationStatus == .approved }
    var hasSelection: Bool { !selection.applicationTokens.isEmpty || !selection.categoryTokens.isEmpty }

    init() {
        if let data = shared.data(forKey: "focus.selection"), let s = try? JSONDecoder().decode(FamilyActivitySelection.self, from: data) { selection = s }
        session = shared.data(forKey: "focus.session").flatMap { try? JSONDecoder().decode(FocusSession.self, from: $0) }
        request = shared.data(forKey: "focus.request").flatMap { try? JSONDecoder().decode(FocusRequest.self, from: $0) }
    }

    /// Screen Time permission for this person's own phone (not parental supervision).
    func requestAuthorization() async {
        do {
            try await AuthorizationCenter.shared.requestAuthorization(for: .individual)
        } catch {
            lastError = "Screen Time access wasn't given, so focus can't keep apps closed. You can change this in Settings."
        }
    }

    func saveSelection() {
        shared.set(try? JSONEncoder().encode(selection), forKey: "focus.selection")
    }

    func offer(_ r: FocusRequest?) {
        request = r
        shared.set(r.flatMap { try? JSONEncoder().encode($0) }, forKey: "focus.request")
    }

    /// Starts a session; `limits` are the ones this person gave the friend who asked, if any.
    @discardableResult
    func start(minutes: Int, limits: FocusLimits? = nil, friendId: String? = nil, friendName: String? = nil, commandId: String? = nil) -> Bool {
        guard authorized, hasSelection,
              let s = startFocus(id: UUID().uuidString, now: Self.nowMs(), minutes: minutes, limits: limits, friendId: friendId, friendName: friendName, commandId: commandId) else { return false }
        save(s)
        applyShield()
        schedule(sessionActivity, until: Date(timeIntervalSince1970: TimeInterval(s.endsAt) / 1000))
        return true
    }

    /// A finished challenge earns a short break; the monitor restores the shield when it ends.
    @discardableResult
    func takeBreak() -> Bool {
        guard let s = session, let next = WakeMeCore.takeBreak(s, now: Self.nowMs()), let until = next.breakUntil else { return false }
        save(next)
        store.clearAllSettings()
        schedule(breakActivity, until: Date(timeIntervalSince1970: TimeInterval(until) / 1000))
        return true
    }

    /// "End focus": always allowed, at once, offline.
    func end() {
        guard let s = session else { return }
        save(endFocus(s, now: Self.nowMs()))
        store.clearAllSettings()
        center.stopMonitoring([sessionActivity, breakActivity])
    }

    /// When the app becomes active: catch up with the clock (the extension may not have run).
    func refresh() {
        guard let s = session else { return }
        let state = focusState(s, now: Self.nowMs())
        switch state {
        case .finished: store.clearAllSettings()
        case .active: applyShield()
        case .onBreak: store.clearAllSettings()
        }
    }

    func state(now: Int64 = FocusController.nowMs()) -> FocusState? { session.map { focusState($0, now: now) } }

    private func applyShield() {
        store.shield.applications = selection.applicationTokens
        store.shield.applicationCategories = .specific(selection.categoryTokens)
    }

    /// DeviceActivity needs an interval of at least 15 minutes, so a short one starts in the past;
    /// only its end matters here.
    private func schedule(_ activity: DeviceActivityName, until end: Date) {
        let start = min(Date(), end.addingTimeInterval(-15 * 60 - 60))
        let parts: Set<Calendar.Component> = [.year, .month, .day, .hour, .minute, .second]
        let schedule = DeviceActivitySchedule(intervalStart: Calendar.current.dateComponents(parts, from: start),
                                              intervalEnd: Calendar.current.dateComponents(parts, from: end), repeats: false)
        do {
            try center.startMonitoring(activity, during: schedule)
        } catch {
            // The app still ends the shield when it next becomes active (refresh).
            lastError = "iOS didn't accept the focus schedule. Open Wake Me when it ends so the apps open again."
        }
    }

    private func save(_ s: FocusSession) {
        session = s
        shared.set(try? JSONEncoder().encode(s), forKey: "focus.session")
        // What the extensions read, without depending on WakeMeCore.
        shared.set(Double(s.endsAt), forKey: "focus.endsAt")
        shared.set(s.endedAt != nil, forKey: "focus.ended")
        shared.set(s.friendName, forKey: "focus.friendName")
    }

    nonisolated static func nowMs() -> Int64 { Int64(Date().timeIntervalSince1970 * 1000) }
}
