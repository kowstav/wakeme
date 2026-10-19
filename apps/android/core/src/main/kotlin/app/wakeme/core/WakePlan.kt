package app.wakeme.core

import java.time.Duration
import java.time.Instant
import java.time.ZoneId

/** Wake-ups set up before signing in live only on this phone, under ids with this prefix. They
 * never produce server receipts and move to the account when the person signs in. */
const val LOCAL_PLAN_PREFIX = "local-"

fun isLocalPlan(planId: String): Boolean = planId.startsWith(LOCAL_PLAN_PREFIX)

/**
 * An installed routine version as the phone keeps it: everything needed to
 * ring and run the challenge with no network at ring time.
 */
@kotlinx.serialization.Serializable
data class InstalledRoutine(
    val planId: String,
    val version: Int,
    val label: String,
    val localTime: String,
    val timeZone: String,
    val timeZonePolicy: String,
    val recurrence: Recurrence,
    val soundId: String,
    val ringSeconds: Int,
    val challengeRevisionId: String?,
    val followUpDelaySeconds: Int?,
    val paused: Boolean,
)

enum class AlarmRole { MAIN, FOLLOW_UP }

/**
 * ADR 0007: only the primary phone installs and rings routines. With no primary
 * recorded yet, the registering phone counts as primary so a lone phone never
 * skips its alarms. Same rule as iOS `installsRoutines`.
 */
fun installsRoutines(primaryDeviceId: String?, thisDeviceId: String?): Boolean =
    primaryDeviceId == null || (thisDeviceId != null && primaryDeviceId == thisDeviceId)

data class ScheduledAlarm(val planId: String, val version: Int, val occurrenceKey: String, val role: AlarmRole, val fireAt: Instant) {
    /** Stable PendingIntent request code so re-scheduling replaces instead of duplicating. */
    val requestCode: Int get() = (planId + occurrenceKey + role.name).hashCode()
}

/** Routines are armed this far ahead (ADR 0011); the app tops the window up whenever it runs. */
const val ARM_WINDOW_DAYS = 8L

/** At most this many occurrences of one routine are armed at once. */
const val ARM_MAX_OCCURRENCES = 8

/**
 * Computes the alarms to install: every occurrence in the next [ARM_WINDOW_DAYS] days (at
 * most [ARM_MAX_OCCURRENCES], and always the next one even when it is further away), each
 * with one follow-up if the owner accepted it. Arming ahead keeps a routine ringing when
 * the app doesn't run between occurrences. Completing the challenge cancels that
 * occurrence's follow-up locally; stopping the main alarm with the system control does not.
 */
fun scheduleFor(routine: InstalledRoutine, now: Instant, deviceZone: ZoneId): List<ScheduledAlarm> {
    if (routine.paused) return emptyList()
    val zone = if (routine.timeZonePolicy == "device") deviceZone else ZoneId.of(routine.timeZone)
    val until = now.plus(Duration.ofDays(ARM_WINDOW_DAYS))
    val occurrences = nextOccurrences(routine.localTime, zone, routine.recurrence, now, ARM_MAX_OCCURRENCES)
        .filterIndexed { i, o -> i == 0 || !o.instant.isAfter(until) }
    return occurrences.flatMap { o ->
        val main = ScheduledAlarm(routine.planId, routine.version, o.localDate, AlarmRole.MAIN, o.instant)
        val delay = routine.followUpDelaySeconds ?: return@flatMap listOf(main)
        listOf(main, ScheduledAlarm(routine.planId, routine.version, o.localDate, AlarmRole.FOLLOW_UP, o.instant.plusSeconds(delay.toLong())))
    }
}

/** States of one wake session on the phone. Transitions are monotonic. */
enum class WakeSessionState { RINGING, CHALLENGE, FOLLOW_UP_PENDING, COMPLETED, BYPASSED, ENDED }

data class WakeSession(val planId: String, val occurrenceKey: String, val state: WakeSessionState, val hasFollowUp: Boolean) {
    fun onSystemStop(): WakeSession = if (state == WakeSessionState.RINGING || state == WakeSessionState.CHALLENGE) {
        copy(state = if (hasFollowUp) WakeSessionState.FOLLOW_UP_PENDING else WakeSessionState.ENDED)
    } else this

    fun onChallengeCompleted(): WakeSession = if (state in setOf(WakeSessionState.COMPLETED, WakeSessionState.BYPASSED, WakeSessionState.ENDED)) this else copy(state = WakeSessionState.COMPLETED)

    /** The always-available safe escape: stops everything and cancels the follow-up. */
    fun onEscape(): WakeSession = if (state in setOf(WakeSessionState.COMPLETED, WakeSessionState.ENDED)) this else copy(state = WakeSessionState.BYPASSED)

    /** Completion or the safe escape cancels the pending follow-up; a system Stop alone does not. */
    val cancelsFollowUp: Boolean get() = state == WakeSessionState.COMPLETED || state == WakeSessionState.BYPASSED
}
