import Foundation
import WakeMeCore

// Mirrors packages/contracts/openapi.yaml (generated from the server). Only the fields the app uses;
// unknown fields are ignored by JSONDecoder. Same shapes as the Android Dtos.kt.

public struct QuietHoursDTO: Codable, Equatable, Sendable {
    public var enabled: Bool
    public var start: String
    public var end: String
    public init(enabled: Bool, start: String, end: String) {
        self.enabled = enabled
        self.start = start
        self.end = end
    }
}
public struct SettingsDTO: Codable, Equatable, Sendable { public var quietHours: QuietHoursDTO; public var pausedUntil: String?; public var soundBudgetPerHour: Int; public var shareBypass: Bool }
public struct CreditsDTO: Codable, Equatable, Sendable { public var available: Int; public var introRemaining: Int; public var monthlyRemaining: Int; public var monthlyResetsAt: String? }
public struct BillingSummaryDTO: Codable, Equatable, Sendable { public var plan: String; public var status: String; public var expiresAt: String?; public var willRenew: Bool?; public var store: String?; public var credits: CreditsDTO }
public struct LimitDTO: Codable, Equatable, Sendable { public var used: Int; public var max: Int }
public struct LimitsDTO: Codable, Equatable, Sendable { public var activeRoutines: LimitDTO; public var customSounds: LimitDTO }
public struct MeDTO: Codable, Equatable, Sendable {
    public var id: String
    public var displayName: String
    public var locale: String
    public var timeZone: String
    public var accountState: String
    public var deletionScheduledFor: String?
    public var primaryDeviceId: String?
    public var settings: SettingsDTO
    public var billing: BillingSummaryDTO
    public var limits: LimitsDTO
}

public struct DeviceDTO: Codable, Equatable, Sendable { public var id: String; public var platform: String; public var isPrimary: Bool; public var pushRegistered: Bool; public var readinessIssues: [String]; public var osVersion: String?; public var lastSeenAt: String? }
public struct DevicesDTO: Codable, Sendable { public var devices: [DeviceDTO] }

public struct PublicUser: Codable, Equatable, Sendable, Hashable { public var id: String; public var displayName: String }
public struct GrantDTO: Codable, Equatable, Sendable {
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
    public var lastUsedAt: String?
    public var updatedAt: String

    /// The cached form the policy engine re-validates against.
    public var snapshot: GrantSnapshot {
        GrantSnapshot(id: id, grantorId: grantorId, granteeId: granteeId, version: version, capabilities: capabilities, limits: limits, validFrom: validFrom, validUntil: validUntil, paused: paused, revokedAt: revokedAt)
    }
}
public struct FriendAction: Codable, Equatable, Sendable { public var capability: String; public var granted: Bool; public var availableNow: Bool; public var reason: String? }
public struct FriendDTO: Codable, Equatable, Sendable, Identifiable {
    public var friendshipId: String
    public var user: PublicUser
    public var state: String
    public var since: String
    public var conversationId: String?
    public var theyAllowMe: GrantDTO?
    public var iAllowThem: GrantDTO?
    public var actions: [FriendAction]?
    public var id: String { friendshipId }
}
public struct FriendsDTO: Codable, Sendable { public var friends: [FriendDTO] }
public struct GrantsDTO: Codable, Sendable { public var grants: [GrantDTO] }
public struct InviteCreatedDTO: Codable, Equatable, Sendable, Identifiable { public var id: String; public var kind: String; public var token: String; public var code: String; public var url: String; public var expiresAt: String; public var maxUses: Int }
public struct CircleRefDTO: Codable, Equatable, Sendable { public var name: String; public var memberCount: Int }
public struct InvitePreviewDTO: Codable, Equatable, Sendable { public var kind: String; public var inviter: PublicUser; public var circle: CircleRefDTO?; public var expiresAt: String; public var ownInvite: Bool; public var alreadyConnected: Bool }
public struct RedeemDTO: Codable, Sendable { public var kind: String; public var friend: FriendDTO? }

