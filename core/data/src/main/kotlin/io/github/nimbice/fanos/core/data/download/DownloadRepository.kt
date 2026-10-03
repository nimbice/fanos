package io.github.nimbice.fanos.core.data.download

import io.github.nimbice.fanos.core.common.Clock
import io.github.nimbice.fanos.core.common.Dispatcher
import io.github.nimbice.fanos.core.common.ReaderDispatchers
import io.github.nimbice.fanos.core.data.repository.SettingsRepository
import io.github.nimbice.fanos.core.data.work.DownloadScheduler
import io.github.nimbice.fanos.core.database.TransactionRunner
import io.github.nimbice.fanos.core.database.dao.ChapterDao
import io.github.nimbice.fanos.core.database.dao.ContentDao
import io.github.nimbice.fanos.core.database.dao.DownloadDao
import io.github.nimbice.fanos.core.database.dao.NovelDao
import io.github.nimbice.fanos.core.database.entity.DownloadEntity
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Chapters saved for reading offline: queuing them, their state, and deleting them. The queue lives
 * in the database, so it survives the app closing; [Downloader] works through it in the background.
 */
@Singleton
class DownloadRepository @Inject constructor(
    private val queue: DownloadDao,
    private val contents: ContentDao,
    private val chapters: ChapterDao,
    private val novels: NovelDao,
    private val settings: SettingsRepository,
    private val images: DownloadedImages,
    private val downloader: Downloader,
    private val scheduler: DownloadScheduler,
    private val database: TransactionRunner,
    private val clock: Clock,
    @Dispatcher(ReaderDispatchers.IO) private val io: CoroutineDispatcher,
) {
    /** The download under way, if any. */
    val progress: StateFlow<DownloadProgress?> = downloader.progress

    /** The novel's chapters that are saved, waiting, being fetched or failed, by chapter id. */
    fun observe(novelId: Long): Flow<Map<Long, ChapterDownload>> =
        combine(contents.observeSavedIds(novelId), queue.observeForNovel(novelId), downloader.progress) { saved, queued, run ->
            buildMap {
                saved.forEach { put(it, ChapterDownload.Saved) }
                queued.forEach { put(it.chapterId, it.failure?.let(ChapterDownload::Failed) ?: ChapterDownload.Waiting) }
                run?.current?.forEach { chapter -> if (chapter.novelId == novelId && chapter.chapterId in this) put(chapter.chapterId, ChapterDownload.Downloading) }
            }
        }

    /** How much is saved, for the settings. */
    fun observeStorage(): Flow<DownloadStorage> =
        contents.observeSaved().map { DownloadStorage(it.chapters, it.bytes + images.size()) }.flowOn(io)

    /** The novels with chapters saved, the ones taking the most space first. */
    fun observeSavedNovels(): Flow<List<SavedNovel>> =
        contents.observeSavedByNovel().map { rows ->
            // Images are kept a folder to a chapter, outside the database.
            val sizes = images.sizes()
            val imageBytes = if (sizes.isEmpty()) emptyMap() else contents.savedChapters().groupBy { it.novelId }.mapValues { (_, saved) -> saved.sumOf { sizes[it.chapterId] ?: 0L } }
            rows.map { SavedNovel(it.novelId, it.title, it.coverUrl, it.saved, it.read, it.bytes + (imageBytes[it.novelId] ?: 0L)) }.sortedByDescending { it.bytes }
        }.flowOn(io)

    /** The novels with chapters on the queue, the first asked for first. */
    fun observeQueuedNovels(): Flow<List<QueuedNovel>> =
        queue.observeByNovel().map { rows -> rows.map { QueuedNovel(it.novelId, it.title, it.waiting, it.failed, it.failure) } }

    /** Deletes the novel's saved chapters, or only its read ones; returns how many. */
    suspend fun deleteSaved(novelId: Long, readOnly: Boolean): Int {
        val ids = contents.savedIdsOf(novelId, readOnly)
        delete(ids)
        return ids.size
    }

    /** Deletes every saved chapter that has been read; returns how many. */
    suspend fun deleteAllRead(): Int {
        val ids = contents.allSavedReadIds()
        delete(ids)
        return ids.size
    }

    /** Takes the novel's chapters off the queue, failed ones too; one already being fetched still finishes. */
    suspend fun cancelNovel(novelId: Long) = queue.removeNovel(novelId)

    /** Puts the novel's failed chapters back in line. */
    suspend fun retryNovel(novelId: Long) {
        queue.retryNovel(novelId, clock.now())
        scheduler.kick()
    }

    /** Queues [chapterIds] of [novelId] (any already saved are left be), and tries failed ones again. */
    suspend fun download(novelId: Long, chapterIds: List<Long>) {
        if (chapterIds.isEmpty()) return
        val now = clock.now()
        database.transaction {
            chapterIds.chunked(CHUNK).forEach { ids ->
                val saved = contents.savedAmong(ids).toSet()
                val wanted = ids.filter { it !in saved }
                queue.enqueue(wanted.map { DownloadEntity(chapterId = it, novelId = novelId, queuedAt = now) })
                queue.retry(wanted, now)
            }
        }
        scheduler.kick()
    }

    /**
     * Keeps the chapters after [chapterId] saved, as many as "Download ahead while reading" asks, while
     * the reader reads a library novel. Ones saved or waiting already are left as they are.
     */
    suspend fun downloadAhead(novelId: Long, chapterId: Long) {
        val count = settings.appSettings.first().downloadAhead
        if (count <= 0 || novels.get(novelId)?.inLibrary != true) return
        download(novelId, chapters.after(novelId, chapterId, count))
    }

    /** The reader finished [chapterId]: its download is deleted, when the settings ask for that. */
    suspend fun finishedReading(chapterId: Long) {
        if (settings.appSettings.first().deleteAfterReading) delete(listOf(chapterId))
    }

    /** The unread chapters of [novelId] not yet saved or waiting, in list order. */
    suspend fun unreadToDownload(novelId: Long): List<Long> = chapters.unreadToDownload(novelId)

    /** Queues the novel's next [limit] unread chapters (all of them without one); returns how many. */
    suspend fun downloadUnread(novelId: Long, limit: Int? = null): Int {
        val ids = unreadToDownload(novelId).let { if (limit != null) it.take(limit) else it }
        download(novelId, ids)
        return ids.size
    }

    /** Takes chapters off the queue; one already being fetched still finishes. */
    suspend fun cancel(chapterIds: List<Long>) {
        chapterIds.chunked(CHUNK).forEach { queue.remove(it) }
    }

    /** Empties the queue and stops the download under way. */
    suspend fun cancelAll() {
        queue.clear()
        scheduler.cancel()
    }

    /** Deletes the saved copies of [chapterIds], and takes them off the queue. */
    suspend fun delete(chapterIds: List<Long>) {
        chapterIds.chunked(CHUNK).forEach { ids ->
            queue.remove(ids)
            contents.deleteSaved(ids)
        }
        withContext(io) { chapterIds.forEach(images::delete) }
    }

    /** Deletes every saved chapter and empties the queue. */
    suspend fun deleteAll() {
        cancelAll()
        contents.allSavedIds().chunked(CHUNK).forEach { contents.deleteSaved(it) }
        withContext(io) { images.deleteAll() }
    }

    private companion object {
        const val CHUNK = 500
    }
}

/** Where a chapter is with downloading. */
sealed interface ChapterDownload {
    data object Waiting : ChapterDownload

    data object Downloading : ChapterDownload

    /** Saved for reading offline. */
    data object Saved : ChapterDownload

    data class Failed(val reason: String) : ChapterDownload
}

data class DownloadStorage(val chapters: Int, val bytes: Long)

/** A novel with chapters saved: how many, how many of those are read, and the bytes they take, images too. */
data class SavedNovel(val novelId: Long, val title: String, val coverUrl: String?, val saved: Int, val read: Int, val bytes: Long)

/** A novel with chapters on the download queue: how many wait, how many failed, and why one did. */
data class QueuedNovel(val novelId: Long, val title: String, val waiting: Int, val failed: Int, val failure: String?)
