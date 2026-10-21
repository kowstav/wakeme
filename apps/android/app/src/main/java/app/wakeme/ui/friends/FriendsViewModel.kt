package app.wakeme.ui.friends

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.wakeme.R
import app.wakeme.data.WakeMeApi
import app.wakeme.data.net.ApiClient
import app.wakeme.data.net.ApiException
import app.wakeme.data.net.CommandDto
import app.wakeme.data.net.FriendDto
import app.wakeme.data.net.InviteCreatedDto
import app.wakeme.data.net.OfflineException
import app.wakeme.data.local.LocalDb
import app.wakeme.data.local.ScheduledAlarmEntity
import app.wakeme.readiness.Readiness
import app.wakeme.readiness.ReadinessItem
import app.wakeme.sync.SyncEngine
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Literal sender-facing copy for each server displayStatus. */
fun statusText(context: Context, displayStatus: String): String = context.getString(
    when (displayStatus) {
        "sending" -> R.string.status_sending
        "sent" -> R.string.status_sent
        "no_confirmation_yet" -> R.string.status_no_confirmation_yet
        "not_delivered" -> R.string.status_not_delivered
        "phone_received" -> R.string.status_phone_received
        "alarm_scheduled_on_phone" -> R.string.status_alarm_scheduled_on_phone
        "alarm_started" -> R.string.status_alarm_started
        "not_played" -> R.string.status_not_played
        "opened" -> R.string.status_opened
        "acknowledged" -> R.string.status_acknowledged
        "challenge_completed" -> R.string.status_challenge_completed
        "stopped" -> R.string.status_stopped
        "ended_without_challenge" -> R.string.status_ended_without_challenge
        "expired" -> R.string.status_expired
        else -> R.string.status_cancelled
    },
)

/** Why an action isn't available, in words that don't blame or reveal private settings. */
fun reasonText(name: String, reason: String?): String = when (reason) {
    "capability_not_granted" -> "$name hasn't turned this on for you."
    "grant_paused" -> "$name has paused requests from you."
    "outside_allowed_window" -> "It's outside the hours $name chose."
    "quiet_hours" -> "It's $name's quiet time."
    "recipient_paused" -> "$name isn't taking requests right now."
    "recipient_not_ready" -> "$name's phone isn't set up for this yet."
    "feature_disabled" -> "This is temporarily unavailable."
    else -> "Not available right now."
}

data class FriendsState(
    val loading: Boolean = true,
    val friends: List<FriendDto> = emptyList(),
    val invite: InviteCreatedDto? = null,
    val offline: Boolean = false,
    val message: String? = null,
    /** The next main alarm armed on this phone (doc 03 §3: home shows it). */
    val nextAlarm: ScheduledAlarmEntity? = null,
    /** At most one thing stopping it from ringing well; notifications have their own card. */
    val readinessIssue: ReadinessItem? = null,
)

@HiltViewModel
class FriendsViewModel @Inject constructor(private val api: WakeMeApi, sync: SyncEngine, db: LocalDb, private val readiness: Readiness, @ApplicationContext private val context: Context) : ViewModel() {
    private val _state = MutableStateFlow(FriendsState())
    val state: StateFlow<FriendsState> = _state

    init {
        load()
        viewModelScope.launch { sync.changes.collect { if (it == "grant" || it == "friendship" || it == "all") load() } }
        viewModelScope.launch {
            db.alarms().observe().collect { all ->
                val now = System.currentTimeMillis()
                val boot = app.wakeme.alarm.BootAlarms(context)
                _state.value = _state.value.copy(nextAlarm = all.firstOrNull { it.fireAt > now && it.role == "MAIN" && !boot.isEnded(it.planId) })
                refreshReadiness()
            }
        }
    }

    /** Re-checked when the screen resumes (a setting may have changed outside the app). */
    fun refreshReadiness() {
        val missing = readiness.state().missing
        val issue = if (_state.value.nextAlarm == null) null else HOME_ISSUES.firstOrNull { it in missing }
        _state.value = _state.value.copy(readinessIssue = issue)
    }

    fun settingsIntent(item: ReadinessItem) = readiness.settingsIntent(item)

