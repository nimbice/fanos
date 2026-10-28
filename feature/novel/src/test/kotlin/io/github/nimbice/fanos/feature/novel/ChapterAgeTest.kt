package io.github.nimbice.fanos.feature.novel

import io.github.nimbice.fanos.core.model.Chapter
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ChapterAgeTest {

    private val zone = ZoneId.of("America/Los_Angeles")
    private val today = LocalDate.of(2026, 10, 28)

    private fun at(time: LocalDateTime) = time.atZone(zone).toInstant().toEpochMilli()

    private fun chapter(publishedAt: Long? = null, addedAt: Long? = null, seenAfter: Long? = null) =
        Chapter(id = 1, novelId = 1, url = "u", title = "Chapter 1", index = 0, publishedAt = publishedAt, addedAt = addedAt, seenAfter = seenAfter)

    @Test
    fun `days count by the calendar, today from midnight`() {
        assertEquals("Today", daysAgo(today, today))
        assertEquals("1 day ago", daysAgo(today.minusDays(1), today))
        assertEquals("16 days ago", daysAgo(today.minusDays(16), today))
        assertEquals("1,204 days ago", daysAgo(today.minusDays(1204), today))
        // A site's clock a little ahead of the phone's.
        assertEquals("Today", daysAgo(today.plusDays(1), today))
    }

    @Test
    fun `just after midnight is today, just before is yesterday`() {
        assertEquals("Today", daysAgo(chapterDay(chapter(publishedAt = at(today.atTime(0, 1))), zone)!!, today))
        assertEquals("1 day ago", daysAgo(chapterDay(chapter(publishedAt = at(today.minusDays(1).atTime(23, 59))), zone)!!, today))
    }

    @Test
    fun `a date with no time is the site's day, not the evening before`() {
        val midnightUtc = today.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()

        assertEquals(today, chapterDay(chapter(publishedAt = midnightUtc), zone))
    }

    @Test
    fun `the site's date goes before the day it was seen`() {
        val seen = chapter(publishedAt = at(today.minusDays(5).atTime(9, 0)), addedAt = at(today.atTime(8, 0)), seenAfter = at(today.minusDays(1).atTime(8, 0)))

        assertEquals(today.minusDays(5), chapterDay(seen, zone))
    }

    @Test
    fun `an undated chapter found later goes by the day it was first seen`() {
        val found = chapter(addedAt = at(today.minusDays(3).atTime(14, 0)), seenAfter = at(today.minusDays(3).atTime(8, 0)))

        assertEquals(today.minusDays(3), chapterDay(found, zone))
        assertEquals("3 days ago", daysAgo(chapterDay(found, zone)!!, today))
    }

    @Test
    fun `an undated chapter the novel came with says nothing`() {
        assertNull(chapterDay(chapter(addedAt = at(today.minusDays(3).atTime(14, 0))), zone))
        assertNull(chapterAge(chapter(addedAt = at(today.minusDays(3).atTime(14, 0))), today, zone))
        assertNull(chapterDay(chapter(), zone))
    }
}
