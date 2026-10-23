package app.wakeme.core

/** How the sender's delivery trail looks after the steps it has reached. */
enum class TrailState { ACTIVE, WAITING, PROBLEM, DONE }

/**
 * The sender-facing delivery trail: sent → reached their phone → seen (a quack) or rang (a wake
 * alarm). A step counts as reached only when the literal [displayStatus] reports evidence for it,
 * so an animation can never advance the trail further than the server knows. Same table as
 * packages/policy-fixtures/fixtures/delivery-trail.json and iOS `DeliveryTrail`.
 */
data class DeliveryTrail(val reached: Int, val state: TrailState) {
    companion object {
        const val STEPS = 3

        fun of(displayStatus: String, alarm: Boolean): DeliveryTrail = when (displayStatus) {
            "sending" -> DeliveryTrail(0, TrailState.ACTIVE)
            "sent" -> DeliveryTrail(1, TrailState.ACTIVE)
            "no_confirmation_yet" -> DeliveryTrail(1, TrailState.WAITING)
            "not_delivered", "expired", "cancelled" -> DeliveryTrail(1, TrailState.PROBLEM)
            "phone_received", "alarm_scheduled_on_phone" -> DeliveryTrail(2, TrailState.ACTIVE)
            "not_played" -> DeliveryTrail(2, TrailState.PROBLEM)
            // Opening or acknowledging is seeing a quack, but it doesn't prove an alarm rang.
            "opened", "acknowledged" -> DeliveryTrail(if (alarm) 2 else 3, TrailState.DONE)
            // Ringing is the last step for an alarm; a completed challenge never means "awake".
            "alarm_started", "stopped", "ended_without_challenge", "challenge_completed" ->
                DeliveryTrail(if (alarm) 3 else 2, TrailState.DONE)
            // A status from a newer server claims no more than "sent".
            else -> DeliveryTrail(1, TrailState.ACTIVE)
        }
    }
}
