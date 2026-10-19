package app.wakeme.readiness

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.core.content.ContextCompat
import app.wakeme.alarm.AlarmAudio
import app.wakeme.data.device.DeviceIdentity
import app.wakeme.data.net.ApiClient
import app.wakeme.data.net.DeviceDto
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import javax.inject.Inject
import javax.inject.Singleton

enum class ReadinessItem { NOTIFICATIONS, EXACT_ALARMS, FULL_SCREEN, BATTERY, ALARM_VOLUME }

data class ReadinessState(val missing: List<ReadinessItem>) {
    val readyForWake: Boolean get() = ReadinessItem.EXACT_ALARMS !in missing && ReadinessItem.NOTIFICATIONS !in missing
}

/**
 * "Make this phone ready": checks each platform permission the chosen
 * features need and reports a capability snapshot to the server. These are
 * hints; only a real test with the chosen sound shows what is audible.
 */
@Singleton
class Readiness @Inject constructor(
    @ApplicationContext private val context: Context,
    private val api: ApiClient,
    private val device: DeviceIdentity,
) {
    private val alarmManager = context.getSystemService(AlarmManager::class.java)
    private val notifications = context.getSystemService(NotificationManager::class.java)

    fun state(): ReadinessState {
        val missing = buildList {
            val notifGranted = Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
            if (!notifGranted || !notifications.areNotificationsEnabled()) add(ReadinessItem.NOTIFICATIONS)
            if (!alarmManager.canScheduleExactAlarms()) add(ReadinessItem.EXACT_ALARMS)
            if (Build.VERSION.SDK_INT >= 34 && !notifications.canUseFullScreenIntent()) add(ReadinessItem.FULL_SCREEN)
            if (!context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName)) add(ReadinessItem.BATTERY)
            if (AlarmAudio(context).alarmVolumeIsZero()) add(ReadinessItem.ALARM_VOLUME)
        }
        return ReadinessState(missing)
    }

    fun settingsIntent(item: ReadinessItem): Intent = when (item) {
        ReadinessItem.NOTIFICATIONS -> Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
        ReadinessItem.EXACT_ALARMS -> Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:${context.packageName}"))
        ReadinessItem.FULL_SCREEN -> Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, Uri.parse("package:${context.packageName}"))
        ReadinessItem.BATTERY -> Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
        ReadinessItem.ALARM_VOLUME -> Intent(Settings.ACTION_SOUND_SETTINGS)
    }.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    fun capabilitiesJson() = buildJsonObject {
        val s = state()
        put("notificationsAuthorized", ReadinessItem.NOTIFICATIONS !in s.missing)
        put("exactAlarmPermitted", ReadinessItem.EXACT_ALARMS !in s.missing)
        put("fullScreenIntentPermitted", ReadinessItem.FULL_SCREEN !in s.missing)
        put("batteryOptimizationExempt", ReadinessItem.BATTERY !in s.missing)
        put("speakerPreferenceSupported", true)
    }

    suspend fun reportCapabilities() {
        val id = device.deviceId() ?: return
        runCatching { api.patch("/v1/devices/$id", buildJsonObject { put("capabilities", capabilitiesJson()) }, DeviceDto.serializer(), signed = true) }
    }
}
