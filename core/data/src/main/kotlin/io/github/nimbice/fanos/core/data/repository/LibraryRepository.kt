package io.github.nimbice.fanos.core.data.repository

import io.github.nimbice.fanos.core.common.Clock
import io.github.nimbice.fanos.core.common.suspendRunCatching
import io.github.nimbice.fanos.core.data.source.SourceRegistry
import io.github.nimbice.fanos.core.data.toModel
import io.github.nimbice.fanos.core.data.userMessage
import io.github.nimbice.fanos.core.database.TransactionRunner
import io.github.nimbice.fanos.core.database.dao.NovelDao
import io.github.nimbice.fanos.core.model.LibraryNovel
import io.github.nimbice.fanos.core.model.LibraryUpdateProgress
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class LibraryRepository @Inject constructor(
    private val novelDao: NovelDao,
    private val novels: NovelRepository,
    private val sources: SourceRegistry,
    private val database: TransactionRunner,
    private val clock: Clock,
) {
    /** The library in the reader's order. */
    fun observeLibrary(): Flow<List<LibraryNovel>> = novelDao.observeLibrary().map { rows -> rows.map { it.toModel() } }

    /**
     * Keeps the order the reader dragged the library into: [novelIds] from first to last. They can be one
     * section's novels: those take the places they had among the rest, in their new order.
     */
    suspend fun reorder(novelIds: List<Long>) {
        database.transaction {
            mergeOrder(novelDao.libraryIds(), novelIds).forEachIndexed { position, id -> novelDao.setLibraryPosition(id, position) }
        }
    }

    /** Takes the novels out of the library; what comes back puts them back where they were ([putBack]). */
    suspend fun remove(novelIds: List<Long>): List<RemovedNovel> =
        database.transaction {
            novelIds.mapNotNull { id ->
                val novel = novelDao.get(id)?.takeIf { it.inLibrary } ?: return@mapNotNull null
                novelDao.setInLibrary(id, false, null)
                RemovedNovel(id, novel.libraryPosition, novel.addedAt)
            }
        }

    /** Puts novels taken out by [remove] back, each in its old place. */
    suspend fun putBack(removed: List<RemovedNovel>) {
        database.transaction {
            removed.forEach { novel ->
                novelDao.setInLibrary(novel.id, true, novel.addedAt ?: clock.now())
                novelDao.setLibraryPosition(novel.id, novel.position)
            }
        }
    }

    /** Marks every chapter the novels list read, whichever group's; returns the ones that changed, to undo it with. */
    suspend fun markAllRead(novelIds: List<Long>): List<Long> {
        val ids = novelDao.unreadChapterIds(novelIds)
        novels.setRead(ids, read = true)
        return ids
    }

    private val _progress = MutableStateFlow<LibraryUpdateProgress?>(null)

    /** How the running update is going, novel by novel, or null when none is running. */
    val progress: StateFlow<LibraryUpdateProgress?> = _progress.asStateFlow()

    private val running = Mutex()

    /**
     * Checks every library novel for new chapters, in the library's order. Several novels are checked
     * at once; the network layer keeps each site to a couple of requests at a time. A failing novel
     * does not stop the rest, and keeps why it failed for the library to show ([LibraryNovel.updateError]).
     * [progress] follows each novel as it starts and finishes. [onFound] hears of each novel's new chapters
     * once they are saved, so a check stopped part way still knows what it found; it never hears two at once.
     * Novels whose extension isn't installed are left out: there is nothing to check them with. One update
     * runs at a time: one asked for while another runs (the scheduled one and the reader's) leaves it to that one.
     */
    suspend fun update(onFound: suspend (NovelUpdate) -> Unit = {}): LibraryUpdateResult {
        if (!running.tryLock()) return LibraryUpdateResult(checked = 0, withoutExtension = 0, updated = emptyList(), failures = emptyMap())
        try {
            val available = sources.ids()
            val (novelsToCheck, withoutExtension) = novelDao.libraryTitles().partition { it.sourceId in available }
            val waiting = novelsToCheck.mapTo(LinkedHashSet()) { it.id }
            val checking = LinkedHashMap<Long, Long>()
            val lock = Mutex()
            suspend fun report(change: () -> Unit) =
                lock.withLock {
                    change()
                    _progress.value = LibraryUpdateProgress(novelsToCheck.size - waiting.size, novelsToCheck.size, waiting.toSet(), checking.toMap())
                }
            report {}
            val permits = Semaphore(PARALLEL_NOVELS)
            val results =
                coroutineScope {
                    novelsToCheck.map { novel ->
                        async {
                            permits.withPermit {
                                report { checking[novel.id] = System.currentTimeMillis() }
                                val result =
                                    suspendRunCatching { novels.refreshChapters(novel.id) }.map { found ->
                                        NovelUpdate(novel.id, novel.title, found.map { it.title }, found.map { it.id })
                                    }
                                // Kept before the novel stops waiting, so the library shows it the moment it's done.
                                result.exceptionOrNull()?.let { error -> novelDao.setUpdateError(novel.id, userMessage(error)) }
                                report {
                                    checking.remove(novel.id)
                                    waiting.remove(novel.id)
                                }
                                result.getOrNull()?.takeIf { it.chapters.isNotEmpty() }?.let { found -> lock.withLock { onFound(found) } }
                                novel.id to result
                            }
                        }
                    }.awaitAll()
                }
            return LibraryUpdateResult(
                checked = novelsToCheck.size,
                withoutExtension = withoutExtension.size,
                updated = results.mapNotNull { (_, result) -> result.getOrNull()?.takeIf { it.chapters.isNotEmpty() } },
                failures = results.mapNotNull { (id, result) -> result.exceptionOrNull()?.let { id to it } }.toMap(),
            )
        } finally {
            _progress.value = null
            running.unlock()
        }
    }

    private companion object {
        const val PARALLEL_NOVELS = 4
    }
}

data class LibraryUpdateResult(
    val checked: Int,
    /** Library novels left out because their extension isn't installed. */
    val withoutExtension: Int,
    /** The novels that got new chapters, in the library's order. */
    val updated: List<NovelUpdate>,
    val failures: Map<Long, Throwable>,
) {
    val newChapters: Int get() = updated.sumOf { it.chapters.size }
}

/** A novel taken out of the library, and where it was in it. */
data class RemovedNovel(val id: Long, val position: Int, val addedAt: Long?)

/** New chapters a check found for one novel: their titles and ids, in list order. */
data class NovelUpdate(val novelId: Long, val title: String, val chapters: List<String>, val chapterIds: List<Long> = emptyList())

/**
 * The library order [all] with the novels of [part] in [part]'s order, each in a place one of them had: one
 * section reordered within the whole library, the novels outside it left where they were.
 */
internal fun mergeOrder(all: List<Long>, part: List<Long>): List<Long> {
    val present = all.toSet()
    val moved = ArrayDeque(part.filter { it in present })
    val movedIds = moved.toSet()
    return all.map { id -> if (id in movedIds) moved.removeFirst() else id }
}
