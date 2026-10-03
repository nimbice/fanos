package io.github.nimbice.fanos.core.data.repository

import io.github.nimbice.fanos.core.database.entity.ReadMarkRow
import io.github.nimbice.fanos.core.database.entity.ReadingTimeEntity
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneOffset

class ReadingStatsMathTest {

    private val today = LocalDate.of(2026, 9, 30)
    private val zone = ZoneOffset.UTC

    private fun at(date: LocalDate, minute: Int = 0) = date.atStartOfDay(zone).toInstant().toEpochMilli() + minute * 60_000L

    @Test
    fun `the streak runs back from today, or yesterday while today has nothing yet`() {
        val marks = listOf(1L to today.minusDays(1), 1L to today.minusDays(2), 1L to today.minusDays(4)).mapIndexed { i, (novel, date) -> ReadMarkRow(novel, at(date, i)) }
        assertEquals(2, ReadingStatsMath.of(emptyList(), marks, today, zone).streak)
        val time = listOf(ReadingTimeEntity(today.toEpochDay(), 1, 120))
        assertEquals(3, ReadingStatsMath.of(time, marks, today, zone).streak)
        // Under a minute of reading and no chapter doesn't keep it going.
        assertEquals(2, ReadingStatsMath.of(listOf(ReadingTimeEntity(today.toEpochDay(), 1, 30)), marks, today, zone).streak)
    }

    @Test
    fun `the longest run is the most days in a row there have been`() {
        val days = listOf(10L, 11, 12, 13, 20, 21, 2, 1)
        val marks = days.mapIndexed { i, back -> ReadMarkRow(1, at(today.minusDays(back), i)) }
        val stats = ReadingStatsMath.of(emptyList(), marks, today, zone)
        assertEquals(2, stats.streak)
        assertEquals(4, stats.longestStreak)
    }

    @Test
    fun `chapters marked read together don't count, chapters read one by one do`() {
        val bulk = (1..40).map { ReadMarkRow(2, at(today, 5)) }
        val read = listOf(ReadMarkRow(1, at(today, 1)), ReadMarkRow(1, at(today, 9)), ReadMarkRow(2, at(today, 20)))
        val stats = ReadingStatsMath.of(emptyList(), bulk + read, today, zone)
        assertEquals(3, stats.chaptersThisMonth)
        assertEquals(listOf(1L to 2, 2L to 1), stats.byNovel.map { it.novelId to it.chapters })
    }

    @Test
    fun `this month, this week and the novels by time`() {
        val time =
            listOf(
                ReadingTimeEntity(today.toEpochDay(), 1, 600),
                ReadingTimeEntity(today.minusDays(3).toEpochDay(), 2, 1800),
                ReadingTimeEntity(today.minusDays(40).toEpochDay(), 1, 9000),
            )
        val stats = ReadingStatsMath.of(time, emptyList(), today, zone)
        assertEquals(2400L, stats.secondsThisMonth)
        assertEquals(listOf(0L, 0L, 0L, 1800L, 0L, 0L, 600L), stats.week.map { it.seconds })
        assertEquals(today, stats.week.last().date)
        assertEquals(listOf(2L, 1L), stats.byNovel.map { it.novelId })
    }
}
