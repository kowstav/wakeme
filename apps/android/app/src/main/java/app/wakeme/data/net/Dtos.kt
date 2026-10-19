package app.wakeme.data.net

import app.wakeme.core.GrantLimits
import app.wakeme.core.Recurrence
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

val WakeMeApiJson: Json = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
    encodeDefaults = false
    classDiscriminator = "type"
}

// DTOs mirror packages/contracts/openapi.yaml (generated from the server). Only the fields the app uses.

@Serializable data class QuietHoursDto(val enabled: Boolean, val start: String, val end: String)
@Serializable data class SettingsDto(val quietHours: QuietHoursDto, val pausedUntil: String? = null, val soundBudgetPerHour: Int, val shareBypass: Boolean)
@Serializable data class CreditsDto(val available: Int, val introRemaining: Int, val monthlyRemaining: Int, val monthlyResetsAt: String? = null)
@Serializable data class BillingSummaryDto(val plan: String, val status: String, val expiresAt: String? = null, val willRenew: Boolean? = null, val store: String? = null, val credits: CreditsDto)
@Serializable data class LimitDto(val used: Int, val max: Int)
@Serializable data class LimitsDto(val activeRoutines: LimitDto, val customSounds: LimitDto)
@Serializable data class MeDto(
    val id: String,
    val displayName: String,
    val locale: String,
    val timeZone: String,
    val accountState: String,
    val deletionScheduledFor: String? = null,
    val primaryDeviceId: String? = null,
    val settings: SettingsDto,
    val billing: BillingSummaryDto,
    val limits: LimitsDto,
)

@Serializable data class DeviceDto(val id: String, val platform: String, val isPrimary: Boolean, val pushRegistered: Boolean, val readinessIssues: List<String>, val osVersion: String? = null, val lastSeenAt: String? = null)
@Serializable data class DevicesDto(val devices: List<DeviceDto>)

@Serializable data class PublicUser(val id: String, val displayName: String)
@Serializable data class GrantDto(
    val id: String,
    val grantorId: String,
    val granteeId: String,
    val version: Int,
    val capabilities: List<String>,
    val limits: GrantLimits,
    val validFrom: String,
    val validUntil: String,
    val paused: Boolean,
    val revokedAt: String? = null,
    val lastUsedAt: String? = null,
    val updatedAt: String,
)
@Serializable data class FriendAction(val capability: String, val granted: Boolean, val availableNow: Boolean, val reason: String? = null)
@Serializable data class FriendDto(
    val friendshipId: String,
    val user: PublicUser,
    val state: String,
    val since: String,
    val conversationId: String? = null,
    val theyAllowMe: GrantDto? = null,
    val iAllowThem: GrantDto? = null,
    val actions: List<FriendAction> = emptyList(),
)
@Serializable data class FriendsDto(val friends: List<FriendDto>)
@Serializable data class InviteCreatedDto(val id: String, val kind: String, val token: String, val code: String, val url: String, val expiresAt: String, val maxUses: Int)
@Serializable data class CircleRefDto(val name: String, val memberCount: Int)
@Serializable data class InvitePreviewDto(val kind: String, val inviter: PublicUser, val circle: CircleRefDto? = null, val expiresAt: String, val ownInvite: Boolean, val alreadyConnected: Boolean)
@Serializable data class RedeemDto(val kind: String, val friend: FriendDto? = null)
@Serializable data class GrantsDto(val grants: List<GrantDto>)
@Serializable data class PresetDto(val id: String, val capabilities: List<String>, val limits: GrantLimits, val validUntil: String)
@Serializable data class PresetsDto(val presets: List<PresetDto>)

@Serializable data class CommandStateDto(val lifecycle: String, val transport: String, val device: String, val human: String, val refusedReason: String? = null)
@Serializable data class CommandPayloadDto(val soundId: String? = null, val ringSeconds: Int? = null, val challengeRevisionId: String? = null, val minutes: Int? = null, val goal: String? = null)
@Serializable data class CommandDto(
    val id: String,
    val capability: String,
    val actor: PublicUser,
    val target: PublicUser,
    val grantVersion: Int,
    val payload: CommandPayloadDto,
    val deliveryMode: String,
    val expiresAt: String,
    val createdAt: String,
    val state: CommandStateDto,
    val displayStatus: String,
)
@Serializable data class CommandsDto(val commands: List<CommandDto>)