    fun load() = viewModelScope.launch {
        try {
            val friends = api.friends()
            _state.value = _state.value.copy(loading = false, friends = friends, offline = false)
            QuackShortcuts.publish(context, friends)
        } catch (e: OfflineException) {
            _state.value = _state.value.copy(loading = false, offline = true)
        } catch (e: ApiException) {
            _state.value = _state.value.copy(loading = false, message = e.message)
        }
    }

    fun createInvite() = viewModelScope.launch {
        runCatching { api.createInvite() }.onSuccess { _state.value = _state.value.copy(invite = it) }.onFailure { _state.value = _state.value.copy(message = it.message) }
    }

    fun redeemCode(code: String) = viewModelScope.launch {
        runCatching { api.redeemInvite(null, code) }.onSuccess { load() }.onFailure { _state.value = _state.value.copy(message = it.message) }
    }

    fun dismissMessage() {
        _state.value = _state.value.copy(message = null, invite = null)
    }
}

data class DetailState(
    val friend: FriendDto? = null,
    val recent: List<CommandDto> = emptyList(),
    val sending: String? = null,
    val lastSent: CommandDto? = null,
    val message: String? = null,
    /** Bumped on each send result: haptics and the quack animation follow them (doc 03 §2). */
    val sentTick: Int = 0,
    val failTick: Int = 0,
)

@HiltViewModel
class FriendDetailViewModel @Inject constructor(private val api: WakeMeApi) : ViewModel() {
    private val _state = MutableStateFlow(DetailState())
    val state: StateFlow<DetailState> = _state
    private var id: String? = null

    fun load(friendshipId: String) = viewModelScope.launch {
        id = friendshipId
        runCatching {
            val f = api.friend(friendshipId)
            _state.value = _state.value.copy(friend = f, recent = api.commands("sent", f.user.id).take(5))
        }.onFailure { _state.value = _state.value.copy(message = it.message) }
    }

    /**
     * One idempotency key per tap: if the network flakes and we retry, the
     * server returns the same command instead of sending a second quack.
     */
    fun send(capability: String, extra: Map<String, Any>) = viewModelScope.launch {
        val f = _state.value.friend ?: return@launch
        val key = ApiClient.idempotencyKey()
        _state.value = _state.value.copy(sending = capability)
        try {
            val cmd = api.sendCommand(f.user.id, capability, f.theyAllowMe?.version, extra, key)
            _state.value = _state.value.copy(sending = null, lastSent = cmd, sentTick = _state.value.sentTick + 1)
            poll(cmd.id)
        } catch (e: ApiException) {
            _state.value = _state.value.copy(sending = null, message = e.message, failTick = _state.value.failTick + 1)
            if (e.code == "grant_changed") id?.let { load(it) }
        } catch (e: OfflineException) {
            _state.value = _state.value.copy(sending = null, message = "You're offline, so nothing was sent.", failTick = _state.value.failTick + 1)
        }
    }

    /** Refresh evidence for a short while; the server is the record, not this screen. */
    private suspend fun poll(commandId: String) {
        repeat(12) {
            delay(5_000)
            val c = runCatching { api.command(commandId) }.getOrNull() ?: return
            _state.value = _state.value.copy(lastSent = c)
            if (c.displayStatus in setOf("acknowledged", "challenge_completed", "not_delivered", "expired", "cancelled", "stopped")) return
        }
    }

    fun pause(paused: Boolean) = viewModelScope.launch {
        _state.value.friend?.iAllowThem?.let { runCatching { api.pauseGrant(it.id, paused) }; id?.let { load(it) } }
    }

    fun remove(onDone: () -> Unit) = viewModelScope.launch { id?.let { runCatching { api.removeFriend(it) }.onSuccess { onDone() } } }

    fun block(reason: String?, onDone: () -> Unit) = viewModelScope.launch { id?.let { runCatching { api.block(it, reason) }.onSuccess { onDone() } } }

    fun dismissMessage() {
        _state.value = _state.value.copy(message = null)
    }
}

/** What could stop the next alarm, most serious first. Notifications have their own card. */
private val HOME_ISSUES = listOf(ReadinessItem.EXACT_ALARMS, ReadinessItem.ALARM_VOLUME, ReadinessItem.FULL_SCREEN)
