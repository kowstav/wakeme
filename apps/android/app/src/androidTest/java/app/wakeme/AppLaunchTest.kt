package app.wakeme

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
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
        // Small screens scroll; each route must be reachable and visible once scrolled to.
        for (label in listOf("Play preview", "I have an invite", "Set up my own wake-up")) {
            compose.onNodeWithText(label).performScrollTo().assertIsDisplayed()
        }
    }

    @Test
    fun selfSetupLeadsToSignIn() {
        compose.waitUntil(15_000) { compose.onAllNodes(androidx.compose.ui.test.hasText("Set up my own wake-up")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Set up my own wake-up").performScrollTo().performClick()
        compose.waitUntil(10_000) { compose.onAllNodes(androidx.compose.ui.test.hasText("Save your connection")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Save your connection").assertIsDisplayed()
        DeviceEvidence.screenshot("02-sign-in")
    }
}
