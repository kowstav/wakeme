import Foundation
import WakeMeCore

/// Typed calls the screens use. Every remote action is authorized by the server
/// against recipient-owned grants. Mirrors the Android WakeMeApi.
public struct WakeMeAPI: Sendable {
    public let client: APIClient
    public init(client: APIClient) { self.client = client }

    // MARK: account
    public func me() async throws -> MeDTO { try await client.get("/v1/me") }

    struct CreateAccount: Encodable { let displayName: String; let timeZone: String; let locale: String; let ageAttestation = true }
    public func createAccount(name: String, timeZone: String, locale: String) async throws -> MeDTO {
        try await client.post("/v1/account", body: CreateAccount(displayName: name, timeZone: timeZone, locale: locale), idempotent: true)
    }

    public struct MePatch: Encodable, Sendable {
        public var quietHours: QuietHoursDTO?
        public var soundBudgetPerHour: Int?
        public var shareBypass: Bool?
        public init(quietHours: QuietHoursDTO? = nil, soundBudgetPerHour: Int? = nil, shareBypass: Bool? = nil) {
            self.quietHours = quietHours
            self.soundBudgetPerHour = soundBudgetPerHour
            self.shareBypass = shareBypass
        }
    }
    public func updateMe(_ patch: MePatch) async throws -> MeDTO { try await client.patch("/v1/me", body: patch) }

    /// pausedUntil nil resumes; explicit JSON null is required, so this is its own call.
    public func setPaused(until: Date?) async throws -> MeDTO {
        struct Pause: Encodable {
            let until: String?
            func encode(to encoder: Encoder) throws {
                var c = encoder.container(keyedBy: K.self)
                try c.encode(until, forKey: .pausedUntil) // encodes null when nil
            }
            enum K: String, CodingKey { case pausedUntil }
        }
        return try await client.patch("/v1/me", body: Pause(until: until.map(Instant.format)))
    }

    public struct DeletionDTO: Decodable, Sendable { public var deletionScheduledFor: String }
    /// Deactivates now and deletes after the grace period. Needs a sign-in within the last
    /// 15 minutes (error code `reauth_required` otherwise).
    public func deleteAccount() async throws -> DeletionDTO { try await client.delete("/v1/account") }
    public func cancelDeletion() async throws -> MeDTO { try await client.post("/v1/account/deletion/cancel", body: Optional<Empty>.none) }
    /// The person's own data as JSON, exactly as the server sent it.
    public func exportAccount() async throws -> Data { try await client.getData("/v1/account/export") }

    public func devices() async throws -> [DeviceDTO] { try await client.get("/v1/devices", as: DevicesDTO.self).devices }

    public struct DeviceCapabilities: Encodable, Sendable {
        public var notificationsAuthorized: Bool?
        public var timeSensitiveAuthorized: Bool?
        /// iOS: AlarmKit authorization. The server treats an iPhone as able to ring wake alarms only when true.
        public var alarmAuthorized: Bool?
        public var backgroundRefreshAvailable: Bool?
        public var speakerPreferenceSupported: Bool?
        public init(notificationsAuthorized: Bool? = nil, timeSensitiveAuthorized: Bool? = nil, alarmAuthorized: Bool? = nil, backgroundRefreshAvailable: Bool? = nil, speakerPreferenceSupported: Bool? = nil) {
            self.notificationsAuthorized = notificationsAuthorized
            self.timeSensitiveAuthorized = timeSensitiveAuthorized
            self.alarmAuthorized = alarmAuthorized
            self.backgroundRefreshAvailable = backgroundRefreshAvailable
            self.speakerPreferenceSupported = speakerPreferenceSupported
        }
    }

    public struct DevicePush: Encodable, Sendable {
        public var provider = "apns"
        public var token: String
        public var environment: String
        public init(token: String, environment: String) {
            self.token = token
            self.environment = environment
        }
    }

    struct RegisterDevice: Encodable {
        let installId: String
        let platform = "ios"
        let osVersion: String
        let appBuild: String
        let publicKey: String
        let push: DevicePush?
        let capabilities: DeviceCapabilities
        let makePrimary: Bool?
    }

