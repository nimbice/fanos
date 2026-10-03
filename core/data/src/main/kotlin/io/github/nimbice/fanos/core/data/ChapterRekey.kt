package io.github.nimbice.fanos.core.data

import io.github.nimbice.fanos.core.database.entity.ChapterEntity

/**
 * Keeps a novel's stored chapters under the keys their source gives them now. A source that comes to know chapters by
 * the site's own numbers for them (see NovelSource.chapterKey) changes their keys; and a site that moved its chapters
 * to new addresses before then (Royal Road does when an author renames a story) left two stored copies of each: the one
 * with the reader's read mark and place, no longer listed, and one listed as new. Copies found to be one chapter are
 * made one: the copy holding the most of the reader's is kept, and takes the others' read mark, place, saved copy and
 * place on the download queue where it has none of its own, and the earliest time the app saw any of them, so a copy
 * listed as new doesn't stay among the new chapters; the others go.
 */
internal object ChapterRekey {

    /** What of the reader's a stored chapter holds besides its read mark: its reading place (when kept), a saved copy, a place on the download queue. */
    data class Held(val placeAt: Long? = null, val content: Boolean = false, val queued: Boolean = false)

    data class Merge(
        val keep: Long,
        val drop: List<Long>,
        /** The earliest of the others' read marks, when the kept copy has none. */
        val readAt: Long? = null,
        /** The copy whose reading place, the latest, goes to the kept one. */
        val placeFrom: Long? = null,
        /** The copy whose saved copy goes to the kept one. */
        val contentFrom: Long? = null,
        /** The copy whose place on the download queue goes to the kept one. */
        val queueFrom: Long? = null,
        /** The kept copy is no longer listed, but another is: the chapter is listed. */
        val relist: Boolean = false,
        /** When the app first saw another copy, earlier than the kept one: when it first saw the chapter. */
        val addedAt: Long? = null,
    )

    data class Plan(val merges: List<Merge> = emptyList(), val keys: Map<Long, String> = emptyMap()) {
        val isEmpty: Boolean get() = merges.isEmpty() && keys.isEmpty()
    }

    /** Whether any of [stored] are copies of one chapter under [keys]: only then is what they hold needed. */
    fun hasCopies(keys: Map<Long, String>): Boolean = keys.values.toSet().size < keys.size

    /** [keys] are the stored chapters' keys now, by id; [state], what they hold, where copies are to be made one. */
    fun plan(stored: List<ChapterEntity>, keys: Map<Long, String>, state: Map<Long, Held>): Plan {
        // Stored keys are one to a chapter: unchanged, there are no copies to find.
        if (stored.all { keys.getValue(it.id) == it.urlKey }) return Plan()
        val merges = ArrayList<Merge>()
        val rekeys = HashMap<Long, String>()
        for ((key, copies) in stored.groupBy { keys.getValue(it.id) }) {
            val keep =
                if (copies.size == 1) {
                    copies.single()
                } else {
                    copies.maxWith(compareBy<ChapterEntity>({ weight(it, state[it.id]) }, { it.removedAt == null }, { -it.id }))
                }
            if (copies.size > 1) merges += merge(keep, copies.filter { it.id != keep.id }, state)
            if (keep.urlKey != key) rekeys[keep.id] = key
        }
        return Plan(merges, rekeys)
    }

    private fun merge(keep: ChapterEntity, others: List<ChapterEntity>, state: Map<Long, Held>): Merge {
        val kept = state[keep.id] ?: Held()
        val contentFrom = if (kept.content) null else others.firstOrNull { state[it.id]?.content == true }?.id
        return Merge(
            keep = keep.id,
            drop = others.map { it.id },
            readAt = if (keep.readAt != null) null else others.mapNotNull { it.readAt }.minOrNull(),
            placeFrom = if (kept.placeAt != null) null else others.filter { state[it.id]?.placeAt != null }.maxByOrNull { state.getValue(it.id).placeAt!! }?.id,
            contentFrom = contentFrom,
            queueFrom = if (kept.queued || kept.content || contentFrom != null) null else others.firstOrNull { state[it.id]?.queued == true }?.id,
            relist = keep.removedAt != null && others.any { it.removedAt == null },
            addedAt = others.minOf { it.addedAt }.takeIf { it < keep.addedAt },
        )
    }

    /** How much of the reader's a copy holds: its read mark, place and saved copy. */
    private fun weight(chapter: ChapterEntity, held: Held?): Int =
        listOf(chapter.readAt != null, held?.placeAt != null, held?.content == true).count { it }
}
