package io.github.nimbice.fanos.core.data.repository

import io.github.nimbice.fanos.core.common.Clock
import io.github.nimbice.fanos.core.database.dao.ChapterDao
import io.github.nimbice.fanos.core.database.dao.NovelDao
import io.github.nimbice.fanos.core.model.ContinueReading
import io.github.nimbice.fanos.core.model.HistoryEntry
import io.github.nimbice.fanos.core.model.RecentChapter
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton
import io.github.nimbice.fanos.core.data.release.releaseTimes
import io.github.nimbice.fanos.core.model.NovelStatus
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** What came in lately (chapters that arrived in library novels) and what was read lately (the novels read, last first). */
@Singleton
class RecentRepository @Inject constructor(
    private val novels: NovelDao,
    private val chapters: ChapterDao,
    private val settings: SettingsRepository,
    private val clock: Clock,
) {

    fun observeUpdates(): Flow<List<RecentChapter>> =
        chapters.observeRecent(LIMIT).map { rows ->
            rows.map { RecentChapter(it.chapterId, it.novelId, it.novelTitle, it.coverUrl, it.chapterTitle, it.addedAt, it.readAt != null, it.saved, it.waiting) }
        }

    fun observeHistory(): Flow<List<HistoryEntry>> =
        novels.observeHistory(LIMIT).map { rows ->
            rows.map { HistoryEntry(it.novelId, it.novelTitle, it.coverUrl, it.inLibrary, it.lastReadAt, it.chapterId, it.chapterTitle) }
        }

    /** Library novels still coming out, with the days their chapters came out of late: when new ones usually do. */
    suspend fun releaseDays(zone: ZoneId): List<NovelReleaseDays> {
        val since = clock.now() - RELEASE_HISTORY_MS
        val times = chapters.releaseTimes(since).groupBy { it.novelId }
        return chapters.releaseSummaries()
            .filter { it.status == NovelStatus.Ongoing || it.status == NovelStatus.Unknown }
            .mapNotNull { novel ->
                val rows = times[novel.novelId] ?: return@mapNotNull null
                val published = rows.mapNotNull { it.publishedAt }.filter { it >= since }
                val chosen = releaseTimes(novel.chapters, novel.dated, novel.firstAddedAt, published, rows.map { it.addedAt })
                NovelReleaseDays(novel.novelId, novel.novelTitle, novel.coverUrl, chosen.map { Instant.ofEpochMilli(it).atZone(zone).toLocalDate() })
            }
    }

    /** The last [limit] novels read, for the continue-reading widget. */
    suspend fun continueReading(limit: Int): List<ContinueReading> =
        novels.continueReading(limit).map { ContinueReading(it.novelId, it.novelTitle, it.coverUrl, it.chapterId, it.chapterTitle, it.unreadCount) }

    /** How many chapters came into library novels, unread, since the Updates tab was last looked at: the number on it. */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun observeUnseenCount(): Flow<Int> =
        settings.newChaptersSeenAt.flatMapLatest { seen -> if (seen == null) flowOf(0) else chapters.observeUnreadRecentCount(seen) }

    /**
     * The Updates tab is looked at: what came in so far no longer counts. Chapters stamped later than now, after the
     * phone's clock was turned back, are seen too, or the number would stay up until the clock caught up with them.
     */
    suspend fun markUpdatesSeen() = settings.setNewChaptersSeenAt(maxOf(clock.now(), chapters.lastAddedAt() ?: 0L))

    /** Counts from now on, the first time the app runs with the Updates tab, rather than every chapter there is. */
    suspend fun startCountingUnseen() {
        if (settings.newChaptersSeenAt.first() == null) markUpdatesSeen()
    }

    /** Takes the novel off the history; the reader's place in it stays. Returns when it was last read, to put it back with. */
    suspend fun forget(novelId: Long): Long? {
        val at = novels.get(novelId)?.lastReadAt
        novels.clearLastReadAt(novelId)
        return at
    }

    suspend fun putBack(novelId: Long, lastReadAt: Long) = novels.setLastReadAt(novelId, lastReadAt)

    private companion object {
        const val LIMIT = 300
    }
}

/** A library novel, and the days its chapters came out of late. */
data class NovelReleaseDays(val novelId: Long, val title: String, val coverUrl: String?, val days: List<LocalDate>)

/** How far back the chapters' dates are looked at for when new ones usually come out: more than the eight weeks used. */
private const val RELEASE_HISTORY_MS = 120L * 24 * 60 * 60 * 1000
