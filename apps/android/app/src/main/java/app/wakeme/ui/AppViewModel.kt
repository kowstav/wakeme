package app.wakeme.ui

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.wakeme.billing.Billing
import app.wakeme.config.AppConfig
import app.wakeme.data.WakeMeApi
import app.wakeme.data.auth.AuthException
import app.wakeme.data.auth.AuthState
import app.wakeme.data.auth.SessionManager
import app.wakeme.data.device.DeviceIdentity
import app.wakeme.data.local.Prefs
import app.wakeme.data.net.ApiException
import app.wakeme.data.net.InvitePreviewDto
import app.wakeme.data.net.MeDto
import app.wakeme.sync.DeviceRegistrar
import app.wakeme.sync.SyncEngine
import app.wakeme.sync.SyncWorker
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.time.ZoneId
import java.util.Locale
import javax.inject.Inject

sealed class Gate {
    data object Loading : Gate()
    data object Welcome : Gate()
    data object SignIn : Gate()
    data object Profile : Gate()
    data class Invite(val token: String, val preview: InvitePreviewDto?) : Gate()
    /** Self-setup route: make this phone ready for alarms before the first wake-up (doc 03 §5 steps 7–9). */
    data object Ready : Gate()
    data object Main : Gate()
}

/** App-level routing: signed in? profile? pending invite? Invites never auto-accept. */
@HiltViewModel
class AppViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val session: SessionManager,
    private val api: WakeMeApi,
    private val prefs: Prefs,
    private val registrar: DeviceRegistrar,
    private val sync: SyncEngine,
    private val device: DeviceIdentity,
    private val billing: Billing,
) : ViewModel() {
    private val _gate = MutableStateFlow<Gate>(Gate.Loading)
    val gate: StateFlow<Gate> = _gate
    private val _me = MutableStateFlow<MeDto?>(null)
    val me: StateFlow<MeDto?> = _me
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error
    private val _route = MutableStateFlow<String?>(null)
    val route: StateFlow<String?> = _route

    init {
        viewModelScope.launch {
            session.restore()
            route()
        }
    }

    fun onInviteLink(token: String) {
        viewModelScope.launch {
            prefs.setPendingInvite(token)
            route()
        }
    }

    fun openRoute(r: String) {
        _route.value = r
    }

    fun consumeRoute() {
        _route.value = null
    }

    fun chooseSelfSetup() {
        viewModelScope.launch { prefs.setOnboardingStep(STEP_SELF_SETUP) }
        _gate.value = Gate.SignIn
    }

    /** Leaves self-setup; opens the routine editor when the phone is ready. */
    fun finishSelfSetup(openRoutineEditor: Boolean) = viewModelScope.launch {
        prefs.setOnboardingStep(null)
        _gate.value = Gate.Main
        if (openRoutineEditor) _route.value = "routine-new"
    }

    fun chooseInviteFlow() {
        _gate.value = Gate.SignIn
    }

    private suspend fun route() {
        if (session.state.value !is AuthState.SignedIn) {
            _gate.value = if (_gate.value == Gate.SignIn) Gate.SignIn else Gate.Welcome
            return
        }
        val me = try {
            api.me()
        } catch (e: ApiException) {
            if (e.code == "profile_required") {
                _gate.value = Gate.Profile
                return
            }
            prefs.currentMe() ?: run { _error.value = e.message; _gate.value = Gate.Welcome; return }
        } catch (e: Exception) {
            // Offline: keep working from the cached profile; local alarms never need the network.
            prefs.currentMe() ?: run { _error.value = "You're offline. Connect to finish setting up."; _gate.value = Gate.Welcome; return }
        }
        _me.value = me
        prefs.saveMe(me)
        runCatching {
            if (device.deviceId() == null) registrar.register(makePrimary = me.primaryDeviceId == null)
        }
        runCatching { billing.configure(me.id) }
        SyncWorker.schedulePeriodic(context)
        runCatching { sync.sync() }
        val invite = prefs.pendingInvite()
        _gate.value = when {
            invite != null -> Gate.Invite(invite, runCatching { api.previewInvite(invite, null) }.getOrNull())
            prefs.onboardingStep.first() == STEP_SELF_SETUP -> Gate.Ready
            else -> Gate.Main
        }
    }

    fun requestCode(email: String, onSent: () -> Unit) = launchCatching { session.requestEmailCode(email); onSent() }

    fun verifyCode(email: String, code: String) = launchCatching {
        session.verifyEmailCode(email, code)
        route()
    }

    fun googleSignIn(idToken: String, rawNonce: String) = launchCatching {
        session.signInWithGoogleIdToken(idToken, rawNonce)
        route()
    }

    fun devSignIn() = launchCatching {
        if (session.devSignIn()) route()
    }

    fun createProfile(name: String, adult: Boolean) = launchCatching {
        if (!adult) {
            _error.value = "Wake Me is for people 18 and over."
            return@launchCatching
        }
        api.createAccount(name.trim(), ZoneId.systemDefault().id, Locale.getDefault().toLanguageTag())
        route()
    }

    fun acceptInvite(token: String) = launchCatching {
        api.redeemInvite(token, null)
        prefs.setPendingInvite(null)
        _gate.value = Gate.Main
        _route.value = "friends"
    }

    fun redeemCode(code: String, onDone: () -> Unit) = launchCatching {
        api.redeemInvite(null, code)
        onDone()
    }

    fun declineInvite() = launchCatching {
        prefs.setPendingInvite(null)
        _gate.value = Gate.Main
    }

    fun refreshMe() = launchCatching {
        val m = api.me()
        _me.value = m
        prefs.saveMe(m)
    }

    fun signOut() = launchCatching {
        sync.clearLocal()
        device.reset()
        session.signOut()
        prefs.clear()
        _me.value = null
        _gate.value = Gate.Welcome
    }

    fun showError(message: String) {
        _error.value = message
    }

    fun clearError() {
        _error.value = null
    }

    val devAuth: Boolean get() = AppConfig.useDevAuth

    private companion object {
        const val STEP_SELF_SETUP = "self_setup"
    }

    private fun launchCatching(block: suspend () -> Unit) = viewModelScope.launch {
        try {
            block()
        } catch (e: ApiException) {
            _error.value = e.message
        } catch (e: AuthException) {
            _error.value = e.message
        } catch (e: Exception) {
            _error.value = "Something went wrong. Check your connection and try again."
        }
    }
}
