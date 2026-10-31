package app.wakeme.core

import kotlinx.serialization.Serializable

/** Never longer than this, whatever a grant says (doc 02 §12: focus restrictions are finite). */
const val FOCUS_MAX_MINUTES = 240
const val FOCUS_BREAK_MINUTES = 5
const val FOCUS_MAX_BREAKS = 3

/**
 * A focus session on this phone (ADR 0014). Times are epoch milliseconds. Everything here works
 * offline: the end, breaks and "End focus" never wait for the server. Mirrors Swift FocusSession.
 */
@Serializable
data class FocusSession(
    val id: String,
    val startedAt: Long,
    val endsAt: Long,
    val friendId: String? = null,
    val friendName: String? = null,
    /** The friend's request this session answers, if any. */
    val commandId: String? = null,
    val breakUntil: Long? = null,
    val breaksTaken: Int = 0,
    val endedAt: Long? = null,
    /** "completed" | "ended_early" */
    val endReason: String? = null,
)

sealed class FocusState {
    data class Active(val remainingMs: Long) : FocusState()
    data class OnBreak(val breakRemainingMs: Long, val sessionRemainingMs: Long) : FocusState()
    data class Finished(val reason: String) : FocusState()
}

/**
 * Starts a session of [minutes], bounded by the friend's limit (if the session answers a friend)
 * and by [FOCUS_MAX_MINUTES]. Returns null for a non-positive length.
 */
fun startFocus(id: String, now: Long, minutes: Int, limits: FocusLimits? = null, friendId: String? = null, friendName: String? = null, commandId: String? = null): FocusSession? {
    if (minutes <= 0) return null
    val cap = minOf(FOCUS_MAX_MINUTES, limits?.maxSessionMinutes ?: FOCUS_MAX_MINUTES)
    val length = minOf(minutes, cap)
    return FocusSession(id, now, now + length * 60_000L, friendId, friendName, commandId)
}

fun focusState(s: FocusSession, now: Long): FocusState = when {
    s.endedAt != null -> FocusState.Finished(s.endReason ?: "ended_early")
    now >= s.endsAt -> FocusState.Finished("completed")
    s.breakUntil != null && now < s.breakUntil -> FocusState.OnBreak(s.breakUntil - now, s.endsAt - now)
    else -> FocusState.Active(s.endsAt - now)
}

/** The chosen apps are shielded only while a session is active and not on a break. */
fun shouldShield(s: FocusSession?, now: Long): Boolean = s != null && focusState(s, now) is FocusState.Active

/** A finished challenge earns a short break, at most [FOCUS_MAX_BREAKS] per session; null if none is left. */
fun takeBreak(s: FocusSession, now: Long): FocusSession? {
    if (focusState(s, now) !is FocusState.Active || s.breaksTaken >= FOCUS_MAX_BREAKS) return null
    return s.copy(breakUntil = minOf(s.endsAt, now + FOCUS_BREAK_MINUTES * 60_000L), breaksTaken = s.breaksTaken + 1)
}

/** "End focus": always allowed, takes effect at once (the safe escape). */
fun endFocus(s: FocusSession, now: Long): FocusSession =
    if (s.endedAt != null) s else s.copy(endedAt = now, endReason = if (now >= s.endsAt) "completed" else "ended_early")
