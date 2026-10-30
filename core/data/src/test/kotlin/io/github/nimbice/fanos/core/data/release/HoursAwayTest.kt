package io.github.nimbice.fanos.core.data.release

import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.Locale
import kotlin.test.assertEquals
import kotlin.test.assertNull

class HoursAwayTest {

    private val zone = ZoneId.of("America/Los_Angeles")

    // Thursday 29 October 2026.
    private val today = LocalDate.of(2026, 10, 29)

    private fun at(day: LocalDate, hour: Int, minute: Int = 0) = ZonedDateTime.of(day.atTime(hour, minute), zone)

    private val sixPm = ReleaseTime.At(LocalTime.of(18, 0))
    private val twoToFive = ReleaseTime.Window(LocalTime.of(14, 0), LocalTime.of(17, 0))

    @Test
    fun `one time, about so many hours`() {
        assertEquals("about 5 hours from now", hoursAway(today, sixPm, at(today, 13, 10)))
        assertEquals("about an hour from now", hoursAway(today, sixPm, at(today, 17, 0)))
        assertEquals("less than an hour from now", hoursAway(today, sixPm, at(today, 17, 40)))
    }

    @Test
    fun `a window, as a range of hours`() {
        assertEquals("4 to 7 hours from now", hoursAway(today, twoToFive, at(today, 10, 0)))
        assertEquals("4 to 5 hours from now", hoursAway(today, ReleaseTime.Window(LocalTime.of(16, 0), LocalTime.of(17, 0)), at(today, 12, 0)))
        // Open now.
        assertEquals("within 2 hours", hoursAway(today, twoToFive, at(today, 15, 0)))
        assertEquals("within an hour", hoursAway(today, twoToFive, at(today, 16, 20)))
    }

    @Test
    fun `only within the next day, and not once it's gone by`() {
        assertEquals("about 23 hours from now", hoursAway(today.plusDays(1), sixPm, at(today, 19, 0)))
        assertNull(hoursAway(today.plusDays(1), sixPm, at(today, 17, 0)))
        assertNull(hoursAway(today, sixPm, at(today, 18, 0)))
        assertNull(hoursAway(today, twoToFive, at(today, 17, 1)))
    }

    @Test
    fun `out most days, today until it's out or gone by, then tomorrow`() {
        val daily = ReleasePattern.MostDays(everyDay = true)

        assertEquals(today, expectedDay(daily, sixPm, emptyList(), at(today, 9)))
        assertEquals(today.plusDays(1), expectedDay(daily, sixPm, listOf(today), at(today, 9)))
        assertEquals(today.plusDays(1), expectedDay(daily, sixPm, emptyList(), at(today, 18, 30)))
        assertNull(expectedDay(ReleasePattern.Between(3, 5, today, today.plusDays(2)), sixPm, emptyList(), at(today, 9)))
    }

    @Test
    fun `the novel's page says it in place of the next day`() {
        val thursdays = ReleasePattern.Weekdays(listOf(DayOfWeek.THURSDAY), next = today)
        val daily = ReleasePattern.MostDays(everyDay = true)

        assertEquals(
            "New chapters usually on Thursdays, around 6 PM\nNext about 5 hours from now",
            releaseText(thursdays, today, Locale.ENGLISH, time = sixPm, away = "about 5 hours from now"),
        )
        assertEquals(
            "New chapters every day, around 6 PM\nNext about 5 hours from now",
            releaseText(daily, today, Locale.ENGLISH, time = sixPm, away = "about 5 hours from now"),
        )
        assertEquals("New chapters every day, around 6 PM", releaseText(daily, today, Locale.ENGLISH, time = sixPm))
    }
}
