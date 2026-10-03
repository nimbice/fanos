package io.github.nimbice.fanos.core.data

import io.github.nimbice.fanos.core.data.ChapterReconciler.Listed
import io.github.nimbice.fanos.core.database.entity.ChapterEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChapterReconcilerTest {

    private fun stored(id: Long, key: String, index: Int, removedAt: Long? = null, readAt: Long? = null) =
        ChapterEntity(id = id, novelId = 1, urlKey = key, url = "https://site$key", title = "Chapter $key", sourceIndex = index, readAt = readAt, removedAt = removedAt, addedAt = 0)

    private fun listed(key: String, title: String = "Chapter $key") = Listed(key, "https://site$key", title, null)

    @Test
    fun `the first fetch inserts every chapter in order`() {
        val plan = ChapterReconciler.plan(emptyList(), listOf(listed("/1"), listed("/2"), listed("/3")))

        assertEquals(listOf(0 to "/1", 1 to "/2", 2 to "/3"), plan.inserts.map { (i, c) -> i to c.key })
        assertTrue(plan.updates.isEmpty() && plan.removals.isEmpty())
    }

    @Test
    fun `a locked chapter is listed but not new, and a lock that opens or closes is written`() {
        val first = ChapterReconciler.plan(emptyList(), listOf(listed("/1"), Listed("/2", "https://site/2", "Chapter /2", null, locked = true)))

        assertEquals(listOf("/1", "/2"), first.inserts.map { it.second.key })
        assertEquals(listOf("/1"), first.arrived.map { it.key })

        val opened = ChapterReconciler.plan(listOf(stored(10, "/1", 0), stored(11, "/2", 1).copy(locked = true)), listOf(listed("/1"), listed("/2")))
        assertEquals(listOf(11L), opened.updates.map { it.id })
    }

    @Test
    fun `a chapter takes its group from the list, and keeps it when a list doesn't say`() {
        val first = ChapterReconciler.plan(emptyList(), listOf(listed("/1").copy(group = "Lamp Light")))
        assertEquals(listOf("Lamp Light"), first.inserts.map { it.second.group })

        val grouped = stored(10, "/1", 0).copy(group = "Lamp Light")
        assertTrue(ChapterReconciler.plan(listOf(grouped), listOf(listed("/1"))).isEmpty)
        assertTrue(ChapterReconciler.plan(listOf(grouped), listOf(listed("/1").copy(group = "Lamp Light"))).isEmpty)
        assertEquals(
            listOf("Ember"),
            ChapterReconciler.plan(listOf(grouped), listOf(listed("/1").copy(group = "Ember"))).updates.map { it.chapter.group },
        )
    }

    @Test
    fun `an unchanged list changes nothing`() {
        val plan = ChapterReconciler.plan(listOf(stored(10, "/1", 0), stored(11, "/2", 1)), listOf(listed("/1"), listed("/2")))

        assertTrue(plan.isEmpty)
    }

    @Test
    fun `a chapter inserted mid-list shifts the ones after it and keeps their ids`() {
        val plan =
            ChapterReconciler.plan(
                listOf(stored(10, "/1", 0), stored(11, "/3", 1, readAt = 5)),
                listOf(listed("/1"), listed("/2"), listed("/3")),
            )

        assertEquals(listOf(1 to "/2"), plan.inserts.map { (i, c) -> i to c.key })
        assertEquals(listOf(11L to 2), plan.updates.map { it.id to it.index })
        assertTrue(plan.removals.isEmpty())
    }

    @Test
    fun `a list with no chapters at all removes none`() {
        val plan = ChapterReconciler.plan(listOf(stored(10, "/1", 0), stored(11, "/2", 1)), emptyList())

        assertTrue(plan.isEmpty)
    }

    @Test
    fun `chapters the site stops listing are marked removed once and come back when listed again`() {
        val gone = ChapterReconciler.plan(listOf(stored(10, "/1", 0), stored(11, "/2", 1)), listOf(listed("/1")))
        assertEquals(listOf(11L), gone.removals)

        val stillGone = ChapterReconciler.plan(listOf(stored(10, "/1", 0), stored(11, "/2", 1, removedAt = 7)), listOf(listed("/1")))
        assertTrue(stillGone.isEmpty)

        val back = ChapterReconciler.plan(listOf(stored(10, "/1", 0), stored(11, "/2", 1, removedAt = 7)), listOf(listed("/1"), listed("/2")))
        assertEquals(listOf(11L to 1), back.updates.map { it.id to it.index })
        assertEquals("a chapter that comes back is fetched afresh", listOf(11L), back.restored)
    }

    @Test
    fun `chapters new to the reader are the added ones and unread ones listed again, in list order`() {
        val plan =
            ChapterReconciler.plan(
                listOf(
                    stored(10, "/1", 0, readAt = 5),
                    stored(11, "/2", 1, removedAt = 7, readAt = 5),
                    stored(12, "/3", 2, removedAt = 7),
                    stored(13, "/5", 3),
                ),
                listOf(listed("/1"), listed("/2"), listed("/3"), listed("/4"), listed("/5"), listed("/6")),
            )

        assertEquals(listOf("Chapter /3", "Chapter /4", "Chapter /6"), plan.arrived.map { it.title })
    }

    @Test
    fun `renamed chapters are updated and duplicates in the list are taken once`() {
        val plan =
            ChapterReconciler.plan(
                listOf(stored(10, "/1", 0)),
                listOf(listed("/1", "Chapter 1: The Start"), listed("/2"), listed("/2")),
            )

        assertEquals(listOf("Chapter 1: The Start"), plan.updates.map { it.chapter.title })
        assertEquals(listOf(1 to "/2"), plan.inserts.map { (i, c) -> i to c.key })
    }
}
