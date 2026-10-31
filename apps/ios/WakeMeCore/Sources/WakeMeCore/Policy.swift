import Foundation

/// Clock skew tolerated at expiry and window edges (server uses the same value).
public let clockSkewSeconds: TimeInterval = 30

public struct IncomingCommand: Equatable, Sendable {
    public var id: String
    public var capability: String
    public var actorId: String
    public var grantVersion: Int
    public var expiresAt: String
    public var soundId: String?
    public var ringSeconds: Int?

    public init(id: String, capability: String, actorId: String, grantVersion: Int, expiresAt: String, soundId: String? = nil, ringSeconds: Int? = nil) {
        self.id = id
        self.capability = capability
        self.actorId = actorId
        self.grantVersion = grantVersion
        self.expiresAt = expiresAt
        self.soundId = soundId
        self.ringSeconds = ringSeconds
    }
}

public enum LocalDecision: Equatable, Sendable {
    /// mode: ring (native alarm), notify (ordinary notification), silent (no sound).
    case execute(mode: String)
    case skip(reason: String)
}

public func isGrantActive(_ grant: GrantSnapshot?, now: Date) throws -> Bool {
    guard let grant, grant.revokedAt == nil else { return false }
    return try now >= Instant.parse(grant.validFrom) && now < Instant.parse(grant.validUntil)
}

public func grantAllows(_ grant: GrantSnapshot?, _ capability: String, now: Date) throws -> Bool {
    guard let grant, try isGrantActive(grant, now: now) else { return false }
    return !grant.paused && grant.capabilities.contains(capability)
}

/// Re-validates a remote command on the phone that would ring, against the
/// locally cached grant. A revoked or narrowed grant wins even if the server
/// dispatched first. Works offline. Mirrors packages/policy evaluateLocalExecution.
public func evaluateLocalExecution(
    now: Date,
    command: IncomingCommand,
    localGrant: GrantSnapshot?,
    zone: TimeZone,
    settings: RecipientSettings,
    alreadyProcessed: Bool
) throws -> LocalDecision {
    if alreadyProcessed { return .skip(reason: "duplicate") }
    if now > (try Instant.parse(command.expiresAt)).addingTimeInterval(clockSkewSeconds) { return .skip(reason: "expired") }
    guard let grant = localGrant, grant.granteeId == command.actorId else { return .skip(reason: "not_authorized_locally") }
    if grant.version < command.grantVersion { return .skip(reason: "needs_sync") }
    if !(try grantAllows(grant, command.capability, now: now)) { return .skip(reason: "not_authorized_locally") }

    let isSafety = Capability.safety.contains(command.capability)
    let paused = try settings.pausedUntil.map { try Instant.parse($0) > now } ?? false
    if paused && !isSafety { return .skip(reason: "recipient_paused") }

    var windows: [LocalWindow]?
    var quietOverride = false
    switch command.capability {
    case Capability.nudge:
        guard let l = grant.limits.nudge else { return .skip(reason: "not_authorized_locally") }
        guard let sound = command.soundId, l.sounds.contains(sound) else { return .skip(reason: "not_authorized_locally") }
        windows = l.windows
        quietOverride = l.overridesQuietHours
    case Capability.alarmRequest:
        guard let l = grant.limits.alarm else { return .skip(reason: "not_authorized_locally") }
        guard let sound = command.soundId, l.sounds.contains(sound) else { return .skip(reason: "not_authorized_locally") }
        guard let ring = command.ringSeconds, ring <= l.maxAlarmSeconds else { return .skip(reason: "not_authorized_locally") }
        windows = l.windows
        quietOverride = true
    case Capability.focus:
        windows = grant.limits.focus?.windows
    default:
        break
    }
    if let windows {
        let skewed = now.addingTimeInterval(-clockSkewSeconds)
        let inside = try isWithinAnyWindow(now, zone, windows)
        let insideSkewed = try isWithinAnyWindow(skewed, zone, windows)
        if !inside && !insideSkewed {
            return .skip(reason: "outside_allowed_window")
        }
    }
    if try isQuietTime(now, zone, settings.quietHours) {
        if isSafety { return .execute(mode: "silent") }
        if !quietOverride { return .skip(reason: "quiet_hours") }
    }
    return command.capability == Capability.alarmRequest ? .execute(mode: "ring") : .execute(mode: "notify")
}
