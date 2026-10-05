package io.github.nimbice.fanos.core.database.entity

import androidx.room.ColumnInfo
import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import io.github.nimbice.fanos.core.model.LibrarySection
import io.github.nimbice.fanos.core.model.NovelStatus

/**
 * A novel the user has opened. Ids are AUTOINCREMENT, so a deleted novel's id is never handed to a
 * new one, and everything that belongs to a novel is deleted with it through foreign keys.
 */
@Entity(
    tableName = "novels",
    indices = [
        Index(value = ["source_id", "url_key"], unique = true),
        Index(value = ["in_library"]),
    ],
)
data class NovelEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "source_id") val sourceId: String,
    /** Identity within the source: the URL's path relative to the source's base URL. */
    @ColumnInfo(name = "url_key") val urlKey: String,
    val url: String,
    val title: String,
    val author: String? = null,
    @ColumnInfo(name = "cover_url") val coverUrl: String? = null,
    val description: String? = null,
    val genres: List<String> = emptyList(),
    val status: NovelStatus = NovelStatus.Unknown,
    @ColumnInfo(name = "in_library", defaultValue = "0") val inLibrary: Boolean = false,
    @ColumnInfo(name = "added_at") val addedAt: Long? = null,
    /** Where the reader put it in the library, smallest first. New additions go to the top. */
    @ColumnInfo(name = "library_position", defaultValue = "0") val libraryPosition: Int = 0,
    @ColumnInfo(name = "last_read_at") val lastReadAt: Long? = null,
    @ColumnInfo(name = "details_fetched_at") val detailsFetchedAt: Long? = null,
    @ColumnInfo(name = "chapters_fetched_at") val chaptersFetchedAt: Long? = null,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    /** The reader's own title and cover, shown instead of the site's; the site's stay above, kept up to date. */
    @ColumnInfo(name = "custom_title") val customTitle: String? = null,
    @ColumnInfo(name = "custom_cover_url") val customCoverUrl: String? = null,
    /** The novel's own reading settings, as JSON; null while it follows the defaults. */
    @ColumnInfo(name = "reader_settings") val readerSettings: String? = null,
    /** The translation group whose chapters the reader follows, for a novel several translate; null for all of them. */
    @ColumnInfo(name = "chapter_group") val chapterGroup: String? = null,
    /** The reader picked [chapterGroup], rather than the app following a site's default version for them. */
    @ColumnInfo(name = "chapter_group_chosen", defaultValue = "0") val chapterGroupChosen: Boolean = false,
    /** The novel's own chapter order, newest first or not; null while it follows the app's. */
    @ColumnInfo(name = "chapters_newest_first") val chaptersNewestFirst: Boolean? = null,
    /** Why the last library update couldn't check it, for the library to show; cleared once its chapters are fetched. */
    @ColumnInfo(name = "update_error") val updateError: String? = null,
)

@Entity(
    tableName = "chapters",
    foreignKeys = [
        ForeignKey(entity = NovelEntity::class, parentColumns = ["id"], childColumns = ["novel_id"], onDelete = ForeignKey.CASCADE),
    ],
    indices = [
        Index(value = ["novel_id", "url_key"], unique = true),
        Index(value = ["novel_id", "source_index"]),
    ],
)
data class ChapterEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "novel_id") val novelId: Long,
    @ColumnInfo(name = "url_key") val urlKey: String,
    val url: String,
    val title: String,
    /** Position in the source's list, 0 for the first chapter. */
    @ColumnInfo(name = "source_index") val sourceIndex: Int,
    @ColumnInfo(name = "published_at") val publishedAt: Long? = null,
    @ColumnInfo(name = "read_at") val readAt: Long? = null,
    /** Set when the source stopped listing the chapter; read state and saved content are kept. */
    @ColumnInfo(name = "removed_at") val removedAt: Long? = null,
    /** When the app first saw the chapter. */
    @ColumnInfo(name = "added_at") val addedAt: Long,
    /** The site lists it as one the reader can't read now, such as a Patreon post above their tier. */
    @ColumnInfo(defaultValue = "0") val locked: Boolean = false,
    /** The translation group that put it out, when the site says: Novel Updates lists several groups' side by side. */
    @ColumnInfo(name = "group_name") val group: String? = null,
)

