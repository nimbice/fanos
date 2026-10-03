package io.github.nimbice.fanos.core.data.move

import android.net.Uri
import io.github.nimbice.fanos.core.common.Clock
import io.github.nimbice.fanos.core.data.download.DownloadRepository
import io.github.nimbice.fanos.core.data.repository.CoverFiles
import io.github.nimbice.fanos.core.data.repository.NovelRepository
import io.github.nimbice.fanos.core.data.source.SourceRegistry
import io.github.nimbice.fanos.core.data.source.SourceUrls
import io.github.nimbice.fanos.core.database.TransactionRunner
import io.github.nimbice.fanos.core.database.dao.ChapterDao
import io.github.nimbice.fanos.core.database.dao.NovelDao
import io.github.nimbice.fanos.core.database.dao.ProgressDao
import io.github.nimbice.fanos.core.database.dao.SectionDao
import io.github.nimbice.fanos.core.database.entity.ChapterProgressEntity
import io.github.nimbice.fanos.core.database.entity.NovelSectionEntity
import io.github.nimbice.fanos.core.model.NovelSummary
import io.github.nimbice.fanos.source.api.ChapterInfo
import javax.inject.Inject
import javax.inject.Singleton

/**
 * What moving a novel to another site would do, worked out from that site's chapter list before anything changes:
 * how many of the chapters read are there too, and where reading goes on.
 */
class MovePlan internal constructor(
    val novelId: Long,
    /** The novel on the other site. */
    val target: NovelSummary,
    /** The other site's name. */
    val site: String,
    /** How many chapters the novel lists where it is now. */
    val chapters: Int,
    /** How many chapters it lists on the other site. */
    val targetChapters: Int,
    /** How many chapters have been read. */
    val read: Int,
    /** How many of those the other site has too. */
    val matched: Int,
    /** The chapter Continue opens there, when there's one left to read. */
    val continueFrom: String?,
    internal val list: List<ChapterInfo>,
    internal val readAt: Map<String, Long>,
    internal val resume: Resume?,
) {
    internal class Resume(val key: String, val updatedAt: Long)
}

/**
 * Moves a novel to another site that has it. The novel there takes its place in the library, its sections, its reading
 * settings and the title and cover the reader gave it; the chapters read here are read there too, matched by
 * [ChapterMatcher], and reading goes on from the same chapter. Unless the reader keeps it, the novel here leaves the
 * library and the reading history, and its saved chapters are deleted; its record, read marks and all, stays.
 */
@Singleton
class NovelMover @Inject constructor(
    private val database: TransactionRunner,
    private val novels: NovelDao,
    private val chapters: ChapterDao,
    private val progress: ProgressDao,
    private val sections: SectionDao,
    private val sources: SourceRegistry,
    private val novelRepository: NovelRepository,
    private val downloads: DownloadRepository,
    private val coverFiles: CoverFiles,
    private val clock: Clock,
) {
    /** Reads the chapter list of [target], on its site, and says what moving the novel [novelId] there would do. */
    suspend fun plan(novelId: Long, target: NovelSummary): MovePlan {
        val source = sources[target.sourceId]
        val list = source.chapterList(target.url)
        val keys = list.map { SourceUrls.chapterKey(source, it.url) }
        // Every chapter, listed now or not: a read mark on one the site has since dropped counts as much as any.
        val stored = chapters.all(novelId).sortedBy { it.sourceIndex }
        val matches = ChapterMatcher.match(stored.map { it.title }, list.map { it.title })
        val readAt = HashMap<String, Long>()
        var read = 0
        stored.forEachIndexed { index, chapter ->
            val at = chapter.readAt ?: return@forEachIndexed
            read++
            matches[index]?.let { readAt[keys[it]] = at }
        }
        // Where reading stopped here, and that chapter there.
        val latest = progress.latest(novelId)
        val stopped = latest?.let { position -> stored.indexOfFirst { it.id == position.chapterId } }?.takeIf { it >= 0 }
        val counterpart = stopped?.let { matches[it] }
        return MovePlan(
            novelId = novelId,
            target = target,
            site = source.name,
            chapters = stored.count { it.removedAt == null },
            targetChapters = list.size,
            read = read,
            matched = readAt.size,
            continueFrom = continueFrom(list, keys, readAt, counterpart, finished = stopped != null && stored[stopped].readAt != null),
            list = list,
            readAt = readAt,
            resume = counterpart?.let { MovePlan.Resume(keys[it], checkNotNull(latest).updatedAt) },
        )
    }

    /**
     * The chapter Continue will open there, found as Continue finds it: the one reading stopped at, or the next when
     * that one was finished; else the first unread one.
     */
    private fun continueFrom(list: List<ChapterInfo>, keys: List<String>, readAt: Map<String, Long>, stopped: Int?, finished: Boolean): String? {
        if (stopped != null) return list.getOrNull(if (finished) stopped + 1 else stopped)?.title
        return list.indices.firstOrNull { keys[it] !in readAt }?.let { list[it].title }
    }

    /** Moves the novel as [plan] says, keeping the novel where it is now as well when [keep]; returns its id on its new site. */
    suspend fun move(plan: MovePlan, keep: Boolean): Long {
        val novel = novels.get(plan.novelId) ?: throw IllegalStateException("This novel is no longer in the app.")
        val movedId = novelRepository.open(plan.target)
        novelRepository.storeChapters(movedId, plan.list)
        // A cover picked from the phone is kept per novel: the moved one gets a copy of its own.
        val cover = novel.customCoverUrl?.let { if (it.startsWith("file:")) coverFiles.keep(movedId, Uri.parse(it)) else it }
        val inSections = sections.sectionsOf(novel.id)
        val now = clock.now()
        database.transaction {
            val listed = chapters.all(movedId).associateBy { it.urlKey }
            plan.readAt.forEach { (key, at) -> listed[key]?.let { chapters.markReadAt(it.id, at) } }
            plan.resume?.let { resume ->
                listed[resume.key]?.let { progress.upsert(ChapterProgressEntity(it.id, movedId, block = 0, offset = 0, updatedAt = resume.updatedAt)) }
            }
            val moved = checkNotNull(novels.get(movedId))
            if (novel.inLibrary) {
                if (!moved.inLibrary) novels.setInLibrary(movedId, true, novel.addedAt ?: now)
                novels.setLibraryPosition(movedId, novel.libraryPosition)
                if (inSections.isNotEmpty()) sections.addNovel(inSections.map { NovelSectionEntity(movedId, it) })
            }
            novel.lastReadAt?.let { if (it > (moved.lastReadAt ?: 0)) novels.setLastReadAt(movedId, it) }
            if (moved.readerSettings == null && novel.readerSettings != null) novels.setReaderSettings(movedId, novel.readerSettings)
            val title = moved.customTitle ?: novel.customTitle
            val customCover = moved.customCoverUrl ?: cover
            if (title != moved.customTitle || customCover != moved.customCoverUrl) novels.setEdits(movedId, title, customCover)
            if (!keep) {
                novels.setInLibrary(novel.id, false, null)
                // Reading goes on in the moved one: the history shows that, not both.
                novels.clearLastReadAt(novel.id)
            }
        }
        if (!keep) downloads.delete(chapters.all(novel.id).map { it.id })
        return movedId
    }
}
