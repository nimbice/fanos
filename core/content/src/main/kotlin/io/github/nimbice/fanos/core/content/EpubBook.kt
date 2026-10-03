package io.github.nimbice.fanos.core.content

import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.parser.Parser
import java.io.ByteArrayInputStream
import java.net.URLDecoder
import java.net.URLEncoder

/**
 * An EPUB, read through a function that gives a file in the book by its path from the book's root (null for none): its
 * title, author, description and cover, and its chapters in reading order. A chapter is a contents entry and the book's
 * documents up to the next one, so a chapter the book splits over several files reads as one; a book without contents
 * has a chapter to a document.
 */
class EpubBook private constructor(
    val title: String?,
    val author: String?,
    val description: String?,
    /** The cover picture's path in the book, when it names one. */
    val coverPath: String?,
    val chapters: List<EpubChapter>,
) {
    /**
     * [chapter]'s text, its documents one after another, through [extractor]; its pictures' addresses are under [BASE]
     * (see [pathOf]), as a site's pictures would be, so they're kept like a site's.
     */
    fun content(chapter: EpubChapter, extractor: ChapterExtractor, read: (String) -> ByteArray?): ChapterContent {
        var first: ChapterContent? = null
        val blocks = ArrayList<Block>()
        for (path in chapter.paths) {
            val bytes = read(path) ?: continue
            val document = Jsoup.parse(ByteArrayInputStream(bytes), null, BASE + encode(path))
            val part = extractor.extract(document, document.body(), if (first == null) chapter.title else null)
            if (first == null) first = part
            blocks += part.blocks
        }
        return ChapterContent(blocks, hasOwnTitle = first?.hasOwnTitle == true)
    }

    companion object {
        /** Where the book's documents seem to be, so the page keeps their pictures' addresses. */
        const val BASE = "https://book.invalid/"

        /** The path in the book of [src], an address under [BASE]; null for any other. */
        fun pathOf(src: String): String? = if (src.startsWith(BASE)) decode(src.removePrefix(BASE).substringBefore('#').substringBefore('?')) else null

        /** The book [read] gives the files of; null when it isn't an EPUB, or has nothing to read. */
        fun open(read: (String) -> ByteArray?): EpubBook? {
            val container = read("META-INF/container.xml")?.let(::xml) ?: return null
            val opfPath = container.all("rootfile").firstOrNull()?.attr("full-path")?.takeIf { it.isNotBlank() } ?: return null
            val opf = read(opfPath)?.let(::xml) ?: return null
            val opfDir = opfPath.substringBeforeLast('/', "")
            val metadata = opf.all("metadata").firstOrNull()

            fun meta(name: String): String? = metadata?.all(name)?.firstNotNullOfOrNull { it.text().trim().takeIf(String::isNotEmpty) }

            val manifest = opf.all("item").associate { it.attr("id") to Item(resolve(opfDir, it.attr("href")), it.attr("media-type"), it.attr("properties").split(' ')) }
            val spine = opf.all("spine").firstOrNull()
            val order = spine?.all("itemref")?.mapNotNull { manifest[it.attr("idref")] }?.filter { it.isDocument }?.map { it.path }?.distinct().orEmpty()
            if (order.isEmpty()) return null
            val cover =
                manifest.values.firstOrNull { "cover-image" in it.properties }?.path
                    ?: metadata?.all("meta")?.firstOrNull { it.attr("name") == "cover" }?.attr("content")?.let { manifest[it]?.path }
            val nav = manifest.values.firstOrNull { "nav" in it.properties }?.path
            val ncx = (spine?.attr("toc")?.takeIf { it.isNotBlank() }?.let { manifest[it] } ?: manifest.values.firstOrNull { it.mediaType == NCX })?.path
            val contents = navTitles(nav, read).ifEmpty { ncxTitles(ncx, read) }.filterKeys { it in order }
            val description = meta("description")?.let { Jsoup.parse(it).text().trim() }?.takeIf { it.isNotEmpty() }
            return EpubBook(meta("title"), meta("creator"), description, cover, chaptersOf(order, contents))
        }

        private fun chaptersOf(order: List<String>, contents: Map<String, String>): List<EpubChapter> {
            if (contents.isEmpty()) return order.map { EpubChapter(null, listOf(it)) }
            val chapters = ArrayList<Pair<String?, MutableList<String>>>()
            for (path in order) {
                val title = contents[path]
                if (title != null || chapters.isEmpty()) chapters += title to mutableListOf(path) else chapters.last().second += path
            }
            return chapters.map { (title, paths) -> EpubChapter(title, paths.toList()) }
        }

        /** An EPUB 3 book's contents: its contents page's links, by the document each goes to. */
        private fun navTitles(path: String?, read: (String) -> ByteArray?): Map<String, String> {
            val document = path?.let(read)?.let { Jsoup.parse(ByteArrayInputStream(it), null, "") } ?: return emptyMap()
            val navs = document.select("nav")
            val toc = navs.firstOrNull { it.attr("epub:type").split(' ').contains("toc") } ?: navs.firstOrNull() ?: return emptyMap()
            val dir = path.substringBeforeLast('/', "")
            val titles = LinkedHashMap<String, String>()
            for (link in toc.select("a[href]")) {
                val title = link.text().trim().takeIf { it.isNotEmpty() } ?: continue
                titles.putIfAbsent(resolve(dir, link.attr("href")), title)
            }
            return titles
        }

        /** An EPUB 2 book's contents, from its NCX file. */
        private fun ncxTitles(path: String?, read: (String) -> ByteArray?): Map<String, String> {
            val document = path?.let(read)?.let(::xml) ?: return emptyMap()
            val dir = path.substringBeforeLast('/', "")
            val titles = LinkedHashMap<String, String>()
            for (point in document.all("navPoint")) {
                val title = point.children().firstOrNull { it.local == "navlabel" }?.all("text")?.firstOrNull()?.text()?.trim()?.takeIf { it.isNotEmpty() } ?: continue
                val src = point.children().firstOrNull { it.local == "content" }?.attr("src")?.takeIf { it.isNotBlank() } ?: continue
                titles.putIfAbsent(resolve(dir, src), title)
            }
            return titles
        }

        /** [href] from a document in [dir], as a path from the book's root, without its fragment. */
        private fun resolve(dir: String, href: String): String {
            val clean = decode(href.substringBefore('#'))
            val joined = if (clean.startsWith("/") || dir.isEmpty()) clean else "$dir/$clean"
            val parts = ArrayList<String>()
            for (part in joined.split('/')) {
                when (part) {
                    "", "." -> Unit
                    ".." -> if (parts.isNotEmpty()) parts.removeAt(parts.lastIndex)
                    else -> parts += part
                }
            }
            return parts.joinToString("/")
        }

        private fun decode(text: String): String = runCatching { URLDecoder.decode(text.replace("+", "%2B"), "UTF-8") }.getOrDefault(text)

        private fun encode(path: String): String = path.split('/').joinToString("/") { URLEncoder.encode(it, "UTF-8").replace("+", "%20") }

        private fun xml(bytes: ByteArray): Document = Jsoup.parse(ByteArrayInputStream(bytes), null, "", Parser.xmlParser())

        /** Elements named [local], whatever their namespace's prefix. */
        private fun Element.all(local: String): List<Element> {
            val name = local.lowercase()
            return getAllElements().filter { it.local == name }
        }

        private val Element.local: String get() = tagName().substringAfterLast(':').lowercase()

        private const val NCX = "application/x-dtbncx+xml"
    }

    private class Item(val path: String, val mediaType: String, val properties: List<String>) {
        val isDocument: Boolean
            get() = mediaType == "application/xhtml+xml" || mediaType == "text/html" || path.substringAfterLast('.').lowercase() in setOf("xhtml", "html", "htm")
    }
}

/** A chapter of a book: its title from the book's contents (null for a book without), and the documents it's made of. */
data class EpubChapter(val title: String?, val paths: List<String>)
