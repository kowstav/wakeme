package app.wakeme.data

import app.wakeme.data.net.ApiClient
import app.wakeme.data.net.ChallengeDto
import app.wakeme.data.net.ChallengesDto
import app.wakeme.data.net.CommandDto
import app.wakeme.data.net.CommandsDto
import app.wakeme.data.net.ConversationsDto
import app.wakeme.data.net.DevicesDto
import app.wakeme.data.net.FriendDto
import app.wakeme.data.net.FriendsDto
import app.wakeme.data.net.GenerationDto
import app.wakeme.data.net.GrantDto
import app.wakeme.data.net.InviteCreatedDto
import app.wakeme.data.net.InvitePreviewDto
import app.wakeme.data.net.LatestLocationDto
import app.wakeme.data.net.MeDto
import app.wakeme.data.net.MessageDto
import app.wakeme.data.net.MessagesDto
import app.wakeme.data.net.OkDto
import app.wakeme.data.net.PlanDto
import app.wakeme.data.net.PlansDto
import app.wakeme.data.net.PresetsDto
import app.wakeme.data.net.RedeemDto
import app.wakeme.data.net.SafetySessionDto
import app.wakeme.data.net.SafetySessionsDto
import app.wakeme.data.net.TemplatesDto
import app.wakeme.data.net.WakeMeApiJson
import app.wakeme.core.GrantLimits
import app.wakeme.core.Recurrence
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.put
import java.time.Instant
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/** Typed calls the screens use. Every remote action is authorized by the server against recipient-owned grants. */
@Singleton
class WakeMeApi @Inject constructor(private val api: ApiClient) {
    // ------------------------------------------------------------- account
    suspend fun me() = api.get("/v1/me", MeDto.serializer())
    suspend fun createAccount(name: String, timeZone: String, locale: String) =
        api.post("/v1/account", buildJsonObject { put("displayName", name); put("timeZone", timeZone); put("locale", locale); put("ageAttestation", true) }, MeDto.serializer(), idempotent = true)
    suspend fun updateMe(body: JsonObject) = api.patch("/v1/me", body, MeDto.serializer())
    suspend fun deleteAccount() = api.delete("/v1/account", JsonObject.serializer())
    suspend fun cancelDeletion() = api.post("/v1/account/deletion/cancel", null, MeDto.serializer())
    suspend fun exportAccount() = api.get("/v1/account/export", JsonObject.serializer())

    suspend fun devices() = api.get("/v1/devices", DevicesDto.serializer()).devices

    // ------------------------------------------------------------- friends
    suspend fun friends() = api.get("/v1/friends", FriendsDto.serializer()).friends
    suspend fun friend(friendshipId: String) = api.get("/v1/friendships/$friendshipId", FriendDto.serializer())
    suspend fun createInvite() = api.post("/v1/invites", buildJsonObject { put("kind", "friend") }, InviteCreatedDto.serializer())
    suspend fun previewInvite(token: String?, code: String?) = api.post("/v1/invites/preview", inviteRef(token, code), InvitePreviewDto.serializer())
    suspend fun redeemInvite(token: String?, code: String?) = api.post("/v1/invites/redeem", inviteRef(token, code), RedeemDto.serializer())
    private fun inviteRef(token: String?, code: String?) = buildJsonObject { token?.let { put("token", it) }; code?.let { put("code", it) } }
    suspend fun removeFriend(friendshipId: String) = api.delete("/v1/friendships/$friendshipId", JsonObject.serializer())
    suspend fun block(friendshipId: String, reportReason: String?) =
        api.post("/v1/friendships/$friendshipId/block", buildJsonObject { reportReason?.let { r -> put("report", buildJsonObject { put("reason", r) }) } }, JsonObject.serializer())

