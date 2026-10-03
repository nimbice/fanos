package io.github.nimbice.fanos.core.data.download

import io.github.nimbice.fanos.core.common.suspendRunCatching
import io.github.nimbice.fanos.core.data.source.UnknownSourceException
import io.github.nimbice.fanos.core.data.userMessage
import io.github.nimbice.fanos.core.database.dao.DownloadDao
import io.github.nimbice.fanos.core.database.entity.PendingDownloadRow
import io.github.nimbice.fanos.source.api.ChallengeRequiredException
import io.github.nimbice.fanos.source.api.HttpStatusException
import io.github.nimbice.fanos.source.api.SignInRequiredException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.random.Random

/**
 * Works through the download queue without crowding the sites: one chapter at a time from each
 * site, a few seconds apart (on top of the network layer's own limit per host), and at most
 * [PARALLEL_SITES] sites at once.
 *
 * - A chapter the site refuses for good (gone, locked, unreadable) is set aside with the reason.
 * - Trouble that passes (a dropped connection, the site busy or rate limiting) is waited out, a
 *   little longer each time; a site still in trouble after [TRIES] goes is left for a later run,
 *   and its chapters keep their places.
 * - A site that wants a browser check has all its waiting chapters set aside, since nothing will
 *   get through until the reader has passed the check.
 */
@Singleton
class Downloader @Inject constructor(
    private val queue: DownloadDao,
    private val saver: ChapterSaving,
) {
    private val state = MutableStateFlow<DownloadProgress?>(null)

    /** The run under way, if any. */
    val progress: StateFlow<DownloadProgress?> = state.asStateFlow()

    /** Downloads the waiting chapters, and any queued while it runs. [onProgress] hears each change. */
    suspend fun run(onProgress: suspend (DownloadProgress) -> Unit): DownloadOutcome {
        val run = Run(onProgress)
        try {
            while (true) {
                val waiting = queue.waiting().filter { it.sourceId !in run.leftAlone }
                if (waiting.isEmpty()) break
                run.report {}
                val permits = Semaphore(PARALLEL_SITES)
                coroutineScope {
                    waiting.groupBy { it.sourceId }.forEach { (site, chapters) ->
                        launch { permits.withPermit { run.site(site, chapters) } }
                    }
                }
            }
        } finally {
            state.value = null
        }
        return DownloadOutcome(run.downloaded, run.failed.toList(), run.later)
    }

    private inner class Run(private val onProgress: suspend (DownloadProgress) -> Unit) {
        private val lock = Mutex()
        private val current = LinkedHashMap<Long, DownloadingChapter>()
        private var latest: DownloadingChapter? = null
        var downloaded = 0
        val failed = ArrayList<FailedDownload>()
        val leftAlone = HashSet<String>()
        var later = false

        suspend fun report(change: () -> Unit) {
            lock.withLock {
                change()
                current.values.lastOrNull()?.let { latest = it }
                val progress = DownloadProgress(current.values.toList(), downloaded, queue.waitingCount(), latest)
                state.value = progress
                onProgress(progress)
            }
        }

        suspend fun site(site: String, chapters: List<PendingDownloadRow>) {
            var trouble = 0
            for (chapter in chapters) {
                while (true) {
                    // Taken off the queue (or set aside) since the run began.
                    if (!queue.isWaiting(chapter.chapterId)) break
                    report { current[chapter.chapterId] = chapter.toDownloading() }
                    val outcome = suspendRunCatching { saver.save(chapter.chapterId) }
                    val error = outcome.exceptionOrNull()
                    if (error == null) {
                        queue.remove(listOf(chapter.chapterId))
                        report {
                            current.remove(chapter.chapterId)
                            downloaded++
                        }
                        trouble = 0
                        if (outcome.getOrDefault(false)) delay(PACE_MS + Random.nextLong(PACE_JITTER_MS))
                        break
                    }
                    report { current.remove(chapter.chapterId) }
                    when (verdict(error)) {
                        Verdict.GiveUp -> {
                            fail(listOf(chapter), userMessage(error))
                            break
                        }
                        Verdict.CheckInBrowser -> {
                            fail(queue.waiting().filter { it.sourceId == site }, userMessage(error))
                            leave(site, retryLater = false)
                            return
                        }
                        Verdict.WaitAndRetry -> {
                            trouble++
                            if (trouble >= TRIES) {
                                leave(site, retryLater = true)
                                return
                            }
                            delay(BACKOFF_MS * trouble)
                        }
                    }
                }
            }
        }

        private suspend fun fail(chapters: List<PendingDownloadRow>, reason: String) {
            if (chapters.isEmpty()) return
            queue.fail(chapters.map { it.chapterId }, reason)
            report { chapters.forEach { failed += FailedDownload(it.novelId, it.novelTitle, it.chapterTitle, reason) } }
        }

        private suspend fun leave(site: String, retryLater: Boolean) {
            lock.withLock {
                leftAlone += site
                if (retryLater) later = true
            }
        }
    }

    private enum class Verdict { GiveUp, CheckInBrowser, WaitAndRetry }

    private fun verdict(error: Throwable): Verdict =
        when (error) {
            is ChallengeRequiredException, is SignInRequiredException -> Verdict.CheckInBrowser
            is HttpStatusException -> if (error.code == 408 || error.code == 429 || error.code >= 500) Verdict.WaitAndRetry else Verdict.GiveUp
            is UnknownSourceException -> Verdict.GiveUp
            is IOException -> Verdict.WaitAndRetry
            else -> Verdict.GiveUp
        }

    private fun PendingDownloadRow.toDownloading() = DownloadingChapter(chapterId, novelId, novelTitle, chapterTitle)

    private companion object {
        const val PARALLEL_SITES = 2
        const val PACE_MS = 2_000L
        const val PACE_JITTER_MS = 1_500L
        const val TRIES = 3
        const val BACKOFF_MS = 15_000L
    }
}

/** A download run as it goes. */
data class DownloadProgress(
    /** The chapters being fetched now, at most one per site. */
    val current: List<DownloadingChapter>,
    val done: Int,
    /** Chapters still waiting, the ones being fetched included. */
    val left: Int,
    /** The chapter fetched last, or being fetched: what the run is on, between chapters too. */
    val latest: DownloadingChapter? = null,
)

data class DownloadingChapter(val chapterId: Long, val novelId: Long, val novelTitle: String, val chapterTitle: String)

data class FailedDownload(val novelId: Long, val novelTitle: String, val chapterTitle: String, val reason: String)

/** What came of a run. [retryLater]: some site was in trouble, and its chapters wait for another run. */
data class DownloadOutcome(val downloaded: Int, val failed: List<FailedDownload>, val retryLater: Boolean)
