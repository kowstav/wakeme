package app.wakeme

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.wakeme.data.local.Prefs
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Cold launch reaches the welcome screen without a backend, account or network. */
@RunWith(AndroidJUnit4::class)
class AppLaunchTest {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun welcomeScreenShowsBothRoutes() {
        compose.waitUntil(15_000) { compose.onAllNodes(androidx.compose.ui.test.hasText("I have an invite")).fetchSemanticsNodes().isNotEmpty() }
        DeviceEvidence.screenshot("01-welcome")
        // The two routes are pinned at the bottom (always visible); the preview is in the scrolling
        // story, so small screens scroll to it. Each must be visible.
        for (label in listOf("I have an invite", "Set up my own wake-up")) {
            compose.onNodeWithText(label).assertIsDisplayed()
        }
        compose.onNodeWithText("Play preview").performScrollTo().assertIsDisplayed()
    }

    /** Doc 03 §5 step 2: a personal wake-up needs no account; signing in is for friends. */
    @Test
    fun selfSetupStartsWithoutAnAccount() {
        try {
            compose.waitUntil(15_000) { compose.onAllNodes(androidx.compose.ui.test.hasText("Set up my own wake-up")).fetchSemanticsNodes().isNotEmpty() }
            // Pinned at the bottom of the welcome screen, so no scrolling is needed.
            compose.onNodeWithText("Set up my own wake-up").performClick()
            compose.waitUntil(10_000) { compose.onAllNodes(androidx.compose.ui.test.hasText("Your wake-ups")).fetchSemanticsNodes().isNotEmpty() }
            DeviceEvidence.screenshot("02-guest-home")
            // The list composes only what is on screen: scroll it to the button first.
            compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasText("Sign in to add friends"))
            compose.onNodeWithText("Sign in to add friends").performClick()
            compose.waitUntil(10_000) { compose.onAllNodes(androidx.compose.ui.test.hasText("Save your connection")).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("Save your connection").assertIsDisplayed()
            DeviceEvidence.screenshot("02-sign-in")
        } finally {
            // Later tests start from the welcome screen.
            runBlocking { Prefs(InstrumentationRegistry.getInstrumentation().targetContext).setGuest(false) }
        }
    }
}
