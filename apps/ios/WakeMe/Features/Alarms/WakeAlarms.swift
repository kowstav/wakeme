import ActivityKit
@preconcurrency import AlarmKit
import AppIntents
import Foundation
import SwiftUI
import WakeMeCore

/// What travels with an AlarmKit alarm, so the app can open the right wake session.
nonisolated struct WakeAlarmMetadata: AlarmMetadata {
    var planId: String
    var occurrenceKey: String
    var role: String
    var challengeRevisionId: String?
}

/// Opens the in-app wake challenge from the alarm's secondary button. Never needs the
/// network: the challenge revision was installed and verified with the routine.
struct OpenWakeChallengeIntent: LiveActivityIntent {
    static let title: LocalizedStringResource = "Start wake challenge"
    static let openAppWhenRun = true

    @Parameter(title: "Plan") var planId: String
    @Parameter(title: "Occurrence") var occurrenceKey: String

    init() {}
    init(planId: String, occurrenceKey: String) {
        self.planId = planId
        self.occurrenceKey = occurrenceKey
    }

    func perform() async throws -> some IntentResult {
        await MainActor.run { WakeSessionRouter.shared.pending = .init(planId: planId, occurrenceKey: occurrenceKey) }
        return .result()
    }
}

/// Runs after the person stops an alarm with the system control; the alarm has already
/// stopped, and this never replaces or delays stopping. Records "stopped" (never completion)
/// and arms the occurrence that entered the window (ADR 0011). Needs no network: the outcome
/// waits in the queue.
struct WakeAlarmStoppedIntent: LiveActivityIntent {
    static let title: LocalizedStringResource = "Wake alarm stopped"
    static let isDiscoverable = false

    @Parameter(title: "Plan") var planId: String
    @Parameter(title: "Occurrence") var occurrenceKey: String
    @Parameter(title: "Version") var version: Int

    init() {}
    init(planId: String, occurrenceKey: String, version: Int) {
        self.planId = planId
        self.occurrenceKey = occurrenceKey
        self.version = version
    }

    func perform() async throws -> some IntentResult {
        // Local work first; reporting to the server comes last and may finish later.
        await ReceiptQueue.shared.record(.init(planId: planId, occurrenceKey: occurrenceKey, version: version, outcome: "stopped"))
        await SyncEngine.shared.topUp()
        return .result()
    }
}

/// The wake session the app should show next (set by the alarm's challenge button).
@MainActor
@Observable
final class WakeSessionRouter {
    static let shared = WakeSessionRouter()
    struct Pending: Equatable, Identifiable {
        let planId: String
        let occurrenceKey: String
        var id: String { "\(planId):\(occurrenceKey)" }
    }
    var pending: Pending?
}

enum AlarmAuthorization: Equatable {
    case notDetermined, denied, authorized
}

/// Result of installing a routine, reported to the server as an installation receipt.
enum InstallResult: Equatable {
    case scheduled([Date])
    case nothingToSchedule
    case notAuthorized
    case failed(String)
}

/// What this phone armed for one routine, kept so the window can be topped up offline.
struct ArmedRoutine: Codable, Equatable {
    struct Alarm: Codable, Equatable {
        var id: UUID
        var role: AlarmRole
        var occurrenceKey: String
        var fireAt: Date
    }

    var routine: InstalledRoutine
    var label: String
    /// Repeated in each receipt for this version (e.g. challenge_unavailable).
    var errorCode: String?
    var alarms: [Alarm]
    /// Occurrences whose wake session finished: their follow-up is never armed again.
    var finished: [String] = []
}

/// AlarmKit integration (doc 06): alarms are scheduled locally from the accepted routine
/// version, so ringing never needs the network. Each routine is armed over a rolling window
/// (ADR 0011) because the app doesn't run when an alarm rings; the window is topped up whenever
/// it does. The system Stop control is kept as is (current `Alert` initializer without a custom
/// stop button). The secondary button opens the challenge. Completing the challenge cancels the
/// follow-up; stopping does not.
/// Not main-actor isolated: AlarmManager is a thread-safe system service and isn't Sendable,
/// so its async calls must stay in this type's own (nonisolated) context. Installs and top-ups
/// are serialized by SyncEngine; the bookkeeping itself is behind a lock.
final class WakeAlarms: @unchecked Sendable {
    static let shared = WakeAlarms()
    private let manager = AlarmManager.shared
    private let armedKey = "wakeme.armed" // planId → ArmedRoutine
    private let lock = NSLock()

    var authorization: AlarmAuthorization {
        switch manager.authorizationState {
        case .authorized: .authorized
        case .denied: .denied
        default: .notDetermined
        }
    }

    /// Asked for only when the person sets up a wake-up (doc 03 §5 step 7).
    func requestAuthorization() async -> AlarmAuthorization {
        do {
            switch try await manager.requestAuthorization() {
            case .authorized: return .authorized
            case .denied: return .denied
            default: return .notDetermined
            }
        } catch {
            return authorization
        }
    }

