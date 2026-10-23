import Foundation

// Wire models shared with the server (packages/contracts) and Android (:core).
// Decoding tolerates unknown keys from the server; nothing is invented locally.

public struct LocalWindow: Codable, Equatable, Sendable {
    public var days: [Int]
    public var start: String
    public var end: String
    public init(days: [Int], start: String, end: String) {
        self.days = days
        self.start = start
        self.end = end
    }
}

public struct QuietHours: Codable, Equatable, Sendable {
    public var enabled: Bool
    public var start: String
    public var end: String
    public init(enabled: Bool, start: String, end: String) {
        self.enabled = enabled
        self.start = start
        self.end = end
    }
}

public struct RecipientSettings: Codable, Equatable, Sendable {
    public var quietHours: QuietHours
    public var pausedUntil: String?
    public var soundBudgetPerHour: Int

    public init(quietHours: QuietHours, pausedUntil: String? = nil, soundBudgetPerHour: Int = 6) {
        self.quietHours = quietHours
        self.pausedUntil = pausedUntil
        self.soundBudgetPerHour = soundBudgetPerHour
    }

    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        quietHours = try c.decode(QuietHours.self, forKey: .quietHours)
        pausedUntil = try c.decodeIfPresent(String.self, forKey: .pausedUntil)
        soundBudgetPerHour = try c.decodeIfPresent(Int.self, forKey: .soundBudgetPerHour) ?? 6
    }
}

public struct NudgeLimits: Codable, Equatable, Sendable {
    public var maxPerHour: Int
    public var windows: [LocalWindow]
    public var sounds: [String]
    public var overridesQuietHours: Bool
    public init(maxPerHour: Int, windows: [LocalWindow], sounds: [String], overridesQuietHours: Bool) {
        self.maxPerHour = maxPerHour
        self.windows = windows
        self.sounds = sounds
        self.overridesQuietHours = overridesQuietHours
    }
}

public struct FollowUp: Codable, Equatable, Sendable {
    public var allowed: Bool
    public var maxDelaySeconds: Int
    public init(allowed: Bool, maxDelaySeconds: Int) {
        self.allowed = allowed
        self.maxDelaySeconds = maxDelaySeconds
    }
}

public struct AlarmLimits: Codable, Equatable, Sendable {
    public var maxAlarmSeconds: Int
    public var maxPerDay: Int
    public var windows: [LocalWindow]
    public var sounds: [String]
    public var followUp: FollowUp
    public init(maxAlarmSeconds: Int, maxPerDay: Int, windows: [LocalWindow], sounds: [String], followUp: FollowUp) {
        self.maxAlarmSeconds = maxAlarmSeconds
        self.maxPerDay = maxPerDay
        self.windows = windows
        self.sounds = sounds
        self.followUp = followUp
    }
}

public struct ChallengeLimits: Codable, Equatable, Sendable {
    public var types: [String]
    public var maxDurationSeconds: Int
    public var maxDifficulty: String
    public init(types: [String], maxDurationSeconds: Int, maxDifficulty: String) {
        self.types = types
        self.maxDurationSeconds = maxDurationSeconds
        self.maxDifficulty = maxDifficulty
    }
}

public struct FocusLimits: Codable, Equatable, Sendable {
    public var maxSessionMinutes: Int
    public var maxPerDay: Int
    public var windows: [LocalWindow]
    public init(maxSessionMinutes: Int, maxPerDay: Int, windows: [LocalWindow]) {
        self.maxSessionMinutes = maxSessionMinutes
        self.maxPerDay = maxPerDay
        self.windows = windows
    }
}

public struct CheckinLimits: Codable, Equatable, Sendable {
    public var maxPerDay: Int
    public init(maxPerDay: Int) { self.maxPerDay = maxPerDay }
}

public struct GrantLimits: Codable, Equatable, Sendable {
    public var nudge: NudgeLimits?
    public var alarm: AlarmLimits?
    public var challenge: ChallengeLimits?
    public var focus: FocusLimits?
    public var checkin: CheckinLimits?
    public init(nudge: NudgeLimits? = nil, alarm: AlarmLimits? = nil, challenge: ChallengeLimits? = nil, focus: FocusLimits? = nil, checkin: CheckinLimits? = nil) {
        self.nudge = nudge
        self.alarm = alarm
        self.challenge = challenge
        self.focus = focus
        self.checkin = checkin
    }
}

