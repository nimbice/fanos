package io.github.nimbice.fanos.core.data.repository

import io.github.nimbice.fanos.core.model.LibraryFilter
import io.github.nimbice.fanos.core.model.LibraryNovel
import io.github.nimbice.fanos.core.model.LibrarySort
import io.github.nimbice.fanos.core.model.NovelStatus

/**
 * The library as the reader asked to see it: of [novels], in the reader's own order, those that match [query] (in their
 * title, the site's title or the author) and pass every one of [filters], in [sort]'s order. Novels the order doesn't
 * tell apart keep the reader's order between them.
 */
fun arrangeLibrary(novels: List<LibraryNovel>, query: String, filters: Set<LibraryFilter>, sort: LibrarySort): List<LibraryNovel> {
    val words = query.trim().lowercase().split(' ').filter { it.isNotEmpty() }
    val shown =
        novels.filter { item ->
            val text = listOfNotNull(item.novel.title, item.novel.siteTitle, item.novel.author).joinToString(" ").lowercase()
            words.all { it in text } && filters.all { item.passes(it) }
        }
    return when (sort) {
        LibrarySort.Yours -> shown
        LibrarySort.LastRead -> shown.sortedByDescending { it.novel.lastReadAt ?: Long.MIN_VALUE }
        LibrarySort.NewChapters -> shown.sortedByDescending { it.latestChapterAt ?: Long.MIN_VALUE }
        LibrarySort.Title -> shown.sortedBy { it.novel.title.lowercase() }
        LibrarySort.Unread -> shown.sortedByDescending { it.unreadCount }
    }
}

private fun LibraryNovel.passes(filter: LibraryFilter): Boolean =
    when (filter) {
        LibraryFilter.Unread -> unreadCount > 0
        LibraryFilter.Downloaded -> savedChapters > 0
        LibraryFilter.Completed -> novel.status == NovelStatus.Completed
    }