    /// Installs the routine's window, replacing whatever was armed for it. Reports exactly what
    /// AlarmKit accepted: if it stops accepting alarms part-way, the earlier ones stay armed.
    func install(_ routine: InstalledRoutine, label: String, errorCode: String? = nil, now: Date = Date()) async -> InstallResult {
        guard authorization == .authorized else { return .notAuthorized }
        let alarms: [ScheduledAlarm]
        do {
            alarms = try scheduleFor(routine, now: now, deviceZone: .current)
        } catch {
            return .failed("invalid_routine")
        }
        cancel(planId: routine.planId)
        if alarms.isEmpty { return .nothingToSchedule }
        var armed: [ArmedRoutine.Alarm] = []
        for a in alarms {
            do {
                try await schedule(a, routine: routine, label: label)
            } catch {
                break
            }
            armed.append(.init(id: a.alarmUUID, role: a.role, occurrenceKey: a.occurrenceKey, fireAt: a.fireAt))
        }
        guard !armed.isEmpty else { return .failed("alarmkit_schedule_failed") }
        update { $0[routine.planId] = ArmedRoutine(routine: routine, label: label, errorCode: errorCode, alarms: armed) }
        return .scheduled(armed.map(\.fireAt))
    }

    /// Arms the occurrences that entered the window since the routine was installed, and
    /// re-arms future alarms AlarmKit no longer has (alarms turned off and on again). Alarms
    /// already armed stay as they are: a follow-up pending for an occurrence that just rang is
    /// kept, and one cancelled by finishing the wake session isn't brought back. Local only.
    /// Returns true when alarms were added.
    @discardableResult
    func topUp(planId: String, now: Date = Date()) async -> Bool {
        guard authorization == .authorized, let entry = armedMap()[planId] else { return false }
        let present = (try? manager.alarms).map { Set($0.map(\.id)) }
        var fresh: [ArmedRoutine.Alarm] = []
        func arm(_ a: ScheduledAlarm) async -> Bool {
            guard (try? await schedule(a, routine: entry.routine, label: entry.label)) != nil else { return false }
            fresh.append(.init(id: a.alarmUUID, role: a.role, occurrenceKey: a.occurrenceKey, fireAt: a.fireAt))
            return true
        }
        if let present {
            for a in entry.alarms where a.fireAt > now && !present.contains(a.id) && isArmed(planId: planId, id: a.id) {
                _ = await arm(ScheduledAlarm(planId: planId, version: entry.routine.version, occurrenceKey: a.occurrenceKey, role: a.role, fireAt: a.fireAt))
            }
        }
        let known = Set(entry.alarms.map(\.id))
        var added = false
        for a in (try? scheduleFor(entry.routine, now: now, deviceZone: .current)) ?? [] where !known.contains(a.alarmUUID) {
            if a.role == .followUp && entry.finished.contains(a.occurrenceKey) { continue }
            guard await arm(a) else { break }
            added = true
        }
        let cutoff = now.addingTimeInterval(-86_400)
        var orphaned: [ArmedRoutine.Alarm] = []
        update { map in
            // Merge into what is stored now; it may have changed while AlarmKit was busy.
            guard var current = map[planId] else {
                orphaned = fresh
                return
            }
            guard current.routine == entry.routine else { return }
            current.alarms.removeAll { $0.fireAt < cutoff }
            for f in fresh where !current.alarms.contains(where: { $0.id == f.id }) { current.alarms.append(f) }
            current.alarms.sort { $0.fireAt < $1.fireAt }
            map[planId] = current
        }
        // The routine was ended meanwhile: nothing just added may ring.
        for a in orphaned { try? manager.cancel(id: a.id) }
        return added && orphaned.isEmpty
    }

    /// Alarms still ahead for a routine, soonest first.
    func armed(planId: String, after now: Date = Date()) -> [ArmedRoutine.Alarm] {
        (armedMap()[planId]?.alarms ?? []).filter { $0.fireAt > now }.sorted { $0.fireAt < $1.fireAt }
    }

    func armedRoutine(planId: String) -> ArmedRoutine? { armedMap()[planId] }

    /// The installed version and challenge for a routine on this phone (for the wake session).
    func installedRoutine(planId: String) -> (version: Int, challengeRevisionId: String?)? {
        armedMap()[planId].map { ($0.routine.version, $0.routine.challengeRevisionId) }
    }

    /// Local cancellation first, so an ended routine never rings even when offline.
    func cancel(planId: String) {
        var removed: ArmedRoutine?
        update { map in removed = map.removeValue(forKey: planId) }
        for a in removed?.alarms ?? [] { try? manager.cancel(id: a.id) }
    }

    /// Completion or the safe escape cancels the pending follow-up; the system Stop alone does not.
    func cancelFollowUp(planId: String, occurrenceKey: String) {
        let id = ScheduledAlarm(planId: planId, version: 0, occurrenceKey: occurrenceKey, role: .followUp, fireAt: Date()).alarmUUID
        update { map in
            guard var entry = map[planId] else { return }
            entry.alarms.removeAll { $0.id == id }
            if !entry.finished.contains(occurrenceKey) { entry.finished = Array((entry.finished + [occurrenceKey]).suffix(16)) }
            map[planId] = entry
        }
        try? manager.cancel(id: id)
    }

