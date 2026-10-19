package app.wakeme.core

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** JSON settings shared by API, sync cache and fixtures. Unknown keys are tolerated from the server, never invented. */
val WakeMeJson: Json = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
    classDiscriminator = "type"
    encodeDefaults = true
}

@Serializable
data class LocalWindow(val days: List<Int>, val start: String, val end: String)

@Serializable
data class QuietHours(val enabled: Boolean, val start: String, val end: String)

@Serializable
data class RecipientSettings(
    val quietHours: QuietHours,
    val pausedUntil: String? = null,
    val soundBudgetPerHour: Int = 6,
)

@Serializable
data class NudgeLimits(val maxPerHour: Int, val windows: List<LocalWindow>, val sounds: List<String>, val overridesQuietHours: Boolean)

@Serializable
data class FollowUp(val allowed: Boolean, val maxDelaySeconds: Int)

@Serializable
data class AlarmLimits(
    val maxAlarmSeconds: Int,
    val maxPerDay: Int,
    val windows: List<LocalWindow>,
    val sounds: List<String>,
    val followUp: FollowUp,
)

@Serializable
data class ChallengeLimits(val types: List<String>, val maxDurationSeconds: Int, val maxDifficulty: String)

@Serializable
data class FocusLimits(val maxSessionMinutes: Int, val maxPerDay: Int, val windows: List<LocalWindow>)

@Serializable
data class CheckinLimits(val maxPerDay: Int)

@Serializable
data class GrantLimits(
    val nudge: NudgeLimits? = null,
    val alarm: AlarmLimits? = null,
    val challenge: ChallengeLimits? = null,
    val focus: FocusLimits? = null,
    val checkin: CheckinLimits? = null,
)

/** A recipient-owned grant as cached on the recipient's phone (grants this user gave to friends). */
@Serializable
data class GrantSnapshot(
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
)

object Capability {
    const val NUDGE = "nudge.send"
    const val ALARM_REQUEST = "alarm.request"
    const val ALARM_SCHEDULE = "alarm.schedule"
    const val CHALLENGE_ASSIGN = "challenge.assign"
    const val FOCUS = "focus.request"
    const val CHECKIN = "safety.checkin.request"
    const val LOCATION = "location.request"
    val SAFETY = setOf(CHECKIN, LOCATION)
}

@Serializable
data class Recurrence(val kind: String, val days: List<Int>? = null, val date: String? = null) {
    companion object {
        fun weekly(days: List<Int>) = Recurrence("weekly", days = days)
        fun once(date: String) = Recurrence("once", date = date)
    }
}

// ---------------------------------------------------------------- challenges

@Serializable
sealed class ChallengeStep {
    abstract val id: String

    @Serializable
    @SerialName("multiple_choice")
    data class MultipleChoice(override val id: String, val prompt: String, val options: List<String>, val correctIndex: Int, val source: String = "creator_provided", val factId: String? = null) : ChallengeStep()

    @Serializable
    @SerialName("short_answer")
    data class ShortAnswer(override val id: String, val prompt: String, val acceptedAnswers: List<String>, val source: String = "creator_provided", val factId: String? = null) : ChallengeStep()

    @Serializable
    @SerialName("math")
    data class MathStep(override val id: String, val operands: List<Long>, val operator: String, val answer: Long) : ChallengeStep()

    @Serializable
    @SerialName("memory_order")
    data class MemoryOrder(override val id: String, val prompt: String, val items: List<String>, val displaySeconds: Int) : ChallengeStep()

    @Serializable
    @SerialName("chess")
    data class Chess(override val id: String, val prompt: String, val fen: String, val solutions: List<String>, val goal: String = "mate_in_1", val puzzleId: String? = null) : ChallengeStep()

    @Serializable
    @SerialName("timer")
    data class Timer(override val id: String, val prompt: String, val seconds: Int, val activity: String) : ChallengeStep()

    @Serializable
    @SerialName("qr_code")
    data class QrCode(override val id: String, val prompt: String, val codeHash: String) : ChallengeStep()

    @Serializable
    @SerialName("self_attested")
    data class SelfAttested(override val id: String, val prompt: String, val items: List<String>, val category: String, val alternative: String? = null) : ChallengeStep()
}

@Serializable
data class Fallback(val type: String = "accessible_checkin", val prompt: String? = null)

@Serializable
data class ChallengeDefinition(
    val schemaVersion: Int,
    val title: String,
    val kind: String,
    val difficulty: String,
    val maxDurationSeconds: Int,
    val steps: List<ChallengeStep>,
    val verification: String,
    val fallback: Fallback,
    val permissionsRequired: List<String> = emptyList(),
)

/** What the user did for one step; mirrors the server's StepResponse. */
sealed class StepResponse {
    data class Index(val index: Int) : StepResponse()
    data class Text(val text: String) : StepResponse()
    data class Order(val order: List<String>) : StepResponse()
    data class Move(val move: String) : StepResponse()
    data class Elapsed(val elapsedSeconds: Double) : StepResponse()
    data class Checked(val checked: List<Boolean>) : StepResponse()
}