    // -------------------------------------------------------------- grants
    suspend fun presets() = api.get("/v1/grants/presets", PresetsDto.serializer()).presets
    suspend fun createGrant(granteeId: String, capabilities: List<String>, limits: GrantLimits, validUntil: Instant) =
        api.post("/v1/grants", grantTerms(capabilities, limits, validUntil).plus("granteeId", granteeId), GrantDto.serializer())
    suspend fun updateGrant(id: String, expectedVersion: Int, capabilities: List<String>, limits: GrantLimits, validUntil: Instant, paused: Boolean) =
        api.put("/v1/grants/$id", grantTerms(capabilities, limits, validUntil).plus("expectedVersion", expectedVersion).plus("paused", paused), GrantDto.serializer())
    suspend fun pauseGrant(id: String, paused: Boolean) = api.post("/v1/grants/$id/pause", buildJsonObject { put("paused", paused) }, GrantDto.serializer())
    suspend fun revokeGrant(id: String) = api.delete("/v1/grants/$id", JsonObject.serializer())
    private fun grantTerms(capabilities: List<String>, limits: GrantLimits, validUntil: Instant) = buildJsonObject {
        put("capabilities", buildJsonArray { capabilities.forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) } })
        put("limits", WakeMeApiJson.encodeToJsonElement(limits))
        put("validUntil", validUntil.toString())
    }

    // ------------------------------------------------------------ commands
    /** Idempotency key generated once per user action, so a retry never sends twice. */
    suspend fun sendCommand(targetUserId: String, capability: String, expectedGrantVersion: Int?, extra: Map<String, Any>, idempotencyKey: String = ApiClient.idempotencyKey()) =
        api.post(
            "/v1/commands",
            buildJsonObject {
                put("targetUserId", targetUserId)
                put("capability", capability)
                put("idempotencyKey", idempotencyKey)
                expectedGrantVersion?.let { put("expectedGrantVersion", it) }
                for ((k, v) in extra) when (v) {
                    is String -> put(k, v)
                    is Int -> put(k, v)
                    is Boolean -> put(k, v)
                }
            },
            CommandDto.serializer(),
            signed = true,
            idempotent = true,
        )
    suspend fun command(id: String) = api.get("/v1/commands/$id", CommandDto.serializer())
    suspend fun commands(direction: String, friendId: String?) = api.get("/v1/commands?direction=$direction" + (friendId?.let { "&friendId=$it" } ?: ""), CommandsDto.serializer()).commands
    suspend fun cancelCommand(id: String) = api.post("/v1/commands/$id/cancel", null, CommandDto.serializer())

    // ------------------------------------------------------------ routines
    suspend fun plans() = api.get("/v1/alarm-plans", PlansDto.serializer())
    suspend fun plan(id: String) = api.get("/v1/alarm-plans/$id", PlanDto.serializer())
    suspend fun createPlan(ownerId: String?, terms: PlanTerms) = api.post("/v1/alarm-plans", terms.json(ownerId, null), PlanDto.serializer())
    suspend fun editPlan(id: String, expectedLatestVersion: Int, terms: PlanTerms) = api.patch("/v1/alarm-plans/$id", terms.json(null, expectedLatestVersion), PlanDto.serializer())
    suspend fun acceptPlan(id: String, version: Int) = api.post("/v1/alarm-plans/$id/accept", buildJsonObject { put("version", version) }, PlanDto.serializer())
    suspend fun declinePlan(id: String, version: Int) = api.post("/v1/alarm-plans/$id/decline", buildJsonObject { put("version", version) }, PlanDto.serializer())
    suspend fun pausePlan(id: String, paused: Boolean) = api.post("/v1/alarm-plans/$id/pause", buildJsonObject { put("paused", paused) }, PlanDto.serializer())
    suspend fun cancelPlan(id: String) = api.delete("/v1/alarm-plans/$id", PlanDto.serializer())

    // ---------------------------------------------------------- challenges
    suspend fun templates() = api.get("/v1/challenges/templates", TemplatesDto.serializer()).templates
    suspend fun challenges() = api.get("/v1/challenges", ChallengesDto.serializer()).challenges
    suspend fun challenge(id: String) = api.get("/v1/challenges/$id", ChallengeDto.serializer())
    suspend fun createChallenge(body: JsonElement) = api.post("/v1/challenges", buildJsonObject { put("body", body) }, ChallengeDto.serializer())
    suspend fun fromTemplate(templateId: String, difficulty: String) = api.post("/v1/challenges/from-template", buildJsonObject { put("templateId", templateId); put("difficulty", difficulty) }, ChallengeDto.serializer())
    suspend fun markPreviewed(id: String, revision: Int) = api.post("/v1/challenges/$id/revisions/$revision/previewed", null, OkDto.serializer())
    suspend fun publish(id: String, revision: Int) = api.post("/v1/challenges/$id/publish", buildJsonObject { put("revision", revision) }, ChallengeDto.serializer())
    suspend fun generate(prompt: String, facts: List<Triple<String, String, List<String>>>, difficulty: String) = api.post(
        "/v1/challenges/generations",
        buildJsonObject {
            put("prompt", prompt)
            put("difficulty", difficulty)
            put("facts", buildJsonArray {
                for ((q, a, d) in facts) add(buildJsonObject { put("question", q); put("answer", a); put("distractors", buildJsonArray { d.forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) } }) })
            })
        },
        GenerationDto.serializer(),
    )
    suspend fun generation(id: String) = api.get("/v1/challenges/generations/$id", GenerationDto.serializer())

    // ------------------------------------------------------------ messages
    suspend fun conversations() = api.get("/v1/conversations", ConversationsDto.serializer()).conversations
    suspend fun messages(conversationId: String) = api.get("/v1/conversations/$conversationId/messages", MessagesDto.serializer()).messages
    suspend fun sendMessage(conversationId: String, text: String) =
        api.post("/v1/conversations/$conversationId/messages", buildJsonObject { put("body", text); put("clientId", "c_" + UUID.randomUUID().toString().replace("-", "")) }, MessageDto.serializer(), idempotent = true)
    /** One of the server's REACTIONS; replaces this user's previous reaction. */
    suspend fun react(messageId: String, emoji: String) = api.post("/v1/messages/$messageId/reactions", buildJsonObject { put("emoji", emoji) }, JsonObject.serializer())
    suspend fun unreact(messageId: String) = api.delete("/v1/messages/$messageId/reactions", JsonObject.serializer())
    suspend fun markRead(conversationId: String) = api.post("/v1/conversations/$conversationId/read", null, OkDto.serializer())
    suspend fun report(kind: String, id: String, reason: String, note: String?) =
        api.post("/v1/reports", buildJsonObject { put("targetKind", kind); put("targetId", id); put("reason", reason); note?.let { put("note", it) } }, JsonObject.serializer())

    // -------------------------------------------------------------- safety
    suspend fun safetySessions() = api.get("/v1/location-sessions", SafetySessionsDto.serializer())
    suspend fun startSafety(purpose: String, viewerIds: List<String>, minutes: Int, shareLocation: Boolean, checkinDueAt: Instant?, message: String?) = api.post(
        "/v1/location-sessions",
        buildJsonObject {
            put("purpose", purpose)
            put("viewerIds", buildJsonArray { viewerIds.forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) } })
            put("durationMinutes", minutes)
            put("shareLocation", shareLocation)
            checkinDueAt?.let { put("checkinDueAt", it.toString()) }
            message?.let { put("message", it) }
        },
        SafetySessionDto.serializer(),
    )
    suspend fun sos(viewerIds: List<String>, shareLocation: Boolean) =
        api.post("/v1/sos", buildJsonObject { put("viewerIds", buildJsonArray { viewerIds.forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) } }); put("shareLocation", shareLocation) }, SafetySessionDto.serializer())
    suspend fun postLocation(sessionId: String, capturedAt: Instant, lat: Double, lon: Double, accuracy: Int) = api.post(
        "/v1/location-sessions/$sessionId/samples",
        buildJsonObject { put("capturedAt", capturedAt.toString()); put("latitude", lat); put("longitude", lon); put("accuracyMeters", accuracy) },
        OkDto.serializer(),
        signed = true,
    )
    suspend fun latestLocation(sessionId: String) = api.get("/v1/location-sessions/$sessionId/latest", LatestLocationDto.serializer())
    suspend fun endSafety(sessionId: String) = api.post("/v1/location-sessions/$sessionId/end", null, SafetySessionDto.serializer())
    suspend fun checkIn(sessionId: String) = api.post("/v1/location-sessions/$sessionId/check-in", buildJsonObject { }, SafetySessionDto.serializer())
    suspend fun acknowledgeSafety(sessionId: String) = api.post("/v1/location-sessions/$sessionId/acknowledge", null, OkDto.serializer())
}

