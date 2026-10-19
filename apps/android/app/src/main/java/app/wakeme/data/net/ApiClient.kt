package app.wakeme.data.net

import app.wakeme.config.AppConfig
import app.wakeme.data.auth.SessionManager
import app.wakeme.data.device.DeviceIdentity
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.security.MessageDigest
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resumeWithException
import kotlin.random.Random

@Serializable
data class ErrorBody(val error: ErrorDetail)

@Serializable
data class ErrorDetail(val code: String, val message: String, val retryable: Boolean = false, val requestId: String = "", val retryAfterSeconds: Int? = null)

/** A structured API error. `message` is safe to show to the user. */
class ApiException(val status: Int, val code: String, override val message: String, val retryable: Boolean, val retryAfterSeconds: Int?, val requestId: String) : Exception(message)

/** Network unavailable: callers show an offline state; local alarms and escape never depend on this. */
class OfflineException(cause: Throwable) : IOException("You're offline. Personal alarms on this phone still work.", cause)

private val JSON_TYPE = "application/json".toMediaType()

suspend fun Call.await(): Response = suspendCancellableCoroutine { cont ->
    enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) = cont.resumeWithException(e)
        override fun onResponse(call: Call, response: Response) = cont.resume(response) { _, _, _ -> response.close() }
    })
    cont.invokeOnCancellation { cancel() }
}

/**
 * Typed HTTP client for the Wake Me API.
 *  - Bearer access token from [SessionManager], refreshed once on 401.
 *  - Phone-affecting writes are signed by this device's Keystore key.
 *  - Only idempotent requests are retried, with jittered backoff and Retry-After.
 *  - Platform TLS trust only (see network_security_config); no custom trust managers.
 */
@Singleton
class ApiClient @Inject constructor(
    private val http: OkHttpClient,
    private val session: SessionManager,
    private val device: DeviceIdentity,
) {
    suspend fun <T> get(path: String, out: KSerializer<T>): T = call("GET", path, null, out, signed = false, idempotent = true)

    suspend fun <T> post(path: String, body: JsonElement?, out: KSerializer<T>, signed: Boolean = false, idempotent: Boolean = false): T =
        call("POST", path, body, out, signed, idempotent)

    suspend fun <T> put(path: String, body: JsonElement, out: KSerializer<T>): T = call("PUT", path, body, out, signed = false, idempotent = true)

    suspend fun <T> patch(path: String, body: JsonElement, out: KSerializer<T>, signed: Boolean = false): T = call("PATCH", path, body, out, signed, idempotent = true)

    suspend fun <T> delete(path: String, out: KSerializer<T>): T = call("DELETE", path, null, out, signed = false, idempotent = true)

    private suspend fun <T> call(method: String, path: String, body: JsonElement?, out: KSerializer<T>, signed: Boolean, idempotent: Boolean): T {
        var attempt = 0
        var refreshed = false
        while (true) {
            attempt++
            val bodyText = body?.let { WakeMeApiJson.encodeToString(JsonElement.serializer(), it) }
            val request = buildRequest(method, path, bodyText, signed)
            val response = try {
                http.newCall(request).await()
            } catch (e: IOException) {
                if (idempotent && attempt < 3) {
                    delay(backoffMs(attempt, null))
                    continue
                }
                throw OfflineException(e)
            }
            response.use { res ->
                val text = res.body.string()
                if (res.isSuccessful) return WakeMeApiJson.decodeFromString(out, text.ifBlank { "{}" })
                val err = runCatching { WakeMeApiJson.decodeFromString(ErrorBody.serializer(), text).error }.getOrNull()
                val retryAfter = res.header("retry-after")?.toIntOrNull() ?: err?.retryAfterSeconds
                if (res.code == 401 && !refreshed && err?.code in setOf("invalid_token", "missing_token")) {
                    refreshed = true
                    if (session.refresh()) continue
                }
                if (idempotent && attempt < 3 && (res.code == 429 || res.code >= 500) && (retryAfter ?: 0) <= 30) {
                    delay(backoffMs(attempt, retryAfter))
                    continue
                }
                throw ApiException(res.code, err?.code ?: "http_${res.code}", err?.message ?: "Something went wrong. Try again.", err?.retryable ?: (res.code >= 500), retryAfter, err?.requestId ?: "")
            }
        }
    }

    private suspend fun buildRequest(method: String, path: String, bodyText: String?, signed: Boolean): Request {
        val builder = Request.Builder().url(AppConfig.apiBaseUrl + path).header("accept", "application/json")
        session.accessToken()?.let { builder.header("authorization", "Bearer $it") }
        val bytes = bodyText?.toByteArray(Charsets.UTF_8) ?: ByteArray(0)
        if (signed) {
            val ts = (System.currentTimeMillis() / 1000).toString()
            val canonical = listOf("WAKEME-V1", method, path, ts, sha256Hex(bytes)).joinToString("\n")
            builder.header("x-wakeme-device", device.deviceId() ?: error("device not registered"))
            builder.header("x-wakeme-timestamp", ts)
            builder.header("x-wakeme-signature", device.signBase64(canonical.toByteArray(Charsets.UTF_8)))
        }
        val requestBody = if (bodyText != null) bytes.toRequestBody(JSON_TYPE) else if (method == "POST") ByteArray(0).toRequestBody(null) else null
        return builder.method(method, requestBody).build()
    }

    private fun backoffMs(attempt: Int, retryAfterSeconds: Int?): Long =
        retryAfterSeconds?.let { it * 1000L } ?: ((500L shl (attempt - 1)) * (80 + Random.nextInt(40)) / 100)

    companion object {
        fun sha256Hex(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        fun idempotencyKey(): String = "k_" + UUID.randomUUID().toString().replace("-", "")
    }
}
