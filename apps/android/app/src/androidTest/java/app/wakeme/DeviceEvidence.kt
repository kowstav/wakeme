package app.wakeme

import android.graphics.Bitmap
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File

/**
 * Writes screenshots and observations to the app's external files dir, which
 * CI pulls as evidence (`/sdcard/Android/data/<pkg>/files/evidence`). Results
 * from an emulator are not device evidence; they show the code path runs.
 */
object DeviceEvidence {
    private val dir: File by lazy {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        File(ctx.getExternalFilesDir(null), "evidence").apply { mkdirs() }
    }

    fun screenshot(name: String) {
        val bmp = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot() ?: return
        File(dir, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    fun note(name: String, text: String) {
        Log.i("WakeMeEvidence", "$name: $text")
        File(dir, "$name.txt").writeText(text + "\n")
    }
}
