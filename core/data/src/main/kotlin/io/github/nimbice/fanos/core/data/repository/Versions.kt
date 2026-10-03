package io.github.nimbice.fanos.core.data.repository

import io.github.nimbice.fanos.core.data.move.ChapterMatcher

/** A chapter as one upload of it: its [title], which version of it ([group]) it is, and when it came out. */
internal data class Upload(val title: String, val group: String?, val publishedAt: Long?)

/**
 * The version of a novel's chapters to follow until the reader picks one, for a site whose groups are versions: of
 * those that reach the highest-numbered chapter, the one with the most chapters, and of those the last uploaded. Null
 * when the chapters come in no versions.
 */
internal fun preferredVersion(uploads: List<Upload>): String? =
    uploads.filter { it.group != null }.groupBy { it.group!! }.maxWithOrNull(
        compareBy<Map.Entry<String, List<Upload>>>(
            { (_, version) -> version.maxOf { ChapterMatcher.number(it.title) ?: Double.NEGATIVE_INFINITY } },
            { (_, version) -> version.size },
            { (_, version) -> version.maxOf { it.publishedAt ?: Long.MIN_VALUE } },
        ),
    )?.key
