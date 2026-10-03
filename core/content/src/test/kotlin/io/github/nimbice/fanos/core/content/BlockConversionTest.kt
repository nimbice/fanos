package io.github.nimbice.fanos.core.content

import io.github.nimbice.fanos.core.content.Span.Companion.BOLD
import io.github.nimbice.fanos.core.content.Span.Companion.CODE
import io.github.nimbice.fanos.core.content.Span.Companion.ITALIC
import io.github.nimbice.fanos.core.content.Span.Companion.STRIKE
import io.github.nimbice.fanos.core.content.Span.Companion.SUBSCRIPT
import io.github.nimbice.fanos.core.content.Span.Companion.SUPERSCRIPT
import io.github.nimbice.fanos.core.content.Span.Companion.UNDERLINE
import org.jsoup.Jsoup
import org.junit.Test
import kotlin.system.measureNanoTime
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BlockConversionTest {

    private fun blocks(body: String): List<Block> = extract("<html><body><div id=\"c\">$body</div></body></html>", content = "#c").blocks

    @Test
    fun `double line breaks split a chapter held in one element, single ones stay line breaks`() {
        // NovelFull, LibRead and Novel Buddy put whole chapters in one element this way.
        assertEquals(
            listOf(p("First paragraph, running over two source lines."), p("Second paragraph.\nIts second line."), p("Third.")),
            blocks(
                """
                <div></div> First paragraph,
                   running over two source lines.
                <br><br>
                Second paragraph.<br> Its second line. <br>&nbsp;<br>Third.<br><br>
                """,
            ),
        )
        assertEquals(listOf(p("One"), p("Two", align = Align.Center)), blocks("<p>One<br><br><br></p><p style='text-align:center'><br>Two</p>"))
    }

    @Test
    fun `emphasis comes from tags and inline styles, and neighbouring text of one style merges`() {
        assertEquals(
            listOf(
                p(Span("Bold and strong ", BOLD), Span("then plain")),
                p(Span("Italic ", ITALIC), Span("and "), Span("styled italic", ITALIC)),
                p(Span("Heavy and bold ", BOLD), Span("but not this")),
                // Google Docs wraps what it copies in <b style="font-weight:normal">.
                p(Span("Docs bold ", BOLD), Span("and plain")),
                // Spaces stay out of lines, raised text and code, where they would show.
                p(Span("under lined", UNDERLINE), Span(" "), Span("struck deleted through", STRIKE)),
                p(Span("H"), Span("2", SUBSCRIPT), Span("O, 5"), Span("th", SUPERSCRIPT), Span(" and "), Span("code", CODE)),
            ),
            blocks(
                """
                <p><b>Bold</b><strong> and strong</strong> then plain</p>
                <p><i>Italic</i> and <span style="font-style:italic">styled italic</span></p>
                <p><span style="font-weight:700">Heavy</span> <span style="font-weight: bold">and bold</span> <span style="font-weight:400">but not this</span></p>
                <p><b style="font-weight:normal;" id="docs-internal-guid-1"><span style="font-weight:700">Docs bold</span><span> and plain</span></b></p>
                <p><u>under</u> <span style="text-decoration: underline">lined</span> <s>struck</s> <del>deleted</del> <span style="text-decoration:line-through">through</span></p>
                <p>H<sub>2</sub>O, 5<span style="vertical-align: super">th</span> and <code>code</code></p>
                """,
            ),
        )
    }

    @Test
    fun `alignment comes from style, center and align, and is inherited`() {
        assertEquals(
            listOf(
                p("By style", align = Align.Center),
                p("By tag", align = Align.Center),
                p("By attribute", align = Align.Center),
                p("Right", align = Align.End),
                p("Justified", align = Align.Justify),
                p("Back to the start"),
            ),
            blocks(
                """
                <p style="text-align:center">By style</p>
                <center>By tag</center>
                <div align="center"><p>By attribute</p></div>
                <p style="text-align: right">Right</p>
                <p align="justify">Justified</p>
                <div style="text-align:center"><p style="text-align:left">Back to the start</p></div>
                """,
            ),
        )
    }

    @Test
    fun `scene breaks are rules and short rows of symbols, and never at the ends`() {
        for (row in listOf("***", "* * *", "◇◇◇", "~~~", "———", "◆◆◆", "ooo", "o0o", "⁂", "*~*~*")) {
            assertEquals(listOf(p("Before"), Block.SceneBreak, p("After")), blocks("<p>Before</p><p style=\"text-align:center\">$row</p><p>After</p>"), row)
        }
        for (line in listOf("...", "…", "-_-", "^_^", "**", "Ooooh", "000", "2 * 3 = 6")) {
            assertEquals(listOf(p(line)), blocks("<p>$line</p>"), line)
        }
        assertEquals(listOf(p("Before"), Block.SceneBreak, p("After")), blocks("<hr><p>Before</p><hr><p>* * *</p><hr><p>After</p><p>***</p>"))
    }

    @Test
    fun `status windows become boxes, real tables stay tables`() {
        assertEquals(
            listOf(
                p("Before"),
                // Royal Road's editor makes status windows one-cell tables.
                Block.Box(listOf(p(Span("[Status]", BOLD)), p("Level: 4\nStrength: 12"))),
                p("Between"),
                Block.Box(listOf(p("You have learned a skill."), p("Quest updated."), p("Mana restored."))),
                p("After"),
                Block.Table(
                    listOf(
                        listOf(TableCell(listOf(p("Skill")), header = true), TableCell(listOf(p("Rank")), header = true)),
                        listOf(TableCell(listOf(p("Swordplay")), colspan = 2, rowspan = 3)),
                    ),
                ),
            ),
            blocks(
                """
                <p>Before</p>
                <table style="border-collapse: collapse; width: 100%;" border="1"><tbody><tr><td style="width: 100%;">
                  <p><strong>[Status]</strong></p><p>Level: 4<br>Strength: 12</p>
                </td></tr></tbody></table>
                <p>Between</p>
                <div style="border: 1px solid #ccc; padding: 4px">You have learned a skill.</div>
                <div class="system-message">Quest updated.</div>
                <p style="background-color: #1e2a3a">Mana restored.</p>
                <p>After</p>
                <table><tr><th>Skill</th><th>Rank</th></tr><tr><td colspan="2" rowspan="3">Swordplay</td></tr></table>
                """,
            ),
        )
        // A frame around most of the chapter is the site's layout.
        assertEquals(listOf(p("A chapter in a one-cell layout table.")), blocks("<table><tr><td>A chapter in a one-cell layout table.</td></tr></table>"))
        assertEquals(listOf(p("No frame: no border or none at all.")), blocks("<p>Lead.</p><div style='border: none'>No frame: no border or none at all.</div>").drop(1))
    }

    @Test
    fun `lists, quotes and preformatted text keep their structure`() {
        assertEquals(
            listOf(
                Block.ListBlock(ordered = false, items = listOf(listOf(p("One")), listOf(p("Two"), Block.ListBlock(ordered = true, items = listOf(listOf(p("Two a"))))))),
                Block.Quote(listOf(p("Quoted"), p("Still quoted"))),
                Block.Preformatted("STR  10\n  DEX   8"),
            ),
            blocks("<ul><li>One</li><li> </li><li>Two<ol><li>Two a</li></ol></li></ul><blockquote><p>Quoted</p>Still quoted</blockquote><pre>STR  10\n  DEX   8  \n\n</pre>"),
        )
    }

    @Test
    fun `images are absolute, lazy sources are followed, and placeholders and pixels dropped`() {
        assertEquals(
            listOf(
                Block.Image("https://novels.example.com/images/map.png", "The map"),
                Block.Image("https://cdn.example.com/lazy.jpg"),
                Block.Image("https://cdn.example.com/lazier.jpg"),
                Block.Image("https://cdn.example.com/original.jpg"),
                Block.Image("https://cdn.example.com/small.jpg"),
                p("Text between"),
            ),
            blocks(
                """
                <p><img src="/images/map.png" alt=" The   map "></p>
                <img src="data:image/gif;base64,R0lGODlhAQABAAAAACw=" data-src="https://cdn.example.com/lazy.jpg">
                <img data-lazy-src="//cdn.example.com/lazier.jpg">
                <img data-original="https://cdn.example.com/original.jpg" src="/placeholder.gif">
                <img src="data:image/png;base64,iVBORw0KGgo=" srcset="https://cdn.example.com/small.jpg 480w, https://cdn.example.com/large.jpg 1080w">
                <p>Text <img src="data:image/png;base64,iVBORw0KGgo="> between</p>
                <img src="https://tracker.example.com/pixel.gif" width="1" height="1">
                <img src="https://tracker.example.com/pixel2.gif" style="width:2px;height:2px">
                """,
            ),
        )
    }

    @Test
    fun `links keep absolute addresses, and links that only lead to other chapters go`() {
        assertEquals(
            listOf(
                p(Span("See the "), Span("glossary", href = "https://novels.example.com/glossary#mana"), Span(" and "), Span("this", ITALIC), Span(".")),
                p("The end."),
            ),
            blocks(
                """
                <p><a href="/novel/chapter-2">« Previous Chapter</a> | <a href="/novel">Index</a> | <a href="/novel/chapter-4">Next Chapter »</a></p>
                <p>See the <a href="/glossary#mana">glossary</a> and <a href="javascript:void(0)"><i>this</i></a>.</p>
                <p>The end. <a href="/novel/chapter-4">Next</a></p>
                <ul><li><a href="/novel/chapter-2">Prev</a></li><li><a href="/toc">[Table of Contents]</a></li></ul>
                """,
            ),
        )
    }

    @Test
    fun `whitespace collapses outside preformatted text, and empty paragraphs go`() {
        assertEquals(
            listOf(p("Spaces collapse across lines."), p("A B C")),
            blocks("<p>  Spaces   collapse\n\t across    lines.  </p><p>&nbsp;</p><p> &nbsp; <br> </p><p><span></span><b> </b></p><p>A&nbsp;&nbsp;B\u200B C</p>"),
        )
    }

    @Test
    fun `odd markup never throws`() {
        val deep = "<div>".repeat(3000) + "deep" + "</div>".repeat(3000)
        val odd = listOf(
            "",
            "<frameset><frame src='a.html'></frameset>",
            deep,
            "<table><tr></tr><td>stray</td></table><ul></ul><ol><li></li></ol><table></table>",
            "<p>unclosed <b>bold <i>both</p> after",
            "<svg><text>drawn</text></svg><math><mi>x</mi></math><select><option>opt</option></select>",
            "<pre></pre><pre>\n\n</pre><blockquote></blockquote><div style='border:1px solid'></div>",
            "<img><img src=''><img srcset=','><a href='javascript:alert(1)'>js</a><iframe src='javascript:x'></iframe>",
            "<p style='font-weight:;text-align:'>empty declarations</p><p style='::;;'>junk</p><td colspan='x'>cell</td>",
            "<style>@media { .a { display:none } .b{</style><style>}}}{{{</style><p class='a'>styles</p>",
            "<script>window.chapter = 'only in a script';</script>",
        )
        for (html in odd) {
            val page = Jsoup.parse(html, PAGE_URL)
            for (content in listOf(null, page.body(), page.selectFirst("p"), page)) DefaultChapterExtractor().extract(page, content, "Chapter 1")
        }
        assertEquals(listOf("deep"), extract(deep).texts())
        // Content that is not part of the page is cleaned by the page's rules all the same.
        val page = Jsoup.parse("<html><head><style>.x{display:none}</style></head><body><p>$PARAGRAPH</p></body></html>", PAGE_URL)
        val detached = Jsoup.parse("<div><p>Kept</p><p class='x'>Hidden</p><img src='/a.png'></div>", PAGE_URL).selectFirst("div")
        assertEquals(listOf(p("Kept"), Block.Image("https://novels.example.com/a.png")), DefaultChapterExtractor().extract(page, detached, null).blocks)
    }

    @Test
    fun `the page given is left as it was`() {
        val html = "<html><head><style>.x{display:none}</style></head><body><div id='c'><p>$PARAGRAPH</p><p class='x'>Hidden</p><script>x()</script></div></body></html>"
        val page = Jsoup.parse(html, PAGE_URL)
        val before = page.outerHtml()
        DefaultChapterExtractor().extract(page, page.selectFirst("#c"), null)
        DefaultChapterExtractor().extract(page, null, null)
        assertEquals(before, page.outerHtml())
    }

    @Test
    fun `a 20,000-word chapter extracts in well under 100 ms`() {
        // Royal Road's markup (a class and a style on every paragraph) and NovelFull's (one element, <br><br>).
        val sentence = "The river kept its own counsel, and the ferryman kept <em>his</em>, mostly. "
        val tagged = (1..840).joinToString("\n") { "<p class=\"cnM$it\" style=\"text-align: left\"><span style=\"font-weight: 400\">${sentence.repeat(2)}</span></p>" }
        val bare = (1..840).joinToString("<br><br>\n") { sentence.repeat(2) }
        val pages = listOf(
            "<html><head><style>.cmHidden{display:none}</style></head><body><div class=\"chapter-inner chapter-content\">$tagged</div></body></html>",
            "<html><body><div id=\"chapter-content\">$bare</div><div id=\"comments\"><p>$PARAGRAPH</p></div></body></html>",
        )
        val extractor = DefaultChapterExtractor()
        for (html in pages) {
            val page = Jsoup.parse(html, PAGE_URL)
            val content = page.selectFirst(".chapter-content, #chapter-content")
            for (element in listOf(content, null)) {
                val result = extractor.extract(page, element, null)
                assertEquals(840, result.leaves.size)
                assertTrue(result.plainText().split(Regex("\\s+")).size >= 20_000)
                repeat(3) { extractor.extract(page, element, null) }
                val best = (1..5).minOf { measureNanoTime { extractor.extract(page, element, null) } } / 1e6
                assertTrue(best < 100, "best of five took $best ms")
            }
        }
    }
}
