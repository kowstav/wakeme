import Foundation

/// Never longer than this, whatever a grant says (doc 02 §12: focus restrictions are finite).
public let focusMaxMinutes = 240
public let focusBreakMinutes = 5
public let focusMaxBreaks = 3

/// A focus session on this phone (ADR 0014). Times are epoch milliseconds, as on Android. The end,
/// breaks and "End focus" never wait for the server. Mirrors Kotlin FocusSession.
public struct FocusSession: Codable, Equatable, Sendable {
    public var id: String
    public var startedAt: Int64
    public var endsAt: Int64
    public var friendId: String?
    public var friendName: String?
    /// The friend's request this session answers, if any.
    public var commandId: String?
    public var breakUntil: Int64?
    public var breaksTaken: Int
    public var endedAt: Int64?
    /// "completed" | "ended_early"
    public var endReason: String?

    public init(id: String, startedAt: Int64, endsAt: Int64, friendId: String? = nil, friendName: String? = nil, commandId: String? = nil,
                breakUntil: Int64? = nil, breaksTaken: Int = 0, endedAt: Int64? = nil, endReason: String? = nil) {
        self.id = id
        self.startedAt = startedAt
        self.endsAt = endsAt
        self.friendId = friendId
        self.friendName = friendName
        self.commandId = commandId
        self.breakUntil = breakUntil
        self.breaksTaken = breaksTaken
        self.endedAt = endedAt
        self.endReason = endReason
    }
}

public enum FocusState: Equatable, Sendable {
    case active(remainingMs: Int64)
    case onBreak(breakRemainingMs: Int64, sessionRemainingMs: Int64)
    case finished(reason: String)
}

/// Starts a session bounded by the friend's limit (if it answers a friend) and by `focusMaxMinutes`.
public func startFocus(id: String, now: Int64, minutes: Int, limits: FocusLimits? = nil, friendId: String? = nil, friendName: String? = nil, commandId: String? = nil) -> FocusSession? {
    guard minutes > 0 else { return nil }
    let cap = min(focusMaxMinutes, limits?.maxSessionMinutes ?? focusMaxMinutes)
    let length = min(minutes, cap)
    return FocusSession(id: id, startedAt: now, endsAt: now + Int64(length) * 60_000, friendId: friendId, friendName: friendName, commandId: commandId)
}

public func focusState(_ s: FocusSession, now: Int64) -> FocusState {
    if s.endedAt != nil { return .finished(reason: s.endReason ?? "ended_early") }
    if now >= s.endsAt { return .finished(reason: "completed") }
    if let until = s.breakUntil, now < until { return .onBreak(breakRemainingMs: until - now, sessionRemainingMs: s.endsAt - now) }
    return .active(remainingMs: s.endsAt - now)
}

/// The chosen apps are shielded only while a session is active and not on a break.
public func shouldShield(_ s: FocusSession?, now: Int64) -> Bool {
    guard let s else { return false }
    if case .active = focusState(s, now: now) { return true }
    return false
}

/// A finished challenge earns a short break, at most `focusMaxBreaks` per session; nil if none is left.
public func takeBreak(_ s: FocusSession, now: Int64) -> FocusSession? {
    guard case .active = focusState(s, now: now), s.breaksTaken < focusMaxBreaks else { return nil }
    var next = s
    next.breakUntil = min(s.endsAt, now + Int64(focusBreakMinutes) * 60_000)
    next.breaksTaken += 1
    return next
}

/// "End focus": always allowed, takes effect at once (the safe escape).
public func endFocus(_ s: FocusSession, now: Int64) -> FocusSession {
    guard s.endedAt == nil else { return s }
    var next = s
    next.endedAt = now
    next.endReason = now >= s.endsAt ? "completed" : "ended_early"
    return next
}