@Serializable data class PlanVersionDto(
    val version: Int,
    val label: String,
    val localTime: String,
    val timeZone: String,
    val timeZonePolicy: String,
    val recurrence: Recurrence,
    val soundId: String,
    val ringSeconds: Int,
    val challengeRevisionId: String? = null,
    val challengePreview: JsonElement? = null,
    val followUpDelaySeconds: Int? = null,
    val createdBy: String,
    val createdAt: String,
)
@Serializable data class InstallationDto(val deviceId: String, val version: Int, val result: String, val errorCode: String? = null, val nextFireAt: String? = null, val reportedAt: String)
@Serializable data class OccurrenceDto(val localDate: String, val instant: String)
@Serializable data class PlanDto(
    val id: String,
    val owner: PublicUser,
    val creator: PublicUser,
    val state: String,
    val accepted: PlanVersionDto? = null,
    val pending: PlanVersionDto? = null,
    val readiness: String,
    val installation: InstallationDto? = null,
    val nextOccurrences: List<OccurrenceDto> = emptyList(),
    val createdAt: String,
    val updatedAt: String,
)
@Serializable data class PlansDto(val mine: List<PlanDto>, val helping: List<PlanDto>)

@Serializable data class RevisionDto(val id: String, val revision: Int, val state: String, val origin: String, val body: JsonElement, val previewedAt: String? = null, val publishedAt: String? = null)
@Serializable data class ChallengeDto(val id: String, val creatorId: String, val circleId: String? = null, val title: String, val latestRevision: Int, val publishedRevision: Int? = null, val revisions: List<RevisionDto>)
@Serializable data class ChallengesDto(val challenges: List<ChallengeDto>)
@Serializable data class TemplateDto(val id: String, val title: String, val description: String, val preview: JsonElement)
@Serializable data class TemplatesDto(val templates: List<TemplateDto>)
@Serializable data class GenerationDto(val id: String, val status: String, val difficulty: String, val definitionId: String? = null, val rejection: JsonElement? = null, val errorCode: String? = null, val creditsRemaining: Int)

@Serializable data class SyncChangeDto(val entity: String, val id: String, val op: String, val data: JsonElement? = null)
@Serializable data class SyncDto(val changes: List<SyncChangeDto>, val cursor: String, val hasMore: Boolean, val bootstrap: Boolean, val resetRequired: Boolean? = null)

@Serializable data class ConversationDto(val id: String, val kind: String, val title: String, val lastMessageAt: String? = null, val unread: Int)
@Serializable data class ConversationsDto(val conversations: List<ConversationDto>)
@Serializable data class ReactionDto(val emoji: String, val userId: String)
@Serializable data class MessageDto(val id: String, val conversationId: String, val senderId: String? = null, val body: String? = null, val clientId: String, val state: String, val reactions: List<ReactionDto>, val createdAt: String)
@Serializable data class MessagesDto(val messages: List<MessageDto>)

@Serializable data class ViewerDto(val id: String, val displayName: String, val revoked: Boolean, val acknowledgedAt: String? = null)
@Serializable data class SafetySessionDto(
    val id: String,
    val owner: PublicUser,
    val purpose: String,
    val shareLocation: Boolean,
    val message: String? = null,
    val active: Boolean,
    val startedAt: String,
    val endsAt: String,
    val endedAt: String? = null,
    val checkinDueAt: String? = null,
    val checkedInAt: String? = null,
    val viewers: List<ViewerDto>,
)
@Serializable data class SafetySessionsDto(val mine: List<SafetySessionDto>, val sharedWithMe: List<SafetySessionDto>)
@Serializable data class LatestLocationDto(val status: String, val capturedAt: String? = null, val receivedAt: String? = null, val ageSeconds: Int? = null, val accuracyMeters: Int? = null, val latitude: Double? = null, val longitude: Double? = null)

@Serializable data class BillingCreditsDto(val available: Int, val introRemaining: Int, val monthlyRemaining: Int, val monthlyResetsAt: String? = null, val plusMonthlyAllowance: Int)
@Serializable data class BillingStatusDto(val plan: String, val status: String, val productId: String? = null, val store: String? = null, val expiresAt: String? = null, val willRenew: Boolean? = null, val credits: BillingCreditsDto, val manageUrl: String? = null)

@Serializable data class ConfigKeyDto(val keyId: String, val alg: String, val spki: String)
@Serializable data class PublicConfigDto(val apiVersion: String, val challengeSigningKeys: List<ConfigKeyDto>, val features: Map<String, Boolean>)

@Serializable data class OkDto(val ok: Boolean = true)
@Serializable data class SignedEnvelopeDto(val revisionId: String, val payload: String, val signature: String, val keyId: String, val digest: String, val alg: String)
