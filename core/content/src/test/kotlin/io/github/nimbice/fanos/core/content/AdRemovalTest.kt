// Derived from NovelLibrary's HtmlCleaner (Apache-2.0), modified.
package io.github.nimbice.fanos.core.content

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AdRemovalTest {

    private fun clean(body: String): ChapterContent = extract("<html><body>$body</body></html>", content = "body")

    @Test
    fun `ad frames go, and so do the wrappers they leave empty`() {
        // NovelFull's ad markup: a frame at the top, and one at the end with a paid link laid over it.
        val chapter = extract(
            """
            <html><body><div id="chapter-content" class="chapter-c" style="font-family: Arial, sans-serif; font-size: 18px;">
              <div align="center"><iframe data-aa="2244198" src="//ad.a-ads.com/2244198?size=300x250" title="Advertisement"></iframe></div>
              <p></p>
              <div><p>The kettle sang twice before anyone moved to lift it.</p></div>
              <div style="position:relative; width:300px; height:250px;">
                <iframe src="https://freegames.click/ads01/kdt/" width="300" height="250"></iframe>
                <a href="https://freegames.click/ads01/go/" target="_blank" rel="sponsored nofollow noopener noreferrer"></a>
              </div>
              <div>If you find any errors, please let us know.</div>
            </div></body></html>
            """,
            content = "#chapter-content",
        )

        assertEquals(listOf(p("The kettle sang twice before anyone moved to lift it."), p("If you find any errors, please let us know.")), chapter.blocks)
    }

    @Test
    fun `frames without a source, plugin objects and nested wrappers go`() {
        val chapter = clean(
            """
            <p>Before</p>
            <div><div style="min-height:250px"><iframe></iframe></div></div>
            <object data="https://ads.example.com/banner.swf"></object>
            <embed src="/ad.swf">
            <p>After</p>
            """,
        )

        assertEquals(listOf("Before", "After"), chapter.texts())
    }

    @Test
    fun `embedded videos and documents stay, as links`() {
        val chapter = clean(
            """
            <p><iframe src="https://www.youtube.com/embed/abc123"></iframe></p>
            <iframe src="https://player.vimeo.com/video/42" title="The river song"></iframe>
            <iframe src="https://docs.google.com/document/d/e/xyz/pub?embedded=true"></iframe>
            """,
        )

        assertEquals(
            listOf(
                p(Span("https://www.youtube.com/embed/abc123", href = "https://www.youtube.com/embed/abc123")),
                p(Span("The river song", href = "https://player.vimeo.com/video/42")),
                p(Span("https://docs.google.com/document/d/e/xyz/pub?embedded=true", href = "https://docs.google.com/document/d/e/xyz/pub?embedded=true")),
            ),
            chapter.blocks,
        )
    }

    @Test
    fun `ad slots and paid banners go, while text and paid text links stay`() {
        val chapter = clean(
            """
            <p>Chapter text <ins class="adsbygoogle" data-ad-client="ca-pub-1" data-ad-slot="2"></ins></p>
            <div class="wpcnt"><div class="wpa"><span class="wpa-about">Advertisements</span></div></div>
            <p><a rel="sponsored" href="https://ads.example.com/go"><img src="https://ads.example.com/banner.png"></a></p>
            <p>Buy the official release <a rel="sponsored" href="https://example.com/book">here</a>.</p>
            <div class="ad-label"><span>Advertisement</span></div>
            """,
        )

        assertEquals(
            listOf(p("Chapter text"), p(Span("Buy the official release "), Span("here", href = "https://example.com/book"), Span("."))),
            chapter.blocks,
        )
    }

    @Test
    fun `pictures shown through object or embed stay, plugins go`() {
        val chapter = clean(
            """
            <p><object data="/maps/continent.svg" type="image/svg+xml">A map of the continent</object></p>
            <p><embed src="https://cdn.example.com/maps/city.png?v=2"></p>
            <p><object data="https://ads.example.com/banner.swf" type="application/x-shockwave-flash"></object></p>
            """,
        )

        assertEquals(
            listOf(
                Block.Image("https://novels.example.com/maps/continent.svg", "A map of the continent"),
                Block.Image("https://cdn.example.com/maps/city.png?v=2"),
            ),
            chapter.blocks,
        )
    }

    @Test
    fun `wrapper removal stops at table cells and list items`() {
        val chapter = clean(
            """
            <table><tr><td><div><iframe src="//ad.example.com/1"></iframe></div></td><td>Cell</td></tr></table>
            <ul><li><span><iframe src="//ad.example.com/2"></iframe></span></li><li>Item</li></ul>
            """,
        )

        assertEquals(
            listOf(
                Block.Table(listOf(listOf(TableCell(emptyList()), TableCell(listOf(p("Cell")))))),
                Block.ListBlock(ordered = false, items = listOf(listOf(p("Item")))),
            ),
            chapter.blocks,
        )
        assertTrue(chapter.leaves.none { it is Block.Image })
    }
}
