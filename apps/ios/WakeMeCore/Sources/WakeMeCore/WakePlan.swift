import Foundation
#if canImport(CryptoKit)
import CryptoKit
#else
import Crypto
#endif

/// An installed routine version as the phone keeps it: everything needed to
/// ring and run the challenge with no network at ring time.
public struct InstalledRoutine: Codable, Equatable, Sendable {
    public var planId: String
    public var version: Int
    public var label: String
    public var localTime: String
    public var timeZone: String
    public var timeZonePolicy: String
    public var recurrence: Recurrence
    public var soundId: String
    public var ringSeconds: Int
    public var challengeRevisionId: String?
    public var followUpDelaySeconds: Int?
    public var paused: Bool

    public init(planId: String, version: Int, label: String, localTime: String, timeZone: String, timeZonePolicy: String, recurrence: Recurrence, soundId: String, ringSeconds: Int, challengeRevisionId: String?, followUpDelaySeconds: Int?, paused: Bool) {
        self.planId = planId
        self.version = version
        self.label = label
        self.localTime = localTime
        self.timeZone = timeZone
        self.timeZonePolicy = timeZonePolicy
        self.recurrence = recurrence
        self.soundId = soundId
        self.ringSeconds = ringSeconds
        self.challengeRevisionId = challengeRevisionId
        self.followUpDelaySeconds = followUpDelaySeconds
        self.paused = paused
    }
}

public enum AlarmRole: String, Codable, Sendable { case main = "MAIN", followUp = "FOLLOW_UP" }

/// Wake-ups set up before signing in live only on this phone, under ids with this prefix. They
/// never produce server receipts and move to the account at sign-in (same rule as Android).
public let localPlanPrefix = "local-"

public func isLocalPlan(_ planId: String) -> Bool { planId.hasPrefix(localPlanPrefix) }

/// ADR 0007: only the primary phone installs and rings routines. With no primary
/// recorded yet, the registering phone counts as primary so a lone phone never
/// skips its alarms. Same rule as Android `installsRoutines`.
public func installsRoutines(primaryDeviceId: String?, thisDeviceId: String?) -> Bool {
    guard let primaryDeviceId else { return true }
    return thisDeviceId == primaryDeviceId
}

public struct ScheduledAlarm: Equatable, Sendable {
    public let planId: String
    public let version: Int
    public let occurrenceKey: String
    public let role: AlarmRole
    public let fireAt: Date

    public init(planId: String, version: Int, occurrenceKey: String, role: AlarmRole, fireAt: Date) {
        self.planId = planId
        self.version = version
        self.occurrenceKey = occurrenceKey
        self.role = role
        self.fireAt = fireAt
    }

    /// Stable identifier so re-scheduling replaces instead of duplicating (AlarmKit/notification id).
    public var stableId: String { "\(planId):\(occurrenceKey):\(role.rawValue)" }

    /// A name-based UUID (version 5 layout over SHA-256) of `stableId`: the same
    /// occurrence always maps to the same AlarmKit alarm ID.
    public var alarmUUID: UUID { stableUUID(stableId) }
}

/// Deterministic UUID for a name: SHA-256, truncated to 16 bytes, with RFC 4122 version 5 and variant bits.
public func stableUUID(_ name: String) -> UUID {
    var b = Array(SHA256.hash(data: Data(("wakeme:" + name).utf8)).prefix(16))
    b[6] = (b[6] & 0x0F) | 0x50
    b[8] = (b[8] & 0x3F) | 0x80
    return UUID(uuid: (b[0], b[1], b[2], b[3], b[4], b[5], b[6], b[7], b[8], b[9], b[10], b[11], b[12], b[13], b[14], b[15]))
}

/// Routines are armed this far ahead (ADR 0011); the app tops the window up whenever it runs.
public let armWindowDays = 8

/// At most this many occurrences of one routine are armed at once.
public let armMaxOccurrences = 8

/// Computes the alarms to install: every occurrence in the next `armWindowDays` days (at most
/// `armMaxOccurrences`, and always the next one even when it is further away), each with one
/// follow-up if the owner accepted it. Arming ahead keeps a routine ringing when the app doesn't
/// run between occurrences. Completing the challenge cancels that occurrence's follow-up locally;
/// stopping the main alarm with the system control does not.
public func scheduleFor(_ routine: InstalledRoutine, now: Date, deviceZone: TimeZone) throws -> [ScheduledAlarm] {
    if routine.paused { return [] }
    let zone = routine.timeZonePolicy == "device" ? deviceZone : try timeZone(routine.timeZone)
    let until = now.addingTimeInterval(TimeInterval(armWindowDays * 86_400))
    let occurrences = try nextOccurrences(routine.localTime, zone, routine.recurrence, after: now, count: armMaxOccurrences)
        .enumerated().filter { $0.offset == 0 || $0.element.instant <= until }.map(\.element)
    return occurrences.flatMap { o -> [ScheduledAlarm] in
        let main = ScheduledAlarm(planId: routine.planId, version: routine.version, occurrenceKey: o.localDate, role: .main, fireAt: o.instant)
        guard let delay = routine.followUpDelaySeconds else { return [main] }
        return [main, ScheduledAlarm(planId: routine.planId, version: routine.version, occurrenceKey: o.localDate, role: .followUp, fireAt: o.instant.addingTimeInterval(TimeInterval(delay)))]
    }
}

/// States of one wake session on the phone. Transitions are monotonic.
public enum WakeSessionState: String, Codable, Sendable { case ringing, challenge, followUpPending, completed, bypassed, ended }

public struct WakeSession: Equatable, Sendable {
    public var planId: String
    public var occurrenceKey: String
    public var state: WakeSessionState
    public var hasFollowUp: Bool

    public init(planId: String, occurrenceKey: String, state: WakeSessionState, hasFollowUp: Bool) {
        self.planId = planId
        self.occurrenceKey = occurrenceKey
        self.state = state
        self.hasFollowUp = hasFollowUp
    }

    public func onSystemStop() -> WakeSession {
        guard state == .ringing || state == .challenge else { return self }
        var s = self
        s.state = hasFollowUp ? .followUpPending : .ended
        return s
    }

    public func onChallengeCompleted() -> WakeSession {
        if [.completed, .bypassed, .ended].contains(state) { return self }
        var s = self
        s.state = .completed
        return s
    }

    /// The always-available safe escape: stops everything and cancels the follow-up.
    public func onEscape() -> WakeSession {
        if [.completed, .ended].contains(state) { return self }
        var s = self
        s.state = .bypassed
        return s
    }

    /// Completion or the safe escape cancels the pending follow-up; a system Stop alone does not.
    public var cancelsFollowUp: Bool { state == .completed || state == .bypassed }
}
