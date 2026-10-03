package io.github.nimbice.fanos.core.content

import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode
import org.jsoup.select.NodeFilter
import org.jsoup.select.NodeTraversor
import java.util.IdentityHashMap

/**
 * Stage 2 of extraction when the source does not say where the chapter is. In the manner of
 * Readability, the chapter is the element holding most of the page's paragraph text. Text in
 * links, navigation, comments and sidebars does not count, so neither a long comment thread nor a
 * menu of chapter links can win.
 *
 * From the body it descends into whichever child holds nearly all of the counted text, and stops
 * where the text spreads over several children: the paragraphs' container.
 */
internal object ContentLocator {

    // A child holding this share of its parent's text is where the chapter is.
    private const val DOMINANT_SHARE = 0.9

    // Leaving out the elements named like comments or sidebars must leave at least this much text
    // (about a sentence); otherwise the names were wrong, as on a wrapper holding the whole page,
    // and are ignored.
    private const val MIN_COUNTED = 50

    private val EXCLUDED_TAGS = setOf(
        "nav", "aside", "footer", "header", "menu", "dialog", "button", "select", "textarea", "template", "svg", "iframe",
    )
    private val EXCLUDED_ROLES = setOf("navigation", "complementary", "banner", "contentinfo", "search", "menu", "menubar", "dialog", "alertdialog")

    // Matched at the start of a word; "has-sidebar" and the like describe a layout wrapper, not a sidebar.
    private val NEGATIVE_NAMES = Regex(
        """(?<![a-z])(?<!has-|with-|no-)(?:comment|disqus|respond|discussion|sidebar|widget|related|share|sharing|social|nav|""" +
            """menu|breadcrumb|pagination|pager|footer|masthead|header|meta|byline|author|tags|categor|subscribe|""" +
            """newsletter|popup|modal|cookie|banner|sponsor|promo|login|signup|rating|toolbar|announcement)""",
    )
    private val POSITIVE_NAMES = Regex("""(?<![a-z])(?:article|body|column|content|main)""")

    fun locate(doc: Document): Element {
        val body = doc.body()
        var skipNamed = true
        var text = measure(body, skipNamed)
        if (text.counted(body) < MIN_COUNTED && text.all(body) > text.counted(body)) {
            skipNamed = false
            text = measure(body, skipNamed)
        }
        var container: Element = body
        while (true) {
            val total = text.counted(container)
            if (total == 0) break
            val best = container.children().maxByOrNull { text.counted(it) } ?: break
            if (text.counted(best) < DOMINANT_SHARE * total) break
            container = best
        }
        prune(container, skipNamed)
        return container
    }

    private class Measurement(private val lengths: IdentityHashMap<Element, IntArray>) {
        /** Text outside links and outside the regions left out. */
        fun counted(element: Element): Int = lengths[element]?.get(0) ?: 0

        /** Text outside links, counting regions left out only by their names. */
        fun all(element: Element): Int = lengths[element]?.get(1) ?: 0
    }

    private class Frame(val skipped: Boolean, val inLink: Boolean) {
        val lengths = IntArray(2)
    }

    /** Visible text lengths of every element under [body], in one pass that never assembles text. */
    private fun measure(body: Element, skipNamed: Boolean): Measurement {
        val lengths = IdentityHashMap<Element, IntArray>()
        val stack = ArrayList<Frame>()
        NodeTraversor.filter(
            object : NodeFilter {
                override fun head(node: Node, depth: Int): NodeFilter.FilterResult {
                    val parent = stack.lastOrNull()
                    when (node) {
                        is Element -> {
                            if (node !== body && isExcluded(node)) return NodeFilter.FilterResult.SKIP_ENTIRELY
                            val named = skipNamed && node !== body && isNamedAside(node)
                            stack += Frame(
                                skipped = named || parent?.skipped == true,
                                inLink = node.normalName() == "a" || parent?.inLink == true,
                            )
                        }
                        is TextNode -> if (parent != null && !parent.inLink) {
                            val length = visibleLength(node.wholeText)
                            parent.lengths[1] += length
                            if (!parent.skipped) parent.lengths[0] += length
                        }
                    }
                    return NodeFilter.FilterResult.CONTINUE
                }

                override fun tail(node: Node, depth: Int): NodeFilter.FilterResult {
                    if (node is Element) {
                        val frame = stack.removeAt(stack.size - 1)
                        lengths[node] = frame.lengths
                        stack.lastOrNull()?.let { parent ->
                            parent.lengths[0] += frame.lengths[0]
                            parent.lengths[1] += frame.lengths[1]
                        }
                    }
                    return NodeFilter.FilterResult.CONTINUE
                }
            },
            body,
        )
        return Measurement(lengths)
    }

    /** Removes what the measurement left out from inside [container]: navigation, and, when their names were trusted, comments and sidebars. */
    private fun prune(container: Element, skipNamed: Boolean) {
        NodeTraversor.filter(
            object : NodeFilter {
                override fun head(node: Node, depth: Int): NodeFilter.FilterResult =
                    if (node is Element && node !== container && (isExcluded(node) || (skipNamed && isNamedAside(node)))) {
                        NodeFilter.FilterResult.REMOVE
                    } else {
                        NodeFilter.FilterResult.CONTINUE
                    }
            },
            container,
        )
    }

    private fun isExcluded(element: Element): Boolean =
        element.normalName() in EXCLUDED_TAGS || element.attr("role").lowercase() in EXCLUDED_ROLES

    /** Named like something beside the chapter (comments, a sidebar, sharing buttons) and not like the chapter's container. */
    private fun isNamedAside(element: Element): Boolean {
        val names = (element.className() + " " + element.id()).lowercase()
        return names.isNotBlank() && NEGATIVE_NAMES.containsMatchIn(names) && !POSITIVE_NAMES.containsMatchIn(names)
    }
}
