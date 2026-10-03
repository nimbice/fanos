package io.github.nimbice.fanos.feature.reader.text

import io.github.nimbice.fanos.core.content.Block
import io.github.nimbice.fanos.core.content.ChapterContent
import io.github.nimbice.fanos.core.content.Span
import io.github.nimbice.fanos.core.content.TableCell
import io.github.nimbice.fanos.core.model.ReadingPosition
import org.junit.Assert.assertEquals
import org.junit.Test

class FindTest {

    private fun paragraph(text: String) = Block.Paragraph(listOf(Span(text)))

    @Test
    fun `every match, in reading order, whatever the capitals and quotes`() {
        val content = ChapterContent(listOf(paragraph("Zeke met zeke."), paragraph("Zeke’s turn."), paragraph("None here.")))
        assertEquals(listOf(Match(0, 0, 4), Match(0, 9, 13), Match(1, 0, 4)), findIn(content, null, " zeke "))
        assertEquals(listOf(Match(1, 0, 6)), findIn(content, null, "Zeke's"))
        assertEquals(emptyList<Match>(), findIn(content, null, "   "))
    }

    @Test
    fun `the title above a chapter is searched, tables aren't`() {
        val table = Block.Table(listOf(listOf(TableCell(listOf(paragraph("Dreamwalker stats"))))))
        val content = ChapterContent(listOf(table, paragraph("A Dreamwalker woke.")))
        assertEquals(listOf(Match(-1, 0, 11), Match(1, 2, 13)), findIn(content, "Dreamwalker II", "dreamwalker"))
    }

    @Test
    fun `a search starts at the first match from where the reader is`() {
        val matches = listOf(Match(0, 0, 4), Match(2, 5, 9), Match(4, 0, 4))
        assertEquals(0, firstFrom(matches, null))
        assertEquals(1, firstFrom(matches, ReadingPosition(1, 0)))
        assertEquals(1, firstFrom(matches, ReadingPosition(2, 6)))
        assertEquals(0, firstFrom(matches, ReadingPosition(9, 0)))
    }
}
