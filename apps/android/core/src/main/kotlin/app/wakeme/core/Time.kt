package app.wakeme.core

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

// Same rules as the server (packages/policy) and iOS, checked by shared fixtures:
//  * windows start on listed ISO weekdays; end <= start continues into the next day
//  * DST gap: shift later by the gap length; DST fold: earlier instant
// java.time's ZonedDateTime.of implements exactly these gap/fold rules.

fun minuteOfDay(hhmm: String): Int {
    require(Regex("^([01]\\d|2[0-3]):[0-5]\\d$").matches(hhmm)) { "invalid HH:MM $hhmm" }
    return hhmm.substring(0, 2).toInt() * 60 + hhmm.substring(3, 5).toInt()
}

fun isWithinWindow(instant: Instant, zone: ZoneId, window: LocalWindow): Boolean {
    val local = instant.atZone(zone)
    val now = local.hour * 60 + local.minute
    val start = minuteOfDay(window.start)
    val end = minuteOfDay(window.end)
    val weekday = local.dayOfWeek.value
    if (start < end) return weekday in window.days && now >= start && now < end
    val previousDay = if (weekday == 1) 7 else weekday - 1
    return (weekday in window.days && now >= start) || (previousDay in window.days && now < end)
}

fun isWithinAnyWindow(instant: Instant, zone: ZoneId, windows: List<LocalWindow>): Boolean = windows.any { isWithinWindow(instant, zone, it) }

/**
 * Whether a wall-clock time on an ISO weekday falls inside one of the windows: the same rule
 * as [isWithinWindow], without a zone, for a time that will ring in someone else's zone (a
 * wake-up suggested for a friend, whose zone stays private). Same as iOS.
 */
fun isWithinAnyWindow(localTime: String, weekday: Int, windows: List<LocalWindow>): Boolean {
    val now = minuteOfDay(localTime)
    val previousDay = if (weekday == 1) 7 else weekday - 1
    return windows.any { w ->
        val start = minuteOfDay(w.start)
        val end = minuteOfDay(w.end)
        if (start < end) weekday in w.days && now >= start && now < end
        else (weekday in w.days && now >= start) || (previousDay in w.days && now < end)
    }
}

fun isQuietTime(instant: Instant, zone: ZoneId, quiet: QuietHours): Boolean =
    quiet.enabled && isWithinWindow(instant, zone, LocalWindow((1..7).toList(), quiet.start, quiet.end))

data class Occurrence(val localDate: String, val instant: Instant)

fun zonedToInstant(date: LocalDate, hhmm: String, zone: ZoneId): Instant {
    val minutes = minuteOfDay(hhmm)
    return ZonedDateTime.of(LocalDateTime.of(date, LocalTime.of(minutes / 60, minutes % 60)), zone).toInstant()
}

/** Next [count] occurrences strictly after [after]. */
fun nextOccurrences(localTime: String, zone: ZoneId, recurrence: Recurrence, after: Instant, count: Int): List<Occurrence> {
    require(count in 1..64)
    if (recurrence.kind == "once") {
        val date = LocalDate.parse(requireNotNull(recurrence.date))
        val instant = zonedToInstant(date, localTime, zone)
        return if (instant.isAfter(after)) listOf(Occurrence(date.toString(), instant)) else emptyList()
    }
    val days = recurrence.days.orEmpty()
    if (days.isEmpty()) return emptyList()
    val out = ArrayList<Occurrence>()
    var date = after.atZone(zone).toLocalDate().minusDays(1)
    var i = 0
    while (i < 8 * count + 2 && out.size < count) {
        if (date.dayOfWeek.value in days) {
            val instant = zonedToInstant(date, localTime, zone)
            if (instant.isAfter(after)) out += Occurrence(date.toString(), instant)
        }
        date = date.plusDays(1)
        i++
    }
    return out
}

/** ISO weekday (Monday = 1 ... Sunday = 7), matching [DayOfWeek.getValue]. */
fun isoWeekday(date: LocalDate): Int = date.dayOfWeek.value
