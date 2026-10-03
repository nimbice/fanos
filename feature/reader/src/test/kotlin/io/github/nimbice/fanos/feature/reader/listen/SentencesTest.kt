package io.github.nimbice.fanos.feature.reader.listen

import io.github.nimbice.fanos.core.content.Block
import io.github.nimbice.fanos.core.content.ChapterContent
import io.github.nimbice.fanos.core.content.Span
import io.github.nimbice.fanos.core.content.TableCell
import io.github.nimbice.fanos.core.model.Pronunciation
import io.github.nimbice.fanos.core.model.ReadingPosition
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Locale

class SentencesTest {

    private fun paragraph(text: String) = Block.Paragraph(listOf(Span(text)))

    @Test
    fun `paragraphs are cut into sentences, each where it is in its leaf`() {
        val content = ChapterContent(listOf(paragraph("Viola was not dreaming. She knew that much."), paragraph("It was odd.")))
        val sentences = Sentences.of(content, title = null, Locale.ENGLISH)
        assertEquals(listOf("Viola was not dreaming.", "She knew that much.", "It was odd."), sentences.map { it.text })
        assertEquals(listOf(0 to 0, 0 to 24, 1 to 0), sentences.map { it.leaf to it.start })
        assertEquals("She knew that much.", "Viola was not dreaming. She knew that much.".substring(sentences[1].start, sentences[1].end))
    }

    @Test
    fun `a long press picks the sentence it's in, without the space around it`() {
        val text = "Viola was not dreaming.  She knew that much. "
        assertEquals("Viola was not dreaming.", text.substring(Sentences.around(text, 3, Locale.ENGLISH)!!))
        assertEquals("She knew that much.", text.substring(Sentences.around(text, 25, Locale.ENGLISH)!!))
        assertEquals("She knew that much.", text.substring(Sentences.around(text, text.length, Locale.ENGLISH)!!))
        assertEquals(null, Sentences.around("   ", 1, Locale.ENGLISH))
    }

    @Test
    fun `a look-up takes the word pressed, and nothing between words`() {
        val text = "Zeke's heart hammered, twice."
        assertEquals("hammered", text.substring(Sentences.wordAt(text, 15, Locale.ENGLISH)!!))
        assertEquals("Zeke's", text.substring(Sentences.wordAt(text, 2, Locale.ENGLISH)!!))
        assertEquals(null, Sentences.wordAt(text, 21, Locale.ENGLISH))
    }

    @Test
    fun `the title comes first when the chapter has none of its own`() {
        val content = ChapterContent(listOf(paragraph("Text.")))
        assertEquals(listOf(-1 to "Chapter 22: Dreamwalker II", 0 to "Text."), Sentences.of(content, "Chapter 22: Dreamwalker II", Locale.ENGLISH).map { it.leaf to it.text })
        val titled = ChapterContent(listOf(Block.Heading(1, listOf(Span("Chapter 22"))), paragraph("Text.")), hasOwnTitle = true)
        assertEquals(listOf(0 to "Chapter 22", 1 to "Text."), Sentences.of(titled, "Chapter 22", Locale.ENGLISH).map { it.leaf to it.text })
    }

    @Test
    fun `tables, credits, breaks and rows of stars aren't read, but their leaves still count`() {
        val table = Block.Table(listOf(listOf(TableCell(listOf(paragraph("Strength"))), TableCell(listOf(paragraph("12"))))))
        val content =
            ChapterContent(
                listOf(Block.Credit(listOf(Span("Translator: Lamp"))), table, Block.SceneBreak, paragraph("* * *"), Block.Box(listOf(paragraph("Level up!"))), paragraph("After.")),
            )
        assertEquals(listOf(5 to "Level up!", 6 to "After."), Sentences.of(content, title = null, Locale.ENGLISH).map { it.leaf to it.text })
    }

    @Test
    fun `reading picks up at the sentence the place is in`() {
        val sentences = Sentences.of(ChapterContent(listOf(paragraph("One. Two. Three."), paragraph("Four."))), null, Locale.ENGLISH)
        assertEquals(0, Sentences.indexAt(sentences, null))
        assertEquals(1, Sentences.indexAt(sentences, ReadingPosition(0, 6)))
        assertEquals(2, Sentences.indexAt(sentences, ReadingPosition(0, 9)))
        assertEquals(3, Sentences.indexAt(sentences, ReadingPosition(1, 0)))
        assertEquals(3, Sentences.indexAt(sentences, ReadingPosition(9, 0)))
    }

    @Test
    fun `pronunciations replace whole words, whatever their capitals`() {
        val said = Sentences.spoken("Zeke met Zekel. zeke smiled.", listOf(Pronunciation("Zeke", "Zeek")))
        assertEquals("Zeek met Zekel. Zeek smiled.", said)
    }
}
