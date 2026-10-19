package app.wakeme.core

import java.time.Instant
import java.time.ZoneId

/** Clock skew tolerated at expiry and window edges (server uses the same value). */
const val CLOCK_SKEW_SECONDS = 30L

data class IncomingCommand(
    val id: String,
    val capability: String,
    val actorId: String,
    val grantVersion: Int,
    val expiresAt: String,
    val soundId: String? = null,
    val ringSeconds: Int? = null,
)

sealed class LocalDecision {
    /** mode: ring (native alarm), notify (ordinary notification), silent (no sound). */
    data class Execute(val mode: String) : LocalDecision()
    data class Skip(val reason: String) : LocalDecision()
}

fun isGrantActive(grant: GrantSnapshot?, now: Instant): Boolean {
    if (grant == null || grant.revokedAt != null) return false
    return !now.isBefore(Instant.parse(grant.validFrom)) && now.isBefore(Instant.parse(grant.validUntil))
}

fun grantAllows(grant: GrantSnapshot?, capability: String, now: Instant): Boolean =
    isGrantActive(grant, now) && !grant!!.paused && capability in grant.capabilities

/**
 * Re-validates a remote command on the phone that would ring, against the
 * locally cached grant. A revoked or narrowed grant wins even if the server
 * dispatched first. Works offline. Mirrors packages/policy evaluateLocalExecution.
 */
fun evaluateLocalExecution(
    now: Instant,
    command: IncomingCommand,
    localGrant: GrantSnapshot?,
    zone: ZoneId,
    settings: RecipientSettings,
    alreadyProcessed: Boolean,
): LocalDecision {
    if (alreadyProcessed) return LocalDecision.Skip("duplicate")
    if (now.isAfter(Instant.parse(command.expiresAt).plusSeconds(CLOCK_SKEW_SECONDS))) return LocalDecision.Skip("expired")
    if (localGrant == null || localGrant.granteeId != command.actorId) return LocalDecision.Skip("not_authorized_locally")
    if (localGrant.version < command.grantVersion) return LocalDecision.Skip("needs_sync")
    if (!grantAllows(localGrant, command.capability, now)) return LocalDecision.Skip("not_authorized_locally")

    val isSafety = command.capability in Capability.SAFETY
    val paused = settings.pausedUntil?.let { Instant.parse(it).isAfter(now) } ?: false
    if (paused && !isSafety) return LocalDecision.Skip("recipient_paused")

    var windows: List<LocalWindow>? = null
    var quietOverride = false
    when (command.capability) {
        Capability.NUDGE -> {
            val l = localGrant.limits.nudge ?: return LocalDecision.Skip("not_authorized_locally")
            if (command.soundId == null || command.soundId !in l.sounds) return LocalDecision.Skip("not_authorized_locally")
            windows = l.windows
            quietOverride = l.overridesQuietHours
        }
        Capability.ALARM_REQUEST -> {
            val l = localGrant.limits.alarm ?: return LocalDecision.Skip("not_authorized_locally")
            if (command.soundId == null || command.soundId !in l.sounds) return LocalDecision.Skip("not_authorized_locally")
            val ring = command.ringSeconds ?: return LocalDecision.Skip("not_authorized_locally")
            if (ring > l.maxAlarmSeconds) return LocalDecision.Skip("not_authorized_locally")
            windows = l.windows
            quietOverride = true
        }
        Capability.FOCUS -> windows = localGrant.limits.focus?.windows
    }
    if (windows != null) {
        val skewed = now.minusSeconds(CLOCK_SKEW_SECONDS)
        if (!isWithinAnyWindow(now, zone, windows) && !isWithinAnyWindow(skewed, zone, windows)) return LocalDecision.Skip("outside_allowed_window")
    }
    if (isQuietTime(now, zone, settings.quietHours)) {
        if (isSafety) return LocalDecision.Execute("silent")
        if (!quietOverride) return LocalDecision.Skip("quiet_hours")
    }
    return if (command.capability == Capability.ALARM_REQUEST) LocalDecision.Execute("ring") else LocalDecision.Execute("notify")
}
