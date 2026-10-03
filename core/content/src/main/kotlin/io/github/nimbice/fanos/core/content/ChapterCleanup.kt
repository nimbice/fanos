// Derived from NovelLibrary's HtmlCleaner (Apache-2.0), modified.
package io.github.nimbice.fanos.core.content

import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode
import org.jsoup.select.NodeFilter
import org.jsoup.select.NodeTraversor
import java.text.Normalizer
import java.util.Collections
import java.util.IdentityHashMap

/**
 * Stage 3 of extraction: clean-up inside the chapter. Removes the lines sites add to chapters (their
 * notices, disguised watermarks) and the links that only lead to other chapters, all of which would
 * otherwise be shown, read aloud and counted as the chapter's. Translator and editor credits stay:
 * they name the people behind the chapter, and conversion sets them apart as credits.
 */
internal object ChapterCleanup {

    // Whole lines some sites add to chapters.
    private val SITE_NOTICES = listOf(
        Regex("""if you find any errors\b.*\breport chapter\b.*""", RegexOption.IGNORE_CASE),
        Regex("""tip: you can use left, right,? a and d keyboard keys to browse between chapters\.?""", RegexOption.IGNORE_CASE),
    )
    private const val SITE_NOTICE_BLOCKS = "p,div,span,center,font,h1,h2,h3,h4,h5,h6,strong,b,em,i"
    private const val MAX_SITE_NOTICE_LENGTH = 300

    // The label WordPress and ad scripts leave where an ad was.
    private val AD_LABEL = Regex("""advertisements?""", RegexOption.IGNORE_CASE)
    private val AD_LABEL_TAGS = setOf("div", "span")

    // Unicode's mathematical alphanumeric letters (𝘧𝑟𝑒𝑒…), which aggregators spell their domain with
    // so that filters for the plain name miss it, and the domain names they spell.
    private val STYLISED_LETTERS = 0x1D400..0x1D7FF
    private val WATERMARK_DOMAIN = Regex(
        """(?<![a-z0-9-])[a-z0-9-]{2,}\.(?:com|net|org|me|co|io|site|online|xyz|info|club|app|top|cc|us|ws|to)(?![a-z0-9])""",
        RegexOption.IGNORE_CASE,
    )

    // Link texts that only lead elsewhere in the novel: "« Previous Chapter", "Next »", "[Index]".
    private val PREV_REGEX = Regex("""^\s*\W*\s*Prev(?:ious)?(?:\sChapter)?$""", RegexOption.IGNORE_CASE)
    private val NEXT_REGEX = Regex("""^\s*\W*\s*Next(?:\sChapter)?\s*\W*\s*$""", RegexOption.IGNORE_CASE)
    private val TOC_REGEX = Regex("""^\s*\W*\s*(Index|TOC|Table of Contents)(\s*\W*\s*|\s\W\s.+)$""", RegexOption.IGNORE_CASE)
    private const val MAX_LINK_TEXT = 80

    // Beyond this many nodes an element is more than a row of navigation links.
    private const val MAX_NAVIGATION_NODES = 60

    private val MEDIA_TAGS = setOf("img", "picture", "iframe", "object", "embed", "video", "audio", "svg")

    fun clean(root: Element) {
        removeSiteNotices(root)
        removeDisguisedDomains(root)
        removeDirectionalLinks(root)
    }

    /** Removes the blocks whose whole text is one of the lines in [SITE_NOTICES]. */
    private fun removeSiteNotices(root: Element) {
        val protected = setOf(root)
        for (block in root.select(SITE_NOTICE_BLOCKS)) {
            // A notice is one line, a block with no blocks inside. Skipping the containers also
            // spares assembling the chapter's whole text for each of them.
            if (hasBlockChild(block) || !isWithin(block, root)) continue
            val text = boundedText(block, MAX_SITE_NOTICE_LENGTH) ?: continue
            val notice = SITE_NOTICES.any { it.matches(text) } ||
                (block.normalName() in AD_LABEL_TAGS && block.childrenSize() == 0 && AD_LABEL.matches(text))
            if (!notice) continue
            if (block === root) root.empty() else PageCleanup.removeWithEmptyWrappers(block, protected)
        }
    }

    private fun removeDisguisedDomains(root: Element) {
        root.forEachNode { node ->
            if (node is TextNode) {
                val text = node.wholeText
                val cleaned = withoutDisguisedDomains(text)
                if (cleaned != text) node.text(cleaned)
            }
        }
    }

