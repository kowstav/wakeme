package app.wakeme.core

import java.time.Duration
import java.time.Instant

/** Delivery evidence reduced by precedence (never last-event-wins). Mirrors packages/policy delivery.ts. */
data class DeliveryEvent(val kind: String, val seq: Long, val reason: String? = null)

data class DeliveryState(
    val lifecycle: String = "accepted",
    val transport: String = "queued",
    val device: String = "unknown",
    val human: String = "none",
    val refusedReason: String? = null,
)

private val TRANSPORT_RANK = mapOf("queued" to 1, "not_sent" to 2, "provider_rejected" to 2, "provider_accepted" to 3)
private val DEVICE_RANK = mapOf("unknown" to 0, "received" to 1, "installed" to 2, "execution_reported" to 3)
private val HUMAN_RANK = mapOf("none" to 0, "opened" to 1, "acknowledged" to 2, "challenge_completed" to 3, "bypassed" to 3)

fun reduceDelivery(events: List<DeliveryEvent>): DeliveryState {
    var s = DeliveryState()
    var transportSeen = false
    for (e in events.sortedBy { it.seq }) {
        when (e.kind) {
            "expired", "cancelled" -> if (s.lifecycle == "accepted") s = s.copy(lifecycle = e.kind)
            "queued", "provider_accepted", "provider_rejected", "not_sent" -> {
                if (!transportSeen || TRANSPORT_RANK.getValue(e.kind) > TRANSPORT_RANK.getValue(s.transport)) s = s.copy(transport = e.kind)
                transportSeen = true
            }
            "received", "installed", "execution_reported" -> if (DEVICE_RANK.getValue(e.kind) > DEVICE_RANK.getValue(s.device)) s = s.copy(device = e.kind)
            "execution_refused" -> {
                if (s.refusedReason == null) s = s.copy(refusedReason = e.reason ?: "refused")
                if (s.device == "unknown") s = s.copy(device = "received")
            }
            "opened", "acknowledged", "challenge_completed", "bypassed" -> {
                if (HUMAN_RANK.getValue(s.human) != 3 && HUMAN_RANK.getValue(e.kind) > HUMAN_RANK.getValue(s.human)) s = s.copy(human = e.kind)
            }
        }
    }
    if (s.human != "none" && s.device == "unknown") s = s.copy(device = "received")
    return s
}

/** Literal sender-facing status. "phone_received" is not "heard it"; "challenge_completed" is not "awake". */
fun displayStatus(state: DeliveryState, now: Instant, sentAt: Instant, kind: String, shareBypass: Boolean, graceSeconds: Long = 60): String {
    when (state.human) {
        "challenge_completed" -> return "challenge_completed"
        "bypassed" -> return if (shareBypass) "ended_without_challenge" else "stopped"
        "acknowledged" -> return "acknowledged"
        "opened" -> return "opened"
    }
    if (state.refusedReason != null && state.device != "execution_reported") return "not_played"
    when (state.device) {
        "execution_reported" -> return if (kind == "alarm") "alarm_started" else "phone_received"
        "installed" -> return if (kind == "alarm") "alarm_scheduled_on_phone" else "phone_received"
        "received" -> return "phone_received"
    }
    if (state.lifecycle == "cancelled") return "cancelled"
    if (state.lifecycle == "expired") return if (state.transport == "provider_rejected" || state.transport == "not_sent") "not_delivered" else "expired"
    return when (state.transport) {
        "queued" -> "sending"
        "provider_rejected", "not_sent" -> "not_delivered"
        else -> if (Duration.between(sentAt, now).seconds < graceSeconds) "sent" else "no_confirmation_yet"
    }
}
