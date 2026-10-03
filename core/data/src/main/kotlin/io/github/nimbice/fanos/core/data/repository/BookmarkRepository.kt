package io.github.nimbice.fanos.core.data.repository

import io.github.nimbice.fanos.core.common.Clock
import io.github.nimbice.fanos.core.database.dao.BookmarkDao
import io.github.nimbice.fanos.core.database.entity.BookmarkEntity
import io.github.nimbice.fanos.core.model.Bookmark
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/** Places the reader marked in a novel's chapters, a paragraph each. */
@Singleton
class BookmarkRepository @Inject constructor(private val bookmarks: BookmarkDao, private val clock: Clock) {

    /** The novel's bookmarks, the latest first. */
    fun observe(novelId: Long): Flow<List<Bookmark>> =
        bookmarks.observeForNovel(novelId).map { rows -> rows.map { Bookmark(it.id, it.chapterId, it.chapterTitle, it.block, it.snippet, it.createdAt) } }

    /** Bookmarks paragraph [block] of [chapterId], or takes its bookmark off if it has one; true when it's bookmarked now. */
    suspend fun toggle(novelId: Long, chapterId: Long, block: Int, text: String): Boolean {
        val there = bookmarks.find(chapterId, block)
        if (there != null) {
            bookmarks.delete(there.id)
            return false
        }
        bookmarks.insert(BookmarkEntity(novelId = novelId, chapterId = chapterId, block = block, snippet = snippetOf(text), createdAt = clock.now()))
        return true
    }

    suspend fun remove(id: Long) = bookmarks.delete(id)

    companion object {
        /** What a bookmark shows of its paragraph: its start, to a word's end. */
        fun snippetOf(text: String): String {
            val plain = text.trim().replace(Regex("\\s+"), " ")
            if (plain.length <= SNIPPET) return plain
            val cut = plain.lastIndexOf(' ', SNIPPET).takeIf { it > SNIPPET / 2 } ?: SNIPPET
            return plain.substring(0, cut) + "\u2026"
        }

        private const val SNIPPET = 120
    }
}
