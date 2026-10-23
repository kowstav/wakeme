package app.wakeme

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.wakeme.alarm.AlarmScheduler
import app.wakeme.alarm.ScheduleResult
import app.wakeme.core.AlarmRole
import app.wakeme.core.InstalledRoutine
import app.wakeme.core.Recurrence
import app.wakeme.data.local.LocalDb
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Duration
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * ADR 0011 on the platform's AlarmManager: a routine is armed over the window, the window is
 * topped up after an occurrence rings without dropping that occurrence's pending follow-up,
 * and a follow-up cancelled by finishing the wake session isn't brought back. The alarms are
 * days ahead and removed afterwards; nothing rings during the test.
 */
@RunWith(AndroidJUnit4::class)
class ArmingWindowTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val db = Room.inMemoryDatabaseBuilder(context, LocalDb::class.java).build()
    private val scheduler = AlarmScheduler(context, db)
    private val routine = InstalledRoutine(
        planId = "arming-test", version = 1, label = "Test", localTime = "07:00", timeZone = "UTC", timeZonePolicy = "fixed",
        recurrence = Recurrence.weekly(listOf(1, 2, 3, 4, 5, 6, 7)), soundId = "bundled:rise.gentle", ringSeconds = 60,
        challengeRevisionId = null, followUpDelaySeconds = 180, paused = false,
    )

    @After
    fun tearDown() = runBlocking {
        scheduler.cancelAll()
        db.close()
    }

    @Test
    fun windowIsToppedUpWithoutDroppingAPendingFollowUp() = runBlocking {
        assumeTrue("exact alarms not allowed", scheduler.canScheduleExact())
        val firstDay = LocalDate.now(ZoneOffset.UTC).plusDays(2)
        val start = firstDay.atTime(6, 0).toInstant(ZoneOffset.UTC)

        assertTrue(scheduler.installRoutine(routine, null, start) is ScheduleResult.Scheduled)
        assertEquals("8 occurrences, each with its follow-up", 16, db.alarms().forPlan(routine.planId).size)

        // A minute after the first occurrence rang: one more day enters the window.
        val afterFirst = start.plus(Duration.ofMinutes(61))
        assertTrue(scheduler.topUp(routine, null, afterFirst))
        val rows = db.alarms().forPlan(routine.planId)
        assertEquals(18, rows.size)
        val first = firstDay.toString()
        assertTrue("the first occurrence's follow-up is still pending", rows.any { it.role == AlarmRole.FOLLOW_UP.name && it.occurrenceKey == first })
        assertTrue(rows.any { it.occurrenceKey == firstDay.plusDays(8).toString() })

        // Finishing the session cancels that follow-up; topping up again doesn't bring it back.
        scheduler.cancelFollowUp(routine.planId, first)
        assertFalse(scheduler.topUp(routine, null, afterFirst))
        assertFalse(db.alarms().forPlan(routine.planId).any { it.role == AlarmRole.FOLLOW_UP.name && it.occurrenceKey == first })
        DeviceEvidence.note("arming-window", "armed=${db.alarms().forPlan(routine.planId).size} after top-up and one finished session")
    }
}
