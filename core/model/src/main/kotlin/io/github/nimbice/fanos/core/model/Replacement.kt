package io.github.nimbice.fanos.core.model

/** A word the reader replaced in the text: [find] shown and read aloud as [replace], in one novel or, [everywhere], in all. */
data class Replacement(
    val id: Long,
    val find: String,
    val replace: String,
    val everywhere: Boolean,
    val wholeWord: Boolean,
    val matchCase: Boolean,
)
