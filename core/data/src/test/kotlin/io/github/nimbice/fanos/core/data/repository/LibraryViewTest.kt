package io.github.nimbice.fanos.core.data.repository

import io.github.nimbice.fanos.core.model.LibraryFilter
import io.github.nimbice.fanos.core.model.LibraryNovel
import io.github.nimbice.fanos.core.model.LibrarySort
import io.github.nimbice.fanos.core.model.Novel
import io.github.nimbice.fanos.core.model.NovelStatus
import org.junit.Assert.assertEquals
import org.junit.Test

class LibraryViewTest {

    private fun novel(id: Long, title: String, unread: Int = 0, saved: Int = 0, lastRead: Long? = null, latest: Long? = null, author: String? = null, completed: Boolean = false) =
        LibraryNovel(
            Novel(id = id, sourceId = "s", url = "https://s/$id", title = title, author = author, lastReadAt = lastRead, status = if (completed) NovelStatus.Completed else NovelStatus.Ongoing),
            chapterCount = 10,
            unreadCount = unread,
            savedChapters = saved,
            latestChapterAt = latest,
        )

    private val library =
        listOf(
            novel(1, "Lord of the Mysteries", unread = 1398, lastRead = 50, latest = 900, saved = 3),
            novel(2, "Magical Girl Killer", unread = 0, lastRead = 70, latest = 100, completed = true),
            novel(3, "Mother of Learning", unread = 86, lastRead = null, latest = 500, author = "nobody103", completed = true),
            novel(4, "Lord of Mysteries: Circle of Inevitability", unread = 212, lastRead = 60, latest = null),
        )

    private fun ids(list: List<LibraryNovel>) = list.map { it.novel.id }

    @Test
    fun `the reader's own order is kept, and every order keeps it between novels it doesn't tell apart`() {
        assertEquals(listOf(1L, 2, 3, 4), ids(arrangeLibrary(library, "", emptySet(), LibrarySort.Yours)))
        assertEquals(listOf(2L, 4, 1, 3), ids(arrangeLibrary(library, "", emptySet(), LibrarySort.LastRead)))
        assertEquals(listOf(1L, 3, 2, 4), ids(arrangeLibrary(library, "", emptySet(), LibrarySort.NewChapters)))
        assertEquals(listOf(4L, 1, 2, 3), ids(arrangeLibrary(library, "", emptySet(), LibrarySort.Title)))
        assertEquals(listOf(1L, 4, 3, 2), ids(arrangeLibrary(library, "", emptySet(), LibrarySort.Unread)))
    }

    @Test
    fun `a search matches every word, in the title or the author, whatever the case`() {
        assertEquals(listOf(1L, 4), ids(arrangeLibrary(library, "LORD", emptySet(), LibrarySort.Yours)))
        assertEquals(listOf(4L), ids(arrangeLibrary(library, "lord circle", emptySet(), LibrarySort.Yours)))
        assertEquals(listOf(3L), ids(arrangeLibrary(library, "nobody", emptySet(), LibrarySort.Yours)))
    }

    @Test
    fun `filters narrow the library, all of them at once`() {
        assertEquals(listOf(1L, 3, 4), ids(arrangeLibrary(library, "", setOf(LibraryFilter.Unread), LibrarySort.Yours)))
        assertEquals(listOf(1L), ids(arrangeLibrary(library, "", setOf(LibraryFilter.Downloaded), LibrarySort.Yours)))
        assertEquals(listOf(3L), ids(arrangeLibrary(library, "", setOf(LibraryFilter.Unread, LibraryFilter.Completed), LibrarySort.Yours)))
    }
}
