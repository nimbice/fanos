package io.github.nimbice.fanos.core.data.repository

import android.net.Uri
import io.github.nimbice.fanos.core.common.Clock
import io.github.nimbice.fanos.core.data.ChapterKeys
import io.github.nimbice.fanos.core.data.ChapterReconciler
import io.github.nimbice.fanos.core.data.NovelKeys
import io.github.nimbice.fanos.core.data.download.DownloadRepository
import io.github.nimbice.fanos.core.data.source.SourceRegistry
import io.github.nimbice.fanos.core.data.source.SourceUrls
import io.github.nimbice.fanos.core.data.toModel
import io.github.nimbice.fanos.core.database.TransactionRunner
import io.github.nimbice.fanos.core.database.dao.ChapterDao
import io.github.nimbice.fanos.core.database.dao.ContentDao
import io.github.nimbice.fanos.core.database.dao.NovelDao
import io.github.nimbice.fanos.core.database.entity.ChapterEntity
import io.github.nimbice.fanos.core.database.entity.NovelEntity
import io.github.nimbice.fanos.core.model.Chapter
import io.github.nimbice.fanos.core.model.ChapterGroup
import io.github.nimbice.fanos.core.model.Novel
import io.github.nimbice.fanos.core.model.NovelSummary
import io.github.nimbice.fanos.source.api.ChapterInfo
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class NovelRepository @Inject constructor(
    private val database: TransactionRunner,
    private val novels: NovelDao,
    private val chapters: ChapterDao,
    private val contents: ContentDao,
    private val sources: SourceRegistry,
    private val settings: SettingsRepository,
    private val downloads: DownloadRepository,
    private val coverFiles: CoverFiles,
    private val chapterKeys: ChapterKeys,
    private val novelKeys: NovelKeys,
    private val clock: Clock,
) {
    fun observeNovel(id: Long): Flow<Novel?> = novels.observe(id).map { it?.toModel() }

    /** The novel's listed chapters the reader follows: the chosen translation group's, when they chose one (see [setChapterGroup]). */
    fun observeChapters(novelId: Long): Flow<List<Chapter>> = chapters.observeListed(novelId).map { list -> list.map { it.toModel() } }

    /** The novel's translation groups, most chapters first; none when the site doesn't say. */
    fun observeGroups(novelId: Long): Flow<List<ChapterGroup>> = chapters.observeGroups(novelId).map { rows -> rows.map { ChapterGroup(it.name, it.chapters) } }

    /** How many chapters the novel lists, whichever group's. */
    fun observeListedCount(novelId: Long): Flow<Int> = chapters.observeListedCount(novelId)

    /**
     * Follows [group]'s chapters of the novel, or all of them for null: the chapter list, Continue, the next and previous
     * chapter, unread counts, new-chapter notices and downloads go by it.
     */
    suspend fun setChapterGroup(novelId: Long, group: String?) {
        novels.setChapterGroup(novelId, group)
    }

    /** Lists the novel's chapters newest first or oldest first, whatever the app's order for other novels. */
    suspend fun setChaptersNewestFirst(novelId: Long, newestFirst: Boolean) {
        novels.setChaptersNewestFirst(novelId, newestFirst)
    }

    /** Goes back to the site's default version of the novel's chapters, for a site whose groups are versions. */
    suspend fun followDefaultVersion(novelId: Long) {
        val listed = chapters.all(novelId).filter { it.removedAt == null }
        novels.useDefaultChapterGroup(novelId, preferredVersion(listed.map { Upload(it.title, it.group, it.publishedAt) }))
    }

    /** The stored novel's id for [summary], storing it first if this is the first time it is opened. */
    suspend fun open(summary: NovelSummary): Long {
        val source = sources[summary.sourceId]
        // The one stored already, though the site has since renamed it in its address.
        novelKeys.find(source, summary.url)?.let { return it.id }
        val key = novelKeys.keyOf(source, summary.url)
        val id =
            novels.insert(
                NovelEntity(
                    sourceId = summary.sourceId,
                    urlKey = key,
                    url = summary.url,
                    title = summary.title,
                    author = summary.author,
                    coverUrl = summary.coverUrl,
                    description = summary.description,
                    createdAt = clock.now(),
                ),
            )
        return if (id > 0) id else checkNotNull(novels.find(summary.sourceId, key)).id
    }

    suspend fun refreshDetails(novelId: Long) {
        val novel = novels.get(novelId) ?: return
        val details = sources[novel.sourceId].novelDetails(novel.url)
        novels.updateDetails(
            id = novelId,
            url = details.url.ifEmpty { novel.url },
            title = details.title.ifEmpty { novel.title },
            author = details.author ?: novel.author,
            coverUrl = details.coverUrl ?: novel.coverUrl,
            description = details.description ?: novel.description,
            genres = details.genres,
            status = details.status.toModel(),
            fetchedAt = clock.now(),
        )
    }

    /**
     * Fetches the novel's chapter list and merges it into the stored one. Returns the titles of the
     * chapters new to the reader, in list order. New chapters of a library novel are queued for
     * download when the settings ask for that.
     */
    suspend fun refreshChapters(novelId: Long): List<ArrivedChapter> {
        val novel = novels.get(novelId) ?: return emptyList()
        return store(novel, sources[novel.sourceId].chapterList(novel.url))
    }

    /** Merges [list], the novel's chapters as its source lists them, into the stored ones, as [refreshChapters] does. */
    internal suspend fun storeChapters(novelId: Long, list: List<ChapterInfo>): List<ArrivedChapter> {
        val novel = novels.get(novelId) ?: return emptyList()
        return store(novel, list)
    }

    private suspend fun store(novel: NovelEntity, list: List<ChapterInfo>): List<ArrivedChapter> {
        val novelId = novel.id
        val source = sources[novel.sourceId]
        val listed = list.map { ChapterReconciler.Listed(SourceUrls.chapterKey(source, it.url), it.url, it.title, it.publishedAt, it.isLocked, it.group) }
        val now = clock.now()
        val arrived = database.transaction {
            // The stored chapters under the keys their source gives them now, so a chapter whose address changed stays one.
            val stored = chapterKeys.rekey(novelId) { SourceUrls.chapterKey(source, it) }
            val plan = ChapterReconciler.plan(stored, listed)
            if (plan.inserts.isNotEmpty()) {
                chapters.insertAll(
                    plan.inserts.map { (index, chapter) ->
                        ChapterEntity(
                            novelId = novelId,
                            urlKey = chapter.key,
                            url = chapter.url,
                            title = chapter.title,
                            sourceIndex = index,
                            publishedAt = chapter.publishedAt,
                            addedAt = now,
                            locked = chapter.locked,
                            group = chapter.group,
                        )
                    },
                )
            }
            plan.updates.forEach {
                chapters.updateListing(it.id, it.chapter.url, it.chapter.title, it.index, it.chapter.publishedAt, it.chapter.locked, it.chapter.group)
            }
            plan.removals.chunked(CHUNK).forEach { chapters.markRemoved(it, now) }
            plan.restored.chunked(CHUNK).forEach { contents.delete(it) }
            novels.setChaptersFetchedAt(novelId, now)
            // Fetched cleanly, from the library update or the novel's page: what an update failed with before is past.
            novels.setUpdateError(novelId, null)
            novels.dropMissingChapterGroup(novelId)
            // A site whose groups are versions of the same chapters: the default one is followed until the reader picks.
            if (source.chapterGroupsAreVersions) novels.followDefaultChapterGroup(novelId, preferredVersion(list.map { Upload(it.title, it.group, it.publishedAt) }))
            // The first fetch fills the list; only later ones bring "new" chapters, and only the followed group's.
            val group = novels.get(novelId)?.chapterGroup
            if (novel.chaptersFetchedAt == null) emptyList() else plan.arrived.filter { group == null || it.group == null || it.group == group }
        }
        if (arrived.isEmpty()) return emptyList()
        val ids = chapters.all(novelId).associate { it.urlKey to it.id }
        val found = arrived.mapNotNull { chapter -> ids[chapter.key]?.let { ArrivedChapter(it, chapter.title) } }
        if (novel.inLibrary && settings.appSettings.first().downloadNewChapters) downloads.download(novelId, found.map { it.id })
        return found
    }

    /**
     * Shows the reader's own [title] and [coverUrl] for the novel instead of the site's. Either one blank,
     * or the same as the site's, goes back to the site's, which refreshes keep up to date meanwhile.
     */
    suspend fun setEdits(novelId: Long, title: String, coverUrl: String?) {
        val novel = novels.get(novelId) ?: return
        val customTitle = title.trim().takeIf { it.isNotEmpty() && it != novel.title }
        val customCover = coverUrl?.takeIf { it.isNotBlank() && it != novel.coverUrl }
        novels.setEdits(novelId, customTitle, customCover)
        coverFiles.tidy(novelId, customCover)
    }

    /** Keeps a copy of a picture from the phone to use as the novel's cover; returns the copy's address. */
    suspend fun keepPickedCover(novelId: Long, picture: Uri): String = coverFiles.keep(novelId, picture)

    /** Lets go of pictures picked for the novel's cover but not kept. */
    suspend fun discardPickedCovers(novelId: Long) {
        coverFiles.tidy(novelId, novels.get(novelId)?.customCoverUrl)
    }

    suspend fun setInLibrary(novelId: Long, inLibrary: Boolean) {
        novels.setInLibrary(novelId, inLibrary, if (inLibrary) clock.now() else null)
    }

    suspend fun setRead(chapterIds: List<Long>, read: Boolean) {
        val now = clock.now()
        chapterIds.chunked(CHUNK).forEach { if (read) chapters.markRead(it, now) else chapters.markUnread(it) }
    }

    private companion object {
        const val CHUNK = 500
    }
}

/** A chapter a refresh found new, in the order the site lists them. */
data class ArrivedChapter(val id: Long, val title: String)
