package app.wakeme.widget

import android.content.Context
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

/**
 * Keeps one WorkManager job pending so WorkManager never switches its RescheduleReceiver on and
 * off. Each switch is a package change; the widget service answers it by re-registering the
 * widget and resetting it on screen, and a tap at that moment is lost (android-device #102, where
 * Glance's own session job caused the switch). Signed-in phones already keep the periodic sync
 * pending; this covers everyone else. The job is due in ten years and does nothing; if it ever
 * runs, the next start queues it again.
 */
object WidgetKeepAlive {
    private const val NAME = "widget-keepalive"

    /** Needs unlocked storage (WorkManager's database), so call it once the user has unlocked. */
    fun ensure(context: Context) {
        val req = OneTimeWorkRequestBuilder<NeverDueWorker>().setInitialDelay(3650, TimeUnit.DAYS).build()
        WorkManager.getInstance(context).enqueueUniqueWork(NAME, ExistingWorkPolicy.KEEP, req)
    }
}

/** The job behind [WidgetKeepAlive]: due in ten years, and nothing to do if it ever runs. */
class NeverDueWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
    override fun doWork(): Result = Result.success()
}
