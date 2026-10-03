package io.github.nimbice.fanos.feature.novel

import io.github.nimbice.fanos.core.model.Chapter

/**
 * The chapter a number typed in the chapter search stands for, among [chapters] in reading order: the first whose title
 * numbers it so ("Chapter 250", "Ch. 250", "c250", "250: The Door"), else the one at that place in the list. Null for
 * a search that isn't a number, and for a number past the end.
 */
internal fun findChapter(chapters: List<Chapter>, query: String): Chapter? {
    val number = query.trim().takeIf { it.isNotEmpty() && it.all { c -> c.isDigit() || c == '.' } && it.any(Char::isDigit) } ?: return null
    val digits = Regex.escape(number.trimStart('0').ifEmpty { "0" })
    // Not followed by more of a number: "Chapter 25" is no match for 250, nor "Chapter 250.5" for 250.
    val end = """(?!\d|\.\d)"""
    val named = Regex("""(?i)\b(?:chapter|chap|ch|episode|ep|c)\.?\s*0*$digits$end""")
    chapters.firstOrNull { named.containsMatchIn(it.title) }?.let { return it }
    val leading = Regex("""^\s*0*$digits$end""")
    chapters.firstOrNull { leading.containsMatchIn(it.title) }?.let { return it }
    return number.toIntOrNull()?.let { chapters.getOrNull(it - 1) }
}

/** Whether the chapter search is a number to go to, rather than words to find in titles. */
internal fun isChapterNumber(query: String): Boolean = query.trim().let { it.isNotEmpty() && it.all { c -> c.isDigit() || c == '.' } }