    /// Registers (or refreshes) this installation with its hardware-backed public key.
    public func registerDevice(installId: String, osVersion: String, appBuild: String, publicKeySPKIBase64: String, push: DevicePush?, capabilities: DeviceCapabilities, makePrimary: Bool) async throws -> DeviceDTO {
        try await client.post("/v1/devices", body: RegisterDevice(installId: installId, osVersion: osVersion, appBuild: appBuild, publicKey: publicKeySPKIBase64, push: push, capabilities: capabilities, makePrimary: makePrimary ? true : nil), idempotent: true)
    }

    struct CapabilitiesPatch: Encodable { let capabilities: DeviceCapabilities }
    /// Signed by this device: only the phone itself may describe what it can do.
    public func updateCapabilities(deviceId: String, _ capabilities: DeviceCapabilities) async throws -> DeviceDTO {
        try await client.patch("/v1/devices/\(deviceId)", body: CapabilitiesPatch(capabilities: capabilities), signed: true)
    }

    public func makePrimary(deviceId: String) async throws -> DeviceDTO { try await client.post("/v1/devices/\(deviceId)/primary", body: Optional<Empty>.none) }

    // MARK: friends
    public func friends() async throws -> [FriendDTO] { try await client.get("/v1/friends", as: FriendsDTO.self).friends }
    public func friend(_ friendshipId: String) async throws -> FriendDTO { try await client.get("/v1/friendships/\(friendshipId)") }

    struct InviteKind: Encodable { let kind = "friend" }
    public func createInvite() async throws -> InviteCreatedDTO { try await client.post("/v1/invites", body: InviteKind()) }

    struct InviteRef: Encodable { let token: String?; let code: String? }
    public func previewInvite(token: String?, code: String?) async throws -> InvitePreviewDTO { try await client.post("/v1/invites/preview", body: InviteRef(token: token, code: code)) }
    public func redeemInvite(token: String?, code: String?) async throws -> RedeemDTO { try await client.post("/v1/invites/redeem", body: InviteRef(token: token, code: code)) }

    // MARK: grants
    public func grants(direction: String) async throws -> [GrantDTO] { try await client.get("/v1/grants?direction=\(direction)&includeRevoked=true", as: GrantsDTO.self).grants }
    public func revokeGrant(_ id: String) async throws { let _: OkDTO = try await client.delete("/v1/grants/\(id)") }

    public struct PresetDTO: Codable, Equatable, Sendable { public var id: String; public var capabilities: [String]; public var limits: GrantLimits; public var validUntil: String }
    struct PresetsDTO: Codable { let presets: [PresetDTO] }
    /// Editable starting points (Gentle nudges, Wake buddy, Focus buddy); there is no "allow everything".
    public func presets() async throws -> [PresetDTO] { try await client.get("/v1/grants/presets", as: PresetsDTO.self).presets }

    struct NewGrant: Encodable { let granteeId: String; let capabilities: [String]; let limits: GrantLimits; let validUntil: String }
    /// Only the recipient grants. Not retried automatically (one live grant per direction).
    public func createGrant(granteeId: String, capabilities: [String], limits: GrantLimits, validUntil: Date) async throws -> GrantDTO {
        try await client.post("/v1/grants", body: NewGrant(granteeId: granteeId, capabilities: capabilities, limits: limits, validUntil: Instant.format(validUntil)))
    }

    struct GrantEdit: Encodable { let expectedVersion: Int; let paused: Bool; let capabilities: [String]; let limits: GrantLimits; let validUntil: String }
    /// Replaces the terms; fails with `version_conflict` if the grant changed meanwhile.
    public func updateGrant(_ id: String, expectedVersion: Int, paused: Bool, capabilities: [String], limits: GrantLimits, validUntil: Date) async throws -> GrantDTO {
        try await client.put("/v1/grants/\(id)", body: GrantEdit(expectedVersion: expectedVersion, paused: paused, capabilities: capabilities, limits: limits, validUntil: Instant.format(validUntil)))
    }

    struct GrantPause: Encodable { let paused: Bool }
    public func pauseGrant(_ id: String, paused: Bool) async throws -> GrantDTO { try await client.post("/v1/grants/\(id)/pause", body: GrantPause(paused: paused)) }

