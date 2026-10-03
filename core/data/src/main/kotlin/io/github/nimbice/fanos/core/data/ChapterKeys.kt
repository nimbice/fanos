package io.github.nimbice.fanos.core.data

import io.github.nimbice.fanos.core.data.download.DownloadedImages
import io.github.nimbice.fanos.core.database.dao.ChapterDao
import io.github.nimbice.fanos.core.database.dao.ProgressDao
import io.github.nimbice.fanos.core.database.entity.ChapterEntity
import javax.inject.Inject
import javax.inject.Singleton

/** Puts [ChapterRekey]'s plans into effect. */
@Singleton
class ChapterKeys @Inject constructor(
    private val chapters: ChapterDao,
    private val progress: ProgressDao,
    private val images: DownloadedImages,
) {

    /**
     * Stores [novelId]'s chapters under the keys [keyOf] gives their addresses, making copies of one chapter one (see
     * [ChapterRekey]); returns the stored chapters as they are then. Runs inside the caller's transaction.
     */
    suspend fun rekey(novelId: Long, keyOf: (String) -> String): List<ChapterEntity> {
        val stored = chapters.all(novelId)
        val keys = stored.associate { it.id to keyOf(it.url) }
        if (stored.all { keys.getValue(it.id) == it.urlKey }) return stored
        val plan = ChapterRekey.plan(stored, keys, if (ChapterRekey.hasCopies(keys)) held(novelId) else emptyMap())
        for (merge in plan.merges) {
            merge.readAt?.let { chapters.markReadAt(merge.keep, it) }
            merge.placeFrom?.let { chapters.moveProgress(it, merge.keep) }
            merge.contentFrom?.let { chapters.moveContent(it, merge.keep) }
            merge.queueFrom?.let { chapters.moveQueued(it, merge.keep) }
            if (merge.relist) chapters.clearRemoved(merge.keep)
            merge.addedAt?.let { chapters.setAddedAt(merge.keep, it) }
            chapters.moveBookmarks(merge.drop, merge.keep)
            chapters.moveHighlights(merge.drop, merge.keep)
            // Their places, copies, queue entries, bookmarks and highlights still left go with them.
            merge.drop.chunked(CHUNK).forEach { chapters.deleteAll(it) }
        }
        // Out of the way first, so no key is for a moment two chapters'.
        plan.keys.keys.chunked(CHUNK).forEach { chapters.parkKeys(it) }
        plan.keys.forEach { (id, key) -> chapters.setKey(id, key) }
        // A saved copy's images go where it went; the dropped copies' others go.
        for (merge in plan.merges) {
            merge.contentFrom?.let { images.move(it, merge.keep) }
            merge.drop.filter { it != merge.contentFrom }.forEach(images::delete)
        }
        return chapters.all(novelId)
    }

    private suspend fun held(novelId: Long): Map<Long, ChapterRekey.Held> {
        val places = progress.forNovel(novelId).associate { it.chapterId to it.updatedAt }
        val contents = chapters.contentIdsOf(novelId).toSet()
        val queued = chapters.queuedIdsOf(novelId).toSet()
        return (places.keys + contents + queued).associateWith { ChapterRekey.Held(places[it], it in contents, it in queued) }
    }

    private companion object {
        const val CHUNK = 500
    }
}
