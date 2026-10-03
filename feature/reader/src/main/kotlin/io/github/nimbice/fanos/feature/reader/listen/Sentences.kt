package io.github.nimbice.fanos.feature.reader.listen

import io.github.nimbice.fanos.core.content.Block
import io.github.nimbice.fanos.core.content.ChapterContent
import io.github.nimbice.fanos.core.content.leaves
import io.github.nimbice.fanos.core.content.text
import io.github.nimbice.fanos.core.model.Pronunciation
import io.github.nimbice.fanos.core.model.ReadingPosition
import java.text.BreakIterator
import java.util.Locale

/**
 * A sentence to read aloud: its [leaf] of the chapter's content (-1 for the title the reader puts above a chapter
 * without its own), where in the leaf's text it runs from and to, and its text.
 */
data class Sentence(val leaf: Int, val start: Int, val end: Int, val text: String) {
    /** Where the reader is when this is being read, for keeping the place. */
    val position: ReadingPosition get() = ReadingPosition(leaf.coerceAtLeast(0), start)
}

/** A chapter cut into sentences to read aloud, in reading order. */
object Sentences {

    /**
     * The sentences of [content]: [title] first when the chapter doesn't open with its own, then its paragraphs and
     * headings. Credits, pictures, scene breaks, tables and preformatted text aren't read: a voice makes nothing of an
     * ASCII table or a column of stats, and the credits are the site's, not the story's.
     */
    fun of(content: ChapterContent, title: String?, locale: Locale = Locale.getDefault()): List<Sentence> {
        val out = ArrayList<Sentence>()
        if (content.titleLeaf < 0 && !title.isNullOrBlank()) split(-1, title.trim(), locale, out)
        val skipped = tableLeaves(content)
        content.leaves.forEachIndexed { leaf, block ->
            if (leaf in skipped) return@forEachIndexed
            if (block is Block.Paragraph || block is Block.Heading) split(leaf, block.text(), locale, out)
        }
        return out
    }

    /** The first sentence at or after [position]; the first of all for none. */
    fun indexAt(sentences: List<Sentence>, position: ReadingPosition?): Int {
        if (position == null || (position.block <= 0 && position.offset <= 0)) return 0
        val at = sentences.indexOfFirst { it.leaf > position.block || (it.leaf == position.block && it.end > position.offset) }
        return if (at >= 0) at else (sentences.size - 1).coerceAtLeast(0)
    }

    /** Where the sentence of [text] that character [offset] is in runs, without the space around it; null in blank text. */
    fun around(text: String, offset: Int, locale: Locale = Locale.getDefault()): IntRange? {
        if (text.isBlank()) return null
        val at = offset.coerceIn(0, text.length - 1)
        val breaker = BreakIterator.getSentenceInstance(locale)
        breaker.setText(text)
        var end = breaker.following(at).let { if (it == BreakIterator.DONE) text.length else it }
        var start = breaker.preceding(end).let { if (it == BreakIterator.DONE) 0 else it }
        while (start < end && text[start].isWhitespace()) start++
        while (end > start && text[end - 1].isWhitespace()) end--
        return if (start < end) start until end else null
    }

    /** Where the word of [text] that character [offset] is in runs; null when it's in no word (a space, a mark). */
    fun wordAt(text: String, offset: Int, locale: Locale = Locale.getDefault()): IntRange? {
        if (text.isEmpty()) return null
        val at = offset.coerceIn(0, text.length - 1)
        val breaker = BreakIterator.getWordInstance(locale)
        breaker.setText(text)
        val end = breaker.following(at).let { if (it == BreakIterator.DONE) text.length else it }
        val start = breaker.preceding(end).let { if (it == BreakIterator.DONE) 0 else it }
        return if ((start until end).any { text[it].isLetterOrDigit() }) start until end else null
    }

    /** [text] with [pronunciations] put in, whole words only and whatever their capitals. */
    fun spoken(text: String, pronunciations: List<Pronunciation>): String =
        pronunciations.fold(text) { said, it ->
            if (it.word.isBlank()) said else Regex("(?<![\\p{L}\\p{N}])" + Regex.escape(it.word.trim()) + "(?![\\p{L}\\p{N}])", RegexOption.IGNORE_CASE).replace(said, Regex.escapeReplacement(it.sayAs))
        }

    private fun split(leaf: Int, text: String, locale: Locale, out: MutableList<Sentence>) {
        val breaker = BreakIterator.getSentenceInstance(locale)
        breaker.setText(text)
        var start = breaker.first()
        var end = breaker.next()
        while (end != BreakIterator.DONE) {
            addTrimmed(leaf, text, start, end, out)
            start = end
            end = breaker.next()
        }
    }

    /** The part of [text] from [from] to [to] without the space around it, cut again if it's more than a voice takes at once. */
    private fun addTrimmed(leaf: Int, text: String, from: Int, to: Int, out: MutableList<Sentence>) {
        var start = from
        var end = to
        while (start < end && text[start].isWhitespace()) start++
        while (end > start && text[end - 1].isWhitespace()) end--
        if (start >= end) return
        // Nothing to say in a row of stars or dashes.
        if ((start until end).none { text[it].isLetterOrDigit() }) return
        if (end - start <= LONGEST) {
            out += Sentence(leaf, start, end, text.substring(start, end))
            return
        }
        // Too long for a voice in one go: at the last comma, or else space, before the limit.
        val limit = start + LONGEST
        val cut = (text.lastIndexOf(',', limit).takeIf { it > start } ?: text.lastIndexOf(' ', limit).takeIf { it > start } ?: limit - 1) + 1
        addTrimmed(leaf, text, start, cut, out)
        addTrimmed(leaf, text, cut, end, out)
    }

    /** The leaves inside tables: the page shows them in grids, where nothing is read aloud or marked. */
    internal fun tableLeaves(content: ChapterContent): Set<Int> {
        val skipped = HashSet<Int>()
        var index = 0
        fun walk(block: Block) {
            when (block) {
                is Block.Table -> block.leaves().forEach { _ -> skipped += index++ }
                is Block.Quote -> block.blocks.forEach(::walk)
                is Block.Box -> block.blocks.forEach(::walk)
                is Block.ListBlock -> block.items.flatten().forEach(::walk)
                else -> index++
            }
        }
        content.blocks.forEach(::walk)
        return skipped
    }

    /** Well under the most a voice takes at once (TextToSpeech.getMaxSpeechInputLength, 4000). */
    private const val LONGEST = 1000
}
