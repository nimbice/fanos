package io.github.nimbice.fanos.core.data

import io.github.nimbice.fanos.core.database.entity.ChapterEntity

/**
 * Works out how a freshly fetched chapter list changes the stored one. Every path that refreshes
 * chapters goes through this; the old app had three copies of the logic, with different rules.
 *
 * - A chapter is matched by its key, never by position, so read state and saved content follow it.
 * - Listed chapters take their position from the new list; a chapter inserted mid-list shifts the
 *   ones after it instead of colliding with them.
 * - Chapters the source no longer lists are marked removed, not deleted: they keep their read
 *   state and saved content, and come back if the site lists them again. A chapter that comes back
 *   may have changed (an early-access chapter that has opened up, an author's rewrite), so its
 *   saved copy is dropped then, to be fetched again.
 * - A list with no chapters at all removes none: a site listing nothing is far likelier to be failing,
 *   or to have taken its links down, than to have emptied the novel, and the reader keeps the list.
 * - A chapter the site lists twice is taken at its first position.
 * - Chapters new to the reader are the ones added to the list, and unread ones it lists again (an
 *   early-access chapter that has opened up): both appear in the list where there was nothing. A
 *   locked chapter isn't new: there is nothing in it for the reader yet, to announce or download.
 * - A chapter keeps the translation group it had when a list doesn't say (the site failed to, this once).
 */
internal object ChapterReconciler {

    data class Listed(
        val key: String,
        val url: String,
        val title: String,
        val publishedAt: Long?,
        val locked: Boolean = false,
        val group: String? = null,
    )

    data class Update(val id: Long, val index: Int, val chapter: Listed)

    data class Plan(
        val inserts: List<Pair<Int, Listed>>,
        val updates: List<Update>,
        val removals: List<Long>,
        /** Removed chapters listed again. */
        val restored: List<Long> = emptyList(),
        /** The chapters new to the reader, in list order. */
        val arrived: List<Listed> = emptyList(),
    ) {
        val isEmpty: Boolean get() = inserts.isEmpty() && updates.isEmpty() && removals.isEmpty()
    }

    fun plan(stored: List<ChapterEntity>, listed: List<Listed>): Plan {
        val byKey = stored.associateBy { it.urlKey }
        val seen = HashSet<String>()
        val inserts = ArrayList<Pair<Int, Listed>>()
        val updates = ArrayList<Update>()
        val arrived = ArrayList<Listed>()
        var index = 0
        for (chapter in listed) {
            if (!seen.add(chapter.key)) continue
            val existing = byKey[chapter.key]
            when {
                existing == null -> inserts += index to chapter
                existing.changedFrom(index, chapter) -> updates += Update(existing.id, index, chapter)
            }
            if (!chapter.locked && (existing == null || (existing.removedAt != null && existing.readAt == null))) arrived += chapter
            index++
        }
        val removals = if (listed.isEmpty()) emptyList() else stored.filter { it.urlKey !in seen && it.removedAt == null }.map { it.id }
        val restored = stored.filter { it.urlKey in seen && it.removedAt != null }.map { it.id }
        return Plan(inserts, updates, removals, restored, arrived)
    }

    private fun ChapterEntity.changedFrom(index: Int, chapter: Listed): Boolean =
        sourceIndex != index || title != chapter.title || url != chapter.url || removedAt != null || locked != chapter.locked ||
            (chapter.publishedAt != null && publishedAt != chapter.publishedAt) || (chapter.group != null && group != chapter.group)
}