    func installedPlanIds() -> [String] { Array(armedMap().keys) }

    private func schedule(_ a: ScheduledAlarm, routine: InstalledRoutine, label: String) async throws {
        let metadata = WakeAlarmMetadata(planId: a.planId, occurrenceKey: a.occurrenceKey, role: a.role.rawValue, challengeRevisionId: routine.challengeRevisionId)
        let title: LocalizedStringResource = a.role == .main ? "\(label.isEmpty ? "Wake up" : label)" : "Follow-up: \(label.isEmpty ? "wake up" : label)"
        let hasChallenge = routine.challengeRevisionId != nil
        let alert = Self.alert(
            title: title,
            secondary: hasChallenge ? AlarmButton(text: "Start challenge", textColor: WMColor.textOnAccent, systemImageName: "puzzlepiece.extension") : nil
        )
        let attributes = AlarmAttributes<WakeAlarmMetadata>(presentation: AlarmPresentation(alert: alert), metadata: metadata, tintColor: WMColor.accent)
        let configuration = AlarmManager.AlarmConfiguration<WakeAlarmMetadata>.alarm(
            schedule: .fixed(a.fireAt),
            attributes: attributes,
            stopIntent: WakeAlarmStoppedIntent(planId: a.planId, occurrenceKey: a.occurrenceKey, version: a.version),
            secondaryIntent: hasChallenge ? OpenWakeChallengeIntent(planId: a.planId, occurrenceKey: a.occurrenceKey) : nil,
            sound: Self.sound(for: routine.soundId)
        )
        _ = try await manager.schedule(id: a.alarmUUID, configuration: configuration)
    }

    private func isArmed(planId: String, id: UUID) -> Bool {
        armedMap()[planId]?.alarms.contains { $0.id == id } ?? false
    }

    private func armedMap() -> [String: ArmedRoutine] {
        lock.lock()
        defer { lock.unlock() }
        return load()
    }

    private func update(_ change: (inout [String: ArmedRoutine]) -> Void) {
        lock.lock()
        defer { lock.unlock() }
        var map = load()
        change(&map)
        UserDefaults.standard.set(try? JSONEncoder().encode(map), forKey: armedKey)
    }

    private func load() -> [String: ArmedRoutine] {
        UserDefaults.standard.data(forKey: armedKey).flatMap { try? JSONDecoder().decode([String: ArmedRoutine].self, from: $0) } ?? [:]
    }

    /// A one-off alarm about a minute from now with a bundled sound, for "Make this phone ready".
    func scheduleTestAlarm(in seconds: TimeInterval = 60) async -> InstallResult {
        guard authorization == .authorized else { return .notAuthorized }
        let fireAt = Date().addingTimeInterval(seconds)
        let alert = Self.alert(title: "Wake Me test alarm", secondary: nil)
        let attributes = AlarmAttributes<WakeAlarmMetadata>(presentation: AlarmPresentation(alert: alert), metadata: WakeAlarmMetadata(planId: "test", occurrenceKey: "test", role: "MAIN"), tintColor: WMColor.accent)
        do {
            _ = try await manager.schedule(id: stableUUID("test-alarm"), configuration: .alarm(schedule: .fixed(fireAt), attributes: attributes, sound: Self.sound(for: "bundled:rise.gentle")))
            return .scheduled([fireAt])
        } catch {
            return .failed("alarmkit_schedule_failed")
        }
    }

    /// iOS 26.1+ supplies the system Stop control itself (doc 06: use the current initializer);
    /// iOS 26.0 needs an explicit stop button, labelled plainly.
    static func alert(title: LocalizedStringResource, secondary: AlarmButton?) -> AlarmPresentation.Alert {
        if #available(iOS 26.1, *) {
            return AlarmPresentation.Alert(title: title, secondaryButton: secondary, secondaryButtonBehavior: secondary == nil ? nil : .custom)
        }
        return AlarmPresentation.Alert(
            title: title,
            stopButton: AlarmButton(text: "Stop", textColor: .white, systemImageName: "stop.circle"),
            secondaryButton: secondary,
            secondaryButtonBehavior: secondary == nil ? nil : .custom
        )
    }

    /// Bundled sounds ship in the app; approved custom sounds fall back to a bundled one when not cached.
    static func sound(for soundId: String) -> AlertConfiguration.AlertSound {
        let bundled = ["bundled:quack.classic": "quack_classic", "bundled:quack.double": "quack_double", "bundled:chime.soft": "chime_soft",
                       "bundled:bell.bright": "bell_bright", "bundled:rise.gentle": "rise_gentle", "bundled:pulse.wake": "pulse_wake"]
        return .named("\(bundled[soundId] ?? "rise_gentle").wav")
    }
}
