package io.github.nimbice.fanos.source.api

/**
 * A source whose site keeps reading lists for readers signed in to it, as Novel Updates does. The app can keep them in
 * step with its library: it puts the library's novels from the source on the reader's lists, moves their bookmarks on as
 * the reader reads, and takes them off when the reader takes them out of the library. The app only ever tells the site;
 * nothing done on the site changes the library. A source offers this by implementing it as well as [NovelSource].
 *
 * The reader signs in on the site in the app's browser; until they do, each of these throws [SignInRequiredException].
 * Since API level 4.
 */
interface ReadingListSource {
    /** The reader's reading lists, in the site's order. */
    suspend fun readingLists(): List<ReadingList>

    /** The novels on [list], each with its bookmark. */
    suspend fun listedNovels(list: ReadingList): List<ListedNovel>

    /** Puts the novel at [novelUrl] on [list], taking it off any other of the reader's lists. */
    suspend fun putOnList(novelUrl: String, list: ReadingList)

    /** Moves the novel's bookmark to [chapterUrl], one of its chapters as [NovelSource.chapterList] gives them. */
    suspend fun setBookmark(novelUrl: String, chapterUrl: String)

    /** Takes the novel off the reader's lists. */
    suspend fun takeOffLists(novelUrl: String)
}

/** One of the reader's reading lists; [id] is the site's own for it. */
data class ReadingList(val id: String, val name: String)

/** A novel on a reading list: its URL, as the source's novels have them, and the chapter its bookmark is at, when it has one. */
data class ListedNovel(val novelUrl: String, val bookmarkUrl: String? = null)
