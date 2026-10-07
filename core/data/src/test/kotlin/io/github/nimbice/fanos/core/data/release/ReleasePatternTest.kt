package io.github.nimbice.fanos.core.data.release

import io.github.nimbice.fanos.core.model.Chapter
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneOffset
import java.util.Locale
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ReleasePatternTest {

    // Thursday 1 October 2026.
    private val today = LocalDate.of(2026, 10, 1)

    /** The days from [from] to [to] that are [on]. */
    private fun days(from: LocalDate, to: LocalDate, vararg on: DayOfWeek): List<LocalDate> =
        generateSequence(from) { it.plusDays(1) }.takeWhile { it <= to }.filter { it.dayOfWeek in on }.toList()

    @Test
    fun `chapters on set days of the week`() {
        val days = days(LocalDate.of(2026, 8, 7), LocalDate.of(2026, 9, 30), DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY, DayOfWeek.FRIDAY)
        val pattern = releasePattern(days, today)
        assertEquals(ReleasePattern.Weekdays(listOf(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY, DayOfWeek.FRIDAY), LocalDate.of(2026, 10, 2)), pattern)
        assertEquals("New chapters usually Mon, Wed and Fri\nNext about tomorrow", releaseText(pattern!!, today, Locale.ENGLISH))
        // A few extra chapters on other days don't hide the pattern.
        val extras = days(LocalDate.of(2026, 8, 20), LocalDate.of(2026, 10, 1), DayOfWeek.TUESDAY, DayOfWeek.THURSDAY, DayOfWeek.SUNDAY) +
            listOf(LocalDate.of(2026, 8, 21), LocalDate.of(2026, 9, 4), LocalDate.of(2026, 9, 9), LocalDate.of(2026, 9, 25))
        assertEquals(
            "New chapters usually Tue, Thu and Sun\nNext about Sunday",
            releaseText(releasePattern(extras, today)!!, today, Locale.ENGLISH),
        )
        // One day a week.
        val fridays = days(LocalDate.of(2026, 7, 1), LocalDate.of(2026, 9, 25), DayOfWeek.FRIDAY)
        assertEquals("New chapters usually on Fridays\nNext about tomorrow", releaseText(releasePattern(fridays, today)!!, today, Locale.ENGLISH))
    }

    @Test
    fun `most days, and every so many days`() {
        val weekdays = days(LocalDate.of(2026, 8, 10), LocalDate.of(2026, 9, 30), *DayOfWeek.entries.take(5).toTypedArray())
        assertEquals(ReleasePattern.MostDays(), releasePattern(weekdays, today))
        val everyThird = generateSequence(LocalDate.of(2026, 8, 6)) { it.plusDays(3) }.takeWhile { it <= LocalDate.of(2026, 9, 29) }.toList()
        assertEquals(ReleasePattern.Every(3, LocalDate.of(2026, 10, 2)), releasePattern(everyThird, today))
        assertEquals("New chapters about every 3 days\nNext about tomorrow", releaseText(ReleasePattern.Every(3, LocalDate.of(2026, 10, 2)), today, Locale.ENGLISH))
    }

    @Test
    fun `no pattern without one, too little to go by, or after a long pause`() {
        val scattered = listOf(1, 2, 9, 13, 14, 25, 33, 34, 40, 51).map { LocalDate.of(2026, 8, 1).plusDays(it.toLong()) }
        assertNull(releasePattern(scattered, LocalDate.of(2026, 9, 22)))
        assertNull(releasePattern(days(LocalDate.of(2026, 9, 14), LocalDate.of(2026, 9, 30), DayOfWeek.MONDAY, DayOfWeek.THURSDAY), today))
        val paused = days(LocalDate.of(2026, 6, 1), LocalDate.of(2026, 8, 1), DayOfWeek.MONDAY, DayOfWeek.THURSDAY)
        assertNull(releasePattern(paused, today))
    }

    @Test
    fun `undated chapters go by when this device first saw them, not the ones that came with the novel`() {
        val added = 1_000_000L
        fun chapter(id: Long, publishedAt: Long?, addedAt: Long) = Chapter(id, 1, "u$id", "C$id", id.toInt(), publishedAt = publishedAt, addedAt = addedAt)
        val day = 24 * 60 * 60 * 1000L
        val chapters = List(50) { chapter(it.toLong(), null, added) } + List(3) { chapter(100L + it, null, added + (it + 1) * day) }
        assertEquals(3, releaseDays(chapters, ZoneOffset.UTC).size)
        val dated = List(10) { chapter(it.toLong(), it * day, added) }
        assertEquals(10, releaseDays(dated, ZoneOffset.UTC).size)
    }

    @Test
    fun `the calendar's days, and its words`() {
        val weekly = ReleasePattern.Weekdays(listOf(DayOfWeek.MONDAY, DayOfWeek.THURSDAY), LocalDate.of(2026, 10, 1))
        assertEquals(listOf(true, false, false, false, true), (0L..4L).map { weekly.dueOn(today.plusDays(it)) })
        val every3 = ReleasePattern.Every(3, LocalDate.of(2026, 10, 2))
        assertEquals(listOf(false, true, false, false, true), (0L..4L).map { every3.dueOn(today.plusDays(it)) })
        assertEquals("Mon and Thu", patternText(weekly, Locale.ENGLISH))
        assertEquals("Fridays", patternText(ReleasePattern.Weekdays(listOf(DayOfWeek.FRIDAY), today), Locale.ENGLISH))
        assertEquals("Every 3 days", patternText(every3, Locale.ENGLISH))
        assertEquals("Most days", patternText(ReleasePattern.MostDays(), Locale.ENGLISH))
        assertEquals("Every day", patternText(ReleasePattern.MostDays(everyDay = true), Locale.ENGLISH))
    }

    @Test
    fun `every so many to so many days, on the earliest day as an early guess`() {
        // Gaps of 6 to 9 days on no set day, the last chapter on Monday 14 September.
        val days = listOf(5, 7, 9, 6, 8, 5, 9, 7).runningFold(LocalDate.of(2026, 7, 20)) { day, gap -> day.plusDays(gap.toLong()) }
        val friday = LocalDate.of(2026, 9, 18)
        val pattern = releasePattern(days, friday)
        assertEquals(ReleasePattern.Between(6, 9, LocalDate.of(2026, 9, 20), LocalDate.of(2026, 9, 23)), pattern)
        pattern as ReleasePattern.Between
        assertEquals(
            "New chapters every 6 to 9 days, no set day\nNext between Sunday and Wednesday, Sunday at the earliest",
            releaseText(pattern, friday, Locale.ENGLISH),
        )
        assertEquals("Every 6 to 9 days", patternText(pattern, Locale.ENGLISH))
        assertEquals("could be Sun to Wed", windowText(pattern, friday, Locale.ENGLISH))
        // Only the earliest day has it on the calendar.
        assertEquals(listOf(false, false, true, false, false, false), (0L..5L).map { pattern.dueOn(friday.plusDays(it)) })
        // Come the earliest day, it could be today.
        val sunday = LocalDate.of(2026, 9, 20)
        val now = releasePattern(days, sunday) as ReleasePattern.Between
        assertEquals("could be today, or as late as Wed", windowText(now, sunday, Locale.ENGLISH))
        assertEquals(
            "New chapters every 6 to 9 days, no set day, around 6\u00A0PM\nNext could be today, or as late as Wednesday",
            releaseText(now, sunday, Locale.ENGLISH, time = ReleaseTime.At(LocalTime.of(18, 0))),
        )
    }

    @Test
    fun `a novel out every day shows after a week, with its chapters a day`() {
        // Two chapters a day, first seen a little over a week ago (a novel only just added, its site giving no dates).
        val days = (0L..8L).flatMap { listOf(LocalDate.of(2026, 9, 22).plusDays(it), LocalDate.of(2026, 9, 22).plusDays(it)) }
        val pattern = releasePattern(days, today)
        assertEquals(ReleasePattern.MostDays(everyDay = true), pattern)
        assertEquals(2, chaptersPerDay(days))
        assertEquals("2 chapters a day", perDayText(pattern!!, 2))
        assertEquals(
            "New chapters every day, 2 a day, around 6\u00A0PM",
            releaseText(pattern, today, Locale.ENGLISH, time = ReleaseTime.At(LocalTime.of(18, 0)), perDay = 2),
        )
        // Two at a time on set days.
        assertEquals("2 chapters at a time", perDayText(ReleasePattern.Weekdays(listOf(DayOfWeek.MONDAY), today), 2))
        assertNull(perDayText(pattern, 1))
        // A few days are not enough to go by.
        assertNull(releasePattern(days.take(8), today))
    }

    @Test
    fun `semi-regular chapters, a day or two apart now and then`() {
        val days = listOf(1, 3, 1, 5, 2, 4, 1, 4, 3).runningFold(LocalDate.of(2026, 9, 3)) { day, gap -> day.plusDays(gap.toLong()) }
        // The last on 27 September; on 28 September the next could be any day to 2 October.
        val pattern = releasePattern(days, LocalDate.of(2026, 9, 28))
        assertEquals(ReleasePattern.Between(1, 4, LocalDate.of(2026, 9, 28), LocalDate.of(2026, 10, 1)), pattern)
    }

    @Test
    fun `the time of day, from the site's own times`() {
        val zone = ZoneOffset.UTC
        fun at(day: Int, hour: Int, minute: Int) = LocalDate.of(2026, 9, 1).plusDays(day.toLong()).atTime(hour, minute).toInstant(ZoneOffset.UTC).toEpochMilli()
        // Three in four within half an hour of one another: about one time, an odd one out or not.
        val steady = listOf(at(0, 18, 0), at(2, 18, 10), at(4, 17, 55), at(7, 18, 5), at(9, 18, 20), at(11, 18, 0), at(14, 9, 0))
        assertEquals(ReleaseTime.At(LocalTime.of(18, 0)), releaseTime(steady, zone))
        // Within four hours: a window, to the half hour.
        val afternoon = listOf(at(0, 14, 10), at(2, 15, 40), at(4, 16, 50), at(7, 14, 30), at(9, 16, 0), at(11, 15, 5))
        assertEquals(ReleaseTime.Window(LocalTime.of(14, 0), LocalTime.of(16, 0)), releaseTime(afternoon, zone))
        // A window can run past midnight.
        val late = listOf(at(0, 23, 10), at(2, 23, 50), at(4, 0, 30), at(7, 0, 10), at(9, 23, 30), at(11, 0, 50))
        assertEquals(ReleaseTime.Window(LocalTime.of(23, 0), LocalTime.of(0, 30)), releaseTime(late, zone))
        // No time when they're all over the day, when the site gives only dates, or with too few to go by.
        assertNull(releaseTime(listOf(at(0, 3, 0), at(2, 9, 0), at(4, 13, 0), at(7, 17, 0), at(9, 20, 0), at(11, 23, 0)), zone))
        assertNull(releaseTime(List(8) { at(it * 2, 0, 0) }, zone))
        assertNull(releaseTime(steady.take(4), zone))
        // Chapters this device first saw, with no date from the site, give no time.
        fun chapter(id: Long, addedAt: Long) = Chapter(id, 1, "u$id", "C$id", id.toInt(), publishedAt = null, addedAt = addedAt)
        assertNull(releaseTime(steady.mapIndexed { i, time -> chapter(i.toLong(), time) }, zone))
    }

    @Test
    fun `the time of day estimated from checks, for sites that give no times`() {
        val zone = ZoneOffset.UTC
        fun at(day: Int, hour: Int, minute: Int) = LocalDate.of(2026, 9, 1).plusDays(day.toLong()).atTime(hour, minute).toInstant(ZoneOffset.UTC).toEpochMilli()
        // Out about 7:15 each day; checks every six hours or so, at different times, find it.
        val windows =
            listOf(
                at(0, 4, 0) to at(0, 10, 0), at(1, 6, 30) to at(1, 12, 0), at(2, 2, 0) to at(2, 7, 40),
                at(3, 5, 10) to at(3, 9, 0), at(4, 7, 0) to at(4, 11, 30), at(5, 1, 0) to at(5, 8, 0),
            )
        // All six windows share 7:00 to 7:40.
        assertEquals(ReleaseTime.Window(LocalTime.of(7, 0), LocalTime.of(8, 0), estimated = true), estimatedReleaseTime(windows, zone))
        assertEquals("likely between 7 and 8\u00A0AM", timeText(estimatedReleaseTime(windows, zone)!!))
        // Two chapters found together count once; too few windows, or ones that don't agree, give nothing.
        assertNull(estimatedReleaseTime(windows.take(3) + windows.take(3), zone))
        val apart = listOf(at(0, 1, 0) to at(0, 3, 0), at(1, 9, 0) to at(1, 11, 0), at(2, 17, 0) to at(2, 19, 0), at(3, 21, 0) to at(3, 23, 0))
        assertNull(estimatedReleaseTime(apart, zone))
        // Checks too far apart say little.
        assertNull(estimatedReleaseTime(List(6) { at(it, 0, 0) to at(it, 20, 0) }, zone))
        // A novel's chapters: the site's times when it gives them, else the estimate.
        fun chapter(id: Int, window: Pair<Long, Long>) = Chapter(id.toLong(), 1, "u$id", "C$id", id, publishedAt = null, addedAt = window.second, seenAfter = window.first)
        assertEquals(estimatedReleaseTime(windows, zone), releaseTime(windows.mapIndexed { i, w -> chapter(i, w) }, zone))
    }

    @Test
    fun `times of day in words`() {
        assertEquals("around 6\u00A0PM", timeText(ReleaseTime.At(LocalTime.of(18, 0))))
        assertEquals("around 6:30\u00A0AM", timeText(ReleaseTime.At(LocalTime.of(6, 30))))
        assertEquals("around 18:00", timeText(ReleaseTime.At(LocalTime.of(18, 0)), is24Hour = true))
        assertEquals("between 2 and 5\u00A0PM", timeText(ReleaseTime.Window(LocalTime.of(14, 0), LocalTime.of(17, 0))))
        assertEquals("between 11\u00A0PM and 1\u00A0AM", timeText(ReleaseTime.Window(LocalTime.of(23, 0), LocalTime.of(1, 0))))
        assertEquals("between 9:30\u00A0AM and 12\u00A0PM", timeText(ReleaseTime.Window(LocalTime.of(9, 30), LocalTime.of(12, 0))))
        assertEquals("between 14:00 and 17:00", timeText(ReleaseTime.Window(LocalTime.of(14, 0), LocalTime.of(17, 0)), is24Hour = true))
        val weekly = ReleasePattern.Weekdays(listOf(DayOfWeek.MONDAY, DayOfWeek.THURSDAY), LocalDate.of(2026, 10, 2))
        assertEquals(
            "New chapters usually Mon and Thu, around 6\u00A0PM\nNext about tomorrow",
            releaseText(weekly, today, Locale.ENGLISH, time = ReleaseTime.At(LocalTime.of(18, 0))),
        )
    }
}