/** Where the reader left a chapter. The latest row of a novel is where reading resumes. */
@Entity(
    tableName = "chapter_progress",
    foreignKeys = [
        ForeignKey(entity = NovelEntity::class, parentColumns = ["id"], childColumns = ["novel_id"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = ChapterEntity::class, parentColumns = ["id"], childColumns = ["chapter_id"], onDelete = ForeignKey.CASCADE),
    ],
    indices = [Index(value = ["novel_id", "updated_at"])],
)
data class ChapterProgressEntity(
    @PrimaryKey @ColumnInfo(name = "chapter_id") val chapterId: Long,
    @ColumnInfo(name = "novel_id") val novelId: Long,
    val block: Int,
    @ColumnInfo(name = "block_offset") val offset: Int,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
)

/**
 * A chapter's extracted content (serialised ChapterContent). Stored with the extractor version,
 * so content made by an older extractor is extracted again when the page can be fetched.
 */
@Entity(
    tableName = "chapter_content",
    foreignKeys = [
        ForeignKey(entity = ChapterEntity::class, parentColumns = ["id"], childColumns = ["chapter_id"], onDelete = ForeignKey.CASCADE),
    ],
)
data class ChapterContentEntity(
    @PrimaryKey @ColumnInfo(name = "chapter_id") val chapterId: Long,
    @ColumnInfo(name = "extractor_version") val extractorVersion: Int,
    val json: String,
    @ColumnInfo(name = "fetched_at") val fetchedAt: Long,
    /** Saved for offline reading on purpose, rather than cached from reading. */
    @ColumnInfo(name = "saved", defaultValue = "0") val saved: Boolean = false,
)

/**
 * A chapter waiting to be saved for offline reading. Chapters are taken in the order they were
 * asked for. A row goes once its chapter is saved; one that can't be (the site refused it, say)
 * stays, with why, until it is tried again or taken off.
 */
@Entity(
    tableName = "download_queue",
    foreignKeys = [
        ForeignKey(entity = ChapterEntity::class, parentColumns = ["id"], childColumns = ["chapter_id"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = NovelEntity::class, parentColumns = ["id"], childColumns = ["novel_id"], onDelete = ForeignKey.CASCADE),
    ],
    indices = [Index(value = ["novel_id"])],
)
data class DownloadEntity(
    @PrimaryKey @ColumnInfo(name = "chapter_id") val chapterId: Long,
    @ColumnInfo(name = "novel_id") val novelId: Long,
    @ColumnInfo(name = "queued_at") val queuedAt: Long,
    /** Why the last try failed; null while the chapter waits its turn. */
    val failure: String? = null,
)

/** A chapter waiting its turn, with what fetching it needs. */
data class PendingDownloadRow(
    @ColumnInfo(name = "chapter_id") val chapterId: Long,
    @ColumnInfo(name = "novel_id") val novelId: Long,
    @ColumnInfo(name = "source_id") val sourceId: String,
    @ColumnInfo(name = "novel_title") val novelTitle: String,
    @ColumnInfo(name = "chapter_title") val chapterTitle: String,
)

data class SavedContentRow(val chapters: Int, val bytes: Long)

/** A novel with chapters saved: how many, how many of those are read, and the bytes their text takes. */
data class SavedNovelRow(val novelId: Long, val title: String, val coverUrl: String?, val saved: Int, val read: Int, val bytes: Long)

/** A novel with chapters on the download queue: how many wait, how many failed, and why one did. */
data class QueuedNovelRow(val novelId: Long, val title: String, val waiting: Int, val failed: Int, val failure: String?)

/** A saved chapter and its novel. */
data class SavedChapterRow(val chapterId: Long, val novelId: Long)

/** A library novel, with what tells when its chapters come out: how many it has, how many dated, when one was first seen. */
data class ReleaseSummaryRow(
    @ColumnInfo(name = "novel_id") val novelId: Long,
    @ColumnInfo(name = "novel_title") val novelTitle: String,
    @ColumnInfo(name = "cover_url") val coverUrl: String?,
    val status: NovelStatus,
    val chapters: Int,
    val dated: Int,
    @ColumnInfo(name = "first_added_at") val firstAddedAt: Long?,
)

/** A chapter's dates, for when a novel's chapters come out: the site's, and when this device first saw it. */
data class ReleaseTimeRow(
    @ColumnInfo(name = "novel_id") val novelId: Long,
    @ColumnInfo(name = "published_at") val publishedAt: Long?,
    @ColumnInfo(name = "added_at") val addedAt: Long,
)

/** A chapter that arrived in a library novel, with what the Updates list shows of it. */
data class RecentChapterRow(
    @ColumnInfo(name = "chapter_id") val chapterId: Long,
    @ColumnInfo(name = "novel_id") val novelId: Long,
    @ColumnInfo(name = "novel_title") val novelTitle: String,
    @ColumnInfo(name = "cover_url") val coverUrl: String?,
    @ColumnInfo(name = "chapter_title") val chapterTitle: String,
    @ColumnInfo(name = "added_at") val addedAt: Long,
    @ColumnInfo(name = "read_at") val readAt: Long?,
    val saved: Boolean,
    val waiting: Boolean,
)

/** A novel the reader read, when, and the chapter they were on last. */
data class HistoryRow(
    @ColumnInfo(name = "novel_id") val novelId: Long,
    @ColumnInfo(name = "novel_title") val novelTitle: String,
    @ColumnInfo(name = "cover_url") val coverUrl: String?,
    @ColumnInfo(name = "in_library") val inLibrary: Boolean,
    @ColumnInfo(name = "last_read_at") val lastReadAt: Long,
    @ColumnInfo(name = "chapter_id") val chapterId: Long?,
    @ColumnInfo(name = "chapter_title") val chapterTitle: String?,
)

/** A word the reader replaced in the text: in one novel, or in all when [novelId] is null. */
@Entity(
    tableName = "replacements",
    foreignKeys = [ForeignKey(entity = NovelEntity::class, parentColumns = ["id"], childColumns = ["novel_id"], onDelete = ForeignKey.CASCADE)],
    indices = [Index(value = ["novel_id"])],
)
data class ReplacementEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "novel_id") val novelId: Long?,
    val find: String,
    val replace: String,
    @ColumnInfo(name = "whole_word") val wholeWord: Boolean,
    @ColumnInfo(name = "match_case") val matchCase: Boolean,
    @ColumnInfo(name = "created_at") val createdAt: Long,
)

