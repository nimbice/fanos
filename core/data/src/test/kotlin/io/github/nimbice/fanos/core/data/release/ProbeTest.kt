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

    /**
     * A chapter out at a time that wanders, before noon to past one, now and then in the evening (a site that copies
     * another's, a few hours after): the windows the checks gave one novel over four weeks (Pacific time). No minute
     * is in three in four of them, so the probes used to search the whole day: one at noon, the rest left to the library
     * updates, windows two hours wide or more, never enough to tell. Where they crowd says where to probe, and in a
     * couple of weeks, when.
     */
    @Test
    fun `a time that wanders is probed where the windows crowd, and soon told`() {
        val start = today.minusDays(29)
        val windows =
            listOf(
                "0 09:23 12:21", "1 10:52 13:00", "2 10:06 12:22", "3 10:21 12:13", "4 11:13 14:04", "5 11:17 12:44",
                "6 07:28 13:27", "7 18:13 20:53", "8 11:05 14:28", "9 09:36 13:23", "10 16:50 22:54", "11 11:57 13:49",
                "12 11:55 13:55", "13 07:03 11:12", "13 12:22 13:25", "13 18:23 23:49", "14 08:51 12:27", "14 12:27 13:23",
                "15 18:42 00:10", "16 10:21 13:20", "17 13:15 16:42", "18 11:10 14:42", "19 12:53 15:17", "20 12:58 13:51",
                "21 09:07 13:06", "22 10:05 14:03", "23 11:33 13:16", "24 11:30 16:48", "25 11:29 15:20", "26 11:30 13:41",
                "27 11:33 13:54", "28 14:19 19:30",
            ).map { line ->
                val (day, after, found) = line.split(" ")
                val from = at(start.plusDays(day.toLong()), LocalTime.parse(after).hour, LocalTime.parse(after).minute)
                val to = LocalTime.parse(found).let { at(start.plusDays(day.toLong()), it.hour, it.minute) }.let { if (it.isBefore(from)) it.plusDays(1) else it }
                millis(from) to millis(to)
            }.toMutableList()
        assertNull(estimatedReleaseTime(windows, zone))
        val range = assertNotNull(estimatedReleaseRange(windows, zone))
        // About 9:45 to 14:35: three in four of where the chapters likely came out.
        assertTrue(range.start in 9 * 60 + 30..10 * 60 && range.minutes in 4 * 60 + 30..5 * 60, "$range")

        // Three weeks on: out at these times in turn, one day in seven in the evening; library updates every six hours.
        val outs = listOf("11:50", "12:20", "12:55", "11:40", "13:25", "12:05", "19:10").map(LocalTime::parse)
        val regularTimes = listOf(LocalTime.of(2, 5), LocalTime.of(8, 5), LocalTime.of(14, 5), LocalTime.of(20, 5))
        val released = (0..28).map { start.plusDays(it.toLong()) }.toMutableList()
        var lastChecked = millis(at(today, 0, 5))
        fun check(time: ZonedDateTime) {
            val day = time.toLocalDate()
            val out = outs[(day.toEpochDay() % outs.size).toInt()]
            if (day !in released && !time.toLocalTime().isBefore(out)) {
                windows += lastChecked to millis(time)
                released += day
            }
            lastChecked = millis(time)
        }
        fun nextRegular(after: ZonedDateTime) =
            generateSequence(after.toLocalDate()) { it.plusDays(1) }.flatMap { day -> regularTimes.map { at(day, it.hour, it.minute) } }.first { it.isAfter(after) }
        var now = at(today, 0, 5)
        val probes = mutableMapOf<LocalDate, Int>()
        val toldOn = mutableListOf<LocalDate>()
        while (now.toLocalDate() < today.plusDays(21)) {
            val regular = nextRegular(now)
            val probe =
                nextProbe(daily, estimatedReleaseRange(windows, zone), released, lastChecked, now) { probe, until ->
                    regularCheckSoon(probe, until, millis(regular), Duration.ofHours(6).toMillis())
                }
            now = if (probe != null && probe.isBefore(regular)) probe.also { probes.merge(it.toLocalDate(), 1, Int::plus) } else regular
            check(now)
            if (estimatedReleaseTime(windows, zone) != null) toldOn += now.toLocalDate()
        }
        // Each day's window half an hour wide, but on an evening one; told within about ten days, the four weeks of wide
        // windows before still counting.
        val found = windows.drop(32).map { (after, at) -> Duration.ofMillis(at - after) }
        assertTrue(found.count { it <= Duration.ofMinutes(30) } >= found.size * 3 / 4, "$found")
        assertTrue(toldOn.first() <= today.plusDays(10), "first told ${toldOn.firstOrNull()}")
        val time = assertNotNull(estimatedReleaseTime(windows, zone)) as ReleaseTime.Window
        assertTrue(!time.from.isAfter(LocalTime.of(11, 40)) && !time.to.isBefore(LocalTime.of(13, 25)), "$time")
        assertTrue(Duration.between(time.from, time.to) <= Duration.ofMinutes(4 * 60 + 30), "$time")
        // Half-hourly through the stretch it wanders in: more on an evening day, as it isn't found.
        assertTrue(probes.values.all { it <= 8 }, "$probes")
    }
}
