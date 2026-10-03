package io.github.nimbice.fanos.core.data.move

/**
 * Finds a chapter's counterpart on another site. Sites title the same chapter differently ("Chapter 12: The Duel",
 * "c12", "12. The Duel"), so chapters are matched by their numbers, and those without one ("Prologue") by their
 * titles. A number that comes more than once (a chapter in parts, volumes numbered afresh) is matched in turn: the
 * second "Chapter 12" to the other site's second.
 */
internal object ChapterMatcher {

    private val NUMBERED =
        listOf(
            Regex("""\b(?:chapter|chap|ch)\.?\s*(\d+(?:\.\d+)?)""", RegexOption.IGNORE_CASE),
            Regex("""\b(?:episode|ep)\.?\s*(\d+(?:\.\d+)?)""", RegexOption.IGNORE_CASE),
            // Novel Updates' short release names: "c12", "v2c3".
            Regex("""^\s*(?:v\d+\s*)?c(\d+(?:\.\d+)?)\b""", RegexOption.IGNORE_CASE),
            // A title that starts with its number: "12. The Duel", "12 - The Duel", "12".
            Regex("""^\s*(\d+(?:\.\d+)?)(?=\s*[.:)\-–—]|\s|$)"""),
            Regex("""第\s*(\d+)\s*[章话話回]"""),
        )

    /** For each chapter title in [from], in reading order, the index of its counterpart among [to], or null. */
    fun match(from: List<String>, to: List<String>): List<Int?> {
        val unclaimed = HashMap<Key, ArrayDeque<Int>>()
        to.forEachIndexed { index, title -> key(title)?.let { unclaimed.getOrPut(it) { ArrayDeque() }.addLast(index) } }
        return from.map { title -> key(title)?.let { unclaimed[it]?.removeFirstOrNull() } }
    }

    /** The chapter's number, as its title gives it, or null. */
    fun number(title: String): Double? = NUMBERED.firstNotNullOfOrNull { it.find(title)?.groupValues?.get(1)?.toDoubleOrNull() }

    private fun key(title: String): Key? =
        number(title)?.let(Key::Number) ?: title.lowercase().filter { it.isLetterOrDigit() }.ifEmpty { null }?.let(Key::Title)

    private sealed interface Key {
        data class Number(val value: Double) : Key

        data class Title(val text: String) : Key
    }
}
