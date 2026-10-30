package app.wakeme

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertIsDisplayed
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
        // The emulator's first request to the host can time out while its network settles
        // (android-device #94, Android 17), so ask a few times before calling it unreachable.
        var unreachable = backendProblem()
        repeat(5) { if (unreachable != null) { Thread.sleep(3_000); unreachable = backendProblem() } }
        assumeTrue("dev backend not reachable: $unreachable", unreachable == null)

        // Doc 03 §5 step 2: a personal wake-up first, with no account; it is armed on this phone.
        tap("Set up my own wake-up")
        waitFor("Your wake-ups")
        DeviceEvidence.screenshot("21-guest-home")
        tap("New wake-up")
        DeviceEvidence.screenshot("25-new-wake-up")
        tap("Save wake-up")
        waitFor("07:00", timeout = 30_000)
        waitFor("Weekdays", timeout = 30_000)
        waitFor("Scheduled", timeout = 30_000)
        // Regression: a completed activity-scoped editor used to close this second editor at once.
        tap("New wake-up")
        waitFor("Name (optional)")
        waitFor("Save wake-up")
        compose.waitForIdle()
        compose.onNodeWithText("Save wake-up").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Name (optional)").performScrollTo().performTextInput("Second morning")
        DeviceEvidence.screenshot("26-second-guest-editor")
        tap("Save wake-up")
        waitFor("Weekdays · Second morning", timeout = 30_000)
        waitFor("Weekdays") // The first alarm remains after saving the second.
        DeviceEvidence.screenshot("26-two-guest-wake-ups")
        tap("New wake-up")
        waitFor("Name (optional)")
        tap("Cancel")
        waitFor("Scheduled", timeout = 30_000)
        DeviceEvidence.screenshot("26-guest-wake-up")
        // Friends need an account; the wake-up moves to it.
        tap("Sign in")
        tap("Use a local test account (debug build)")
        waitFor("Display name")
        compose.onNodeWithText("Display name").performTextInput("Sam")
        compose.onNode(isToggleable()).performClick()
        DeviceEvidence.screenshot("20-profile")
        tap("Continue")

        tap("Invite", timeout = 30_000)
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
        // The guest wake-up is now the account's routine: the server lists it, and readiness is what
        // this phone reported to the server after AlarmManager armed the server version.
        waitFor("07:00 · Weekdays", timeout = 30_000)
        waitFor("Ready on this phone", timeout = 60_000)
        DeviceEvidence.screenshot("26b-moved-to-account")

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
        // Saving before the answer lands saves a one-question quiz ("Step 1 of 1"); the Android 16
        // emulator is slow enough for that (android-device #88 and #93).
        waitFor("Sam")
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

        // A private circle: created from Friends, Sam is its owner, and inviting to it works.
        tap("Friends")
        tap("New circle")
        type("Circle name", "Flat 3B")
        tap("Create")
        waitFor("Owner", timeout = 30_000)
        waitFor("Flat 3B")
        DeviceEvidence.screenshot("32-circle")
        tap("Invite") // the circle page's quick action, read as "Invite to this circle"
        waitFor("Invite to Flat 3B", timeout = 30_000)
        val circleInviteText = compose.onAllNodes(hasText("type this code", substring = true)).fetchSemanticsNodes().first()
            .config.getOrNull(SemanticsProperties.Text)?.joinToString("") { it.text }.orEmpty()
        val circleCode = checkNotNull(Regex("[A-Z0-9]{3}-[A-Z0-9]{3}-[A-Z0-9]{3}").find(circleInviteText)?.value)
        tap("Close")
        compose.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        tap("Challenges")
        tap("Create a challenge")
        tap("Flat 3B")
        type("Title", "Flat breakfast quiz")
        type("Question 1", "Where do we meet for breakfast?")
        type("Your answer", "Kitchen")
        // A window change must keep the words and the explicitly selected audience.
        compose.activityRule.scenario.recreate()
        waitFor("Flat breakfast quiz")
        waitFor("Kitchen")
        tap("Save and try it")
        waitFor("Step 1 of 1", timeout = 30_000)
        type("Your answer", "Kitchen")
        tap("Check")
        waitFor("Shared with Flat 3B")
        DeviceEvidence.screenshot("33-circle-quiz-preview")
        tap("Publish")
        waitInList("Flat breakfast quiz")
        waitInList("Shared with Flat 3B")
        DeviceEvidence.screenshot("34-circle-quiz-published")

        fun alexChallenges() = get("/v1/challenges", alex).getJSONArray("challenges").let { array ->
            (0 until array.length()).map { array.getJSONObject(it) }
        }
        check(alexChallenges().none { it.getString("title") == "Flat breakfast quiz" }) { "A friend outside the circle can read its quiz" }
        post("/v1/invites/redeem", alex, JSONObject().put("code", circleCode))
        val shared = alexChallenges().single { it.getString("title") == "Flat breakfast quiz" }
        check(shared.getInt("publishedRevision") == 1)
        check(alexChallenges().none { it.getString("title") == "HQ quiz" }) { "The personal quiz leaked through circle membership" }
        val circleId = shared.getString("circleId")
        post("/v1/circles/$circleId/leave", alex, JSONObject())
        check(alexChallenges().none { it.getString("id") == shared.getString("id") }) { "Leaving the circle kept access to its quiz" }

        tap("Routines")
        tap("New wake-up")
        tap("More options")
        tap("Study")
        waitFor("18:00", substring = true)
        tap("Save wake-up")
        waitInList("18:00 · Weekdays")
        tap("Study")
        tap("Use this routine again")
        waitFor("Study")
        compose.activityRule.scenario.recreate()
        waitFor("18:00", substring = true)
        waitFor("Study")
        DeviceEvidence.screenshot("54-reusable-study-routine")
        tap("Cancel")

        tap("Friends")
        tap("Alex")
        tap("Change permissions")
        tap("Wake buddy")
        tap("Save my rules")
        waitFor("Change permissions")
        val buddyPlan = post("/v1/alarm-plans", alex, JSONObject()
            .put("ownerId", samId).put("label", "Buddy morning").put("localTime", "07:00")
            .put("timeZone", "UTC").put("timeZonePolicy", "fixed")
            .put("recurrence", JSONObject().put("kind", "weekly").put("days", org.json.JSONArray(listOf(1, 2, 3, 4, 5))))
            .put("soundId", "bundled:rise.gentle").put("ringSeconds", 60))
        compose.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        tap("Routines")
        waitInList("Buddy morning")
        tap("Buddy morning")
        tap("Accept this version")
        waitFor("Active version")
        val conversationId = (0 until alexFriends.length()).map { alexFriends.getJSONObject(it) }
            .single { it.getJSONObject("user").getString("id") == samId }.getString("conversationId")
        fun thanksCount() = get("/v1/conversations/$conversationId/messages", alex).getJSONArray("messages").let { array ->
            (0 until array.length()).count { array.getJSONObject(it).optString("body") == "Thanks, Alex 🦆" }
        }
        check(thanksCount() == 0)
        compose.activityRule.scenario.onActivity { activity ->
            // ActivityScenario matches lifecycle events by the original Intent's filter fields.
            // Keep those fields while exercising the real onNewIntent path; replace launch flags
            // so the test launcher's CLEAR_TASK flag cannot clear the activity being recreated.
            activity.startActivity(android.content.Intent(activity.intent).setFlags(android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra(MainActivity.EXTRA_ROUTE, "wake-thanks-plan/${buddyPlan.getString("id")}"))
        }
        waitFor("Send thanks")
        check(thanksCount() == 0) { "Opening the thanks screen posted automatically" }
        DeviceEvidence.screenshot("55-optional-thanks")
        tap("Send thanks")
        waitFor("Thanks sent.")
        check(thanksCount() == 1)
        compose.activityRule.scenario.recreate()
        waitFor("Thanks sent.")
        check(thanksCount() == 1) { "Recreation sent a second thanks" }
        tap("Done")
        compose.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        tap("Routines")
        tap("Study")
        tap("End this routine")
        val ownApi = dagger.hilt.android.EntryPointAccessors.fromApplication(
            InstrumentationRegistry.getInstrumentation().targetContext.applicationContext,
            app.wakeme.safety.SafetyDeps::class.java,
        ).api()
        compose.waitUntil(60_000) {
            kotlinx.coroutines.runBlocking { ownApi.plans().mine.none { it.accepted?.label == "Study" && it.state in setOf("active", "paused") } }
        }
        DeviceEvidence.screenshot("57-routine-end-synced")
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
        try {
            compose.waitUntil(timeout) { compose.onAllNodes(hasText(text, substring = substring)).fetchSemanticsNodes().isNotEmpty() }
        } catch (failure: Throwable) {
            DeviceEvidence.screenshot("99-journey-failure")
            // Name what was on screen, so the CI log alone explains the failure.
            val shown = runCatching {
                compose.onAllNodes(hasText("", substring = true), useUnmergedTree = true).fetchSemanticsNodes()
                    .flatMap { n -> n.config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text } + listOfNotNull(n.config.getOrNull(SemanticsProperties.EditableText)?.text) }
                    .filter { it.isNotBlank() }.distinct().take(40)
            }.getOrDefault(emptyList())
            throw AssertionError("\"$text\" didn't appear. On screen: $shown", failure)
        }
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
