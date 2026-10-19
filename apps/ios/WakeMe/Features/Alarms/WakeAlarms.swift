import ActivityKit
import AlarmKit
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

/// The wake session the app should show next (set by the alarm's challenge button).
@MainActor
@Observable
final class WakeSessionRouter {
    static let shared = WakeSessionRouter()
    struct Pending: Equatable { let planId: String; let occurrenceKey: String }
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

/// AlarmKit integration (doc 06): alarms are scheduled locally from the accepted routine
/// version, so ringing never needs the network. The system Stop control is kept as is
/// (current `Alert` initializer without a custom stop button). The secondary button opens
/// the challenge. Completing the challenge cancels the follow-up; stopping does not.
@MainActor
final class WakeAlarms {
    static let shared = WakeAlarms()
    private let manager = AlarmManager.shared
    private let installedKey = "wakeme.installedAlarms" // planId → [alarm UUID]

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

    /// Installs the next occurrence (and its follow-up, if accepted), replacing what was there.
    func install(_ routine: InstalledRoutine, label: String, now: Date = Date()) async -> InstallResult {
        guard authorization == .authorized else { return .notAuthorized }
        let alarms: [ScheduledAlarm]
        do {
            alarms = try scheduleFor(routine, now: now, deviceZone: .current)
        } catch {
            return .failed("invalid_routine")
        }
        cancel(planId: routine.planId)
        if alarms.isEmpty { return .nothingToSchedule }
        var ids: [String] = []
        do {
            for a in alarms {
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
                    secondaryIntent: hasChallenge ? OpenWakeChallengeIntent(planId: a.planId, occurrenceKey: a.occurrenceKey) : nil,
                    sound: Self.sound(for: routine.soundId)
                )
                _ = try await manager.schedule(id: a.alarmUUID, configuration: configuration)
                ids.append(a.alarmUUID.uuidString)
            }
        } catch {
            for id in ids.compactMap(UUID.init(uuidString:)) { try? manager.cancel(id: id) }
            return .failed("alarmkit_schedule_failed")
        }
        var installed = installedMap()
        installed[routine.planId] = ids
        UserDefaults.standard.set(installed, forKey: installedKey)
        return .scheduled(alarms.map(\.fireAt))
    }

    /// Local cancellation first, so an ended routine never rings even when offline.
    func cancel(planId: String) {
        var installed = installedMap()
        for id in (installed[planId] ?? []).compactMap(UUID.init(uuidString:)) { try? manager.cancel(id: id) }
        installed[planId] = nil
        UserDefaults.standard.set(installed, forKey: installedKey)
    }

    /// Completion or the safe escape cancels the pending follow-up; the system Stop alone does not.
    func cancelFollowUp(planId: String, occurrenceKey: String) {
        let id = ScheduledAlarm(planId: planId, version: 0, occurrenceKey: occurrenceKey, role: .followUp, fireAt: Date()).alarmUUID
        try? manager.cancel(id: id)
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

    private func installedMap() -> [String: [String]] {
        UserDefaults.standard.dictionary(forKey: installedKey) as? [String: [String]] ?? [:]
    }

    /// Bundled sounds ship in the app; approved custom sounds fall back to a bundled one when not cached.
    static func sound(for soundId: String) -> AlertConfiguration.AlertSound {
        let bundled = ["bundled:quack.classic": "quack_classic", "bundled:quack.double": "quack_double", "bundled:chime.soft": "chime_soft",
                       "bundled:bell.bright": "bell_bright", "bundled:rise.gentle": "rise_gentle", "bundled:pulse.wake": "pulse_wake"]
        return .named("\(bundled[soundId] ?? "rise_gentle").wav")
    }
}
