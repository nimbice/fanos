// Derived from NovelLibrary's HtmlCleaner (Apache-2.0), modified.
package io.github.nimbice.fanos.core.content

import org.jsoup.Jsoup
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NonChapterTextTest {

    private val paragraph = PARAGRAPH

    private fun page(head: String = "", body: String) = "<html><head>$head</head><body>$body</body></html>"

    // Mathematical italic letters, as LibRead mixes them into "freewebnovel.com".
    private fun italic(c: Char) = String(Character.toChars(0x1D44E + (c - 'a')))

    private val stylised = "freewebnovel.com".map { if (it == '.') "." else italic(it) }.joinToString("")

    @Test
    fun `text hidden by a style rule goes, like Royal Road's stolen-copy notice`() {
        val html = page(
            head = "<style>\n .cmQyNTQ0Yjg3ZTdiMzQzMzk5YjEwYWE4M2U5YTExNTIz{ display: none; speak: never; }\n</style>",
            body = """
                <div class="chapter-inner chapter-content">
                  <p class="cnM3YzZhNTdi" style="text-align: left">$paragraph</p><span class="cmQyNTQ0Yjg3ZTdiMzQzMzk5YjEwYWE4M2U5YTExNTIz"><br>
                  This tale was taken without the author's consent; report it if you find it elsewhere.<br></span>
                  <p class="cnM0NDZmYzdj" style="text-align: left">$paragraph</p>
                </div>
            """,
        )

        assertEquals(listOf(paragraph, paragraph), extract(html, content = ".chapter-content").texts())
        assertEquals(listOf(paragraph, paragraph), extract(html).texts())
    }

    @Test
    fun `text hidden inline or by the hidden attribute goes`() {
        val html = page(
            body = """
                <div id="c">
                  <p>$paragraph</p>
                  <p style="color: red; display:none">Read the latest chapters at example.com</p>
                  <p hidden>Find the original at example.com</p>
                  <div style="visibility: hidden"><span>Visit example.com</span></div>
                  <p>$paragraph</p>
                </div>
            """,
        )

        assertEquals(listOf(paragraph, paragraph), extract(html, content = "#c").texts())
    }

    @Test
    fun `a chapter hidden until a script reveals it is kept`() {
        // Short hidden lines that add up to much of the page, and one long hidden block.
        val locked = (1..8).joinToString("") { "<p class=\"locked\">Line $it of the chapter, hidden until the page's script shows it.</p>" }
        val html = page(
            head = "<style>.locked { display: none }</style>",
            body = """<div id="c"><p>$paragraph</p>$locked<div style="display:none">$paragraph</div></div>""",
        )

        assertEquals(10, extract(html, content = "#c").leaves.size)
    }

    @Test
    fun `rules that hide only on some screens or states are ignored`() {
        val html = page(
            head = """
                <style>
                  /* .note { display: none } */
                  @media (max-width: 600px) { .wide { display: none } }
                  .menu:hover .tip { display: none }
                </style>
            """,
            body = """<div id="c"><p>$paragraph</p><p class="wide">Wide screens only</p><p class="note">A note</p><p class="tip">A tip</p></div>""",
        )

        assertEquals(listOf(paragraph, "Wide screens only", "A note", "A tip"), extract(html, content = "#c").texts())
    }

    @Test
    fun `rules in at-rules that hold other at-rules are ignored too`() {
        val html = page(
            head = """
                <style>
                  @media print { .wide { display: none } @media (max-width: 600px) { .narrow { display: none } } }
                  .tip { display: none }
                </style>
            """,
            body = """<div id="c"><p>$paragraph</p><p class="wide">Wide screens only</p><p class="narrow">Narrow screens only</p><p class="tip">Buy the book at example.com</p></div>""",
        )

        assertEquals(listOf(paragraph, "Wide screens only", "Narrow screens only"), extract(html, content = "#c").texts())
    }

    @Test
    fun `a short hidden chapter of several paragraphs is kept even when the page has more text`() {
        // Three short paragraphs, hidden, on a page whose comments outweigh them; found without a content element.
        val chapter = (1..3).joinToString("") { "<p>Paragraph $it of a very short chapter.</p>" }
        val html = page(
            head = "<style>.chapter { display: none }</style>",
            body = """<div id="c" class="chapter">$chapter</div><div id="comments"><p>$paragraph</p><p>$paragraph</p><p>$paragraph</p></div>""",
        )

        assertEquals((1..3).map { "Paragraph $it of a very short chapter." }, extract(html).texts())
    }

    @Test
    fun `site notices go, while credit lines stay as credits`() {
        val html = page(
            body = """
                <div id="chapter-content">
                  <p><strong>Translator:&nbsp;</strong>Quillfeather&nbsp;<i></i>&nbsp;<strong>Editor:&nbsp;</strong>Inkwell&amp;Sons</p>
                  <p>$paragraph</p>
                  <p>Translator's note: a copper bell is worth ten iron ones.</p>
                  <p>She said that if you find any errors in the report chapter by chapter, you fix them.</p>
                  <div><div>If you find any errors ( Ads popup, ads redirect, broken links, non-standard content, etc.. ), Please let us know &lt; report chapter &gt; so we can fix it as soon as possible.</div></div>
                  <p>Tip: You can use left, right, A and D keyboard keys to browse between chapters.</p>
                </div>
            """,
        )

        val chapter = extract(html, content = "#chapter-content")
        assertEquals(
            listOf(
                "Translator: Quillfeather\nEditor: Inkwell&Sons",
                paragraph,
                "Translator's note: a copper bell is worth ten iron ones.",
                "She said that if you find any errors in the report chapter by chapter, you fix them.",
            ),
            chapter.texts(),
        )
        // A line a role, the site's emphasis kept.
        assertEquals(
            Block.Credit(listOf(Span("Translator: ", Span.BOLD), Span("Quillfeather\n"), Span("Editor: ", Span.BOLD), Span("Inkwell&Sons"))),
            chapter.leaves.first(),
        )
    }

    @Test
    fun `credit lines become one credit, a line each, while translators' notes stay paragraphs`() {
        val html = page(
            body = """
                <div id="c">
                  <p>Translator: Bob</p>
                  <p>TL: Bob | Editor: Alice</p>
                  <div><p>Translated by: Bob</p><p>Edited by: Alice</p></div>
                  <p>$paragraph</p>
                  <p>Translator: the title is a pun in the original, which does not survive in English.</p>
                  <p>Editor: this one took a while, sorry for the wait!</p>
                </div>
            """,
        )

        val chapter = extract(html, content = "#c")
        assertEquals(
            listOf(
                "Translator: Bob\nTL: Bob\nEditor: Alice\nTranslated by: Bob\nEdited by: Alice",
                paragraph,
                "Translator: the title is a pun in the original, which does not survive in English.",
                "Editor: this one took a while, sorry for the wait!",
            ),
            chapter.texts(),
        )
        assertEquals(listOf(true, false, false, false), chapter.leaves.map { it is Block.Credit })
    }

    @Test
    fun `site names spelled in look-alike letters go, the lines they were added to stay`() {
        val mixed = "fre${italic('e')}webnovel.c${italic('o')}m"
        val html = page(
            body = """
                <div id="article">
                  <p>"Mara!" <subtxt>
                     $stylised
                    </subtxt></p>
                  <p>The crowd fell silent.$mixed <em>Slowly.</em></p>
                  <p>He typed example.com into the bar and wrote ${italic('x')} = 2.</p>
                  <p>First line $stylised<br>second line</p>
                </div>
            """,
        )

        assertEquals(
            listOf("\"Mara!\"", "The crowd fell silent. Slowly.", "He typed example.com into the bar and wrote ${italic('x')} = 2.", "First line\nsecond line"),
            extract(html, content = "#article").texts(),
        )
    }

    @Test
    fun `a look-alike site name on its own preformatted line takes only that line's indent`() {
        val html = page(body = "<pre>first line\n    $stylised\nsecond line</pre>")

        assertEquals(listOf(Block.Preformatted("first line\n\nsecond line")), extract(html, content = "body").blocks)
    }

    @Test
    fun `the site name is dropped from the page title`() {
        val royalRoad = Jsoup.parse(
            """<html><head><title>Chapter 12 – The Salt Road - The Ferryman's Daughter | Royal Road</title>
               <meta property="og:site_name" content="Royal Road"></head><body></body></html>""",
        )
        val noSiteName = Jsoup.parse("<html><head><title>Chapter 3 - A Novel | Some Site</title></head><body></body></html>")

        assertEquals("Chapter 12 – The Salt Road - The Ferryman's Daughter", TitleDetection.pageTitle(royalRoad))
        assertEquals("Chapter 3 - A Novel | Some Site", TitleDetection.pageTitle(noSiteName))
    }

    @Test
    fun `the page title heads a chapter only when it has no title of its own`() {
        fun opens(body: String, title: String?, head: String = "") =
            extract(page(head, "<div id=\"c\">$body</div>"), content = "#c", title = title).hasOwnTitle

        val novelFullTitle = "Read The Glass Orchard - Chapter 4492: A Quiet Harvest (Part 2) online free - Novelfull"
        assertTrue(opens("<p></p><div><h4>Chapter 4492: A Quiet Harvest (Part 2)</h4><p>$paragraph</p></div>", novelFullTitle))
        assertTrue(opens("<p><strong>Chapter 025</strong></p><p>The Lantern Test</p><p>$paragraph</p>", "25. The Lantern Test"))
        assertTrue(opens("<p><strong>Prologue: The Wager</strong></p><p>$paragraph</p>", null))
        assertFalse(opens("<p>‘What was any of this even for…?’</p><p>$paragraph</p>", "Chapter 710 – Heavy Weather"))
        assertTrue(opens("<p>25. The Lantern Test</p><p>$paragraph</p>", "25. The Lantern Test"))
        assertTrue(opens("<p><em>Interlude – The Tower</em></p><p>$paragraph</p>", null))
        assertTrue(opens("<img src=\"/cover.png\"><p>Chapter 9<br>The Weir</p><p>$paragraph</p>", null))
        // Credit lines above the title are passed over, and the title is still the chapter's.
        val credited = extract(page(body = "<div id=\"c\"><p>Translator: Bob</p><h3>Chapter 9: The Weir</h3><p>$paragraph</p></div>"), content = "#c")
        assertTrue(credited.hasOwnTitle)
        assertEquals(1, credited.titleLeaf)
        // The novel's name at the top is not the chapter's title, though the page title holds it too.
        assertFalse(opens("<p><strong>The Glass Orchard</strong></p><p>$paragraph</p>", novelFullTitle))
        // Without a chapter title, the page's own serves.
        assertTrue(opens("<p>25. The Lantern Test</p><p>$paragraph</p>", null, head = "<title>25. The Lantern Test | Some Site</title>"))
    }
}