data class PlanTerms(
    val label: String,
    val localTime: String,
    val timeZone: String,
    val timeZonePolicy: String,
    val recurrence: Recurrence,
    val soundId: String,
    val ringSeconds: Int,
    val challengeRevisionId: String?,
    val followUpDelaySeconds: Int?,
) {
    fun json(ownerId: String?, expectedLatestVersion: Int?) = buildJsonObject {
        ownerId?.let { put("ownerId", it) }
        expectedLatestVersion?.let { put("expectedLatestVersion", it) }
        put("label", label)
        put("localTime", localTime)
        put("timeZone", timeZone)
        put("timeZonePolicy", timeZonePolicy)
        put("recurrence", WakeMeApiJson.encodeToJsonElement(recurrence))
        put("soundId", soundId)
        put("ringSeconds", ringSeconds)
        challengeRevisionId?.let { put("challengeRevisionId", it) }
        followUpDelaySeconds?.let { put("followUpDelaySeconds", it) }
    }
}

private fun JsonObject.plus(key: String, value: Any): JsonObject = JsonObject(this + (key to when (value) {
    is String -> kotlinx.serialization.json.JsonPrimitive(value)
    is Int -> kotlinx.serialization.json.JsonPrimitive(value)
    is Boolean -> kotlinx.serialization.json.JsonPrimitive(value)
    else -> error("unsupported")
}))
