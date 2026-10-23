import Foundation

/// Delivery evidence reduced by precedence (never last-event-wins). Mirrors packages/policy delivery.ts.
public struct DeliveryEvent: Equatable, Sendable {
    public var kind: String
    public var seq: Int64
    public var reason: String?
    public init(kind: String, seq: Int64, reason: String? = nil) {
        self.kind = kind
        self.seq = seq
        self.reason = reason
    }
}

public struct DeliveryState: Equatable, Sendable {
    public var lifecycle = "accepted"
    public var transport = "queued"
    public var device = "unknown"
    public var human = "none"
    public var refusedReason: String?

    public init(lifecycle: String = "accepted", transport: String = "queued", device: String = "unknown", human: String = "none", refusedReason: String? = nil) {
        self.lifecycle = lifecycle
        self.transport = transport
        self.device = device
        self.human = human
        self.refusedReason = refusedReason
    }
}

private let transportRank = ["queued": 1, "not_sent": 2, "provider_rejected": 2, "provider_accepted": 3]
private let deviceRank = ["unknown": 0, "received": 1, "installed": 2, "execution_reported": 3]
private let humanRank = ["none": 0, "opened": 1, "acknowledged": 2, "challenge_completed": 3, "bypassed": 3]

public func reduceDelivery(_ events: [DeliveryEvent]) -> DeliveryState {
    var s = DeliveryState()
    var transportSeen = false
    // Stable sort by sequence, like Kotlin's sortedBy.
    let ordered = events.enumerated().sorted { ($0.element.seq, $0.offset) < ($1.element.seq, $1.offset) }.map(\.element)
    for e in ordered {
        switch e.kind {
        case "expired", "cancelled":
            if s.lifecycle == "accepted" { s.lifecycle = e.kind }
        case "queued", "provider_accepted", "provider_rejected", "not_sent":
            if !transportSeen || transportRank[e.kind]! > transportRank[s.transport]! { s.transport = e.kind }
            transportSeen = true
        case "received", "installed", "execution_reported":
            if deviceRank[e.kind]! > deviceRank[s.device]! { s.device = e.kind }
        case "execution_refused":
            if s.refusedReason == nil { s.refusedReason = e.reason ?? "refused" }
            if s.device == "unknown" { s.device = "received" }
        case "opened", "acknowledged", "challenge_completed", "bypassed":
            if humanRank[s.human]! != 3 && humanRank[e.kind]! > humanRank[s.human]! { s.human = e.kind }
        default:
            break
        }
    }
    if s.human != "none" && s.device == "unknown" { s.device = "received" }
    return s
}

/// Literal sender-facing status. "phone_received" is not "heard it"; "challenge_completed" is not "awake".
public func displayStatus(_ state: DeliveryState, now: Date, sentAt: Date, kind: String, shareBypass: Bool, graceSeconds: TimeInterval = 60) -> String {
    switch state.human {
    case "challenge_completed": return "challenge_completed"
    case "bypassed": return shareBypass ? "ended_without_challenge" : "stopped"
    case "acknowledged": return "acknowledged"
    case "opened": return "opened"
    default: break
    }
    if state.refusedReason != nil && state.device != "execution_reported" { return "not_played" }
    switch state.device {
    case "execution_reported": return kind == "alarm" ? "alarm_started" : "phone_received"
    case "installed": return kind == "alarm" ? "alarm_scheduled_on_phone" : "phone_received"
    case "received": return "phone_received"
    default: break
    }
    if state.lifecycle == "cancelled" { return "cancelled" }
    if state.lifecycle == "expired" {
        return state.transport == "provider_rejected" || state.transport == "not_sent" ? "not_delivered" : "expired"
    }
    switch state.transport {
    case "queued": return "sending"
    case "provider_rejected", "not_sent": return "not_delivered"
    default: return now.timeIntervalSince(sentAt).rounded(.towardZero) < graceSeconds ? "sent" : "no_confirmation_yet"
    }
}
