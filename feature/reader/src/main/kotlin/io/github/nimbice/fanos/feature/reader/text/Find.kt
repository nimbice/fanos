package io.github.nimbice.fanos.feature.reader.text

import io.github.nimbice.fanos.core.content.Block
import io.github.nimbice.fanos.core.content.ChapterContent
import io.github.nimbice.fanos.core.content.text
import io.github.nimbice.fanos.core.model.ReadingPosition
import io.github.nimbice.fanos.feature.reader.listen.Sentences

/** Where a search found its words in a chapter: the leaf (-1 for the title above a chapter without its own) and the span. */
internal data class Match(val leaf: Int, val start: Int, val end: Int)

/**
 * Finds [query] in the chapter's text as the page shows it: its title when it has none of its own, then its paragraphs,
 * headings and credits, in reading order. Capitals don't matter, nor curly quotes against straight ones. Tables and
 * preformatted text aren't searched: their matches couldn't be marked on the page.
 */
internal fun findIn(content: ChapterContent, title: String?, query: String): List<Match> {
    val words = plain(query.trim())
    if (words.isEmpty()) return emptyList()
    val found = ArrayList<Match>()
    fun search(leaf: Int, text: String) {
        val haystack = plain(text)
        var at = haystack.indexOf(words, ignoreCase = true)
        while (at >= 0) {
            found += Match(leaf, at, at + words.length)
            at = haystack.indexOf(words, at + words.length, ignoreCase = true)
        }
    }
    if (content.titleLeaf < 0 && !title.isNullOrBlank()) search(-1, title.trim())
    val skipped = Sentences.tableLeaves(content)
    content.leaves.forEachIndexed { leaf, block ->
        if (leaf !in skipped && (block is Block.Paragraph || block is Block.Heading || block is Block.Credit)) search(leaf, block.text())
    }
    return found
}

/** The first of [matches] at or after [position], else the first: where a new search starts. */
internal fun firstFrom(matches: List<Match>, position: ReadingPosition?): Int {
    if (position == null) return 0
    val at = matches.indexOfFirst { it.leaf > position.block || (it.leaf == position.block && it.end > position.offset) }
    return if (at >= 0) at else 0
}

/** Curly quotes made straight, one character for one, so places in the text stay where they are. */
private fun plain(text: String): String =
    text.map {
        when (it) {
            '‘', '’', '‛', '′' -> '\''
            '“', '”', '‟', '″' -> '"'
            else -> it
        }
    }.joinToString("")
