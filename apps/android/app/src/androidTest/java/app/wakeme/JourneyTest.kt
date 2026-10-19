package app.wakeme

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.wakeme.sync.SyncWorker
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.net.HttpURLConnection
import java.net.URL

/**
 * Doc 03 §5 route B and §6 against the local dev backend (services/api dev.ts on the CI host,
 * reached from the emulator at 10.0.2.2:8787): self-setup → test account → profile →
 * readiness → invite → a second account (over HTTP) accepts → Gentle nudges for that friend →
 * a wake-up of your own. Skipped when the backend isn't running. Not device evidence.
 */
@RunWith(AndroidJUnit4::class)
class JourneyTest {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    private val backend = "http://10.0.2.2:8787"

    @Test
    fun selfSetupInviteGrantAndWakeUp() {
        val unreachable = backendProblem()
        assumeTrue("dev backend not reachable: $unreachable", unreachable == null)

        tap("Set up my own wake-up")
        tap("Use a local test account (debug build)")
        waitFor("Display name")
        compose.onNodeWithText("Display name").performTextInput("Sam")
        compose.onNode(isToggleable()).performClick()
        DeviceEvidence.screenshot("20-profile")
        tap("Continue")

        waitFor("Let's make sure your alarm rings")
        DeviceEvidence.screenshot("21-ready")
        tap("Do this later")

        tap("Invite a friend", timeout = 30_000)
        waitFor("Share your invite")
        val dialog = compose.onAllNodes(hasText("type this code", substring = true)).fetchSemanticsNodes().first()
        val text = dialog.config.getOrNull(SemanticsProperties.Text)?.joinToString("") { it.text }.orEmpty()
        val code = Regex("[A-Z0-9]{3}-[A-Z0-9]{3}-[A-Z0-9]{3}").find(text)?.value
        checkNotNull(code) { "no invite code in: $text" }
        DeviceEvidence.screenshot("22-invite")
        tap("Close")

        friendRedeems(code, "Alex")
        SyncWorker.runNow(InstrumentationRegistry.getInstrumentation().targetContext)
        tap("Alex", timeout = 30_000)
        tap("Choose what Alex can do")
        tap("Gentle nudges")
        DeviceEvidence.screenshot("23-permissions")
        tap("Save my rules")
        waitFor("Change permissions", timeout = 30_000)
        DeviceEvidence.screenshot("24-friend-granted")

        compose.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        tap("Routines", timeout = 15_000)
        tap("New wake-up")
        DeviceEvidence.screenshot("25-new-wake-up")
        tap("Save wake-up")
        waitFor("07:00 · Weekdays", timeout = 30_000) // the routines list, not the editor
        // Readiness is what this phone reported to the server after AlarmManager accepted it.
        waitFor("Ready on this phone", timeout = 60_000)
        DeviceEvidence.screenshot("26-routine-saved")
    }

    private fun waitFor(text: String, timeout: Long = 20_000, substring: Boolean = false) {
        compose.waitUntil(timeout) { compose.onAllNodes(hasText(text, substring = substring)).fetchSemanticsNodes().isNotEmpty() }
    }

    /** Waits for the text, scrolls it into view when it's in a scrolling container, then taps it. */
    private fun tap(text: String, timeout: Long = 20_000) {
        waitFor(text, timeout)
        val node = compose.onAllNodes(hasText(text))[0]
        runCatching { node.performScrollTo() }
        node.performClick()
        compose.waitForIdle()
    }

    /** Null when the dev backend answers; otherwise what went wrong, so a skip says why. */
    private fun backendProblem(): String? = runCatching {
        (URL("$backend/healthz").openConnection() as HttpURLConnection).run {
            connectTimeout = 3_000
            readTimeout = 3_000
            if (responseCode == 200) null else "HTTP $responseCode"
        }
    }.getOrElse { it.toString() }

    /** A second account on the dev backend, made over HTTP, that redeems the invite code. */
    private fun friendRedeems(code: String, name: String) {
        val token = post("/dev/token", null, JSONObject()).getString("accessToken")
        post("/v1/account", token, JSONObject().put("displayName", name).put("timeZone", "UTC").put("locale", "en-US").put("ageAttestation", true))
        post("/v1/invites/redeem", token, JSONObject().put("code", code))
    }

    private fun post(path: String, token: String?, body: JSONObject): JSONObject {
        val c = URL(backend + path).openConnection() as HttpURLConnection
        c.requestMethod = "POST"
        c.doOutput = true
        c.setRequestProperty("content-type", "application/json")
        token?.let { c.setRequestProperty("authorization", "Bearer $it") }
        c.outputStream.use { it.write(body.toString().toByteArray()) }
        val status = c.responseCode
        val text = (if (status in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.readText().orEmpty()
        check(status in 200..299) { "POST $path -> $status: $text" }
        return if (text.isBlank()) JSONObject() else JSONObject(text)
    }
}
