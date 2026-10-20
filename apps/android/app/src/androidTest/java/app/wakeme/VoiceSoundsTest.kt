package app.wakeme

import android.Manifest
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.lifecycle.Lifecycle
import androidx.test.rule.GrantPermissionRule
import app.wakeme.data.WakeMeApi
import app.wakeme.designsystem.WakeMeTheme
import app.wakeme.media.SoundLibrary
import app.wakeme.media.VoiceSoundsScreen
import app.wakeme.media.VoiceSoundsViewModel
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

@EntryPoint
@InstallIn(SingletonComponent::class)
interface VoiceTestDeps { fun sounds(): SoundLibrary; fun api(): WakeMeApi }

class VoiceSoundsTest {
    @get:Rule val permission = GrantPermissionRule.grant(Manifest.permission.RECORD_AUDIO)
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun recordingNeedsAnExplicitUploadAndStopsWhenTheAppLeavesTheForeground() {
        val context = compose.activity.applicationContext
        val deps = EntryPointAccessors.fromApplication(context, VoiceTestDeps::class.java)
        lateinit var vm: VoiceSoundsViewModel
        compose.runOnUiThread {
            vm = VoiceSoundsViewModel(context, deps.sounds(), deps.api())
            compose.activity.viewModelStore.put("voice-test", vm)
        }
        compose.setContent { WakeMeTheme { VoiceSoundsScreen(vm) } }
        compose.onNodeWithText("Record a voice sound").performScrollTo().performClick()
        compose.runOnIdle { assertTrue("recording started", vm.state.recording) }
        Thread.sleep(1500) // give the encoder a valid bounded recording
        compose.onNodeWithText("Stop recording").performScrollTo().performClick()
        compose.runOnIdle { assertTrue("local draft", vm.state.hasDraft) }
        compose.onNodeWithText("Upload for checking").performScrollTo().assertIsNotEnabled()
        DeviceEvidence.screenshot("52-voice-rights-before-upload")
        compose.onNodeWithText("Discard recording").performScrollTo().performClick()
        compose.onNodeWithText("Record a voice sound").performScrollTo().performClick()
        Thread.sleep(1500)
        compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        assertFalse("microphone stopped in background", vm.state.recording)
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        compose.runOnIdle { vm.discard(); assertFalse(vm.state.hasDraft) }
    }
}
