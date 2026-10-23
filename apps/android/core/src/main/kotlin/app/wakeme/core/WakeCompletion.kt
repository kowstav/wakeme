package app.wakeme.core

/** Transport evidence describes the action taken, never infers that a person solved a quiz. */
enum class WakeCompletion(val commandReceipt: String, val occurrenceOutcome: String) {
    SOLVED("challenge_completed", "challenge_completed"),
    CHECKED_IN("acknowledged", "session_ended"),
    ESCAPED("bypassed", "bypassed"),
}
