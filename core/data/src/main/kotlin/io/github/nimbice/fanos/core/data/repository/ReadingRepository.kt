package io.github.nimbice.fanos.core.data.repository

import io.github.nimbice.fanos.core.common.Clock
import io.github.nimbice.fanos.core.data.toModel
import io.github.nimbice.fanos.core.database.dao.ChapterDao
import io.github.nimbice.fanos.core.database.dao.NovelDao
import io.github.nimbice.fanos.core.database.dao.ProgressDao
import io.github.nimbice.fanos.core.database.entity.ChapterProgressEntity
import io.github.nimbice.fanos.core.model.Chapter
import io.github.nimbice.fanos.core.model.ReadingPosition
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/** Reading positions, read state as the reader moves on, and which chapter comes next. */
@Singleton
class ReadingRepository @Inject constructor(
    private val novels: NovelDao,
    private val chapters: ChapterDao,
    private val progress: ProgressDao,
    private val clock: Clock,
) {
    suspend fun chapter(id: Long): Chapter? = chapters.get(id)?.toModel()

    /** The novel's listed chapters in reading order, by id. */
    fun observeChapterIds(novelId: Long): Flow<List<Long>> = chapters.observeListedIds(novelId)

    fun observeChapter(id: Long): Flow<Chapter?> = chapters.observe(id).map { it?.toModel() }

    suspend fun position(chapterId: Long): ReadingPosition =
        progress.get(chapterId)?.let { ReadingPosition(it.block, it.offset) } ?: ReadingPosition.Start

    /** Records where the reader is. Writes only the progress row and the novel's last-read time. */
    suspend fun savePosition(chapterId: Long, position: ReadingPosition) {
        val chapter = chapters.get(chapterId) ?: return
        val now = clock.now()
        progress.upsert(ChapterProgressEntity(chapterId, chapter.novelId, position.block, position.offset, now))
        novels.setLastReadAt(chapter.novelId, now)
    }

    suspend fun markRead(chapterId: Long) = chapters.markRead(listOf(chapterId), clock.now())

    suspend fun next(chapterId: Long): Chapter? {
        val chapter = chapters.get(chapterId) ?: return null
        return chapters.next(chapter.novelId, chapter.sourceIndex)?.toModel()
    }

    suspend fun previous(chapterId: Long): Chapter? {
        val chapter = chapters.get(chapterId) ?: return null
        return chapters.previous(chapter.novelId, chapter.sourceIndex)?.toModel()
    }

    /**
     * The chapter "Continue" opens: where reading last stopped (or the next one, when that chapter
     * was finished), else the first unread chapter, else the first. All of them among the chapters
     * of the translation group the reader follows, when they chose one.
     */
    suspend fun resumeChapter(novelId: Long): Chapter? {
        val group = novels.get(novelId)?.chapterGroup
        val last = progress.latest(novelId)?.let { chapters.get(it.chapterId) }
        if (last != null && last.removedAt == null && (group == null || last.group == null || last.group == group)) {
            if (last.readAt == null) return last.toModel()
            chapters.next(novelId, last.sourceIndex)?.let { return it.toModel() }
            return last.toModel()
        }
        return (chapters.firstUnread(novelId) ?: chapters.first(novelId))?.toModel()
    }

    /** The last chapter the reader had open, for labelling the novel screen's continue button. */
    fun observeLastRead(novelId: Long): Flow<Long?> = progress.observeLatest(novelId).map { it?.chapterId }
}
