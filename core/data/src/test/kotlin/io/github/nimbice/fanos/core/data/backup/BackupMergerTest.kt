package io.github.nimbice.fanos.core.data.backup

import io.github.nimbice.fanos.core.database.entity.ChapterEntity
import io.github.nimbice.fanos.core.database.entity.ChapterProgressEntity
import org.junit.Test
import kotlin.test.assertEquals

class BackupMergerTest {

    private val now = 9_000L

    private fun stored(id: Long, key: String, readAt: Long? = null, removedAt: Long? = null) =
        ChapterEntity(id = id, novelId = 1, urlKey = key, url = "https://s$key", title = key, sourceIndex = id.toInt(), readAt = readAt, removedAt = removedAt, addedAt = 1)

    private fun backedUp(key: String, index: Int = 0, readAt: Long? = null, removedAt: Long? = null) =
        BackupChapter(key = key, url = "https://s$key", title = key, index = index, readAt = readAt, removedAt = removedAt)

    @Test
    fun `a phone without chapters takes the backup's list as it was`() {
        val chapters = listOf(backedUp("/1", 0, readAt = 5), backedUp("/2", 1), backedUp("/0", 2, removedAt = 3))
        val plan = BackupMerger.chapters(emptyList(), chapters, now)
        assertEquals(chapters, plan.inserts)
        assertEquals(emptyList(), plan.reads)
    }

    @Test
    fun `chapters the phone's list lacks are kept as ones the site stopped listing`() {
        val plan =
            BackupMerger.chapters(
                stored = listOf(stored(10, "/1"), stored(11, "/2")),
                backedUp = listOf(backedUp("/1"), backedUp("/old", readAt = 4), backedUp("/older", removedAt = 2)),
                now = now,
            )
        assertEquals(listOf(backedUp("/old", readAt = 4).copy(removedAt = now), backedUp("/older", removedAt = 2)), plan.inserts)
    }

    @Test
    fun `a chapter read in either stays read, as of when it was read`() {
        val plan =
            BackupMerger.chapters(
                stored = listOf(stored(10, "/1"), stored(11, "/2", readAt = 50), stored(12, "/3", readAt = 60), stored(13, "/4")),
                backedUp = listOf(backedUp("/1", readAt = 40), backedUp("/2"), backedUp("/3", readAt = 70), backedUp("/4")),
                now = now,
            )
        assertEquals(listOf(10L to 40L), plan.reads)
        assertEquals(emptyList(), plan.inserts)
    }

    @Test
    fun `a chapter listed twice in a backup is taken once`() {
        val plan = BackupMerger.chapters(emptyList(), listOf(backedUp("/1", 0, readAt = 5), backedUp("/1", 1)), now)
        assertEquals(listOf(backedUp("/1", 0, readAt = 5)), plan.inserts)
    }

    @Test
    fun `the latest reading position wins`() {
        val onPhone =
            mapOf(
                1L to ChapterProgressEntity(1, 1, block = 5, offset = 0, updatedAt = 100),
                2L to ChapterProgressEntity(2, 1, block = 9, offset = 3, updatedAt = 300),
            )
        val inBackup =
            mapOf(
                1L to BackupProgress(block = 8, offset = 2, updatedAt = 200),
                2L to BackupProgress(block = 1, offset = 0, updatedAt = 250),
                3L to BackupProgress(block = 2, offset = 1, updatedAt = 50),
            )
        assertEquals(mapOf(1L to inBackup.getValue(1L), 3L to inBackup.getValue(3L)), BackupMerger.positions(onPhone, inBackup))
    }
}
