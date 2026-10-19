package app.wakeme.core

import java.time.Instant
import java.time.ZoneId

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

data class ScheduledAlarm(val planId: String, val version: Int, val occurrenceKey: String, val role: AlarmRole, val fireAt: Instant) {
    /** Stable PendingIntent request code so re-scheduling replaces instead of duplicating. */
    val requestCode: Int get() = (planId + occurrenceKey + role.name).hashCode()
}

/**
 * Computes the alarms to install: the next occurrence and, if the owner
 * accepted it, one follow-up. Completing the challenge cancels the follow-up
 * locally; stopping the main alarm with the system control does not.
 */
fun scheduleFor(routine: InstalledRoutine, now: Instant, deviceZone: ZoneId): List<ScheduledAlarm> {
    if (routine.paused) return emptyList()
    val zone = if (routine.timeZonePolicy == "device") deviceZone else ZoneId.of(routine.timeZone)
    val next = nextOccurrences(routine.localTime, zone, routine.recurrence, now, 1).firstOrNull() ?: return emptyList()
    val main = ScheduledAlarm(routine.planId, routine.version, next.localDate, AlarmRole.MAIN, next.instant)
    val delay = routine.followUpDelaySeconds ?: return listOf(main)
    return listOf(main, ScheduledAlarm(routine.planId, routine.version, next.localDate, AlarmRole.FOLLOW_UP, next.instant.plusSeconds(delay.toLong())))
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
