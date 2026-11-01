package io.github.nimbice.fanos.core.data.repository

import io.github.nimbice.fanos.core.database.entity.ChapterEntity
import io.github.nimbice.fanos.source.api.ChapterInfo
import io.github.nimbice.fanos.source.api.NewestChapters

/** A novel's whole chapter list made from the newest chapters a source read (see NovelSource.newestChapters). */
internal object NewestChapterList {

    /**
     * The chapters [listed] before the first of [newest]'s that was stored, then [newest]'s: what the whole list would
     * have been. A stored chapter after that one which the newest leave out is gone from the site, as from a whole list.
     * Null when [newest] reach back to no stored chapter, or have none, and the whole list has to be read to say.
     */
    fun whole(listed: List<ChapterEntity>, newest: NewestChapters, keyOf: (String) -> String): List<ChapterInfo>? {
        if (newest.all) return newest.chapters
        val keys = newest.chapters.mapTo(HashSet()) { keyOf(it.url) }
        val from = listed.sortedBy { it.sourceIndex }.indexOfFirst { keyOf(it.url) in keys }
        if (from < 0) return null
        return listed.sortedBy { it.sourceIndex }.take(from).map { it.info() } + newest.chapters
    }

    private fun ChapterEntity.info(): ChapterInfo =
        ChapterInfo(url, title, publishedAt).byGroup(group).let { if (locked) it.locked() else it }
}
