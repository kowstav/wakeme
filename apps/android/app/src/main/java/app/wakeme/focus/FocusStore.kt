package app.wakeme.focus

import android.content.Context
import app.wakeme.core.FocusSession
import app.wakeme.core.WakeMeJson
import app.wakeme.core.endFocus
import app.wakeme.core.focusState
import app.wakeme.core.FocusState
import app.wakeme.core.takeBreak
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable
import javax.inject.Inject
import javax.inject.Singleton

/** A friend's focus request waiting for the recipient's choice (nothing starts until they tap Start). */
@Serializable
data class FocusRequest(val commandId: String, val friendId: String, val friendName: String, val minutes: Int, val expiresAt: Long)

/**
 * Focus state on this phone (ADR 0014): the apps the person chose and the current session. Both
 * stay here; the server never learns which apps were chosen. Plain SharedPreferences so the
 * accessibility service can read it from any process state without suspending.
 */
@Singleton
class FocusStore @Inject constructor(@ApplicationContext context: Context) {
    private val prefs = context.getSharedPreferences("focus", Context.MODE_PRIVATE)
    private val _session = MutableStateFlow(load())
    val session: StateFlow<FocusSession?> = _session
    private val _request = MutableStateFlow(loadRequest())
    val request: StateFlow<FocusRequest?> = _request

    var chosenApps: Set<String>
        get() = prefs.getStringSet("apps", emptySet()).orEmpty()
        set(value) { prefs.edit().putStringSet("apps", value).apply() }

    /** The stored session, read fresh (the service and shield may run before this instance saw a change). */
    fun sessionNow(): FocusSession? = load()

    /** Refreshes the observable state from storage. */
    fun reload() {
        _session.value = load()
        _request.value = loadRequest()
    }

    fun start(s: FocusSession) = save(s)

    /** Returns false when no break is left. */
    fun takeBreak(now: Long = System.currentTimeMillis()): Boolean {
        val next = load()?.let { takeBreak(it, now) } ?: return false
        save(next)
        return true
    }

    /** "End focus": always allowed, at once. */
    fun end(now: Long = System.currentTimeMillis()) {
        load()?.let { save(endFocus(it, now)) }
    }

    /** The session, or null once it has finished (kept finished sessions aren't shown again). */
    fun current(now: Long = System.currentTimeMillis()): FocusSession? = _session.value?.takeIf { focusState(it, now) !is FocusState.Finished }

    fun offer(r: FocusRequest?) {
        _request.value = r
        prefs.edit().apply { if (r == null) remove("request") else putString("request", WakeMeJson.encodeToString(FocusRequest.serializer(), r)) }.apply()
    }

    fun clear() {
        prefs.edit().clear().apply()
        _session.value = null
        _request.value = null
    }

    private fun save(s: FocusSession) {
        prefs.edit().putString("session", WakeMeJson.encodeToString(FocusSession.serializer(), s)).apply()
        _session.value = s
    }

    private fun load(): FocusSession? = prefs.getString("session", null)?.let { runCatching { WakeMeJson.decodeFromString(FocusSession.serializer(), it) }.getOrNull() }
    private fun loadRequest(): FocusRequest? = prefs.getString("request", null)?.let { runCatching { WakeMeJson.decodeFromString(FocusRequest.serializer(), it) }.getOrNull() }
}
