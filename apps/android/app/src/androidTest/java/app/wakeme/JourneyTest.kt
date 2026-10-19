package app.wakeme

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
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

        val alex = friendRedeems(code, "Alex")
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

        // Safety (same steps as the iOS journey): Alex sends Sam an SOS without location; Sam
        // sees it first, confirms seeing it, then starts a check-in with Alex and checks in.
        val alexFriends = get("/v1/friends", alex).getJSONArray("friends")
        val samId = (0 until alexFriends.length()).map { alexFriends.getJSONObject(it).getJSONObject("user") }
            .first { it.getString("displayName") == "Sam" }.getString("id")
        post("/v1/sos", alex, JSONObject().put("viewerIds", org.json.JSONArray().put(samId)).put("shareLocation", false))
        tap("You")
        tap("Safety check-ins and location")
        waitFor("SOS from Alex", timeout = 30_000)
        waitFor("Check-in only · no location shared")
        DeviceEvidence.screenshot("27-sos-received")
        tap("I've seen this")
        waitFor("Alex knows you've seen this", timeout = 30_000, substring = true)
        tap("Check in with friends later")
        tap("Alex") // the friend chip in the start dialog
        waitFor("Alex will be notified", substring = true)
        DeviceEvidence.screenshot("28-check-in-dialog")
        // "Start" is also the section title behind the dialog; tap the dialog's button.
        compose.onAllNodes(hasText("Start") and hasClickAction())[0].performClick()
        tap("I'm OK — check in now", timeout = 30_000)
        waitFor("You're not sharing anything", timeout = 30_000)
        val sos = get("/v1/location-sessions", alex).getJSONArray("mine").let { a -> (0 until a.length()).map { a.getJSONObject(it) } }.first { it.getString("purpose") == "sos" }
        check(!sos.getJSONArray("viewers").getJSONObject(0).isNull("acknowledgedAt")) { "Alex is told that Sam saw the SOS" }

        // Challenges: write a quiz with two wrong options, try it and publish it; then edit it,
        // add a question, try the new version and publish it as version 2. The tab bar shows only
        // on tab screens, so leave Safety first.
        compose.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        tap("Challenges")
        tap("Create a challenge")
        type("Title", "HQ quiz")
        type("Question 1", "Where is headquarters?")
        type("Your answer", "Chaos curry")
        type("Wrong options (comma separated, optional)", "Blue door, Bean there")
        waitFor("Blue door, Bean there") // the comma stays while typing
        DeviceEvidence.screenshot("29-quiz-written")
        tap("Save and try it")
        waitFor("Step 1 of 1", timeout = 30_000)
        tap("Chaos curry")
        tap("Publish")
        waitInList("Ready to use · version 1")
        tap("Edit")
        waitFor("Edit challenge", timeout = 30_000)
        // The form starts from version 1; choices were shuffled when published, so either order.
        compose.waitUntil(20_000) { found("Blue door, Bean there") || found("Bean there, Blue door") }
        tap("Add another question")
        type("Question 2", "Who forgets the keys?")
        compose.onAllNodes(hasText("Your answer"))[1].run { runCatching { performScrollTo() }; performTextInput("Sam") }
        DeviceEvidence.screenshot("30-quiz-edited")
        tap("Save and try it")
        waitFor("Step 1 of 2", timeout = 30_000)
        tap("Chaos curry")
        waitFor("Step 2 of 2")
        compose.onNode(hasSetTextAction()).performTextInput("Sam")
        tap("Check")
        tap("Publish")
        waitInList("Ready to use · version 2")
        DeviceEvidence.screenshot("31-quiz-version-2")
    }

    /** Waits for text that may be further down a lazy list, scrolling each list towards it. */
    private fun waitInList(text: String, timeout: Long = 30_000) {
        val deadline = System.currentTimeMillis() + timeout
        while (!found(text)) {
            check(System.currentTimeMillis() < deadline) { "\"$text\" never appeared" }
            val lists = compose.onAllNodes(hasScrollToNodeAction())
            for (i in 0 until lists.fetchSemanticsNodes().size) runCatching { lists[i].performScrollToNode(hasText(text)) }
            if (!found(text)) Thread.sleep(500)
            compose.waitForIdle()
        }
    }

    /** Types into the text field labelled [label], scrolling it into view first. */
    private fun type(label: String, text: String) {
        waitFor(label)
        compose.onAllNodes(hasText(label))[0].run { runCatching { performScrollTo() }; performTextInput(text) }
    }

    private fun found(text: String) = compose.onAllNodes(hasText(text)).fetchSemanticsNodes().isNotEmpty()

    private fun waitFor(text: String, timeout: Long = 20_000, substring: Boolean = false) {
        compose.waitUntil(timeout) { compose.onAllNodes(hasText(text, substring = substring)).fetchSemanticsNodes().isNotEmpty() }
    }

    /** Waits for the text, scrolls it into view when it's in a scrolling container, then taps it. */
    private fun tap(text: String, timeout: Long = 20_000) {
        // Lazy lists compose only what is on screen: if the text isn't there yet, ask each
        // scrollable container to scroll to it.
        runCatching { compose.waitUntil(3_000) { found(text) } }
        if (!found(text)) {
            val lists = compose.onAllNodes(hasScrollToNodeAction())
            for (i in 0 until lists.fetchSemanticsNodes().size) runCatching { lists[i].performScrollToNode(hasText(text)) }
        }
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

    /** A second account on the dev backend, made over HTTP, that redeems the invite code; returns its token. */
    private fun friendRedeems(code: String, name: String): String {
        val token = post("/dev/token", null, JSONObject()).getString("accessToken")
        post("/v1/account", token, JSONObject().put("displayName", name).put("timeZone", "UTC").put("locale", "en-US").put("ageAttestation", true))
        post("/v1/invites/redeem", token, JSONObject().put("code", code))
        return token
    }

    private fun get(path: String, token: String): JSONObject {
        val c = URL(backend + path).openConnection() as HttpURLConnection
        c.setRequestProperty("authorization", "Bearer $token")
        val status = c.responseCode
        val text = (if (status in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.readText().orEmpty()
        check(status in 200..299) { "GET $path -> $status: $text" }
        return JSONObject(text)
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
