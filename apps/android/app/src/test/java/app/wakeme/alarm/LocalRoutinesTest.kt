package app.wakeme.alarm

import android.content.Context
import androidx.room.Room
import org.robolectric.RuntimeEnvironment
import app.wakeme.core.Recurrence
import app.wakeme.core.isLocalPlan
import app.wakeme.data.PlanTerms
import app.wakeme.data.local.LocalDb
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlarmManager

/**
 * Guest wake-ups (doc 02 §9): stored and armed on this phone only, under local plan ids.
 * Robolectric 4.17 predates API 37, so this runs against API 35.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class) // not the Hilt app class
class LocalRoutinesTest {
    private lateinit var db: LocalDb
    private lateinit var local: LocalRoutines
    private lateinit var scheduler: AlarmScheduler

    private val weekdays = PlanTerms("Gym", "07:00", "Europe/London", "fixed", Recurrence.weekly(listOf(1, 2, 3, 4, 5)), "bundled:rise.gentle", 90, null, null)

    @Before
    fun setUp() {
        val context: Context = RuntimeEnvironment.getApplication()
        ShadowAlarmManager.setCanScheduleExactAlarms(true)
        db = Room.inMemoryDatabaseBuilder(context, LocalDb::class.java).allowMainThreadQueries().build()
        scheduler = AlarmScheduler(context, db)
        local = LocalRoutines(db, scheduler)
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun aGuestWakeUpIsStoredAndArmedOnThisPhone() = runTest {
        local.save(weekdays)
        val (routine, armed) = local.routines.first().single()
        assertTrue("local id: ${routine.planId}", isLocalPlan(routine.planId))
        assertEquals("07:00", routine.localTime)
        assertEquals(null, routine.challengeRevisionId) // challenges come from the account
        assertTrue("armed with AlarmManager", armed)
        assertTrue(scheduler.armed(routine.planId).isNotEmpty())
    }

    @Test
    fun deletingStopsItRingingAtOnce() = runTest {
        local.save(weekdays)
        val planId = local.routines.first().single().first.planId
        local.delete(planId)
        assertTrue(local.routines.first().isEmpty())
        assertTrue(scheduler.armed(planId).isEmpty())
    }

    @Test
    fun anEndedRoutineCannotBeReinstalledOrToppedUpFromAnOldSnapshot() = runTest {
        local.save(weekdays)
        val routine = local.routines.first().single().first
        val context: Context = RuntimeEnvironment.getApplication()
        BootAlarms(context).endPlan(routine.planId, "account-a")
        scheduler.cancelPlan(routine.planId)
        assertTrue(scheduler.installRoutine(routine, null) is ScheduleResult.Abandoned)
        assertEquals(false, scheduler.topUp(routine, null))
        assertTrue(scheduler.armed(routine.planId).isEmpty())
    }

    @Test
    fun withoutExactAlarmsItIsKeptButNotArmed() = runTest {
        ShadowAlarmManager.setCanScheduleExactAlarms(false)
        local.save(weekdays)
        val (_, armed) = local.routines.first().single()
        assertEquals(false, armed) // the guest screen says "Not scheduled" and how to fix it
    }
}
