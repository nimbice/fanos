package io.github.nimbice.fanos.core.content

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class ReplacementsTest {

    private fun paragraph(vararg spans: Span) = Block.Paragraph(spans.toList())

    private fun ChapterContent.texts() = leaves.map { it.text() }

    @Test
    fun `whole words, whatever the capitals, unless asked`() {
        val content = ChapterContent(listOf(paragraph(Span("Lin Feng met lin feng; Lin Fengyu stayed."))))
        assertEquals(listOf("Lin Fan met Lin Fan; Lin Fengyu stayed."), content.replaced(listOf(TextRule("Lin Feng", "Lin Fan"))).texts())
        assertEquals(listOf("Lin Fan met lin feng; Lin Fengyu stayed."), content.replaced(listOf(TextRule("Lin Feng", "Lin Fan", matchCase = true))).texts())
        assertEquals(listOf("Lin Fan met Lin Fan; Lin Fanyu stayed."), content.replaced(listOf(TextRule("Lin Feng", "Lin Fan", wholeWord = false))).texts())
    }

    @Test
    fun `every kind of text, styles kept, and nothing done without rules`() {
        val content =
            ChapterContent(
                listOf(
                    Block.Heading(1, listOf(Span("Teh Gate"))),
                    Block.Box(listOf(paragraph(Span("teh ", style = Span.BOLD), Span("end")))),
                    Block.Table(listOf(listOf(TableCell(listOf(paragraph(Span("teh"))))))),
                ),
            )
        val fixed = content.replaced(listOf(TextRule("teh", "the")))
        assertEquals(listOf("the Gate", "the end", "the"), fixed.texts())
        assertEquals(Span.BOLD, (fixed.leaves[1] as Block.Paragraph).spans[0].style)
        assertSame(content, content.replaced(emptyList()))
        // Replacements with characters a regex would read otherwise stay as they are.
        assertEquals(listOf("$1 Gate", "$1 end", "$1"), content.replaced(listOf(TextRule("teh", "$1"))).texts())
    }
}
