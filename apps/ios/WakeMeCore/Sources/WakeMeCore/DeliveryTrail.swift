/// The sender-facing delivery trail: sent → reached their phone → seen (a quack) or rang (a wake
/// alarm). A step counts as reached only when the literal display status reports evidence for it,
/// so an animation can never advance the trail further than the server knows. Same table as
/// packages/policy-fixtures/fixtures/delivery-trail.json and Android `DeliveryTrail`.
public struct DeliveryTrail: Equatable, Sendable {
    public enum State: String, Sendable { case active, waiting, problem, done }

    public static let steps = 3

    public let reached: Int
    public let state: State

    public init(reached: Int, state: State) {
        self.reached = reached
        self.state = state
    }

    public init(displayStatus: String, alarm: Bool) {
        switch displayStatus {
        case "sending": self.init(reached: 0, state: .active)
        case "sent": self.init(reached: 1, state: .active)
        case "no_confirmation_yet": self.init(reached: 1, state: .waiting)
        case "not_delivered", "expired", "cancelled": self.init(reached: 1, state: .problem)
        case "phone_received", "alarm_scheduled_on_phone": self.init(reached: 2, state: .active)
        case "not_played": self.init(reached: 2, state: .problem)
        // Opening or acknowledging is seeing a quack, but it doesn't prove an alarm rang.
        case "opened", "acknowledged": self.init(reached: alarm ? 2 : 3, state: .done)
        // Ringing is the last step for an alarm; a completed challenge never means "awake".
        case "alarm_started", "stopped", "ended_without_challenge", "challenge_completed": self.init(reached: alarm ? 3 : 2, state: .done)
        // A status from a newer server claims no more than "sent".
        default: self.init(reached: 1, state: .active)
        }
    }
}