    // MARK: commands
    public struct CommandRequest: Encodable, Sendable {
        public var targetUserId: String
        public var capability: String
        public var idempotencyKey: String
        public var expectedGrantVersion: Int?
        public var soundId: String?
        public var ringSeconds: Int?
        public init(targetUserId: String, capability: String, idempotencyKey: String = APIClient.idempotencyKey(), expectedGrantVersion: Int? = nil, soundId: String? = nil, ringSeconds: Int? = nil) {
            self.targetUserId = targetUserId
            self.capability = capability
            self.idempotencyKey = idempotencyKey
            self.expectedGrantVersion = expectedGrantVersion
            self.soundId = soundId
            self.ringSeconds = ringSeconds
        }
    }
    /// The idempotency key is generated once per user action, so a retry never sends twice.
    public func send(_ command: CommandRequest) async throws -> CommandDTO { try await client.post("/v1/commands", body: command, signed: true, idempotent: true) }
    public func command(_ id: String) async throws -> CommandDTO { try await client.get("/v1/commands/\(id)") }

    // MARK: routines
    public func plans() async throws -> PlansDTO { try await client.get("/v1/alarm-plans?includeEnded=true") }
    struct Version: Encodable { let version: Int }
    public func acceptPlan(_ id: String, version: Int) async throws -> PlanDTO { try await client.post("/v1/alarm-plans/\(id)/accept", body: Version(version: version)) }
    public func declinePlan(_ id: String, version: Int) async throws -> PlanDTO { try await client.post("/v1/alarm-plans/\(id)/decline", body: Version(version: version)) }
    struct Paused: Encodable { let paused: Bool }
    public func pausePlan(_ id: String, paused: Bool) async throws -> PlanDTO { try await client.post("/v1/alarm-plans/\(id)/pause", body: Paused(paused: paused)) }
    public func cancelPlan(_ id: String) async throws -> PlanDTO { try await client.delete("/v1/alarm-plans/\(id)") }

    /// Terms of a routine. Without `ownerId` it is the caller's own routine and is active at once;
    /// with a friend's `ownerId` it is a suggestion they accept or decline.
    public struct PlanTerms: Encodable, Sendable, Equatable {
        public var ownerId: String?
        public var label: String
        public var localTime: String
        public var timeZone: String
        public var timeZonePolicy: String
        public var recurrence: Recurrence
        public var soundId: String
        public var ringSeconds: Int
        public var challengeRevisionId: String?
        public var followUpDelaySeconds: Int?
        public init(ownerId: String? = nil, label: String, localTime: String, timeZone: String, timeZonePolicy: String, recurrence: Recurrence, soundId: String, ringSeconds: Int, challengeRevisionId: String? = nil, followUpDelaySeconds: Int? = nil) {
            self.ownerId = ownerId
            self.label = label
            self.localTime = localTime
            self.timeZone = timeZone
            self.timeZonePolicy = timeZonePolicy
            self.recurrence = recurrence
            self.soundId = soundId
            self.ringSeconds = ringSeconds
            self.challengeRevisionId = challengeRevisionId
            self.followUpDelaySeconds = followUpDelaySeconds
        }
    }
    /// Not retried automatically: a lost response must not create a second routine.
    public func createPlan(_ terms: PlanTerms) async throws -> PlanDTO { try await client.post("/v1/alarm-plans", body: terms) }

    public struct InstallationReceipt: Encodable, Sendable {
        public var version: Int
        /// scheduled | failed | removed: what this phone's OS actually accepted.
        public var result: String
        public var nextFireAt: String?
        public var scheduledInstants: [String]?
        public var errorCode: String?
        public init(version: Int, result: String, nextFireAt: String? = nil, scheduledInstants: [String]? = nil, errorCode: String? = nil) {
            self.version = version
            self.result = result
            self.nextFireAt = nextFireAt
            self.scheduledInstants = scheduledInstants
            self.errorCode = errorCode
        }
    }
    struct OccurrenceReceipt: Encodable { let occurrenceKey: String; let version: Int; let outcome: String }
    /// What happened to one occurrence on this phone: rang, stopped (system Stop),
    /// challenge_completed, bypassed (the safe escape), follow_up_rang, session_ended.
    /// Outcomes only move forward on the server, so a retry is harmless.
    public func reportOccurrence(planId: String, occurrenceKey: String, version: Int, outcome: String) async throws {
        let _: OkDTO = try await client.post("/v1/alarm-plans/\(planId)/occurrences", body: OccurrenceReceipt(occurrenceKey: occurrenceKey, version: version, outcome: outcome), signed: true, idempotent: true)
    }

