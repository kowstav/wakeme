package app.wakeme.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

class WakePlanTest {
    private val routine = InstalledRoutine(
        planId = "p1", version = 2, label = "Class", localTime = "07:00", timeZone = "America/New_York", timeZonePolicy = "fixed",
        recurrence = Recurrence.weekly(listOf(1, 2, 3, 4, 5)), soundId = "bundled:rise.gentle", ringSeconds = 90,
        challengeRevisionId = null, followUpDelaySeconds = 180, paused = false,
    )

    @Test
    fun armsEveryOccurrenceInTheWindowEachWithItsFollowUp() {
        val alarms = scheduleFor(routine, Instant.parse("2026-10-18T12:00:00Z"), ZoneId.of("Europe/London"))
        assertEquals(Instant.parse("2026-10-19T11:00:00Z"), alarms[0].fireAt)
        assertEquals(AlarmRole.FOLLOW_UP, alarms[1].role)
        assertEquals(Instant.parse("2026-10-19T11:03:00Z"), alarms[1].fireAt)
        // Sunday noon: weekdays through the next Monday, which is inside the 8-day window.
        val mains = alarms.filter { it.role == AlarmRole.MAIN }
        assertEquals(listOf("2026-10-19", "2026-10-20", "2026-10-21", "2026-10-22", "2026-10-23", "2026-10-26"), mains.map { it.occurrenceKey })
        assertEquals(mains.size, alarms.count { it.role == AlarmRole.FOLLOW_UP })
        assertEquals(alarms.size, alarms.map { it.requestCode }.toSet().size)
    }

    @Test
    fun windowIsCappedAndAlwaysIncludesTheNextOccurrence() {
        val daily = routine.copy(timeZone = "UTC", recurrence = Recurrence.weekly(listOf(1, 2, 3, 4, 5, 6, 7)), followUpDelaySeconds = null)
        val alarms = scheduleFor(daily, Instant.parse("2026-10-18T12:00:00Z"), ZoneId.of("UTC"))
        assertEquals(ARM_MAX_OCCURRENCES, alarms.size)
        assertEquals(Instant.parse("2026-10-26T07:00:00Z"), alarms.last().fireAt)
        // A one-off weeks away is still armed.
        val once = scheduleFor(daily.copy(recurrence = Recurrence.once("2026-11-20")), Instant.parse("2026-10-18T12:00:00Z"), ZoneId.of("UTC"))
        assertEquals(listOf(Instant.parse("2026-11-20T07:00:00Z")), once.map { it.fireAt })
    }

    @Test
    fun deviceTimeZonePolicyFollowsThePhone() {
        val alarms = scheduleFor(routine.copy(timeZonePolicy = "device", followUpDelaySeconds = null), Instant.parse("2026-10-18T12:00:00Z"), ZoneId.of("Asia/Tokyo"))
        assertEquals(Instant.parse("2026-10-18T22:00:00Z"), alarms.first().fireAt)
        assertTrue(alarms.all { it.role == AlarmRole.MAIN })
    }

    @Test
    fun pausedRoutinesInstallNothing() {
        assertTrue(scheduleFor(routine.copy(paused = true), Instant.parse("2026-10-18T12:00:00Z"), ZoneId.of("UTC")).isEmpty())
    }

    @Test
    fun systemStopIsNotCompletion() {
        val s = WakeSession("p1", "2026-10-19", WakeSessionState.RINGING, hasFollowUp = true).onSystemStop()
        assertEquals(WakeSessionState.FOLLOW_UP_PENDING, s.state)
        assertFalse(s.cancelsFollowUp)
        assertTrue(s.onChallengeCompleted().cancelsFollowUp)
        assertTrue(s.onEscape().cancelsFollowUp)
        assertEquals(WakeSessionState.COMPLETED, s.onChallengeCompleted().onEscape().state)
    }

    @Test
    fun onlyThePrimaryPhoneInstallsRoutines() {
        assertTrue(installsRoutines(primaryDeviceId = null, thisDeviceId = null))
        assertTrue(installsRoutines(primaryDeviceId = null, thisDeviceId = "d1"))
        assertTrue(installsRoutines(primaryDeviceId = "d1", thisDeviceId = "d1"))
        assertFalse(installsRoutines(primaryDeviceId = "d1", thisDeviceId = "d2"))
        assertFalse(installsRoutines(primaryDeviceId = "d1", thisDeviceId = null))
    }
}
