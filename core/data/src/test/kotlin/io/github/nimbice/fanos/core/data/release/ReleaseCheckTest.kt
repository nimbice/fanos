package io.github.nimbice.fanos.core.data.release

import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ReleaseCheckTest {

    private val zone = ZoneId.of("America/Los_Angeles")

    // Thursday 29 October 2026.
    private val today = LocalDate.of(2026, 10, 29)

    private fun at(day: LocalDate, hour: Int, minute: Int = 0) = ZonedDateTime.of(day.atTime(hour, minute), zone)

    private val sixPm = ReleaseTime.At(LocalTime.of(18, 0))

    @Test
    fun `half an hour after the time on the day it's due`() {
        val thursdays = ReleasePattern.Weekdays(listOf(DayOfWeek.THURSDAY), next = today)

        assertEquals(at(today, 18, 30), nextReleaseCheck(thursdays, sixPm, emptyList(), at(today, 9)))
    }

    @Test
    fun `a window is checked half an hour after it closes`() {
        val daily = ReleasePattern.MostDays(everyDay = true)
        val window = ReleaseTime.Window(LocalTime.of(14, 0), LocalTime.of(17, 0), estimated = true)

        assertEquals(at(today, 17, 30), nextReleaseCheck(daily, window, emptyList(), at(today, 9)))
    }

    @Test
    fun `a window past midnight closes the next day`() {
        val daily = ReleasePattern.MostDays(everyDay = true)
        val window = ReleaseTime.Window(LocalTime.of(23, 0), LocalTime.of(1, 0))

        assertEquals(at(today.plusDays(1), 1, 30), nextReleaseCheck(daily, window, emptyList(), at(today, 9)))
    }

    @Test
    fun `not there yet, again every half hour for two hours`() {
        val thursdays = ReleasePattern.Weekdays(listOf(DayOfWeek.THURSDAY), next = today)

        assertEquals(at(today, 19, 0), nextReleaseCheck(thursdays, sixPm, emptyList(), at(today, 18, 31)))
        assertEquals(at(today, 20, 30), nextReleaseCheck(thursdays, sixPm, emptyList(), at(today, 20, 1)))
    }

    @Test
    fun `two hours on, it's given up until the next day it's due`() {
        val monThu = ReleasePattern.Weekdays(listOf(DayOfWeek.MONDAY, DayOfWeek.THURSDAY), next = today)

        // Thursday's checks ran 6:30 to 8:30: next Monday's.
        assertEquals(at(LocalDate.of(2026, 11, 2), 18, 30), nextReleaseCheck(monThu, sixPm, emptyList(), at(today, 20, 30)))
    }

    @Test
    fun `found, the checks after it stop`() {
        val monThu = ReleasePattern.Weekdays(listOf(DayOfWeek.MONDAY, DayOfWeek.THURSDAY), next = today)

        assertEquals(at(LocalDate.of(2026, 11, 2), 18, 30), nextReleaseCheck(monThu, sixPm, listOf(today), at(today, 19, 1)))
    }

    @Test
    fun `checks late in the evening go on past midnight`() {
        val thursdays = ReleasePattern.Weekdays(listOf(DayOfWeek.THURSDAY), next = today)
        val elevenPm = ReleaseTime.At(LocalTime.of(23, 0))

        // Friday 12:40 AM, Thursday's chapter not there yet: the 1 AM check.
        assertEquals(at(today.plusDays(1), 1, 0), nextReleaseCheck(thursdays, elevenPm, emptyList(), at(today.plusDays(1), 0, 40)))
    }

    @Test
    fun `within an hour of the regular check, it's left to that one`() {
        val thursdays = ReleasePattern.Weekdays(listOf(DayOfWeek.THURSDAY), next = today)
        val regular = at(today, 19, 15).toInstant().toEpochMilli()
        val sixHours = 6 * 60 * 60 * 1000L
        val near = { at: ZonedDateTime -> nearRegularCheck(at, regular, sixHours) }

        // 6:30 to 8:00 are within the hour of 7:15; 8:30 isn't.
        assertEquals(at(today, 20, 30), nextReleaseCheck(thursdays, sixPm, emptyList(), at(today, 9), near))
    }

    @Test
    fun `a day that already had its chapter is passed over`() {
        val daily = ReleasePattern.MostDays(everyDay = true)

        assertEquals(at(today.plusDays(1), 18, 30), nextReleaseCheck(daily, sixPm, listOf(today), at(today, 9)))
    }

    @Test
    fun `every so many days, from the next one due`() {
        val everyThird = ReleasePattern.Every(3, next = today.plusDays(2))

        assertEquals(at(today.plusDays(2), 18, 30), nextReleaseCheck(everyThird, sixPm, emptyList(), at(today, 9)))
        assertEquals(at(today.plusDays(5), 18, 30), nextReleaseCheck(everyThird, sixPm, emptyList(), at(today.plusDays(2), 21)))
    }

    @Test
    fun `no check without a day or a time to go by`() {
        val between = ReleasePattern.Between(3, 5, from = today, to = today.plusDays(2))
        val thursdays = ReleasePattern.Weekdays(listOf(DayOfWeek.THURSDAY), next = today)

        assertNull(nextReleaseCheck(between, sixPm, emptyList(), at(today, 9)))
        assertNull(nextReleaseCheck(thursdays, null, emptyList(), at(today, 9)))
    }

    @Test
    fun `near a regular check, the next one or a later one`() {
        val hour = 60 * 60 * 1000L
        val regular = at(today, 12).toInstant().toEpochMilli()

        assertEquals(true, nearRegularCheck(at(today, 11, 0), regular, 6 * hour))
        assertEquals(false, nearRegularCheck(at(today, 10, 59), regular, 6 * hour))
        assertEquals(true, nearRegularCheck(at(today, 18, 45), regular, 6 * hour))
        assertEquals(false, nearRegularCheck(at(today, 15, 0), regular, 6 * hour))
        // Not a periodic one: only that once.
        assertEquals(false, nearRegularCheck(at(today, 18, 0), regular, 0))
    }
}
