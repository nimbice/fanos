package io.github.nimbice.fanos.core.data.release

import io.github.nimbice.fanos.core.model.Chapter
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate
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
        assertEquals(ReleasePattern.MostDays, releasePattern(weekdays, today))
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
        assertEquals("Most days", patternText(ReleasePattern.MostDays, Locale.ENGLISH))
    }
}
