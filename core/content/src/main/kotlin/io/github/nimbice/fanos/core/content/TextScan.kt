package io.github.nimbice.fanos.core.content

import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode
import org.jsoup.select.NodeFilter
import org.jsoup.select.NodeTraversor

/**
 * [element]'s text as [Element.text] gives it (whitespace collapsed, a space at block boundaries
 * and line breaks), or null as soon as it is longer than [limit]. The clean-up rules only care about
 * short runs of text, and stopping early keeps them from assembling a whole chapter's text for
 * every container they look at.
 */
internal fun boundedText(element: Element, limit: Int): String? {
    val text = StringBuilder()
    var tooLong = false
    NodeTraversor.filter(
        object : NodeFilter {
            override fun head(node: Node, depth: Int): NodeFilter.FilterResult {
                when {
                    node is TextNode -> appendCollapsed(text, node.wholeText)
                    node is Element && node !== element && (node.isBlock || node.normalName() == "br") -> space(text)
                }
                if (text.length > limit + 1) {
                    tooLong = true
                    return NodeFilter.FilterResult.STOP
                }
                return NodeFilter.FilterResult.CONTINUE
            }

            override fun tail(node: Node, depth: Int): NodeFilter.FilterResult {
                if (node is Element && node !== element && node.isBlock) space(text)
                return NodeFilter.FilterResult.CONTINUE
            }
        },
        element,
    )
    val result = text.trim()
    return if (tooLong || result.length > limit) null else result.toString()
}

/** Whether [node] is [ancestor] or inside it. */
internal fun isWithin(node: Node, ancestor: Node): Boolean {
    var current: Node? = node
    while (current != null) {
        if (current === ancestor) return true
        current = current.parentNode()
    }
    return false
}

/** Characters that take no room and carry no meaning in a novel's text. */
internal const val INVISIBLE_CHARS = "\u200B\uFEFF\u00AD\u2060"

/** How many characters of [text] show: whitespace and invisible characters do not count. */
internal fun visibleLength(text: String): Int {
    var n = 0
    for (c in text) if (!c.isWhitespace() && c !in INVISIBLE_CHARS) n++
    return n
}

private fun appendCollapsed(text: StringBuilder, raw: String) {
    for (c in raw) {
        when {
            c.isWhitespace() -> space(text)
            c in INVISIBLE_CHARS -> Unit
            else -> text.append(c)
        }
    }
}

private fun space(text: StringBuilder) {
    if (text.isNotEmpty() && text[text.length - 1] != ' ') text.append(' ')
}