/// A recipient-owned grant as cached on the recipient's phone (grants this user gave to friends).
public struct GrantSnapshot: Codable, Equatable, Sendable {
    public var id: String
    public var grantorId: String
    public var granteeId: String
    public var version: Int
    public var capabilities: [String]
    public var limits: GrantLimits
    public var validFrom: String
    public var validUntil: String
    public var paused: Bool
    public var revokedAt: String?

    public init(id: String, grantorId: String, granteeId: String, version: Int, capabilities: [String], limits: GrantLimits, validFrom: String, validUntil: String, paused: Bool, revokedAt: String? = nil) {
        self.id = id
        self.grantorId = grantorId
        self.granteeId = granteeId
        self.version = version
        self.capabilities = capabilities
        self.limits = limits
        self.validFrom = validFrom
        self.validUntil = validUntil
        self.paused = paused
        self.revokedAt = revokedAt
    }
}

public enum Capability {
    public static let nudge = "nudge.send"
    public static let alarmRequest = "alarm.request"
    public static let alarmSchedule = "alarm.schedule"
    public static let challengeAssign = "challenge.assign"
    public static let focus = "focus.request"
    public static let checkin = "safety.checkin.request"
    public static let location = "location.request"
    public static let safety: Set<String> = [checkin, location]
}

public struct Recurrence: Codable, Equatable, Sendable {
    public var kind: String
    public var days: [Int]?
    public var date: String?
    public init(kind: String, days: [Int]? = nil, date: String? = nil) {
        self.kind = kind
        self.days = days
        self.date = date
    }
    public static func weekly(_ days: [Int]) -> Recurrence { Recurrence(kind: "weekly", days: days) }
    public static func once(_ date: String) -> Recurrence { Recurrence(kind: "once", date: date) }
}

// MARK: - Challenges

public enum ChallengeStep: Codable, Equatable, Sendable {
    case multipleChoice(id: String, prompt: String, options: [String], correctIndex: Int)
    case shortAnswer(id: String, prompt: String, acceptedAnswers: [String])
    case math(id: String, operands: [Int64], operator: String, answer: Int64)
    case memoryOrder(id: String, prompt: String, items: [String], displaySeconds: Int)
    case chess(id: String, prompt: String, fen: String, solutions: [String], goal: String)
    case timer(id: String, prompt: String, seconds: Int, activity: String)
    case qrCode(id: String, prompt: String, codeHash: String)
    case selfAttested(id: String, prompt: String, items: [String], category: String, alternative: String?)

    public var id: String {
        switch self {
        case let .multipleChoice(id, _, _, _), let .shortAnswer(id, _, _), let .math(id, _, _, _), let .memoryOrder(id, _, _, _),
             let .chess(id, _, _, _, _), let .timer(id, _, _, _), let .qrCode(id, _, _), let .selfAttested(id, _, _, _, _):
            return id
        }
    }

    private enum K: String, CodingKey {
        case type, id, prompt, options, correctIndex, acceptedAnswers, operands, `operator`, answer, items, displaySeconds, fen, solutions, goal, seconds, activity, codeHash, category, alternative
    }

