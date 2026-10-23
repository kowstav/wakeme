package app.wakeme.alarm

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.os.UserManager
import androidx.room.Room
import app.wakeme.data.local.LocalDb
import app.wakeme.data.local.ScheduledAlarmEntity
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlarmManager

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class BootAlarmsTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    @Before fun clearMirror() {
        context.createDeviceProtectedStorageContext().getSharedPreferences("boot_alarms", Context.MODE_PRIVATE).edit().clear().commit()
    }
    private fun alarm(code: Int, role: String) = BootAlarm(code, System.currentTimeMillis() + 60_000, "bundled:rise.gentle", 90, "Morning", "plan", null, role, "today", 1, "quiz")

    @Test fun bootMirrorRegistersWithoutCredentialStorageAndKeepsChallengeIdentity() {
        ShadowAlarmManager.setCanScheduleExactAlarms(true)
        shadowOf(context.getSystemService(UserManager::class.java)).setUserUnlocked(false)
        val guarded = object : ContextWrapper(context) {
            override fun getSharedPreferences(name: String?, mode: Int): android.content.SharedPreferences = error("credential storage touched")
            override fun getFilesDir(): java.io.File = error("credential files touched")
            override fun getDatabasePath(name: String): java.io.File = error("Room touched")
        }
        val boot = BootAlarms(guarded)
        boot.replace(listOf(alarm(110, "MAIN"), alarm(111, "FOLLOW_UP")))
        boot.reschedule()
        assertEquals("quiz", boot.get(110)?.challengeRevisionId)
        val manager = shadowOf(context.getSystemService(android.app.AlarmManager::class.java))
        assertEquals(2, manager.scheduledAlarms.size)
        boot.cancelFollowUp("plan", "today")
        assertEquals(listOf(110), boot.all().map { it.requestCode })
        assertEquals(1, manager.scheduledAlarms.size)
        boot.reschedule()
        assertEquals(1, manager.scheduledAlarms.size)
    }

    @Test fun cancellationIsReconciledBeforeRoomCanRearmTheFollowUp() = runTest {
        val boot = BootAlarms(context)
        boot.replace(listOf(alarm(120, "MAIN"), alarm(121, "FOLLOW_UP")))
        boot.cancelFollowUp("plan", "today")
        val db = Room.inMemoryDatabaseBuilder(context, LocalDb::class.java).allowMainThreadQueries().build()
        try {
            for (a in listOf(alarm(120, "MAIN"), alarm(121, "FOLLOW_UP"))) {
                db.alarms().upsert(ScheduledAlarmEntity(a.requestCode, a.planId, a.commandId, a.version, a.occurrenceKey, a.role, a.fireAt, a.soundId, a.ringSeconds, a.label, null, a.challengeRevisionId))
            }
            AlarmScheduler(context, db).reconcileBootCancellations()
            assertNull(db.alarms().get(121))
            assertNotNull(db.alarms().get(120))
            assertTrue(boot.cancelled().isEmpty())
            assertEquals(listOf(120), boot.all().map { it.requestCode })
        } finally { db.close() }
    }

    @Test fun anOfflineEndSurvivesRestartAndCannotBeRearmedByAStaleMirror() {
        ShadowAlarmManager.setCanScheduleExactAlarms(true)
        val boot = BootAlarms(context)
        boot.replace(listOf(alarm(130, "MAIN"), alarm(131, "FOLLOW_UP")))
        boot.reschedule()
        boot.endPlan("plan", "account-a")
        val restarted = BootAlarms(context)
        assertTrue(restarted.all().isEmpty())
        assertEquals(setOf("plan"), restarted.pendingEnds("account-a"))
        assertTrue(restarted.pendingEnds("account-b").isEmpty())
        restarted.reschedule()
        assertTrue(shadowOf(context.getSystemService(android.app.AlarmManager::class.java)).scheduledAlarms.isEmpty())
        restarted.acknowledgeEnd("plan")
        assertTrue(restarted.pendingEnds("account-a").isEmpty())
        assertTrue("an old server backup still cannot revive an ended routine", restarted.isEnded("plan"))
    }

    @Test fun bootDoesNotReplayAPastOccurrenceOrAFreshRemoteCommand() {
        ShadowAlarmManager.setCanScheduleExactAlarms(true)
        val now = System.currentTimeMillis()
        val boot = BootAlarms(context)
        boot.replace(listOf(alarm(140, "MAIN"), alarm(141, "MAIN").copy(fireAt = now - 1000),
            alarm(142, "MAIN").copy(planId = null, commandId = "remote-request")))
        boot.reschedule(now)
        assertEquals(1, shadowOf(context.getSystemService(android.app.AlarmManager::class.java)).scheduledAlarms.size)
    }
}
