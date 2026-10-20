package app.wakeme

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.activity.enableEdgeToEdge
import app.wakeme.designsystem.WakeMeTheme
import app.wakeme.media.VoiceSoundsScreen
import app.wakeme.media.VoiceSoundsViewModel
import dagger.hilt.android.AndroidEntryPoint

/** Debug-only, private host: tests use the same Hilt ViewModel factory as the real app. */
@AndroidEntryPoint
class VoiceSoundsTestActivity : ComponentActivity() {
    val voice: VoiceSoundsViewModel by viewModels()
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { WakeMeTheme { VoiceSoundsScreen(voice) } }
    }
}
