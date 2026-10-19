package app.wakeme.data.auth

import app.wakeme.config.AppConfig
import app.wakeme.data.net.await
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import javax.inject.Inject
import javax.inject.Singleton

@Serializable
private data class TokenResponse(val access_token: String, val refresh_token: String, val expires_in: Long, val user: TokenUser? = null)

@Serializable
private data class TokenUser(val id: String)

@Serializable
private data class DevToken(val subject: String, val accessToken: String, val expiresIn: Long)

sealed class AuthState {
    data object SignedOut : AuthState()
    data class SignedIn(val subject: String) : AuthState()
}

class AuthException(message: String) : Exception(message)

/**
 * Supabase Auth (GoTrue) session handling over its REST API: email one-time
 * codes and Google ID tokens. Access tokens live in memory; the refresh token
 * is stored encrypted by [SecureStore]. Wake Me's API verifies every token
 * server-side; nothing here is trusted as identity by the server.
 */
@Singleton
class SessionManager @Inject constructor(private val http: OkHttpClient, private val store: SecureStore) {
    private val json = Json { ignoreUnknownKeys = true }
    private val mutex = Mutex()
    private var access: String? = null
    private var accessExpiresAt = 0L
    private val _state = MutableStateFlow<AuthState>(AuthState.SignedOut)
    val state: StateFlow<AuthState> = _state

    suspend fun restore() {
        val subject = store.get("subject") ?: return
        if (store.get("refresh") != null || AppConfig.useDevAuth) _state.value = AuthState.SignedIn(subject)
    }

    suspend fun accessToken(): String? = mutex.withLock {
        if (access != null && System.currentTimeMillis() < accessExpiresAt - 60_000) return access
        if (refreshLocked()) access else null
    }

    suspend fun refresh(): Boolean = mutex.withLock { refreshLocked() }

    private suspend fun refreshLocked(): Boolean {
        if (AppConfig.useDevAuth) {
            val subject = store.get("subject") ?: return false
            return devSignIn(subject)
        }
        val refresh = store.get("refresh") ?: return false
        val body = buildJsonObject { put("refresh_token", refresh) }.toString()
        return try {
            val t = gotrue("/auth/v1/token?grant_type=refresh_token", body)
            save(t)
            true
        } catch (e: AuthException) {
            signOutLocal()
            false
        }
    }

    /** Sends a one-time code to the email address. No phone number is ever required. */
    suspend fun requestEmailCode(email: String) {
        gotrueRaw("/auth/v1/otp", buildJsonObject { put("email", email.trim()); put("create_user", true) }.toString())
    }

    suspend fun verifyEmailCode(email: String, code: String) {
        val t = gotrue("/auth/v1/verify", buildJsonObject { put("type", "email"); put("email", email.trim()); put("token", code.trim()) }.toString())
        mutex.withLock { save(t) }
    }

    /** Google sign-in via Credential Manager ID token (raw nonce hashed into the token request). */
    suspend fun signInWithGoogleIdToken(idToken: String, rawNonce: String) {
        val t = gotrue("/auth/v1/token?grant_type=id_token", buildJsonObject { put("provider", "google"); put("id_token", idToken); put("nonce", rawNonce) }.toString())
        mutex.withLock { save(t) }
    }

    /** Debug builds without Supabase use the local dev backend's token issuer. */
    suspend fun devSignIn(existingSubject: String? = null): Boolean {
        val body = buildJsonObject { existingSubject?.let { put("subject", it) } }.toString()
        val req = Request.Builder().url(AppConfig.apiBaseUrl + "/dev/token").post(body.toRequestBody("application/json".toMediaType())).build()
        val res = http.newCall(req).await()
        res.use {
            if (!it.isSuccessful) return false
            val t = json.decodeFromString(DevToken.serializer(), it.body.string())
            access = t.accessToken
            accessExpiresAt = System.currentTimeMillis() + t.expiresIn * 1000
            store.put("subject", t.subject)
            _state.value = AuthState.SignedIn(t.subject)
            return true
        }
    }

    /** Signs out this phone only (`scope=local`); the person's other phones stay signed in. */
    suspend fun signOut() {
        val token = access
        if (token != null && AppConfig.supabaseConfigured) {
            runCatching {
                http.newCall(
                    Request.Builder().url(AppConfig.supabaseUrl + "/auth/v1/logout?scope=local").header("apikey", AppConfig.supabaseAnonKey)
                        .header("authorization", "Bearer $token").post(ByteArray(0).toRequestBody(null)).build(),
                ).await().close()
            }
        }
        mutex.withLock { signOutLocal() }
    }

    private suspend fun signOutLocal() {
        access = null
        accessExpiresAt = 0
        store.clear()
        _state.value = AuthState.SignedOut
    }

    private suspend fun save(t: TokenResponse) {
        access = t.access_token
        accessExpiresAt = System.currentTimeMillis() + t.expires_in * 1000
        store.put("refresh", t.refresh_token)
        t.user?.id?.let { store.put("subject", it) }
        val subject = t.user?.id ?: store.get("subject") ?: ""
        _state.value = AuthState.SignedIn(subject)
    }

    private suspend fun gotrue(path: String, body: String): TokenResponse = json.decodeFromString(TokenResponse.serializer(), gotrueRaw(path, body))

    private suspend fun gotrueRaw(path: String, body: String): String {
        if (!AppConfig.supabaseConfigured) throw AuthException("Sign-in isn't configured for this build.")
        val req = Request.Builder().url(AppConfig.supabaseUrl + path)
            .header("apikey", AppConfig.supabaseAnonKey)
            .post(body.toRequestBody("application/json".toMediaType()))
            .build()
        val res = try {
            http.newCall(req).await()
        } catch (e: java.io.IOException) {
            throw AuthException("You're offline. Try again when you're connected.")
        }
        res.use {
            val text = it.body.string()
            if (!it.isSuccessful) throw AuthException(if (it.code == 400 || it.code == 403) "That code didn't work. Check it and try again." else "Sign-in failed. Try again.")
            return text
        }
    }
}
