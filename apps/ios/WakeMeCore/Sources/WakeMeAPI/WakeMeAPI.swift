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

    public func devices() async throws -> [DeviceDTO] { try await client.get("/v1/devices", as: DevicesDTO.self).devices }

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

    // MARK: messages
    public func messages(_ conversationId: String) async throws -> [MessageDTO] { try await client.get("/v1/conversations/\(conversationId)/messages", as: MessagesDTO.self).messages }
    struct NewMessage: Encodable { let body: String; let clientId: String }
    public func sendMessage(_ conversationId: String, text: String) async throws -> MessageDTO {
        try await client.post("/v1/conversations/\(conversationId)/messages", body: NewMessage(body: text, clientId: "c_" + UUID().uuidString.replacingOccurrences(of: "-", with: "")), idempotent: true)
    }

    // MARK: safety
    public func safetySessions() async throws -> SafetySessionsDTO { try await client.get("/v1/location-sessions") }
    public func endSafety(_ id: String) async throws -> SafetySessionDTO { try await client.post("/v1/location-sessions/\(id)/end", body: Optional<Empty>.none) }
    public func checkIn(_ id: String) async throws -> SafetySessionDTO { try await client.post("/v1/location-sessions/\(id)/check-in", body: Empty()) }

    // MARK: config, sync, challenges
    public func config() async throws -> PublicConfigDTO { try await client.get("/v1/config") }
    public func sync(cursor: String?) async throws -> SyncDTO {
        guard let cursor else { return try await client.get("/v1/sync") }
        let escaped = cursor.addingPercentEncoding(withAllowedCharacters: .alphanumerics) ?? cursor
        return try await client.get("/v1/sync?cursor=\(escaped)")
    }
    public func challengeRevision(_ id: String) async throws -> SignedEnvelope { try await client.get("/v1/challenge-revisions/\(id)") }

    // MARK: billing
    public func billingStatus() async throws -> BillingStatusDTO { try await client.get("/v1/billing/status") }
    public func billingSync() async throws -> BillingStatusDTO { try await client.post("/v1/billing/sync", body: Optional<Empty>.none) }
}
