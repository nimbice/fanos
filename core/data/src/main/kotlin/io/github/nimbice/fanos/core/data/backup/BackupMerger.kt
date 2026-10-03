package io.github.nimbice.fanos.core.data.backup

import io.github.nimbice.fanos.core.database.entity.ChapterEntity
import io.github.nimbice.fanos.core.database.entity.ChapterProgressEntity

/**
 * How a backed-up novel merges into the one on the phone. A restore only adds to what the phone
 * has: it deletes nothing, marks no chapter unread and moves no reading position back, so restoring
 * an old backup, or the same one twice, loses nothing.
 */
internal object BackupMerger {

    data class ChapterPlan(
        /** Backed-up chapters the phone doesn't have, as they are to be stored. */
        val inserts: List<BackupChapter>,
        /** Stored chapters that are read in the backup but not on the phone: id, and when they were read. */
        val reads: List<Pair<Long, Long>>,
    )

    /**
     * Chapters are matched by key. The phone's own list, when it has one, is the site's latest word
     * on which chapters there are, so a backed-up chapter missing from it is stored as one the site
     * stopped listing: it keeps its read state and position, and comes back if a later check finds
     * it listed again. A phone without a list of its own takes the backup's as it was.
     */
    fun chapters(stored: List<ChapterEntity>, backedUp: List<BackupChapter>, now: Long): ChapterPlan {
        val byKey = stored.associateBy { it.urlKey }
        val seen = HashSet<String>()
        val inserts = ArrayList<BackupChapter>()
        val reads = ArrayList<Pair<Long, Long>>()
        for (chapter in backedUp) {
            if (!seen.add(chapter.key)) continue
            val local = byKey[chapter.key]
            when {
                local == null -> inserts += if (stored.isEmpty() || chapter.removedAt != null) chapter else chapter.copy(removedAt = now)
                local.readAt == null && chapter.readAt != null -> reads += local.id to chapter.readAt
            }
        }
        return ChapterPlan(inserts, reads)
    }

    /** The backed-up positions to store, by chapter id: where the phone has none for the chapter, or an older one. */
    fun positions(stored: Map<Long, ChapterProgressEntity>, backedUp: Map<Long, BackupProgress>): Map<Long, BackupProgress> =
        backedUp.filter { (chapterId, position) -> stored[chapterId].let { it == null || it.updatedAt < position.updatedAt } }
}
