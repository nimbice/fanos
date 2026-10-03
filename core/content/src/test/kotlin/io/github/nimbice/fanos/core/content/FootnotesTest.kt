package io.github.nimbice.fanos.core.content

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class FootnotesTest {

    private fun spans(body: String): List<Span> =
        extract("<html><body><div id=\"c\">$body</div></body></html>", content = "#c").leaves.flatMap { leaf ->
            (leaf as? Block.Paragraph)?.spans.orEmpty()
        }

    @Test
    fun `a mark linking to a note further down the page carries the note`() {
        val spans =
            spans(
                """
                <p>He had tempered his Dao Heart<sup id="r1"><a href="#fn1">1</a></sup> for ten years.</p>
                <p>Then it cracked<a href="#fn2" id="r2">[2]</a>.</p>
                <hr>
                <ol class="footnotes">
                  <li id="fn1">A cultivator's resolve. <a href="#r1">↩</a></li>
                  <li id="fn2"><a href="#r2">[2]</a> Not for the first time.</li>
                </ol>
                """,
            )
        // In the chapter's own text (the list's way back to "[2]" marks the line it came from).
        val marks = spans.filter { it.note != null }.take(2)
        assertEquals(listOf("1", "[2]"), marks.map { it.text })
        assertEquals(listOf("A cultivator's resolve.", "Not for the first time."), marks.map { it.note })
    }

    @Test
    fun `a note the page's cleaning would take away is still found`() {
        // The notes' list after the chapter's element, where only the page has it.
        val content =
            extract(
                """
                <html><body>
                <div id="c"><p>The lantern<sup><a href="https://novels.example.com/glass-orchard/chapter-3#n1">*</a></sup> went out.</p></div>
                <div class="notes"><p><a name="n1"></a>A paper lantern, not a glass one.</p></div>
                </body></html>
                """,
                content = "#c",
            )
        val mark = content.leaves.flatMap { (it as Block.Paragraph).spans }.single { it.note != null }
        assertEquals("*", mark.text)
        assertEquals("A paper lantern, not a glass one.", mark.note)
    }

    @Test
    fun `Modern Footnotes' hidden note goes with its mark and out of the text`() {
        val spans =
            spans(
                """
                <p>She bowed to the elder<sup class="modern-footnotes-footnote " data-mfn="1"><a href="javascript:void(0)" role="button">1</a></sup><span id="mfn-content-1" class="modern-footnotes-footnote__note" tabindex="0" data-mfn="1">The sect's oldest member.</span> and left.</p>
                """,
            )
        assertEquals("She bowed to the elder1 and left.", spans.joinToString("") { it.text })
        assertEquals("The sect's oldest member.", spans.single { it.text == "1" }.note)
    }

    @Test
    fun `a mark's title is its note`() {
        val spans = spans("""<p>Qi<span class="tooltip" title="Life energy, roughly.">[i]</span> flowed.</p><p>Five<sup title="Counting the cat.">*</sup> came.</p>""")
        assertEquals(listOf("Counting the cat."), spans.mapNotNull { it.note })
        // Letters are a mark only raised.
        assertNull(spans.single { it.text.contains("[i]") }.note)
    }

    @Test
    fun `links that aren't marks stay links`() {
        val spans =
            spans(
                """
                <p id="top">Read <a href="#top">back to the top</a> or <a href="https://novels.example.com/glass-orchard/chapter-4">2</a>.</p>
                <p><a href="https://elsewhere.example.org/notes#n1">3</a> and <a href="#missing">4</a></p>
                """,
            )
        assertEquals(emptyList(), spans.mapNotNull { it.note })
        assertEquals("https://novels.example.com/glass-orchard/chapter-4", spans.single { it.text == "2" }.href)
    }
}