/** Time spent reading (or listening to) a novel on a day: [day] is the phone's local date, as a count of days since 1970. */
@Entity(
    tableName = "reading_time",
    primaryKeys = ["day", "novel_id"],
    foreignKeys = [ForeignKey(entity = NovelEntity::class, parentColumns = ["id"], childColumns = ["novel_id"], onDelete = ForeignKey.CASCADE)],
    indices = [Index(value = ["novel_id"])],
)
data class ReadingTimeEntity(
    val day: Long,
    @ColumnInfo(name = "novel_id") val novelId: Long,
    val seconds: Int,
)

/** A chapter's read mark, for counting chapters read. */
data class ReadMarkRow(
    @ColumnInfo(name = "novel_id") val novelId: Long,
    @ColumnInfo(name = "read_at") val readAt: Long,
)

/** A novel's title and cover, as the library shows them. */
data class NovelFaceRow(
    val id: Long,
    val title: String,
    @ColumnInfo(name = "cover_url") val coverUrl: String?,
)

/** A place the reader marked in a chapter: a paragraph, from its start, with the start of its text to show. */
@Entity(
    tableName = "bookmarks",
    foreignKeys = [
        ForeignKey(entity = NovelEntity::class, parentColumns = ["id"], childColumns = ["novel_id"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = ChapterEntity::class, parentColumns = ["id"], childColumns = ["chapter_id"], onDelete = ForeignKey.CASCADE),
    ],
    indices = [Index(value = ["novel_id"]), Index(value = ["chapter_id", "block"], unique = true)],
)
data class BookmarkEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "novel_id") val novelId: Long,
    @ColumnInfo(name = "chapter_id") val chapterId: Long,
    val block: Int,
    val snippet: String,
    @ColumnInfo(name = "created_at") val createdAt: Long,
)

/**
 * Text the reader highlighted in a chapter: in a paragraph, from [start] to [end] of its text, with that text as it was
 * (to find it again should the paragraph change), a colour, and a note perhaps. One to a place where it starts.
 */
@Entity(
    tableName = "highlights",
    foreignKeys = [
        ForeignKey(entity = NovelEntity::class, parentColumns = ["id"], childColumns = ["novel_id"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = ChapterEntity::class, parentColumns = ["id"], childColumns = ["chapter_id"], onDelete = ForeignKey.CASCADE),
    ],
    indices = [Index(value = ["novel_id"]), Index(value = ["chapter_id", "block", "start"], unique = true)],
)
data class HighlightEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "novel_id") val novelId: Long,
    @ColumnInfo(name = "chapter_id") val chapterId: Long,
    val block: Int,
    val start: Int,
    val end: Int,
    val text: String,
    val color: Int,
    val note: String?,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    /** The paragraph [end] is in: the same as [block] mostly, and -1 for that in highlights from before there could be another. */
    @ColumnInfo(name = "end_block", defaultValue = "-1") val endBlock: Int = -1,
)

