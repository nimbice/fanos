package io.github.nimbice.fanos.core.data.backup

import io.github.nimbice.fanos.core.data.repository.StoredReaderSettings
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/**
 * A Fanos backup: the library in its order, each novel with its chapters, read state and reading
 * positions, and the settings. Chapter text is left out, since it can be fetched again, which keeps
 * a backup to a few megabytes at most.
 *
 * The file is this as gzipped JSON (see [BackupCodec]). Readers skip fields they don't know and
 * fill in ones a backup lacks, so adding a field leaves [BackupCodec.VERSION] alone. The version
 * goes up only when an older app would misread a newer backup, and an app refuses backups with a
 * higher version than its own rather than misread them.
 */
@Serializable
internal data class Backup(
    val format: String,
    val version: Int,
    val createdAt: Long,
    /** The version of the app that made it. */
    val app: String? = null,
    /** Settings by name, as stored; absent from backups made without them. */
    val settings: JsonObject? = null,
    /** The library's sections in their order; absent from backups made before there were any. */
    val sections: List<BackupSection> = emptyList(),
    /** The library, top first. */
    val novels: List<BackupNovel> = emptyList(),
    /** Word replacements for all novels. */
    val replacements: List<BackupReplacement> = emptyList(),
)

@Serializable
internal data class BackupNovel(
    val source: String,
    /** Identity within the source, as stored: stays the same when a site moves to a new domain. */
    val key: String,
    val url: String,
    val title: String,
    val author: String? = null,
    val cover: String? = null,
    val description: String? = null,
    val genres: List<String> = emptyList(),
    val status: String? = null,
    val addedAt: Long? = null,
    val lastReadAt: Long? = null,
    val detailsFetchedAt: Long? = null,
    val chaptersFetchedAt: Long? = null,
    /** The reader's own title and cover, when they set them; a cover only when it's on the web, not a file on the phone. */
    val customTitle: String? = null,
    val customCover: String? = null,
    /** The novel's own reading settings, when it has some. */
    val readerSettings: StoredReaderSettings? = null,
    /** The names of the sections it's in; none when it's only in the default one. */
    val sections: List<String> = emptyList(),
    /** The translation group whose chapters the reader follows; none for all of them. */
    val chapterGroup: String? = null,
    /** The reader picked [chapterGroup] (else it was a site's default version); groups in older backups all were. */
    val chapterGroupChosen: Boolean = true,
    /** The novel's own chapter order, newest first or not; none while it follows the app's. */
    val chaptersNewestFirst: Boolean? = null,
    /** Every chapter the novel had, in list order, including ones the site no longer lists. */
    val chapters: List<BackupChapter> = emptyList(),
    /** Word replacements in its text. */
    val replacements: List<BackupReplacement> = emptyList(),
    /** Time spent reading it, by day (days since 1970), for the reading stats. */
    val readingTime: Map<Long, Int> = emptyMap(),
)

/** A section of the library; the default one is the one new novels go in, whatever it's called. */
@Serializable
internal data class BackupSection(val name: String, val isDefault: Boolean = false)

@Serializable
internal data class BackupChapter(
    val key: String,
    val url: String,
    val title: String,
    val index: Int,
    val publishedAt: Long? = null,
    val readAt: Long? = null,
    val removedAt: Long? = null,
    val addedAt: Long? = null,
    /** Where the reader left the chapter. */
    val progress: BackupProgress? = null,
    /** Paragraphs of it the reader bookmarked. */
    val bookmarks: List<BackupBookmark> = emptyList(),
    /** Text of it the reader highlighted. */
    val highlights: List<BackupHighlight> = emptyList(),
    /** The translation group that put it out, when the site said. */
    val group: String? = null,
)

@Serializable
internal data class BackupProgress(val block: Int, val offset: Int, val updatedAt: Long)

@Serializable
internal data class BackupReplacement(val find: String, val replace: String, val wholeWord: Boolean = true, val matchCase: Boolean = false, val createdAt: Long = 0)

@Serializable
internal data class BackupBookmark(val block: Int, val snippet: String, val createdAt: Long)

@Serializable
internal data class BackupHighlight(
    val block: Int,
    val start: Int,
    val end: Int,
    val text: String,
    val color: Int = 0,
    val note: String? = null,
    val createdAt: Long = 0,
    /** The block [end] is in, when it runs on past [block]; -1 (backups from before) for the same one. */
    val endBlock: Int = -1,
)

/** Why a file can't be restored. The messages are for the screen. */
sealed class BackupException(message: String, cause: Throwable? = null) : Exception(message, cause) {
    class NotABackup : BackupException("This file isn't a Fanos backup.")

    class TooNew : BackupException("This backup was made by a newer version of Fanos. Update Fanos to restore it.")

    class Damaged(cause: Throwable) : BackupException("This backup is damaged and can't be read.", cause)
}