    /// "Ready" on the server means this receipt arrived, not merely that the plan was saved.
    public func reportInstallation(planId: String, _ receipt: InstallationReceipt) async throws {
        let _: OkDTO = try await client.post("/v1/alarm-plans/\(planId)/installations", body: receipt, signed: true, idempotent: true)
    }

    // MARK: friends actions
    public func removeFriend(_ friendshipId: String) async throws { let _: OkDTO = try await client.delete("/v1/friendships/\(friendshipId)") }

    struct BlockBody: Encodable {
        struct Report: Encodable { let reason: String }
        let report: Report?
    }
    /// Revokes every permission both ways and ends the conversation; optionally files a report.
    public func block(_ friendshipId: String, reportReason: String?) async throws {
        let _: OkDTO = try await client.post("/v1/friendships/\(friendshipId)/block", body: BlockBody(report: reportReason.map(BlockBody.Report.init(reason:))))
    }

    // MARK: messages
    public func messages(_ conversationId: String) async throws -> [MessageDTO] { try await client.get("/v1/conversations/\(conversationId)/messages", as: MessagesDTO.self).messages }
    struct NewMessage: Encodable { let body: String; let clientId: String }
    public func sendMessage(_ conversationId: String, text: String) async throws -> MessageDTO {
        try await client.post("/v1/conversations/\(conversationId)/messages", body: NewMessage(body: text, clientId: "c_" + UUID().uuidString.replacingOccurrences(of: "-", with: "")), idempotent: true)
    }

    public func markRead(_ conversationId: String) async throws { let _: OkDTO = try await client.post("/v1/conversations/\(conversationId)/read", body: Optional<Empty>.none) }
    struct Reaction: Encodable { let emoji: String }
    /// One of the server's small emoji set; replaces your previous reaction.
    public func react(_ messageId: String, emoji: String) async throws { let _: OkDTO = try await client.post("/v1/messages/\(messageId)/reactions", body: Reaction(emoji: emoji)) }
    public func removeReaction(_ messageId: String) async throws { let _: OkDTO = try await client.delete("/v1/messages/\(messageId)/reactions") }
    /// Deletes a message you sent; its text is erased.
    public func deleteMessage(_ messageId: String) async throws { let _: OkDTO = try await client.delete("/v1/messages/\(messageId)") }

    // MARK: reports
    struct NewReport: Encodable { let targetKind: String; let targetId: String; let reason: String; let note: String? }
    struct ReportCreated: Decodable { let id: String }
    /// Reports a person, message, challenge, sound or request; the reported person isn't told who.
    public func report(targetKind: String, targetId: String, reason: String, note: String? = nil) async throws {
        let _: ReportCreated = try await client.post("/v1/reports", body: NewReport(targetKind: targetKind, targetId: targetId, reason: reason, note: note))
    }

    // MARK: safety
    public func safetySessions() async throws -> SafetySessionsDTO { try await client.get("/v1/location-sessions") }
    public func endSafety(_ id: String) async throws -> SafetySessionDTO { try await client.post("/v1/location-sessions/\(id)/end", body: Optional<Empty>.none) }
    /// "I'm OK": completes the check-in and ends the session; viewers are told.
    public func checkIn(_ id: String) async throws -> SafetySessionDTO { try await client.post("/v1/location-sessions/\(id)/check-in", body: Empty()) }