    public struct UnknownStepType: Error, CustomStringConvertible {
        public let type: String
        public var description: String { "unknown challenge step type \(type)" }
    }

    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: K.self)
        let type = try c.decode(String.self, forKey: .type)
        let id = try c.decode(String.self, forKey: .id)
        switch type {
        case "multiple_choice":
            self = .multipleChoice(id: id, prompt: try c.decode(String.self, forKey: .prompt), options: try c.decode([String].self, forKey: .options), correctIndex: try c.decode(Int.self, forKey: .correctIndex))
        case "short_answer":
            self = .shortAnswer(id: id, prompt: try c.decode(String.self, forKey: .prompt), acceptedAnswers: try c.decode([String].self, forKey: .acceptedAnswers))
        case "math":
            self = .math(id: id, operands: try c.decode([Int64].self, forKey: .operands), operator: try c.decode(String.self, forKey: .operator), answer: try c.decode(Int64.self, forKey: .answer))
        case "memory_order":
            self = .memoryOrder(id: id, prompt: try c.decode(String.self, forKey: .prompt), items: try c.decode([String].self, forKey: .items), displaySeconds: try c.decode(Int.self, forKey: .displaySeconds))
        case "chess":
            self = .chess(id: id, prompt: try c.decode(String.self, forKey: .prompt), fen: try c.decode(String.self, forKey: .fen), solutions: try c.decode([String].self, forKey: .solutions), goal: try c.decodeIfPresent(String.self, forKey: .goal) ?? "mate_in_1")
        case "timer":
            self = .timer(id: id, prompt: try c.decode(String.self, forKey: .prompt), seconds: try c.decode(Int.self, forKey: .seconds), activity: try c.decode(String.self, forKey: .activity))
        case "qr_code":
            self = .qrCode(id: id, prompt: try c.decode(String.self, forKey: .prompt), codeHash: try c.decode(String.self, forKey: .codeHash))
        case "self_attested":
            self = .selfAttested(id: id, prompt: try c.decode(String.self, forKey: .prompt), items: try c.decode([String].self, forKey: .items), category: try c.decode(String.self, forKey: .category), alternative: try c.decodeIfPresent(String.self, forKey: .alternative))
        default:
            // An unsupported primitive is rejected, never guessed at.
            throw UnknownStepType(type: type)
        }
    }

    public func encode(to encoder: Encoder) throws {
        var c = encoder.container(keyedBy: K.self)
        try c.encode(id, forKey: .id)
        switch self {
        case let .multipleChoice(_, prompt, options, correctIndex):
            try c.encode("multiple_choice", forKey: .type); try c.encode(prompt, forKey: .prompt); try c.encode(options, forKey: .options); try c.encode(correctIndex, forKey: .correctIndex)
        case let .shortAnswer(_, prompt, accepted):
            try c.encode("short_answer", forKey: .type); try c.encode(prompt, forKey: .prompt); try c.encode(accepted, forKey: .acceptedAnswers)
        case let .math(_, operands, op, answer):
            try c.encode("math", forKey: .type); try c.encode(operands, forKey: .operands); try c.encode(op, forKey: .operator); try c.encode(answer, forKey: .answer)
        case let .memoryOrder(_, prompt, items, displaySeconds):
            try c.encode("memory_order", forKey: .type); try c.encode(prompt, forKey: .prompt); try c.encode(items, forKey: .items); try c.encode(displaySeconds, forKey: .displaySeconds)
        case let .chess(_, prompt, fen, solutions, goal):
            try c.encode("chess", forKey: .type); try c.encode(prompt, forKey: .prompt); try c.encode(fen, forKey: .fen); try c.encode(solutions, forKey: .solutions); try c.encode(goal, forKey: .goal)
        case let .timer(_, prompt, seconds, activity):
            try c.encode("timer", forKey: .type); try c.encode(prompt, forKey: .prompt); try c.encode(seconds, forKey: .seconds); try c.encode(activity, forKey: .activity)
        case let .qrCode(_, prompt, codeHash):
            try c.encode("qr_code", forKey: .type); try c.encode(prompt, forKey: .prompt); try c.encode(codeHash, forKey: .codeHash)
        case let .selfAttested(_, prompt, items, category, alternative):
            try c.encode("self_attested", forKey: .type); try c.encode(prompt, forKey: .prompt); try c.encode(items, forKey: .items); try c.encode(category, forKey: .category); try c.encodeIfPresent(alternative, forKey: .alternative)
        }
    }
}

public struct Fallback: Codable, Equatable, Sendable {
    public var type: String
    public var prompt: String?
}

public struct ChallengeDefinition: Codable, Equatable, Sendable {
    public var schemaVersion: Int
    public var title: String
    public var kind: String
    public var difficulty: String
    public var maxDurationSeconds: Int
    public var steps: [ChallengeStep]
    public var verification: String
    public var fallback: Fallback
    public var permissionsRequired: [String]?
}

/// What the user did for one step; mirrors the server's StepResponse.
public enum StepResponse: Equatable, Sendable {
    case index(Int)
    case text(String)
    case order([String])
    case move(String)
    case elapsed(Double)
    case checked([Bool])
}
