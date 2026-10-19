import Foundation

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

public struct ScheduledAlarm: Equatable, Sendable {
    public let planId: String
    public let version: Int
    public let occurrenceKey: String
    public let role: AlarmRole
    public let fireAt: Date

    /// Stable identifier so re-scheduling replaces instead of duplicating (AlarmKit/notification id).
    public var stableId: String { "\(planId):\(occurrenceKey):\(role.rawValue)" }
}

/// Computes the alarms to install: the next occurrence and, if the owner
/// accepted it, one follow-up. Completing the challenge cancels the follow-up
/// locally; stopping the main alarm with the system control does not.
public func scheduleFor(_ routine: InstalledRoutine, now: Date, deviceZone: TimeZone) throws -> [ScheduledAlarm] {
    if routine.paused { return [] }
    let zone = routine.timeZonePolicy == "device" ? deviceZone : try timeZone(routine.timeZone)
    guard let next = try nextOccurrences(routine.localTime, zone, routine.recurrence, after: now, count: 1).first else { return [] }
    let main = ScheduledAlarm(planId: routine.planId, version: routine.version, occurrenceKey: next.localDate, role: .main, fireAt: next.instant)
    guard let delay = routine.followUpDelaySeconds else { return [main] }
    return [main, ScheduledAlarm(planId: routine.planId, version: routine.version, occurrenceKey: next.localDate, role: .followUp, fireAt: next.instant.addingTimeInterval(TimeInterval(delay)))]
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
