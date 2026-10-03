// Derived from NovelLibrary's HtmlCleaner (Apache-2.0), modified.
package io.github.nimbice.fanos.core.content

import org.jsoup.nodes.Document

/** Heading tags, h1 to h6. */
internal val HEADING = Regex("h[1-6]")

/**
 * Stage 5 of extraction: whether the chapter opens with its own title, so the reader adds one only
 * when it does not. Showing both makes the title appear twice; showing neither leaves the reader
 * without one.
 */
internal object TitleDetection {

    private val CHAPTER_LINE = Regex(
        """(?:(?:chapter|ch\.?|episode|ep\.?|volume|vol\.?|book|part|arc)\s*\d+\b|(?:prologue|epilogue|interlude|side story|extra)\b).{0,100}""",
        RegexOption.IGNORE_CASE,
    )

    /**
     * Whether [blocks] open with the chapter's own title: their first text is a heading, reads like a
     * title ("Chapter 25", "Prologue"), or is a numbered line that [title] contains. The number rules
     * out a novel's name, which page titles hold as well. Pictures and credit lines before the first
     * text are passed over, as a title often follows a chapter's illustration or its translator's name.
     */
    fun opensWithOwnTitle(blocks: List<Block>, title: String?): Boolean {
        val first = blocks.asSequence().flatMap { it.leaves() }.firstOrNull { !it.mayPrecedeTitle } ?: return false
        if (first is Block.Heading) return true
        val line = first.text().lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() } ?: return false
        if (line.length < 4) return false
        return CHAPTER_LINE.matches(line) || (title != null && line.any { it.isDigit() } && title.contains(line, ignoreCase = true))
    }

    /** The page's title, less the site name sites append to it ("Chapter 5 - Novel | Site"). */
    fun pageTitle(page: Document): String? {
        val head = page.head()
        val title = head.getElementsByTag("title").text().trim()
        if (title.isEmpty()) return null
        val siteName = head.selectFirst("meta[property=og:site_name]")?.attr("content")?.trim().orEmpty()
        if (siteName.isEmpty()) return title
        val suffix = Regex("""\s*[|\-–—:·•]+\s*${Regex.escape(siteName)}\s*$""", RegexOption.IGNORE_CASE)
        return title.replace(suffix, "").ifBlank { title }
    }
}
