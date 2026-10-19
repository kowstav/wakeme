package app.wakeme.data.local

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import app.wakeme.core.QuietHours
import app.wakeme.core.RecipientSettings
import app.wakeme.core.WakeMeJson
import app.wakeme.data.net.MeDto
import app.wakeme.data.net.WakeMeApiJson
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton

private val Context.appPrefs by preferencesDataStore(name = "app")

/** Non-secret app state: cached profile/settings for offline re-validation, sync cursor, pending invite. */
@Singleton
class Prefs @Inject constructor(@ApplicationContext private val context: Context) {
    private val meKey = stringPreferencesKey("me")
    private val cursorKey = stringPreferencesKey("sync_cursor")
    private val inviteKey = stringPreferencesKey("pending_invite")
    private val trustedKeysKey = stringPreferencesKey("challenge_keys")
    private val trustedKeysAtKey = longPreferencesKey("challenge_keys_at")
    private val onboardingKey = stringPreferencesKey("onboarding_step")
    private val installErrorsKey = stringPreferencesKey("install_errors")

    val me: Flow<MeDto?> = context.appPrefs.data.map { p -> p[meKey]?.let { runCatching { WakeMeApiJson.decodeFromString(MeDto.serializer(), it) }.getOrNull() } }

    suspend fun saveMe(me: MeDto) = context.appPrefs.edit { it[meKey] = WakeMeApiJson.encodeToString(MeDto.serializer(), me) }
    suspend fun currentMe(): MeDto? = me.first()

    /** Recipient settings as last confirmed by the server; used when the phone is offline. */
    suspend fun recipientSettings(): Pair<RecipientSettings, ZoneId> {
        val m = currentMe()
        val settings = m?.settings?.let { RecipientSettings(QuietHours(it.quietHours.enabled, it.quietHours.start, it.quietHours.end), it.pausedUntil, it.soundBudgetPerHour) }
            ?: RecipientSettings(QuietHours(true, "22:00", "07:00"))
        val zone = runCatching { ZoneId.of(m?.timeZone ?: ZoneId.systemDefault().id) }.getOrDefault(ZoneId.systemDefault())
        return settings to zone
    }

    suspend fun cursor(): String? = context.appPrefs.data.first()[cursorKey]
    suspend fun setCursor(c: String?) = context.appPrefs.edit { if (c == null) it.remove(cursorKey) else it[cursorKey] = c }

    suspend fun pendingInvite(): String? = context.appPrefs.data.first()[inviteKey]
    suspend fun setPendingInvite(token: String?) = context.appPrefs.edit { if (token == null) it.remove(inviteKey) else it[inviteKey] = token }

    suspend fun trustedChallengeKeys(): Map<String, String> =
        context.appPrefs.data.first()[trustedKeysKey]?.let { WakeMeJson.decodeFromString<Map<String, String>>(it) } ?: emptyMap()
    /** When the key list was last fetched (epoch ms), 0 if never. */
    suspend fun trustedChallengeKeysAt(): Long = context.appPrefs.data.first()[trustedKeysAtKey] ?: 0L
    suspend fun setTrustedChallengeKeys(keys: Map<String, String>, at: Long = System.currentTimeMillis()) = context.appPrefs.edit {
        it[trustedKeysKey] = WakeMeJson.encodeToString(keys)
        it[trustedKeysAtKey] = at
    }

    /** Error code of each routine's last installation receipt (e.g. challenge_unavailable), repeated when its window is topped up. */
    suspend fun installError(planId: String): String? = installErrors()[planId]
    suspend fun setInstallError(planId: String, code: String?) = context.appPrefs.edit {
        val errors = it[installErrorsKey]?.let { raw -> runCatching { WakeMeJson.decodeFromString<Map<String, String>>(raw) }.getOrNull() }.orEmpty().toMutableMap()
        if (code == null) errors.remove(planId) else errors[planId] = code
        it[installErrorsKey] = WakeMeJson.encodeToString(errors.toMap())
    }
    private suspend fun installErrors(): Map<String, String> =
        context.appPrefs.data.first()[installErrorsKey]?.let { runCatching { WakeMeJson.decodeFromString<Map<String, String>>(it) }.getOrNull() } ?: emptyMap()

    val onboardingStep: Flow<String?> = context.appPrefs.data.map { it[onboardingKey] }
    suspend fun setOnboardingStep(step: String?) = context.appPrefs.edit { if (step == null) it.remove(onboardingKey) else it[onboardingKey] = step }

    suspend fun clear() = context.appPrefs.edit { it.clear() }
}
