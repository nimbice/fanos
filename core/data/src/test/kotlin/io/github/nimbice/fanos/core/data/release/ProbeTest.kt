package io.github.nimbice.fanos.core.data.release

import io.github.nimbice.fanos.core.model.Chapter
import org.junit.Test
import java.time.Duration
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneOffset
import java.time.ZonedDateTime
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ProbeTest {

    private val zone = ZoneOffset.UTC
    private val today = LocalDate.of(2026, 10, 30)
    private val daily = ReleasePattern.MostDays(everyDay = true)

    private fun at(day: LocalDate, hour: Int, minute: Int = 0) = ZonedDateTime.of(day.atTime(hour, minute), zone)

    private fun millis(time: ZonedDateTime) = time.toInstant().toEpochMilli()

    @Test
    fun `halfway through what's left of the day after the last check`() {
        assertEquals(at(today, 14), nextProbe(daily, null, emptyList(), millis(at(today, 4)), at(today, 5)))
    }

    @Test
    fun `halfway through where the checks put it`() {
        val range = ReleaseRange(4 * 60 + 5, 6 * 60)

        // 4:05 to 10:05, the last check at 4:05: 7:05; that found nothing, 8:35.
        assertEquals(at(today, 7, 5), nextProbe(daily, range, emptyList(), millis(at(today, 4, 5)), at(today, 5)))
        assertEquals(at(today, 8, 35), nextProbe(daily, range, emptyList(), millis(at(today, 7, 5)), at(today, 7, 6)))
    }

    @Test
    fun `out today, tomorrow's`() {
        val range = ReleaseRange(4 * 60 + 5, 6 * 60)

        assertEquals(at(today.plusDays(1), 7, 5), nextProbe(daily, range, listOf(today), millis(at(today, 9)), at(today, 9)))
    }

    @Test
    fun `halfway gone by unchecked, soon`() {
        assertEquals(at(today, 16, 15), nextProbe(daily, null, emptyList(), millis(at(today, 4)), at(today, 16)))
    }

    @Test
    fun `none once it's narrow, or with no set day`() {
        assertNull(nextProbe(daily, ReleaseRange(9 * 60 + 40, 25), emptyList(), null, at(today, 5)))
        // Two checks 20 minutes apart either side of where it's due: nothing left to halve today.
        assertEquals(at(today.plusDays(1), 7, 5), nextProbe(daily, ReleaseRange(4 * 60 + 5, 6 * 60), emptyList(), millis(at(today, 9, 45)), at(today, 9, 46)))
        assertNull(nextProbe(ReleasePattern.Between(2, 4, today, today.plusDays(2)), null, emptyList(), null, at(today, 5)))
    }

    @Test
    fun `a regular check coming within the hour, while there's still something to halve`() {
        val regular = millis(at(today, 10, 5))
        val sixHours = Duration.ofHours(6).toMillis()
        val evening = at(today, 20)

        assertTrue(regularCheckSoon(at(today, 9, 30), evening, regular, sixHours))
        assertTrue(!regularCheckSoon(at(today, 8, 30), evening, regular, sixHours))
        assertTrue(!regularCheckSoon(at(today, 10, 30), evening, regular, sixHours))
        assertTrue(regularCheckSoon(at(today, 15, 30), evening, regular, sixHours))
        // At the end of what's left, it can't tell anything new.
        assertTrue(!regularCheckSoon(at(today, 9, 30), at(today, 10, 5), regular, sixHours))
    }

    /**
     * A chapter out at 10:00 every day, library updates at 4:05, 10:05, 16:05 and 22:05: every window the updates give is
     * 4:05 to 10:05, too wide to say a time. Probing finds it within a few days, a few checks a day.
     */
    @Test
    fun `probing pins down a daily chapter the library updates can't`() {
        val out = LocalTime.of(10, 0)
        val regularTimes = listOf(LocalTime.of(4, 5), LocalTime.of(10, 5), LocalTime.of(16, 5), LocalTime.of(22, 5))
        val start = today.minusDays(10)
        val windows = mutableListOf<Pair<Long, Long>>()
        val released = mutableListOf<LocalDate>()
        var lastChecked = millis(at(start, 0, 5))
        fun check(time: ZonedDateTime) {
            val day = time.toLocalDate()
            if (day !in released && !time.toLocalTime().isBefore(out)) {
                windows += lastChecked to millis(time)
                released += day
            }
            lastChecked = millis(time)
        }
        fun nextRegular(after: ZonedDateTime) =
            generateSequence(after.toLocalDate()) { it.plusDays(1) }.flatMap { day -> regularTimes.map { at(day, it.hour, it.minute) } }.first { it.isAfter(after) }

        // Ten days of library updates alone: a window each day, the same one.
        var now = at(start, 0, 5)
        while (now.toLocalDate() < today) {
            now = nextRegular(now)
            check(now)
        }
        assertEquals(ReleaseRange(4 * 60 + 5, 6 * 60), estimatedReleaseRange(windows, zone))
        assertNull(estimatedReleaseTime(windows, zone))

        // From today, probing too.
        val probes = mutableMapOf<LocalDate, Int>()
        while (now.toLocalDate() < today.plusDays(4)) {
            val regular = nextRegular(now)
            val probe =
                nextProbe(daily, estimatedReleaseRange(windows, zone), released, lastChecked, now) { probe, until ->
                    regularCheckSoon(probe, until, millis(regular), Duration.ofHours(6).toMillis())
                }
            now = if (probe != null && probe.isBefore(regular)) probe.also { probes.merge(it.toLocalDate(), 1, Int::plus) } else regular
            check(now)
        }
        val time = assertNotNull(estimatedReleaseTime(windows, zone)) as ReleaseTime.Window
        assertTrue(!out.isBefore(time.from) && !out.isAfter(time.to), "$time")
        assertTrue(Duration.between(time.from, time.to) <= Duration.ofHours(1), "$time")
        assertTrue(probes.values.all { it <= 5 }, "$probes")
    }

    @Test
    fun `a site that dates its chapters without a time goes by the checks`() {
        fun utc(day: Int, hour: Int, minute: Int = 0) = millis(at(today.minusDays(day.toLong()), hour, minute))
        val chapters =
            (1..6).map { day ->
                Chapter(day.toLong(), 1, "u$day", "C$day", day, publishedAt = utc(day, 0), addedAt = utc(day, 7, 40), seenAfter = utc(day, 6, 50))
            }

        assertEquals(ReleaseTime.Window(LocalTime.of(6, 30), LocalTime.of(8, 0), estimated = true), releaseTime(chapters, zone))
    }
}
