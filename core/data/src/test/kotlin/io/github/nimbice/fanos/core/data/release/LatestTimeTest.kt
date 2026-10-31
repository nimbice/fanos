package io.github.nimbice.fanos.core.data.release

import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneOffset
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class LatestTimeTest {

    private val zone = ZoneOffset.UTC

    private fun at(week: Int, hour: Int, minute: Int = 0) =
        LocalDate.of(2026, 9, 3).plusWeeks(week.toLong()).atTime(hour, minute).toInstant(ZoneOffset.UTC).toEpochMilli()

    @Test
    fun `after a clock change, the latest weeks' time`() {
        // Five weeks at 6:00, then the author's clocks change: four at 5:00.
        val weekly = (0..4).map { at(it, 6) } + (5..8).map { at(it, 5) }

        assertEquals(ReleaseTime.At(LocalTime.of(5, 0)), releaseTime(weekly, zone))
    }

    @Test
    fun `not yet four at the new time, the window still`() {
        val weekly = (0..5).map { at(it, 6) } + (6..8).map { at(it, 5) }

        assertEquals(ReleaseTime.Window(LocalTime.of(5, 0), LocalTime.of(6, 0)), releaseTime(weekly, zone))
    }

    @Test
    fun `the latest just past midnight and just before it agree`() {
        val weekly = (0..4).map { at(it, 21) } + listOf(at(5, 23, 50), at(6, 0, 10), at(7, 23, 55), at(8, 0, 5))

        assertEquals(ReleaseTime.At(LocalTime.of(0, 0)), releaseTime(weekly, zone))
    }

    @Test
    fun `chapters out together count as one day`() {
        // Three chapters at once at 9:00 last week, after six weeks at 6:00: one day at 9:00, not yet the time.
        val weekly = (0..5).map { at(it, 6) } + listOf(at(6, 9), at(6, 9, 1), at(6, 9, 2))

        assertNotEquals<ReleaseTime?>(ReleaseTime.At(LocalTime.of(9, 0)), releaseTime(weekly, zone))
    }
}