    struct NewSafetySession: Encodable {
        let purpose: String
        let viewerIds: [String]
        let durationMinutes: Int
        let shareLocation: Bool
        let checkinDueAt: String?
        let message: String?
    }
    /// A check-in ("checkin", needs `checkinDueAt`) or a location share ("share") with friends you
    /// name, for 5 minutes to 8 hours. It ends on its own; viewers are listed to you.
    public func startSafety(purpose: String, viewerIds: [String], minutes: Int, shareLocation: Bool, checkinDueAt: Date?, message: String?) async throws -> SafetySessionDTO {
        try await client.post("/v1/location-sessions", body: NewSafetySession(
            purpose: purpose, viewerIds: viewerIds, durationMinutes: minutes, shareLocation: shareLocation,
            checkinDueAt: checkinDueAt.map(Instant.format), message: message
        ))
    }
    struct NewSos: Encodable { let viewerIds: [String]; let shareLocation: Bool }
    /// Alerts the friends you pick; shares location for 2 hours if you allow it.
    public func sos(viewerIds: [String], shareLocation: Bool) async throws -> SafetySessionDTO {
        try await client.post("/v1/sos", body: NewSos(viewerIds: viewerIds, shareLocation: shareLocation))
    }
    struct LocationSample: Encodable { let capturedAt: String; let latitude: Double; let longitude: Double; let accuracyMeters: Int }
    /// This phone's latest position for a session it shares, signed by the device key. The server
    /// keeps only the latest sample and refuses old ones.
    public func postLocation(sessionId: String, capturedAt: Date, latitude: Double, longitude: Double, accuracyMeters: Int) async throws {
        let _: OkDTO = try await client.post("/v1/location-sessions/\(sessionId)/samples", body: LocationSample(
            capturedAt: Instant.format(capturedAt), latitude: latitude, longitude: longitude, accuracyMeters: max(0, min(100_000, accuracyMeters))
        ), signed: true)
    }
    /// What a viewer can see: when it was captured and how accurate, never presented as live.
    public func latestLocation(_ sessionId: String) async throws -> LatestLocationDTO { try await client.get("/v1/location-sessions/\(sessionId)/latest") }
    /// A viewer confirms they saw an SOS or check-in, so the sender knows.
    public func acknowledgeSafety(_ sessionId: String) async throws { let _: OkDTO = try await client.post("/v1/location-sessions/\(sessionId)/acknowledge", body: Optional<Empty>.none) }

    // MARK: config, sync, challenges
    public func config() async throws -> PublicConfigDTO { try await client.get("/v1/config") }
    public func sync(cursor: String?) async throws -> SyncDTO {
        guard let cursor else { return try await client.get("/v1/sync") }
        let escaped = cursor.addingPercentEncoding(withAllowedCharacters: .alphanumerics) ?? cursor
        return try await client.get("/v1/sync?cursor=\(escaped)")
    }
    public func challengeRevision(_ id: String) async throws -> SignedEnvelope { try await client.get("/v1/challenge-revisions/\(id)") }

    public struct ChallengeRevisionRef: Codable, Equatable, Sendable { public var id: String; public var revision: Int; public var state: String }
    public struct ChallengeSummary: Codable, Equatable, Sendable, Identifiable {
        public var id: String
        public var creatorId: String?
        public var title: String
        public var publishedRevision: Int?
        public var revisions: [ChallengeRevisionRef]
        /// The revision a routine or practice run uses; nil until one is published.
        public var publishedRevisionId: String? { revisions.first { $0.revision == publishedRevision && $0.state == "published" }?.id }
    }
    struct ChallengesList: Codable { let challenges: [ChallengeSummary] }

    // MARK: making challenges (same flows as Android: template, your own quiz, or an AI draft)
    public struct ChallengeTemplate: Codable, Equatable, Sendable, Identifiable {
        public var id: String
        public var title: String
        public var description: String
    }
    struct TemplatesList: Codable { let templates: [ChallengeTemplate] }
    public func challengeTemplates() async throws -> [ChallengeTemplate] { try await client.get("/v1/challenges/templates", as: TemplatesList.self).templates }

