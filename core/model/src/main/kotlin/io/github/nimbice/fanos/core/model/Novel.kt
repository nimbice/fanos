package io.github.nimbice.fanos.core.model

/**
 * A novel the app knows about: one the user opened, whether or not it is in the library. Its [title]
 * and [coverUrl] are the reader's own when they set them, else the site's ([siteTitle], [siteCoverUrl]).
 */
data class Novel(
    val id: Long,
    val sourceId: String,
    val url: String,
    val title: String,
    val author: String? = null,
    val coverUrl: String? = null,
    val description: String? = null,
    val genres: List<String> = emptyList(),
    val status: NovelStatus = NovelStatus.Unknown,
    val inLibrary: Boolean = false,
    /** When it was added to the library, epoch milliseconds. */
    val addedAt: Long? = null,
    val lastReadAt: Long? = null,
    val detailsFetchedAt: Long? = null,
    val chaptersFetchedAt: Long? = null,
    val siteTitle: String = title,
    val siteCoverUrl: String? = coverUrl,
    /** The translation group whose chapters the reader follows, for a novel several translate; null for all of them. */
    val chapterGroup: String? = null,
    /** The reader picked [chapterGroup]; else, for a site whose groups are versions, the app follows its default one. */
    val chapterGroupChosen: Boolean = false,
    /** The novel's own chapter order, newest first or not; null while it follows the app's. */
    val chaptersNewestFirst: Boolean? = null,
) {
    /** Opened from a book file on the phone, not a site's. */
    val isBook: Boolean get() = sourceId == BOOK_SOURCE

    /** The site's address without "www.": its name when no extension reads from it. A book's is the phone. */
    val site: String get() = if (isBook) BOOK_SITE else runCatching { java.net.URI(url).host?.removePrefix("www.") }.getOrNull() ?: url
}

/** The source of novels opened from book files on the phone: no extension reads them, and nothing is fetched for them. */
const val BOOK_SOURCE = "book"

/** Where a book file's novel says it's from. */
const val BOOK_SITE = "On this device"

/** Whether [url] is a book file's novel or chapter, rather than a page of a site. */
fun isBookUrl(url: String): Boolean = url.startsWith("book:")

enum class NovelStatus { Unknown, Ongoing, Completed, Hiatus, Cancelled }

/** A novel in the library with the counts the library screen shows. */
data class LibraryNovel(
    val novel: Novel,
    val chapterCount: Int,
    val unreadCount: Int,
    /** The sections it's in: never empty, since a novel in none is in the default one. */
    val sectionIds: Set<Long> = setOf(LibrarySection.DEFAULT_ID),
    /** How many of its chapters are saved for offline reading. */
    val savedChapters: Int = 0,
    /** When the latest of the chapters the reader follows arrived, epoch milliseconds. */
    val latestChapterAt: Long? = null,
    /** Why the last library update couldn't check it, until its chapters are fetched cleanly again; null when they were. */
    val updateError: String? = null,
)

/** A section of the library, a tab of its own; a novel can be in several. */
data class LibrarySection(val id: Long, val name: String) {
    /** The section new library novels go in: it can be renamed, not deleted. */
    val isDefault: Boolean get() = id == DEFAULT_ID

    companion object {
        const val DEFAULT_ID = 1L
    }
}

/** How a library update is going: novels checked so far, the ones still to check, and those being checked right now. */
data class LibraryUpdateProgress(
    val done: Int,
    val total: Int,
    /** The novels not checked yet, by id: those being checked and those still to come. */
    val waiting: Set<Long> = emptySet(),
    /** The novels being checked right now, by id, and since when (epoch milliseconds). */
    val checking: Map<Long, Long> = emptyMap(),
)

/** A novel as a source lists it in search results or its catalogue, before the app stores it. */
data class NovelSummary(
    val sourceId: String,
    val url: String,
    val title: String,
    val coverUrl: String? = null,
    val author: String? = null,
    val description: String? = null,
)

data class CatalogPage(val novels: List<NovelSummary>, val hasNextPage: Boolean)

/** A book's cover found online: [thumbnailUrl] to choose from, [coverUrl] to keep. */
data class FoundCover(val title: String, val author: String?, val year: Int?, val thumbnailUrl: String, val coverUrl: String)

/**
 * Covers found for a book. When the book has none yet (not out, say), [covers] are the other books of
 * its [series] instead, which do as well.
 */
data class CoverResults(val covers: List<FoundCover>, val series: String? = null)
