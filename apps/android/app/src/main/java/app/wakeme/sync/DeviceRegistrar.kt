package app.wakeme.sync

import android.content.Context
import android.os.Build
import app.wakeme.BuildConfig
import app.wakeme.config.AppConfig
import app.wakeme.data.device.DeviceIdentity
import app.wakeme.data.net.ApiClient
import app.wakeme.data.net.DeviceDto
import app.wakeme.readiness.Readiness
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.messaging.FirebaseMessaging
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.tasks.await
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Registers this installation (install id, Keystore public key, FCM token,
 * capability snapshot). Firebase is initialised from build configuration so
 * no google-services.json is committed; without it, push is reported as not
 * registered rather than faked.
 */
@Singleton
class DeviceRegistrar @Inject constructor(
    @ApplicationContext private val context: Context,
    private val api: ApiClient,
    private val device: DeviceIdentity,
    private val readiness: Readiness,
) {
    fun initFirebase() {
        if (!AppConfig.firebaseConfigured || FirebaseApp.getApps(context).isNotEmpty()) return
        FirebaseApp.initializeApp(
            context,
            FirebaseOptions.Builder()
                .setProjectId(BuildConfig.FIREBASE_PROJECT_ID)
                .setApplicationId(BuildConfig.FIREBASE_APP_ID)
                .setApiKey(BuildConfig.FIREBASE_API_KEY)
                .setGcmSenderId(BuildConfig.FIREBASE_SENDER_ID)
                .build(),
        )
        FirebaseMessaging.getInstance().isAutoInitEnabled = true
    }

    private suspend fun fcmToken(): String? {
        if (!AppConfig.firebaseConfigured) return null
        initFirebase()
        return runCatching { FirebaseMessaging.getInstance().token.await() }.getOrNull()
    }

    suspend fun register(makePrimary: Boolean = false): DeviceDto {
        val token = fcmToken()
        val body = buildJsonObject {
            put("installId", device.installId())
            put("platform", "android")
            put("osVersion", Build.VERSION.RELEASE.take(32))
            put("appBuild", BuildConfig.VERSION_CODE.toString())
            put("publicKey", device.publicKeyBase64())
            token?.let { putJsonObject("push") { put("provider", "fcm"); put("token", it) } }
            put("capabilities", readiness.capabilitiesJson())
            if (makePrimary) put("makePrimary", true)
        }
        val dto = api.post("/v1/devices", body, DeviceDto.serializer(), idempotent = true)
        device.setDeviceId(dto.id)
        return dto
    }

    suspend fun updatePushToken(token: String) {
        val id = device.deviceId() ?: return
        api.patch("/v1/devices/$id", buildJsonObject { putJsonObject("push") { put("provider", "fcm"); put("token", token) } }, JsonObject.serializer(), signed = true)
    }

    suspend fun makePrimary() {
        val id = device.deviceId() ?: register().id
        api.post("/v1/devices/$id/primary", null, DeviceDto.serializer())
    }
}
