package app.wakeme.alarm

import android.content.Context
import app.wakeme.core.WakeSession
import app.wakeme.core.WakeSessionState
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

/** The wake session currently on screen, persisted in device-protected storage so it survives a crash or reboot. */
@Serializable
data class ActiveWake(
    val requestCode: Int,
    val planId: String?,
    val commandId: String?,
    val occurrenceKey: String,
    val version: Int,
    val label: String,
    val helperName: String?,
    val challengeRevisionId: String?,
    val state: WakeSessionState,
    val hasFollowUp: Boolean,
    val ringing: Boolean,
    val routeType: String = "unknown",
    val playbackFailure: String? = null,
    val startedAt: Long,
    val usedFallbackSound: Boolean = false,
) {
    val identity: String get() = "${planId ?: commandId}:$occurrenceKey:$version"
    val session: WakeSession get() = WakeSession(planId ?: commandId.orEmpty(), occurrenceKey, state, hasFollowUp)
}

@Singleton
class WakeSessions @Inject constructor(@ApplicationContext context: Context) {
    private val prefs = context.createDeviceProtectedStorageContext().getSharedPreferences("wake_session", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }
    private val _active = MutableStateFlow(load())
    val active: StateFlow<ActiveWake?> = _active

    private fun load(): ActiveWake? = prefs.getString("active", null)?.let {
        // A restored session cannot prove a previous process's player is still running.
        runCatching { json.decodeFromString(ActiveWake.serializer(), it).copy(ringing = false, routeType = "unknown") }.getOrNull()
    }

    fun set(value: ActiveWake?) {
        _active.value = value
        prefs.edit().apply { if (value == null) remove("active") else putString("active", json.encodeToString(ActiveWake.serializer(), value)) }.apply()
    }

    fun update(transform: (ActiveWake) -> ActiveWake) {
        _active.value?.let { set(transform(it)) }
    }
}
