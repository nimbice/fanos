package io.github.nimbice.fanos.core.data

import io.github.nimbice.fanos.core.database.entity.NovelEntity

/**
 * Keeps a source's stored novels under the keys it gives them now. A source that comes to know novels by the site's own
 * numbers for them (see NovelSource.novelKey) changes their keys; and a novel opened again after the site renamed it in
 * its address, before then, was stored a second time. Of novels found to be one, the one in the library (else the one
 * read, else the oldest) takes the key; another one nothing of the reader's is in (not in the library, never read) goes,
 * and one that has something stays as it was, for the reader to sort out.
 */
internal object NovelRekey {

    data class Plan(val keys: Map<Long, String> = emptyMap(), val drops: List<Long> = emptyList()) {
        val isEmpty: Boolean get() = keys.isEmpty() && drops.isEmpty()
    }

    /** [keys] are the stored novels' keys now, by id. */
    fun plan(stored: List<NovelEntity>, keys: Map<Long, String>): Plan {
        if (stored.all { keys.getValue(it.id) == it.urlKey }) return Plan()
        val rekeys = HashMap<Long, String>()
        val drops = ArrayList<Long>()
        for ((key, copies) in stored.groupBy { keys.getValue(it.id) }) {
            val keep = copies.maxWith(compareBy<NovelEntity>({ it.inLibrary }, { it.lastReadAt != null }, { -it.id }))
            copies.filter { it.id != keep.id && !it.inLibrary && it.lastReadAt == null }.mapTo(drops) { it.id }
            if (keep.urlKey != key) rekeys[keep.id] = key
        }
        return Plan(rekeys, drops)
    }
}
