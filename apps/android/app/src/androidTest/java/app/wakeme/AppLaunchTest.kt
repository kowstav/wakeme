package app.wakeme

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
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
        compose.onNodeWithText("I have an invite").assertIsDisplayed()
        compose.onNodeWithText("Set up my own wake-up").assertIsDisplayed()
        compose.onNodeWithText("Play preview").assertIsDisplayed()
        DeviceEvidence.screenshot("01-welcome")
    }

    @Test
    fun selfSetupLeadsToSignIn() {
        compose.waitUntil(15_000) { compose.onAllNodes(androidx.compose.ui.test.hasText("Set up my own wake-up")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Set up my own wake-up").performClick()
        compose.waitUntil(10_000) { compose.onAllNodes(androidx.compose.ui.test.hasText("Email me a code")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Email me a code").assertIsDisplayed()
        DeviceEvidence.screenshot("02-sign-in")
    }
}