    /**
     * [text] without the domain names spelled partly in look-alike letters, as in "…he said.
     * 𝘧𝑟𝑒𝑒𝘸𝘦𝘣𝑛𝑜𝘷𝑒𝓁.𝘤𝘰𝓂", which a site appends to a random line on every load. A chapter never
     * spells a web address that way, so only those go; the words around them stay.
     */
    internal fun withoutDisguisedDomains(text: String): String {
        if (text.none { it.isSurrogate() }) return text // the look-alike letters are all outside the BMP
        // Normalise letter by letter, remembering where each normalised character came from.
        val normalized = StringBuilder()
        val origin = ArrayList<Int>()
        val disguised = ArrayList<Boolean>()
        var i = 0
        while (i < text.length) {
            val codePoint = text.codePointAt(i)
            for (c in Normalizer.normalize(String(Character.toChars(codePoint)), Normalizer.Form.NFKC)) {
                normalized.append(c)
                origin += i
                disguised += codePoint in STYLISED_LETTERS
            }
            i += Character.charCount(codePoint)
        }
        origin += text.length
        var result = text
        // From the end, so earlier offsets stay valid.
        for (match in WATERMARK_DOMAIN.findAll(normalized).toList().asReversed()) {
            if (match.range.none { disguised[it] }) continue
            var start = origin[match.range.first]
            val end = origin[match.range.last + 1]
            // Only the blanks before it on its line: a line break in preformatted text is the chapter's.
            while (start > 0 && result[start - 1].isWhitespace() && result[start - 1] !in "\r\n") start--
            result = result.removeRange(start, end)
        }
        return result
    }

    /**
     * Removes links to the previous or next chapter or the table of contents, together with the
     * largest element around each that holds nothing else (a row of such links and the separators
     * between them), so no stray "|" is left behind.
     */
    private fun removeDirectionalLinks(root: Element) {
        val links = root.getElementsByTag("a").filter(::isDirectionalLink)
        if (links.isEmpty()) return
        val navigation = Collections.newSetFromMap(IdentityHashMap<Element, Boolean>()).apply { addAll(links) }
        for (link in links) {
            if (link === root || !isWithin(link, root)) continue
            var unit: Element = link
            while (true) {
                val parent = unit.parent() ?: break
                if (parent === root || !holdsOnlyNavigation(parent, navigation)) break
                unit = parent
            }
            removeSeparators(unit)
            unit.remove()
        }
    }

    private fun isDirectionalLink(link: Element): Boolean {
        val text = boundedText(link, MAX_LINK_TEXT) ?: return false
        return PREV_REGEX.matches(text) || NEXT_REGEX.matches(text) || TOC_REGEX.matches(text)
    }

    /** Whether all of [element]'s text and pictures are inside [navigation] links, apart from punctuation between them. */
    private fun holdsOnlyNavigation(element: Element, navigation: Set<Element>): Boolean {
        var nodes = 0
        var onlyNavigation = true
        NodeTraversor.filter(
            object : NodeFilter {
                override fun head(node: Node, depth: Int): NodeFilter.FilterResult {
                    if (node is Element && node in navigation) return NodeFilter.FilterResult.SKIP_ENTIRELY
                    val content = when (node) {
                        is TextNode -> node.wholeText.any { it.isLetterOrDigit() }
                        is Element -> node.normalName() in MEDIA_TAGS
                        else -> false
                    }
                    if (content || ++nodes > MAX_NAVIGATION_NODES) {
                        onlyNavigation = false
                        return NodeFilter.FilterResult.STOP
                    }
                    return NodeFilter.FilterResult.CONTINUE
                }
            },
            element,
        )
        return onlyNavigation
    }

    /** Drops the punctuation-only text ("|", "•", "/") right before and after [unit]. */
    private fun removeSeparators(unit: Element) {
        for (forward in listOf(true, false)) {
            var sibling = if (forward) unit.nextSibling() else unit.previousSibling()
            while (sibling is TextNode && !sibling.isBlank && sibling.wholeText.none { it.isLetterOrDigit() }) {
                val following = if (forward) sibling.nextSibling() else sibling.previousSibling()
                sibling.remove()
                sibling = following
            }
        }
    }

    private fun hasBlockChild(element: Element): Boolean {
        for (i in 0 until element.childrenSize()) if (element.child(i).isBlock) return true
        return false
    }
}