    /// A challenge with every revision, drafts included (creator and circle members).
    public struct ChallengeDetail: Decodable, Sendable, Identifiable {
        public struct Revision: Decodable, Sendable {
            public var id: String
            public var revision: Int
            public var state: String
            /// Nil when this app can't run one of its steps.
            public var definition: ChallengeDefinition?
            enum CodingKeys: String, CodingKey { case id, revision, state, body }
            public init(from decoder: Decoder) throws {
                let c = try decoder.container(keyedBy: CodingKeys.self)
                id = try c.decode(String.self, forKey: .id)
                revision = try c.decode(Int.self, forKey: .revision)
                state = try c.decode(String.self, forKey: .state)
                definition = try? c.decode(ChallengeDefinition.self, forKey: .body)
            }
        }
        public var id: String
        public var title: String
        public var publishedRevision: Int?
        public var revisions: [Revision]
        public var latest: Revision? { revisions.max { $0.revision < $1.revision } }
    }
    public func challenge(_ id: String) async throws -> ChallengeDetail { try await client.get("/v1/challenges/\(id)") }

    struct FromTemplate: Encodable { let templateId: String; let difficulty: String }
    /// A draft from a built-in template. Not retried automatically: a lost response must not make a second draft.
    public func challengeFromTemplate(_ templateId: String, difficulty: String = "easy") async throws -> ChallengeDetail {
        try await client.post("/v1/challenges/from-template", body: FromTemplate(templateId: templateId, difficulty: difficulty))
    }

    /// One question of a quiz you write: with wrong options it is multiple choice, without them the answer is typed.
    public struct QuizQuestion: Equatable, Sendable {
        public var question: String
        public var answer: String
        public var wrongOptions: [String]
        public init(question: String, answer: String, wrongOptions: [String] = []) {
            self.question = question
            self.answer = answer
            self.wrongOptions = wrongOptions
        }
    }
    /// The declarative definition the server validates (same shape the Android builder sends).
    struct QuizBody: Encodable {
        struct Step: Encodable {
            let type: String
            let id: String
            let prompt: String
            let options: [String]?
            let correctIndex: Int?
            let acceptedAnswers: [String]?
            let source = "creator_provided"
        }
        struct Fallback: Encodable { let type = "accessible_checkin" }
        let schemaVersion = 1
        let title: String
        let kind = "sequence"
        let difficulty: String
        let maxDurationSeconds: Int
        let steps: [Step]
        let verification = "local_deterministic"
        let fallback = Fallback()
        let permissionsRequired: [String] = []

        /// When editing, `original` is the version being edited: a short answer whose main answer
        /// is unchanged keeps its other accepted spellings (same rule as Android's `quizSteps`).
        init(title: String, questions: [QuizQuestion], difficulty: String = "easy", original: [ChallengeStep] = []) {
            self.title = title
            self.difficulty = difficulty
            maxDurationSeconds = min(questions.count * 25 + 30, 600)
            steps = questions.enumerated().map { i, q in
                // Options must differ from each other and from the answer (the server checks).
                var seen: Set<String> = [q.answer.lowercased()]
                let wrong = Array(q.wrongOptions.filter { seen.insert($0.lowercased()).inserted }.prefix(5))
                guard !wrong.isEmpty else {
                    var accepted = [q.answer]
                    if i < original.count, case let .shortAnswer(_, _, before) = original[i], before.first == q.answer { accepted = before }
                    return Step(type: "short_answer", id: "q\(i + 1)", prompt: q.question, options: nil, correctIndex: nil, acceptedAnswers: accepted)
                }
                let options = (wrong + [q.answer]).shuffled()
                return Step(type: "multiple_choice", id: "q\(i + 1)", prompt: q.question, options: options, correctIndex: options.firstIndex(of: q.answer), acceptedAnswers: nil)
            }
        }
    }
    struct NewChallenge: Encodable { let body: QuizBody }
    /// Not retried automatically: a lost response must not make a second draft.
    public func createQuiz(title: String, questions: [QuizQuestion]) async throws -> ChallengeDetail {
        try await client.post("/v1/challenges", body: NewChallenge(body: QuizBody(title: title, questions: questions)))
    }
    /// An edit is a new draft revision of your own challenge; published revisions never change.
    /// Not retried automatically: a lost response must not make a second draft.
    public func reviseQuiz(_ challengeId: String, title: String, questions: [QuizQuestion], editing previous: ChallengeDefinition?) async throws -> ChallengeDetail {
        let body = QuizBody(title: title, questions: questions, difficulty: previous?.difficulty ?? "easy", original: previous?.steps ?? [])
        return try await client.post("/v1/challenges/\(challengeId)/revisions", body: NewChallenge(body: body))
    }
    /// The questions of a quiz-shaped challenge (only multiple-choice and short-answer steps), for
    /// editing; nil for anything else (templates such as maths or chess are made again instead).
    public static func quizQuestions(in definition: ChallengeDefinition) -> [QuizQuestion]? {
        guard !definition.steps.isEmpty else { return nil }
        var questions: [QuizQuestion] = []
        for step in definition.steps {
            switch step {
            case let .multipleChoice(_, prompt, options, correctIndex):
                guard options.indices.contains(correctIndex) else { return nil }
                questions.append(.init(question: prompt, answer: options[correctIndex], wrongOptions: options.enumerated().filter { $0.offset != correctIndex }.map(\.element)))
            case let .shortAnswer(_, prompt, accepted):
                guard let answer = accepted.first else { return nil }
                questions.append(.init(question: prompt, answer: answer))
            default:
                return nil
            }
        }
        return questions
    }

