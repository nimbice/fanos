package io.github.nimbice.fanos.feature.reader

import io.github.nimbice.fanos.core.content.ChapterContent
import io.github.nimbice.fanos.core.content.leaves
import io.github.nimbice.fanos.core.content.text
import io.github.nimbice.fanos.core.model.ReadingPosition
import kotlin.math.ceil

/** The words of [content] from [position] to its end. */
internal fun wordsFrom(content: ChapterContent, position: ReadingPosition): Int {
    val leaves = content.leaves
    var words = 0
    for (index in position.block.coerceAtLeast(0) until leaves.size) {
        val text = leaves[index].text()
        words += wordsIn(if (index == position.block) text.substring(position.offset.coerceIn(0, text.length)) else text)
    }
    return words
}

/** The words read going from [from] to [to] in [content]: none going back. */
internal fun wordsBetween(content: ChapterContent, from: ReadingPosition, to: ReadingPosition): Int = (wordsFrom(content, from) - wordsFrom(content, to)).coerceAtLeast(0)

/** Minutes to read [words] at [wordsPerMinute], rounded up. */
internal fun minutesFor(words: Int, wordsPerMinute: Int): Int = if (words <= 0) 0 else ceil(words / wordsPerMinute.coerceAtLeast(1).toDouble()).toInt()

/**
 * The reading speed after reading [words] in [millis]: a little of the way from [speed] towards it, so one quick skim or
 * a pause doesn't throw it; null for a stretch that says nothing about reading (too short, too long, a jump).
 */
internal fun readingSpeedAfter(speed: Int, words: Int, millis: Long): Int? {
    if (words !in MIN_WORDS..MAX_WORDS || millis !in MIN_MILLIS..MAX_MILLIS) return null
    val sample = words * 60_000.0 / millis
    if (sample !in MIN_SPEED..MAX_SPEED) return null
    return (speed + (sample - speed) * WEIGHT).toInt()
}

private fun wordsIn(text: String): Int = text.split(SPACE).count { word -> word.any(Char::isLetterOrDigit) }

private val SPACE = Regex("\\s+")
private const val MIN_WORDS = 15
private const val MAX_WORDS = 3_000
private const val MIN_MILLIS = 3_000L
private const val MAX_MILLIS = 4 * 60_000L
private const val MIN_SPEED = 60.0
private const val MAX_SPEED = 1_200.0
private const val WEIGHT = 0.15
