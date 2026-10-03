package io.github.nimbice.fanos.core.content

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EpubBookTest {

    private val text = "Viola woke slowly, and for a long while she did not know where she was, or how long she had slept there."

    private fun book(files: Map<String, String>): (String) -> ByteArray? = { files[it]?.toByteArray() }

    private val container =
        """<?xml version="1.0"?><container xmlns="urn:oasis:names:tc:opendocument:xmlns:container" version="1.0">
           <rootfiles><rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/></rootfiles></container>"""

    private fun page(body: String) = """<?xml version="1.0" encoding="utf-8"?><html xmlns="http://www.w3.org/1999/xhtml"><head><title>x</title></head><body>$body</body></html>"""

    @Test
    fun `an EPUB 3 book, its details, and chapters from its contents, split ones read as one`() {
        val files =
            mapOf(
                "META-INF/container.xml" to container,
                "OEBPS/content.opf" to
                    """<?xml version="1.0"?><package xmlns="http://www.idpf.org/2007/opf" version="3.0">
                       <metadata xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:title>The Test Book</dc:title><dc:creator>A. Writer</dc:creator>
                       <dc:description>&lt;p&gt;A &lt;b&gt;short&lt;/b&gt; book.&lt;/p&gt;</dc:description></metadata>
                       <manifest><item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/>
                       <item id="c1" href="text/ch1.xhtml" media-type="application/xhtml+xml"/><item id="c1b" href="text/ch1b.xhtml" media-type="application/xhtml+xml"/>
                       <item id="c2" href="text/ch2.xhtml" media-type="application/xhtml+xml"/>
                       <item id="pic" href="images/pic%201.jpg" media-type="image/jpeg" properties="cover-image"/></manifest>
                       <spine><itemref idref="c1"/><itemref idref="c1b"/><itemref idref="c2"/></spine></package>""",
                "OEBPS/nav.xhtml" to page("""<nav epub:type="toc"><ol><li><a href="text/ch1.xhtml">One</a></li><li><a href="text/ch2.xhtml#start">Two</a></li></ol></nav>"""),
                "OEBPS/text/ch1.xhtml" to page("""<h1>One</h1><p>$text</p><p><img src="../images/pic%201.jpg" alt="A picture"/></p>"""),
                "OEBPS/text/ch1b.xhtml" to page("<p>And then more of chapter one. $text</p>"),
                "OEBPS/text/ch2.xhtml" to page("<p>$text</p>"),
            )
        val book = EpubBook.open(book(files))!!

        assertEquals("The Test Book", book.title)
        assertEquals("A. Writer", book.author)
        assertEquals("A short book.", book.description)
        assertEquals("OEBPS/images/pic 1.jpg", book.coverPath)
        assertEquals(listOf(EpubChapter("One", listOf("OEBPS/text/ch1.xhtml", "OEBPS/text/ch1b.xhtml")), EpubChapter("Two", listOf("OEBPS/text/ch2.xhtml"))), book.chapters)

        val one = book.content(book.chapters[0], DefaultChapterExtractor(), book(files))
        val picture = one.leaves.filterIsInstance<Block.Image>().single()
        assertEquals("OEBPS/images/pic 1.jpg", EpubBook.pathOf(picture.src))
        assertTrue(one.plainText().contains("And then more of chapter one."))
    }

    @Test
    fun `an EPUB 2 book's contents come from its NCX`() {
        val files =
            mapOf(
                "META-INF/container.xml" to container,
                "OEBPS/content.opf" to
                    """<?xml version="1.0"?><opf:package xmlns:opf="http://www.idpf.org/2007/opf" version="2.0"><opf:metadata><dc:title xmlns:dc="http://purl.org/dc/elements/1.1/">Old Book</dc:title>
                       <opf:meta name="cover" content="cov"/></opf:metadata>
                       <opf:manifest><opf:item id="ncx" href="toc.ncx" media-type="application/x-dtbncx+xml"/><opf:item id="cov" href="cover.jpg" media-type="image/jpeg"/>
                       <opf:item id="a" href="a.html" media-type="application/xhtml+xml"/><opf:item id="b" href="b.html" media-type="application/xhtml+xml"/></opf:manifest>
                       <opf:spine toc="ncx"><opf:itemref idref="a"/><opf:itemref idref="b"/></opf:spine></opf:package>""",
                "OEBPS/toc.ncx" to
                    """<?xml version="1.0"?><ncx xmlns="http://www.daisy.org/z3986/2005/ncx/"><navMap>
                       <navPoint id="p1"><navLabel><text>First</text></navLabel><content src="a.html"/></navPoint>
                       <navPoint id="p2"><navLabel><text>Second</text></navLabel><content src="b.html#x"/></navPoint></navMap></ncx>""",
                "OEBPS/a.html" to page("<p>$text</p>"),
                "OEBPS/b.html" to page("<p>$text</p>"),
            )
        val book = EpubBook.open(book(files))!!

        assertEquals("Old Book", book.title)
        assertEquals("OEBPS/cover.jpg", book.coverPath)
        assertEquals(listOf("First", "Second"), book.chapters.map { it.title })
    }

    @Test
    fun `a file that isn't an EPUB isn't a book`() {
        assertNull(EpubBook.open { null })
        assertNull(EpubBook.open(book(mapOf("META-INF/container.xml" to "<container/>"))))
    }
}
