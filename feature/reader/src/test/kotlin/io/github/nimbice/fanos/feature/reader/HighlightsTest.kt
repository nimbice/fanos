package io.github.nimbice.fanos.feature.reader

import io.github.nimbice.fanos.core.content.Block
import io.github.nimbice.fanos.core.content.ChapterContent
import io.github.nimbice.fanos.core.content.Span
import io.github.nimbice.fanos.core.content.text
import io.github.nimbice.fanos.core.model.Highlight
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HighlightsTest {

    private fun paragraph(text: String) = Block.Paragraph(listOf(Span(text)))

    private fun highlight(block: Int, start: Int, text: String) = Highlight(1, 7, "Chapter", block, start, start + text.length, text, 0, null, 0)

    @Test
    fun `a highlight shows where it was kept, or where its text has moved to in its paragraph`() {
        val content = ChapterContent(listOf(paragraph("Viola woke. She was safe."), paragraph("Or was she?")))

        assertEquals(12 to 25, placeOf(highlight(0, 12, "She was safe."), content)?.let { it.start to it.end })
        // A word replaced before it moved it along.
        assertEquals(12 to 25, placeOf(highlight(0, 10, "She was safe."), content)?.let { it.start to it.end })
        // Gone from its paragraph, or the paragraph gone: not shown.
        assertNull(placeOf(highlight(0, 0, "Zeke slept."), content))
        assertNull(placeOf(highlight(5, 0, "Or was she?"), content))
    }

    @Test
    fun `a highlight running on into the next paragraph shows across both while its text is still there`() {
        val content = ChapterContent(listOf(paragraph("Viola woke. She was safe."), paragraph("Or was she? Zeke laughed.")))
        val both = Highlight(2, 7, "Chapter", 0, 12, 11, "She was safe.\nOr was she?", 0, null, 0, endBlock = 1)
        assertEquals("She was safe.\nOr was she?", textBetween(0, 12, 1, 11) { content.leaves.getOrNull(it)?.text() })
        val shown = placeOf(both, content)!!
        assertEquals(12 to 11, shown.start to shown.end)
        assertTrue(shown.covers(0, 12))
        assertTrue(shown.covers(1, 3))
        assertFalse(shown.covers(0, 11))
        assertFalse(shown.covers(1, 11))
        // Its text changed: not shown.
        assertNull(placeOf(both.copy(text = "She was safe.\nOr was he?"), content))
    }

    @Test
    fun `a quote shared says the novel and chapter it's from`() {
        assertEquals("“in passing”\n— Trinity of Magic, Dreamwalker II", quoteText("in passing", "Trinity of Magic", "Dreamwalker II"))
        assertEquals("“in passing”", quoteText("in passing", "", ""))
    }

    @Test
    fun `brightness runs on a curve, and the slider's lowest part dims past the screen's lowest`() {
        assertEquals(1f, screenOf(1f))
        assertEquals(0.01f, screenOf(0.1f))
        assertEquals(0f, shadeOf(0.5f))
        assertTrue(shadeOf(0f) > shadeOf(0.1f))
        for (screen in listOf(0.04f, 0.25f, 0.6f, 1f)) assertEquals(screen, screenOf(sliderOf(screen)), 0.001f)
    }

    @Test
    fun `copying and sharing leave footnote marks out`() {
        val leaves =
            listOf(
                Block.Paragraph(listOf(Span("The ferrymen lit"), Span("[2]", href = Span.NOTE + "At dusk."), Span(" one by one."))),
                Block.Paragraph(listOf(Span("The river "), Span("1", Span.SUPERSCRIPT, Span.NOTE + "The Kesh."), Span(" rose."))),
            )
        assertEquals("The ferrymen lit one by one.\nThe river rose.", unmarkedBetween(leaves, 0, 0, 1, leaves[1].text().length))
        // Part way into a paragraph, the mark's place counted.
        assertEquals("lit one", unmarkedBetween(leaves, 0, 13, 0, 23))
    }
}
