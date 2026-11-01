package io.github.nimbice.fanos.core.data.repository

import io.github.nimbice.fanos.core.database.entity.ChapterEntity
import io.github.nimbice.fanos.source.api.ChapterInfo
import io.github.nimbice.fanos.source.api.NewestChapters
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NewestChapterListTest {

    private fun stored(vararg numbers: Int) =
        numbers.mapIndexed { index, n ->
            ChapterEntity(id = n.toLong(), novelId = 1, urlKey = "c$n", url = "https://site/c$n", title = "Chapter $n", sourceIndex = index, addedAt = 0, group = if (n == 2) "v1" else null, locked = n == 3)
        }

    private fun info(vararg numbers: Int) = numbers.map { ChapterInfo("https://site/c$it", "Chapter $it") }

    private val key = { url: String -> url.substringAfterLast('/') }

    @Test
    fun `the stored chapters before the newest, then the newest`() {
        val whole = NewestChapterList.whole(stored(1, 2, 3, 4, 5), NewestChapters(info(4, 5, 6, 7), all = false), key)!!

        assertEquals((1..7).map { "https://site/c$it" }, whole.map { it.url })
        // The stored ones as they were stored.
        assertEquals("v1", whole[1].group)
        assertTrue(whole[2].isLocked)
    }

    @Test
    fun `a stored chapter among the newest that they leave out is left out`() {
        val whole = NewestChapterList.whole(stored(1, 2, 3, 4, 5), NewestChapters(info(3, 5, 6), all = false), key)!!

        assertEquals(listOf(1, 2, 3, 5, 6).map { "https://site/c$it" }, whole.map { it.url })
    }

    @Test
    fun `newest that reach back to no stored chapter, or none at all, leave it to the whole list`() {
        assertNull(NewestChapterList.whole(stored(1, 2), NewestChapters(info(8, 9), all = false), key))
        assertNull(NewestChapterList.whole(stored(1, 2), NewestChapters(emptyList(), all = false), key))
    }

    @Test
    fun `all of them are the whole list`() {
        assertEquals(info(8, 9), NewestChapterList.whole(stored(1, 2), NewestChapters(info(8, 9), all = true), key))
    }
}
