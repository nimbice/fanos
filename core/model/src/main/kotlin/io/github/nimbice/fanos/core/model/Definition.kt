package io.github.nimbice.fanos.core.model

/**
 * A word's entry in a dictionary: the [word] found (its base form, often), how it's said, its meanings, the
 * dictionary it's from, the [licence] that dictionary shares its words under, and where the entry is ([sourceUrl]).
 */
data class Definition(
    val word: String,
    val phonetic: String?,
    val meanings: List<Meaning>,
    val source: String,
    val licence: String? = null,
    val sourceUrl: String? = null,
)

/** The senses of a word as one part of speech ("verb"), each with an example perhaps. */
data class Meaning(val partOfSpeech: String, val senses: List<Sense>)

data class Sense(val definition: String, val example: String?)
