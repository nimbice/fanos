package io.github.nimbice.fanos.feature.reader

import io.github.nimbice.fanos.core.content.Block
import io.github.nimbice.fanos.core.content.ChapterContent
import io.github.nimbice.fanos.core.content.Span
import io.github.nimbice.fanos.core.model.ReadingPosition
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ReadingTimeTest {

    private val content = ChapterContent(listOf(Block.Paragraph(listOf(Span("One two three four."))), Block.Paragraph(listOf(Span("Five six — seven.")))))

    @Test
    fun `the words left count from the place, a dash being no word`() {
        assertEquals(7, wordsFrom(content, ReadingPosition(0, 0)))
        assertEquals(5, wordsFrom(content, ReadingPosition(0, 8)))
        assertEquals(3, wordsFrom(content, ReadingPosition(1, 0)))
        assertEquals(4, wordsBetween(content, ReadingPosition(0, 0), ReadingPosition(1, 0)))
        assertEquals(0, wordsBetween(content, ReadingPosition(1, 0), ReadingPosition(0, 0)))
    }

    @Test
    fun `minutes round up, and the speed moves a little towards each stretch read`() {
        assertEquals(0, minutesFor(0, 230))
        assertEquals(1, minutesFor(100, 230))
        assertEquals(3, minutesFor(500, 230))
        // 300 words in a minute: from 230, a little of the way to 300.
        assertEquals(240, readingSpeedAfter(230, 300, 60_000))
        // A jump, a pause, or a glance says nothing.
        assertNull(readingSpeedAfter(230, 9_000, 60_000))
        assertNull(readingSpeedAfter(230, 300, 10 * 60_000))
        assertNull(readingSpeedAfter(230, 5, 60_000))
    }
}
