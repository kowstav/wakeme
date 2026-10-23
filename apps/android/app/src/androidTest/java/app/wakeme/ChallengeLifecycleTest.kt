package app.wakeme

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.wakeme.challenge.ChallengeRunner
import app.wakeme.core.ChallengeDefinition
import app.wakeme.core.ChallengeStep
import app.wakeme.core.Fallback
import app.wakeme.designsystem.PrimaryButton
import app.wakeme.designsystem.WakeMeTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Offline wake flow: recreation, bounded time, and escape in small/large-text windows. */
@RunWith(AndroidJUnit4::class)
class ChallengeLifecycleTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private fun challenge(vararg steps: ChallengeStep, limit: Int = 90) = ChallengeDefinition(
        1, "Morning check-in", "sequence", "easy", limit, steps.toList(), "local_deterministic", Fallback(),
    )

    @Test
    fun progressAndTheTypedAnswerSurviveSavedStateRestoration() {
        val definition = challenge(
            ChallengeStep.MultipleChoice("first", "Which cafe?", listOf("HQ", "Brunch"), 0),
            ChallengeStep.MathStep("second", listOf(7, 8), "+", 15),
        )
        val restored = StateRestorationTester(compose)
        val outcomes = mutableListOf<Boolean>()
        restored.setContent {
            WakeMeTheme { ChallengeRunner(definition, outcomes::add, {}, "End wake session", sessionKey = "occurrence-1") }
        }
        compose.onNodeWithText("Brunch").performScrollTo().performClick()
        compose.onNodeWithText("Not quite. Take your time and try again.").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("HQ").performScrollTo().performClick()
        compose.onNodeWithText("Your answer").performScrollTo().performTextInput("15")
        restored.emulateSavedInstanceStateRestore()
        compose.onNodeWithText("Step 2 of 2").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Your answer").assertTextContains("15")
        compose.onNodeWithText("Check").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(listOf(false), outcomes) }
        compose.onNodeWithText("End wake session").assertIsDisplayed()
        DeviceEvidence.screenshot("50-challenge-restored")
    }

    @Test
    fun anotherOccurrenceStartsAgainEvenWithTheSameChallenge() {
        val definition = challenge(
            ChallengeStep.MultipleChoice("first", "Which cafe?", listOf("HQ", "Brunch"), 0),
            ChallengeStep.MathStep("second", listOf(7, 8), "+", 15),
        )
        var occurrence by mutableStateOf("today")
        compose.setContent {
            WakeMeTheme { ChallengeRunner(definition, {}, {}, "End wake session", sessionKey = occurrence) }
        }
        compose.onNodeWithText("HQ").performScrollTo().performClick()
        compose.onNodeWithText("Step 2 of 2").performScrollTo().assertIsDisplayed()
        compose.runOnIdle { occurrence = "tomorrow" }
        compose.onNodeWithText("Step 1 of 2").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun escapeAndStopSoundStayVisibleWithLongContentAndLargeText() {
        val definition = challenge(ChallengeStep.MultipleChoice(
            "first", "Pick the place we met before the long walk home.",
            List(6) { "A long choice with enough words to wrap over several lines, number $it" }, 0,
        ))
        var stopped = false
        var escaped = false
        compose.setContent {
            val density = LocalDensity.current.density
            CompositionLocalProvider(LocalDensity provides Density(density, fontScale = 2f)) {
                WakeMeTheme {
                    Box(Modifier.requiredSize(340.dp, 420.dp)) {
                        ChallengeRunner(definition, {}, { escaped = true }, "End session", controls = {
                            PrimaryButton("Stop sound now", onClick = { stopped = true })
                        })
                    }
                }
            }
        }
        // No scrolling first: these controls must be visible even when the puzzle cannot fit.
        compose.onNodeWithText("Stop sound now").assertIsDisplayed().performClick()
        compose.onNodeWithText("End session").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(true, stopped); assertEquals(true, escaped) }
        DeviceEvidence.screenshot("51-challenge-large-text")
    }

    @Test
    fun expiryOffersTheCheckInWithoutReportingCompletion() {
        val outcomes = mutableListOf<Boolean>()
        val definition = challenge(ChallengeStep.MathStep("first", listOf(7, 8), "+", 15), limit = 1)
        compose.setContent {
            WakeMeTheme { ChallengeRunner(definition, outcomes::add, {}, "End wake session") }
        }
        compose.waitUntil(5_000) {
            compose.onAllNodes(androidx.compose.ui.test.hasText("Time's up. Use the simple check-in or end this session.")).fetchSemanticsNodes().isNotEmpty()
        }
        compose.runOnIdle { assertEquals(emptyList<Boolean>(), outcomes) }
        compose.onNodeWithText("I'm awake").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("End wake session").assertIsDisplayed()
    }
}
