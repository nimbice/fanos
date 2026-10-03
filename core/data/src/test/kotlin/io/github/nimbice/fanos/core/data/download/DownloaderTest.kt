package io.github.nimbice.fanos.core.data.download

import io.github.nimbice.fanos.core.database.dao.DownloadDao
import io.github.nimbice.fanos.core.database.entity.DownloadEntity
import io.github.nimbice.fanos.core.database.entity.PendingDownloadRow
import io.github.nimbice.fanos.source.api.ChallengeRequiredException
import io.github.nimbice.fanos.source.api.HttpStatusException
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.io.IOException
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DownloaderTest {

    /** The queue in memory: chapter id to site, and why it failed if it did. */
    private class Queue(vararg chapters: Pair<Long, String>) : DownloadDao {
        val sites = linkedMapOf(*chapters)
        val failures = HashMap<Long, String>()

        override suspend fun enqueue(downloads: List<DownloadEntity>) = error("unused")

        override suspend fun retry(chapterIds: List<Long>, time: Long) = error("unused")

        override suspend fun fail(chapterIds: List<Long>, failure: String) {
            chapterIds.filter { it in sites }.forEach { failures[it] = failure }
        }

        override suspend fun remove(chapterIds: List<Long>) {
            chapterIds.forEach { sites.remove(it) }
        }

        override suspend fun clear() = sites.clear()

        override suspend fun isWaiting(chapterId: Long) = chapterId in sites && chapterId !in failures

        override suspend fun waiting() =
            sites.filterKeys { it !in failures }.map { (id, site) -> PendingDownloadRow(id, 1, site, "Novel", "Chapter $id") }

        override suspend fun waitingCount() = waiting().size

        override fun observeForNovel(novelId: Long) = error("unused")

        override fun observeByNovel() = error("unused")

        override suspend fun removeNovel(novelId: Long) = error("unused")

        override suspend fun retryNovel(novelId: Long, time: Long) = error("unused")
    }

    /** Saves that take a moment, so overlapping ones show; [siteOf] tells them apart by site. */
    private class Saves(
        private val scope: TestScope,
        private val siteOf: (Long) -> String = { "a" },
        private val answer: suspend (Long) -> Boolean = { true },
    ) : ChapterSaving {
        val calls = ArrayList<Pair<Long, Long>>()
        private val busy = HashMap<String, Int>()
        var mostAtOnce = 0
        var mostAtOnceFromOneSite = 0

        override suspend fun save(chapterId: Long): Boolean {
            calls += chapterId to scope.currentTime
            val site = siteOf(chapterId)
            busy[site] = (busy[site] ?: 0) + 1
            mostAtOnce = maxOf(mostAtOnce, busy.values.sum())
            mostAtOnceFromOneSite = maxOf(mostAtOnceFromOneSite, busy.getValue(site))
            try {
                delay(100)
                return answer(chapterId)
            } finally {
                busy[site] = busy.getValue(site) - 1
            }
        }
    }

    @Test
    fun `every chapter is downloaded once, a site's a few seconds apart`() =
        runTest {
            val queue = Queue(1L to "a", 2L to "a", 3L to "a")
            val saves = Saves(this)
            val outcome = Downloader(queue, saves).run {}
            assertEquals(DownloadOutcome(3, emptyList(), retryLater = false), outcome)
            assertEquals(listOf(1L, 2L, 3L), saves.calls.map { it.first })
            assertTrue(queue.sites.isEmpty())
            saves.calls.zipWithNext { (_, before), (_, after) -> assertTrue(after - before >= 2_000, "only ${after - before} ms apart") }
        }

    @Test
    fun `two sites go side by side, each one chapter at a time`() =
        runTest {
            val sites = mapOf(1L to "a", 2L to "a", 3L to "b", 4L to "b", 5L to "c")
            val saves = Saves(this, siteOf = sites::getValue)
            Downloader(Queue(*sites.toList().toTypedArray()), saves).run {}
            assertEquals(5, saves.calls.size)
            assertEquals(1, saves.mostAtOnceFromOneSite)
            assertEquals(2, saves.mostAtOnce)
        }

    @Test
    fun `a chapter already stored costs the site nothing and waits for nothing`() =
        runTest {
            val saves = Saves(this) { _ -> false }
            Downloader(Queue(1L to "a", 2L to "a"), saves).run {}
            assertTrue(currentTime < 1_000, "took $currentTime ms")
        }

    @Test
    fun `a chapter the site refuses is set aside and the rest go on`() =
        runTest {
            val queue = Queue(1L to "a", 2L to "a", 3L to "a")
            val saves = Saves(this) { id -> if (id == 2L) throw HttpStatusException("https://a/2", 404) else true }
            val outcome = Downloader(queue, saves).run {}
            assertEquals(2, outcome.downloaded)
            assertEquals(listOf("The site no longer has this page."), outcome.failed.map { it.reason })
            assertEquals(mapOf(2L to "The site no longer has this page."), queue.failures)
            assertEquals(false, outcome.retryLater)
        }

    @Test
    fun `trouble that passes is waited out`() =
        runTest {
            var tries = 0
            val saves = Saves(this) { if (++tries == 1) throw IOException("reset") else true }
            val outcome = Downloader(Queue(1L to "a"), saves).run {}
            assertEquals(1, outcome.downloaded)
            assertEquals(2, saves.calls.size)
            assertTrue(saves.calls[1].second - saves.calls[0].second >= 15_000)
        }

    @Test
    fun `a site still in trouble is left for a later run, its chapters keeping their places`() =
        runTest {
            val queue = Queue(1L to "a", 2L to "a", 3L to "b")
            val saves = Saves(this) { id -> if (id != 3L) throw HttpStatusException("https://a/$id", 503) else true }
            val outcome = Downloader(queue, saves).run {}
            assertEquals(true, outcome.retryLater)
            assertEquals(3, saves.calls.count { it.first == 1L })
            assertEquals(0, saves.calls.count { it.first == 2L })
            assertEquals(setOf(1L, 2L), queue.sites.keys)
            assertTrue(queue.failures.isEmpty())
        }

    @Test
    fun `a site that wants a browser check has its chapters set aside`() =
        runTest {
            val queue = Queue(1L to "a", 2L to "a", 3L to "b")
            val saves = Saves(this) { id -> if (id != 3L) throw ChallengeRequiredException("https://a/$id") else true }
            val outcome = Downloader(queue, saves).run {}
            assertEquals(setOf(1L, 2L), queue.failures.keys)
            assertEquals(1, saves.calls.count { it.first != 3L })
            assertEquals(1, outcome.downloaded)
            assertEquals(false, outcome.retryLater)
        }

    @Test
    fun `a chapter taken off the queue meanwhile is skipped`() =
        runTest {
            val queue = Queue(1L to "a", 2L to "a", 3L to "a")
            val saves =
                Saves(this) { id ->
                    if (id == 1L) queue.remove(listOf(2L))
                    true
                }
            Downloader(queue, saves).run {}
            assertEquals(listOf(1L, 3L), saves.calls.map { it.first })
        }

    @Test
    fun `chapters queued while it runs are downloaded too`() =
        runTest {
            val queue = Queue(1L to "a")
            val saves =
                Saves(this) { id ->
                    if (id == 1L) queue.sites[2L] = "b"
                    true
                }
            val outcome = Downloader(queue, saves).run {}
            assertEquals(2, outcome.downloaded)
        }
}
