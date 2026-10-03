package io.github.nimbice.fanos.source.api

import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

/**
 * A site novels are read from. An implementation knows the site's pages; the app fetches through
 * [SourceHttp], stores what the source returns, and turns chapter pages into reader content.
 *
 * URLs are absolute. The app keys stored novels and chapters by their path relative to [baseUrl],
 * so a site that moves to a new domain only needs a new [baseUrl].
 */
interface NovelSource {
    /** Stored with every novel from this source; never change it once released. */
    val id: String
    val name: String
    /** ISO 639-1 language of its novels, such as "en", or "all" for a site with novels in many. */
    val lang: String
    val baseUrl: String

    val supportsPopular: Boolean get() = false
    val supportsLatest: Boolean get() = false

    /**
     * What to type in the search box, for a source that finds novels by something other than their
     * titles, such as a creator's name. Since API level 2.
     */
    val searchHint: String? get() = null

    /**
     * What the site's search finds novels by: their titles, as most sites', or the names of the creators who write them,
     * as Patreon's. Looking for a novel it knows, such as one to move from another site, the app asks a site that
     * searches [SearchBy.Creators] for the novel's author rather than its title. Since API level 5.
     */
    val searchBy: SearchBy get() = SearchBy.Titles

    /**
     * Whether the chapters' groups ([ChapterInfo.group]) are versions of the same chapters, the site's uploads of them
     * over again, rather than different translators' work. The app then follows one version from the start, the one
     * that reaches the highest-numbered chapter, the last uploaded of those, until the reader picks another. Since API
     * level 6.
     */
    val chapterGroupsAreVersions: Boolean get() = false

    /**
     * What stays the same of a chapter's address [url] when the site changes the rest of it: the chapter's own number,
     * where the address also carries the novel's name and the chapter's, which authors change. The app knows the chapter
     * by it, so its read mark, reading place and download stay with it when its address changes. Null, as by default,
     * knows chapters by their whole address. Since API level 7.
     */
    fun chapterKey(url: String): String? = null

    /**
     * What stays the same of a novel's address [url] when the site changes the rest of it: the novel's own number, where
     * the address also carries its name, which authors change. The app knows the novel by it, so a renamed novel found
     * again is the one in the library, not another. Null, as by default, knows novels by their whole address. Since API
     * level 8.
     */
    fun novelKey(url: String): String? = null

    /** Pages count from 1. */
    suspend fun search(query: String, page: Int): NovelsPage

    suspend fun popular(page: Int): NovelsPage = throw UnsupportedOperationException("$name has no popular list")

    suspend fun latest(page: Int): NovelsPage = throw UnsupportedOperationException("$name has no latest-updates list")

    suspend fun novelDetails(novelUrl: String): NovelDetails

    /** The novel's chapters in reading order, first to last. */
    suspend fun chapterList(novelUrl: String): List<ChapterInfo>

    suspend fun chapterPage(chapterUrl: String): ChapterPage
}

/** What a source's search takes; see [NovelSource.searchBy]. */
enum class SearchBy { Titles, Creators }

data class NovelsPage(val novels: List<NovelInfo>, val hasNextPage: Boolean)

data class NovelInfo(
    val url: String,
    val title: String,
    val coverUrl: String? = null,
    val author: String? = null,
    val description: String? = null,
)

data class NovelDetails(
    val url: String,
    val title: String,
    val author: String? = null,
    val coverUrl: String? = null,
    val description: String? = null,
    val genres: List<String> = emptyList(),
    val status: Status = Status.Unknown,
    /** Anything else worth showing, such as a rating or view count, by label. */
    val extra: Map<String, String> = emptyMap(),
) {
    enum class Status { Unknown, Ongoing, Completed, Hiatus, Cancelled }
}

data class ChapterInfo(
    val url: String,
    val title: String,
    /** Epoch milliseconds, when the site says. */
    val publishedAt: Long? = null,
) {
    /**
     * The site says the reader can't read it now: it's for paying members, and they aren't signed in or
     * their membership doesn't include it. It's listed all the same, marked. Set with [locked]. Since API
     * level 3; kept out of the constructor so extensions built before it still load.
     */
    var isLocked: Boolean = false
        private set

    /**
     * The translation group that put the chapter out, for a site that lists the same novel's chapters from several
     * groups side by side, as Novel Updates does; the reader can follow one group's. Set with [byGroup]. Since API
     * level 4; kept out of the constructor, as [isLocked] is.
     */
    var group: String? = null
        private set

    /** This chapter, marked as one the reader can't read now (see [isLocked]). Since API level 3. */
    fun locked(): ChapterInfo = copy().also { it.isLocked = true; it.group = group }

    /** This chapter, as [group] put it out (see [ChapterInfo.group]). Since API level 4. */
    fun byGroup(group: String?): ChapterInfo = copy().also { it.isLocked = isLocked; it.group = group }
}

/**
 * A fetched chapter page. [content] is the element holding the chapter text when the source knows
 * where it is. The app gets the whole [document] as well, because some clean-up needs the page
 * around the chapter: a <style> rule that hides text planted in it, or ads placed outside it.
 */
class ChapterPage(val document: Document, val content: Element?)