    public struct ChallengeGeneration: Decodable, Sendable {
        public struct Rejection: Decodable, Sendable { public var message: String?; public var notes: String? }
        public var id: String
        public var status: String
        public var definitionId: String?
        public var creditsRemaining: Int
        public var rejection: Rejection?
        enum CodingKeys: String, CodingKey { case id, status, definitionId, creditsRemaining, rejection }
        public init(from decoder: Decoder) throws {
            let c = try decoder.container(keyedBy: CodingKeys.self)
            id = try c.decode(String.self, forKey: .id)
            status = try c.decode(String.self, forKey: .status)
            definitionId = try c.decodeIfPresent(String.self, forKey: .definitionId)
            creditsRemaining = try c.decode(Int.self, forKey: .creditsRemaining)
            rejection = try? c.decodeIfPresent(Rejection.self, forKey: .rejection)
        }
    }
    struct Fact: Encodable { let question: String; let answer: String; let distractors: [String]? }
    struct GenerationRequest: Encodable { let prompt: String; let facts: [Fact]; let difficulty: String }
    /// Asks the AI author for a draft from your description and facts (one credit). Quiz answers
    /// come only from the facts you give; the result is an editable draft, never active.
    public func generateChallenge(prompt: String, facts: [QuizQuestion], difficulty: String) async throws -> ChallengeGeneration {
        let body = GenerationRequest(prompt: prompt, facts: facts.map { Fact(question: $0.question, answer: $0.answer, distractors: $0.wrongOptions.isEmpty ? nil : Array($0.wrongOptions.prefix(3))) }, difficulty: difficulty)
        return try await client.post("/v1/challenges/generations", body: body)
    }
    public func challengeGeneration(_ id: String) async throws -> ChallengeGeneration { try await client.get("/v1/challenges/generations/\(id)") }

    /// Trying a draft is required before it can be published.
    public func markPreviewed(challengeId: String, revision: Int) async throws {
        let _: OkDTO = try await client.post("/v1/challenges/\(challengeId)/revisions/\(revision)/previewed", body: Optional<Empty>.none)
    }
    struct PublishBody: Encodable { let revision: Int }
    /// Freezes the tried draft as a signed revision that can be used in wake-ups.
    public func publishChallenge(_ id: String, revision: Int) async throws -> ChallengeDetail {
        try await client.post("/v1/challenges/\(id)/publish", body: PublishBody(revision: revision))
    }
    /// Your challenges and those shared in your circles.
    public func challenges() async throws -> [ChallengeSummary] { try await client.get("/v1/challenges", as: ChallengesList.self).challenges }

    // MARK: billing
    public func billingStatus() async throws -> BillingStatusDTO { try await client.get("/v1/billing/status") }
    public func billingSync() async throws -> BillingStatusDTO { try await client.post("/v1/billing/sync", body: Optional<Empty>.none) }
}
