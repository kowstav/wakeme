package app.wakeme.media

import android.content.Context
import app.wakeme.BuildConfig
import app.wakeme.data.net.ApiClient
import app.wakeme.data.net.OkDto
import app.wakeme.data.net.await
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.io.File
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

@Serializable data class VoiceSound(val id: String, val soundId: String, val ownerId: String, val kind: String, val mime: String, val bytes: Int, val durationMs: Int? = null, val state: String, val rejectionReason: String? = null, val createdAt: String)
@Serializable data class VoiceSounds(val media: List<VoiceSound>)
@Serializable data class OfferedSound(val media: VoiceSound, val offeredBy: SoundSender, val state: String, val decidedAt: String? = null)
@Serializable data class SoundSender(val id: String, val displayName: String)
@Serializable data class OfferedSounds(val sounds: List<OfferedSound>)
@Serializable data class SoundUpload(val media: VoiceSound, val upload: UploadTarget)
@Serializable data class UploadTarget(val url: String, val method: String, val headers: Map<String, String>, val expiresAt: String)
@Serializable data class SoundDownload(val url: String, val expiresAt: String, val sha256: String, val mime: String)

const val MAX_SOUND_BYTES = 1_048_576
const val MAX_SOUND_MS = 30_000
private val MEDIA_ID = Regex("^[a-fA-F0-9-]{36}$")

fun soundDigest(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

internal class PrivateSoundStorage(http: OkHttpClient) {
    private val client = http.newBuilder().followRedirects(false).followSslRedirects(false).build()
    private fun url(raw: String): String {
        val url = raw.toHttpUrl()
        require(url.isHttps || (BuildConfig.DEBUG && url.host in setOf("127.0.0.1", "localhost", "10.0.2.2"))) { "A secure sound download is unavailable." }
        return raw
    }
    suspend fun upload(target: UploadTarget, recording: File) {
        require(target.method == "PUT")
        val request = Request.Builder().url(url(target.url)).put(recording.asRequestBody("audio/mp4".toMediaType())).apply {
            target.headers.forEach { (key, value) ->
                require(!key.equals("Authorization", true) && !key.equals("Cookie", true))
                header(key, value)
            }
        }.build()
        client.newCall(request).await().use { check(it.isSuccessful) { "Upload didn't finish. Your recording is still on this screen." } }
    }
    suspend fun download(signed: SoundDownload): ByteArray = withContext(Dispatchers.IO) {
        val bytes = client.newCall(Request.Builder().url(url(signed.url)).build()).await().use { response ->
            check(response.isSuccessful) { "Couldn't download this sound." }
            response.body.byteStream().use { input ->
                val output = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                var remaining = MAX_SOUND_BYTES + 1
                while (remaining > 0) {
                    val count = input.read(buffer, 0, minOf(buffer.size, remaining))
                    if (count <= 0) break
                    output.write(buffer, 0, count); remaining -= count
                }
                output.toByteArray()
            }
        }
        check(bytes.size in 1..MAX_SOUND_BYTES && soundDigest(bytes) == signed.sha256) { "This sound couldn't be verified. Built-in sounds still work." }
        check(signed.mime in setOf("audio/wav", "audio/mp4"))
        bytes
    }
}

/** Private, hash-verified cache. An offer alone never makes a sound available to an alarm. */
@Singleton
class SoundLibrary @Inject constructor(@ApplicationContext private val context: Context, private val api: ApiClient, http: OkHttpClient) {
    // Signed object URLs never receive account Authorization or device-signature headers, or follow redirects.
    private val storage = PrivateSoundStorage(http)
    private val mutex = Mutex()
    private val _ready = MutableStateFlow<List<String>>(emptyList())
    val ready: StateFlow<List<String>> = _ready
    private val directory get() = File(context.filesDir, "sounds").apply { mkdirs() }
    private fun file(id: String): File { require(MEDIA_ID.matches(id)); return File(directory, "$id.audio") }

    suspend fun own() = api.get("/v1/media", VoiceSounds.serializer()).media
    suspend fun offers() = api.get("/v1/sound-library", OfferedSounds.serializer()).sounds
    suspend fun offer(id: String, to: String) = api.post("/v1/media/$id/offer", buildJsonObject { put("toUserId", to) }, OkDto.serializer())
    suspend fun decide(id: String, approve: Boolean) {
        api.post("/v1/sound-library/$id/decision", buildJsonObject { put("approve", approve) }, OkDto.serializer())
        refresh()
    }
    suspend fun delete(id: String) {
        api.delete("/v1/media/$id", OkDto.serializer())
        withContext(Dispatchers.IO) { file(id).delete() }
        refresh()
    }

    suspend fun upload(recording: File, durationMs: Int, rightsAttested: Boolean): VoiceSound {
        require(rightsAttested) { "Confirm you recorded this or have the right to use it." }
        require(recording.length() in 1..MAX_SOUND_BYTES.toLong() && durationMs in 1..MAX_SOUND_MS) { "Use a recording up to 30 seconds and 1 MB." }
        val digest = withContext(Dispatchers.IO) { soundDigest(recording.readBytes()) }
        val target = api.post("/v1/media/uploads", buildJsonObject {
            put("kind", "voice"); put("mime", "audio/mp4"); put("bytes", recording.length()); put("sha256", digest)
            put("durationMs", durationMs); put("rightsAttestation", true)
        }, SoundUpload.serializer())
        storage.upload(target.upload, recording)
        return api.post("/v1/media/${target.media.id}/finalize", null, VoiceSound.serializer())
    }

    /** Previewing an offered sound is separate from approving it and from using it in grants. */
    suspend fun preview(id: String): File = download(id, cache = false)

    private suspend fun download(id: String, cache: Boolean): File = withContext(Dispatchers.IO) {
        require(MEDIA_ID.matches(id))
        val signed = api.get("/v1/media/$id/download", SoundDownload.serializer())
        val bytes = storage.download(signed)
        val destination = if (cache) file(id) else File(context.cacheDir, "voice-preview-$id.audio")
        val temporary = File.createTempFile("voice-", ".tmp", destination.parentFile)
        try { temporary.writeBytes(bytes); check(temporary.renameTo(destination)) } finally { temporary.delete() }
        destination
    }

    suspend fun refresh() = mutex.withLock {
        val allowed = (own().filter { it.state == "clean" } + offers().filter { it.state == "approved" }.map { it.media }).distinctBy { it.id }
        val ids = allowed.map { it.id }.toSet()
        withContext(Dispatchers.IO) { directory.listFiles()?.filter { it.extension == "audio" && it.nameWithoutExtension !in ids }?.forEach { it.delete() } }
        val cached = mutableListOf<String>()
        for (sound in allowed) {
            try {
                if (!withContext(Dispatchers.IO) { file(sound.id).exists() }) download(sound.id, cache = true)
                cached += sound.soundId
            }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { /* A failed download uses the bundled alarm fallback. */ }
        }
        _ready.value = cached
    }

    suspend fun clear() = mutex.withLock {
        withContext(Dispatchers.IO) {
            directory.listFiles()?.forEach { it.delete() }
            context.cacheDir.listFiles()?.filter { it.name.startsWith("voice-") }?.forEach { it.delete() }
        }
        _ready.value = emptyList()
    }
}