public struct CommandStateDTO: Codable, Equatable, Sendable { public var lifecycle: String; public var transport: String; public var device: String; public var human: String; public var refusedReason: String? }
public struct CommandPayloadDTO: Codable, Equatable, Sendable { public var soundId: String?; public var ringSeconds: Int?; public var challengeRevisionId: String?; public var minutes: Int?; public var goal: String? }
public struct CommandDTO: Codable, Equatable, Sendable, Identifiable {
    public var id: String
    public var capability: String
    public var actor: PublicUser
    public var target: PublicUser
    public var grantVersion: Int
    public var payload: CommandPayloadDTO
    public var deliveryMode: String
    public var expiresAt: String
    public var createdAt: String
    public var state: CommandStateDTO
    public var displayStatus: String
}
public struct CommandsDTO: Codable, Sendable { public var commands: [CommandDTO] }

public struct PlanVersionDTO: Codable, Equatable, Sendable {
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
    public var createdBy: String
    public var createdAt: String
}
public struct InstallationDTO: Codable, Equatable, Sendable { public var deviceId: String; public var version: Int; public var result: String; public var errorCode: String?; public var nextFireAt: String?; public var reportedAt: String }
public struct OccurrenceDTO: Codable, Equatable, Sendable { public var localDate: String; public var instant: String }
public struct PlanDTO: Codable, Equatable, Sendable, Identifiable {
    public var id: String
    public var owner: PublicUser
    public var creator: PublicUser
    public var state: String
    public var accepted: PlanVersionDTO?
    public var pending: PlanVersionDTO?
    public var readiness: String
    public var installation: InstallationDTO?
    public var nextOccurrences: [OccurrenceDTO]?
    public var createdAt: String
    public var updatedAt: String
}
public struct PlansDTO: Codable, Sendable { public var mine: [PlanDTO]; public var helping: [PlanDTO] }

public struct ConversationDTO: Codable, Equatable, Sendable, Identifiable { public var id: String; public var kind: String; public var title: String; public var lastMessageAt: String?; public var unread: Int }
public struct ConversationsDTO: Codable, Sendable { public var conversations: [ConversationDTO] }
public struct ReactionDTO: Codable, Equatable, Sendable { public var emoji: String; public var userId: String }
public struct MessageDTO: Codable, Equatable, Sendable, Identifiable { public var id: String; public var conversationId: String; public var senderId: String?; public var body: String?; public var clientId: String; public var state: String; public var reactions: [ReactionDTO]; public var createdAt: String }
public struct MessagesDTO: Codable, Sendable { public var messages: [MessageDTO] }

public struct ViewerDTO: Codable, Equatable, Sendable { public var id: String; public var displayName: String; public var revoked: Bool; public var acknowledgedAt: String? }
public struct SafetySessionDTO: Codable, Equatable, Sendable, Identifiable {
    public var id: String
    public var owner: PublicUser
    public var purpose: String
    public var shareLocation: Bool
    public var message: String?
    public var active: Bool
    public var startedAt: String
    public var endsAt: String
    public var endedAt: String?
    public var checkinDueAt: String?
    public var checkedInAt: String?
    public var viewers: [ViewerDTO]
}
public struct SafetySessionsDTO: Codable, Sendable { public var mine: [SafetySessionDTO]; public var sharedWithMe: [SafetySessionDTO] }
public struct LatestLocationDTO: Codable, Equatable, Sendable { public var status: String; public var capturedAt: String?; public var receivedAt: String?; public var ageSeconds: Int?; public var accuracyMeters: Int?; public var latitude: Double?; public var longitude: Double? }

public struct BillingCreditsDTO: Codable, Equatable, Sendable { public var available: Int; public var introRemaining: Int; public var monthlyRemaining: Int; public var monthlyResetsAt: String?; public var plusMonthlyAllowance: Int }
public struct BillingStatusDTO: Codable, Equatable, Sendable { public var plan: String; public var status: String; public var productId: String?; public var store: String?; public var expiresAt: String?; public var willRenew: Bool?; public var credits: BillingCreditsDTO; public var manageUrl: String? }

public struct ConfigKeyDTO: Codable, Sendable { public var keyId: String; public var alg: String; public var spki: String }
public struct PublicConfigDTO: Codable, Sendable { public var apiVersion: String; public var challengeSigningKeys: [ConfigKeyDTO]; public var features: [String: Bool] }

public struct SyncChangeDTO: Decodable, Sendable { public var entity: String; public var id: String; public var op: String }
public struct SyncDTO: Decodable, Sendable { public var changes: [SyncChangeDTO]; public var cursor: String; public var hasMore: Bool; public var bootstrap: Bool; public var resetRequired: Bool? }

public struct OkDTO: Codable, Sendable { public var ok: Bool? }
public struct Empty: Codable, Sendable { public init() {} }