/** A highlight with its chapter's title, for listing. */
data class HighlightRow(
    val id: Long,
    @ColumnInfo(name = "chapter_id") val chapterId: Long,
    @ColumnInfo(name = "chapter_title") val chapterTitle: String,
    val block: Int,
    val start: Int,
    val end: Int,
    val text: String,
    val color: Int,
    val note: String?,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "end_block") val endBlock: Int,
)

/** A bookmark with its chapter's title, for listing. */
data class BookmarkRow(
    val id: Long,
    @ColumnInfo(name = "chapter_id") val chapterId: Long,
    @ColumnInfo(name = "chapter_title") val chapterTitle: String,
    val block: Int,
    val snippet: String,
    @ColumnInfo(name = "created_at") val createdAt: Long,
)

/** A novel read lately, with the chapter the reader was on and how many listed chapters are unread. */
data class ContinueReadingRow(
    @ColumnInfo(name = "novel_id") val novelId: Long,
    @ColumnInfo(name = "novel_title") val novelTitle: String,
    @ColumnInfo(name = "cover_url") val coverUrl: String?,
    @ColumnInfo(name = "chapter_id") val chapterId: Long?,
    @ColumnInfo(name = "chapter_title") val chapterTitle: String?,
    @ColumnInfo(name = "unread_count") val unreadCount: Int,
)

/** A translation group of a novel, and how many of the chapters listed are its. */
data class ChapterGroupRow(val name: String, val chapters: Int)

/** A library novel as its reading list on the site should have it: its sections, and the last chapter read. */
data class ReadingListNovelRow(
    val id: Long,
    @ColumnInfo(name = "source_id") val sourceId: String,
    val url: String,
    /** The sections it's in, comma separated; null when it's in none, so only the default one. */
    @ColumnInfo(name = "section_ids") val sectionIds: String?,
    /** The furthest chapter read, in list order. */
    @ColumnInfo(name = "bookmark_url") val bookmarkUrl: String?,
)

data class NovelTitleRow(val id: Long, val title: String, @ColumnInfo(name = "source_id") val sourceId: String)

data class LibraryNovelRow(
    @Embedded val novel: NovelEntity,
    @ColumnInfo(name = "chapter_count") val chapterCount: Int,
    @ColumnInfo(name = "unread_count") val unreadCount: Int,
    /** The sections the novel is in, comma separated; null when it's in none, so only the default one. */
    @ColumnInfo(name = "section_ids") val sectionIds: String? = null,
    /** How many of its chapters are saved for offline reading. */
    @ColumnInfo(name = "saved_count") val savedCount: Int = 0,
    /** When the latest of the chapters the reader follows arrived. */
    @ColumnInfo(name = "latest_chapter_at") val latestChapterAt: Long? = null,
)

/**
 * A section of the library. The one with [DEFAULT_ID] always exists (the database makes it): it can be
 * renamed but not deleted, and a library novel in no section at all counts as in it.
 */
@Entity(tableName = "sections")
data class SectionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    /** Where the reader put it among the sections, smallest first. */
    val position: Int,
) {
    companion object {
        const val DEFAULT_ID = LibrarySection.DEFAULT_ID
        const val DEFAULT_NAME = "Reading"
    }
}

/** A novel in a section; a novel can be in several. */
@Entity(
    tableName = "novel_sections",
    primaryKeys = ["novel_id", "section_id"],
    foreignKeys = [
        ForeignKey(entity = NovelEntity::class, parentColumns = ["id"], childColumns = ["novel_id"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = SectionEntity::class, parentColumns = ["id"], childColumns = ["section_id"], onDelete = ForeignKey.CASCADE),
    ],
    indices = [Index(value = ["section_id"])],
)
data class NovelSectionEntity(
    @ColumnInfo(name = "novel_id") val novelId: Long,
    @ColumnInfo(name = "section_id") val sectionId: Long,
)

/**
 * A novel the app put on the reader's reading list on its site (see ReadingListSource), and what it last told the site:
 * the list, and the chapter the bookmark went to. Kept by the site's own address for the novel rather than by the novel,
 * so a novel taken out of the library, or out of the app, still comes off the list.
 */
@Entity(tableName = "reading_list_entries", primaryKeys = ["source_id", "novel_url"])
data class ReadingListEntryEntity(
    @ColumnInfo(name = "source_id") val sourceId: String,
    @ColumnInfo(name = "novel_url") val novelUrl: String,
    /** The site's id for the list. */
    @ColumnInfo(name = "list_id") val listId: String,
    @ColumnInfo(name = "bookmark_url") val bookmarkUrl: String? = null,
    @ColumnInfo(name = "synced_at") val syncedAt: Long,
)
