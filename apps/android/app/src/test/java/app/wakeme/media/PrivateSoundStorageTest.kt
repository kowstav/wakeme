package app.wakeme.media

import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class PrivateSoundStorageTest {
    private suspend fun rejects(block: suspend () -> Unit) {
        val result = runCatching { block() }
        assertTrue("unsafe or unverified media must be rejected", result.isFailure)
    }

    @Test fun verifiesTheHashAndDoesNotSendAccountHeaders() = runTest {
        MockWebServer().use { server ->
            val bytes = "private voice".toByteArray()
            server.enqueue(MockResponse().setBody(Buffer().write(bytes)))
            val storage = PrivateSoundStorage(OkHttpClient())
            val downloaded = storage.download(SoundDownload(server.url("/sound").toString(), "future", soundDigest(bytes), "audio/mp4"))
            assertArrayEquals(bytes, downloaded)
            val request = server.takeRequest()
            assertNull(request.getHeader("Authorization")); assertNull(request.getHeader("Cookie"))
        }
    }

    @Test fun rejectsTamperingOversizeWrongMimeAndRedirects() = runTest {
        MockWebServer().use { server ->
            val storage = PrivateSoundStorage(OkHttpClient())
            val bytes = "voice".toByteArray()
            fun target(hash: String = soundDigest(bytes), mime: String = "audio/mp4") = SoundDownload(server.url("/sound").toString(), "future", hash, mime)
            server.enqueue(MockResponse().setBody("tampered")); rejects { storage.download(target()) }
            server.enqueue(MockResponse().setBody(Buffer().write(ByteArray(MAX_SOUND_BYTES + 1)))); rejects { storage.download(target()) }
            server.enqueue(MockResponse().setBody("voice")); rejects { storage.download(target(mime = "text/html")) }
            server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", server.url("/redirect"))); rejects { storage.download(target()) }
            assertEquals(4, server.requestCount)
        }
    }

    @Test fun refusesInsecureRemoteUrlsAndUploadCredentialHeaders() = runTest {
        val storage = PrivateSoundStorage(OkHttpClient())
        rejects { storage.download(SoundDownload("http://example.com/private", "future", "0".repeat(64), "audio/mp4")) }
        val file = File.createTempFile("voice-test", ".m4a")
        try {
            file.writeText("voice")
            rejects { storage.upload(UploadTarget("https://example.com/private", "PUT", mapOf("Authorization" to "never-send-this"), "future"), file) }
        } finally { file.delete() }
    }
}
