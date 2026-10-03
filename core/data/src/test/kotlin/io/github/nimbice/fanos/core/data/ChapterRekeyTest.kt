package io.github.nimbice.fanos.core.data

import io.github.nimbice.fanos.core.data.ChapterRekey.Held
import io.github.nimbice.fanos.core.data.ChapterRekey.Merge
import io.github.nimbice.fanos.core.database.entity.ChapterEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChapterRekeyTest {

    // Royal Road's addresses: the story's name and the chapter's in them, which change; its number for the chapter, which doesn't.
    private fun stored(id: Long, story: String, number: Int, readAt: Long? = null, removedAt: Long? = null): ChapterEntity {
        val url = "https://www.royalroad.com/fiction/107917/$story/chapter/$number/chapter-$number"
        return ChapterEntity(id = id, novelId = 1, urlKey = url.removePrefix("https://www.royalroad.com"), url = url, title = "Chapter $number", sourceIndex = 0, readAt = readAt, removedAt = removedAt, addedAt = 0)
    }

    private fun keyOf(chapter: ChapterEntity) = "id:" + Regex("""/chapter/(\d+)""").find(chapter.url)!!.groupValues[1]

    private fun plan(stored: List<ChapterEntity>, held: Map<Long, Held> = emptyMap()) = ChapterRekey.plan(stored, stored.associate { it.id to keyOf(it) }, held)

    @Test
    fun `chapters already under their keys need nothing`() {
        val chapters = listOf(stored(1, "sky-pride", 11), stored(2, "sky-pride", 12)).map { it.copy(urlKey = keyOf(it)) }
        assertTrue(plan(chapters).isEmpty)
        assertTrue(!ChapterRekey.hasCopies(chapters.associate { it.id to keyOf(it) }))
        assertTrue(ChapterRekey.hasCopies(listOf(stored(1, "a", 11), stored(2, "b", 11)).associate { it.id to keyOf(it) }))
    }

    @Test
    fun `a chapter only changes its key`() {
        val plan = plan(listOf(stored(1, "sky-pride", 11)))
        assertEquals(mapOf(1L to "id:11"), plan.keys)
        assertTrue(plan.merges.isEmpty())
    }

    @Test
    fun `a renamed story's copies become one, the read copy kept and listed again`() {
        val old = stored(1, "sky-pride", 11, readAt = 500, removedAt = 900)
        val new = stored(2, "sky-pride-vol-1", 11)
        val plan = plan(listOf(old, new))
        assertEquals(listOf(Merge(keep = 1, drop = listOf(2), relist = true)), plan.merges)
        assertEquals(mapOf(1L to "id:11"), plan.keys)
    }

    @Test
    fun `the kept copy takes what the others hold that it doesn't`() {
        // The new copy was read and downloaded after the rename; the old one has the place.
        val old = stored(1, "sky-pride", 11, removedAt = 900)
        val new = stored(2, "sky-pride-vol-1", 11, readAt = 700)
        val held = mapOf(1L to Held(placeAt = 600), 2L to Held(content = true))
        val plan = plan(listOf(old, new), held)
        // Equal holdings: the listed copy stays.
        assertEquals(listOf(Merge(keep = 2, drop = listOf(1), placeFrom = 1)), plan.merges)
        assertEquals(mapOf(2L to "id:11"), plan.keys)
    }

    @Test
    fun `of several read marks the earliest is taken, and of places the latest`() {
        val a = stored(1, "a", 11, readAt = 300)
        val b = stored(2, "b", 11, readAt = 200)
        val c = stored(3, "c", 11)
        val held = mapOf(1L to Held(placeAt = 10, content = true), 2L to Held(placeAt = 50))
        val merge = plan(listOf(a, b, c), held).merges.single()
        assertEquals(1L, merge.keep)
        assertEquals(listOf(2L, 3L), merge.drop)
        assertEquals(null, merge.readAt)
        assertEquals(null, merge.placeFrom)

        // A copy with a saved copy, and three holding one thing each: the oldest of the equals is kept.
        val saved = stored(5, "a", 12)
        val placed = stored(6, "b", 12)
        val read = stored(7, "c", 12, readAt = 150)
        val placedEarlier = stored(8, "d", 12)
        val held2 = mapOf(5L to Held(content = true, queued = true), 6L to Held(placeAt = 90), 8L to Held(placeAt = 40))
        assertEquals(
            Merge(keep = 5, drop = listOf(6, 7, 8), readAt = 150, placeFrom = 6),
            plan(listOf(saved, placed, read, placedEarlier), held2).merges.single(),
        )
    }

    @Test
    fun `the kept copy keeps the earliest arrival, so a copy listed as new isn't new`() {
        // Nothing of the reader's in either: the listed copy, which came in with the rename, is kept.
        val old = stored(1, "sky-pride", 11, removedAt = 900).copy(addedAt = 100)
        val new = stored(2, "sky-pride-vol-1", 11).copy(addedAt = 800)
        assertEquals(listOf(Merge(keep = 2, drop = listOf(1), addedAt = 100)), plan(listOf(old, new)).merges)
    }

    @Test
    fun `a queue entry follows only to a copy with no saved copy or entry of its own`() {
        val old = stored(1, "sky-pride", 11, readAt = 500, removedAt = 900)
        val new = stored(2, "sky-pride-vol-1", 11)
        val merge = plan(listOf(old, new), mapOf(2L to Held(queued = true))).merges.single()
        assertEquals(Merge(keep = 1, drop = listOf(2), queueFrom = 2, relist = true), merge)
    }
}
