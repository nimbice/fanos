package io.github.nimbice.fanos.core.data.readinglist

import io.github.nimbice.fanos.source.api.ReadingList
import java.net.URI

/** A library novel as its site's reading lists should have it: on the list named like the first of [sections] that names one, bookmarked at [bookmarkUrl]. */
internal data class WantedNovel(val url: String, val sections: List<String>, val bookmarkUrl: String?)

/** What the app last told the site of a novel it put on a list: the list, and where it moved the bookmark. */
internal data class ToldNovel(val url: String, val listId: String, val bookmarkUrl: String?)

/** One thing to tell a site's reading lists. */
internal sealed interface ListStep {
    val novelUrl: String

    data class Put(override val novelUrl: String, val list: ReadingList) : ListStep

    data class Bookmark(override val novelUrl: String, val chapterUrl: String) : ListStep

    data class TakeOff(override val novelUrl: String) : ListStep
}

/**
 * Works out what to tell a site's reading lists so they show what the library does: only what changed since the site was
 * last told, unless [steps] is asked for everything, when every novel is put on its list and bookmarked again (for lists
 * changed on the site since). A novel comes off only when the app put it there: ones the reader keeps there themselves
 * are theirs. A novel is the same whichever way its address is written.
 */
internal object ReadingListPlan {

    fun steps(wanted: List<WantedNovel>, told: List<ToldNovel>, lists: List<ReadingList>, everything: Boolean = false): List<ListStep> {
        if (lists.isEmpty()) return emptyList()
        val before = told.associateBy { key(it.url) }
        val steps = ArrayList<ListStep>()
        for (novel in wanted) {
            val list = checkNotNull(listFor(novel.sections, lists))
            val last = before[key(novel.url)]
            if (everything || last == null || last.listId != list.id) steps += ListStep.Put(novel.url, list)
            val bookmark = novel.bookmarkUrl ?: continue
            if (everything || last == null || last.bookmarkUrl != bookmark) steps += ListStep.Bookmark(novel.url, bookmark)
        }
        val keep = wanted.mapTo(HashSet()) { key(it.url) }
        told.filter { key(it.url) !in keep }.forEach { steps += ListStep.TakeOff(it.url) }
        return steps
    }

    /** The list named like the first of [sections] that has one, else the reader's first list. */
    fun listFor(sections: List<String>, lists: List<ReadingList>): ReadingList? {
        for (section in sections) lists.firstOrNull { it.name.trim().equals(section.trim(), ignoreCase = true) }?.let { return it }
        return lists.firstOrNull()
    }

    /** A novel's address as a key: its path, whatever the scheme, host or closing slash. */
    fun key(url: String): String = (runCatching { URI(url).path }.getOrNull() ?: url).trimEnd('/').lowercase()
}
