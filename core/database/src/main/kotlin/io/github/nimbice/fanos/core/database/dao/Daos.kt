package io.github.nimbice.fanos.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert
import io.github.nimbice.fanos.core.database.entity.BookmarkEntity
import io.github.nimbice.fanos.core.database.entity.BookmarkRow
import io.github.nimbice.fanos.core.database.entity.ChapterContentEntity
import io.github.nimbice.fanos.core.database.entity.NovelFaceRow
import io.github.nimbice.fanos.core.database.entity.ReadMarkRow
import io.github.nimbice.fanos.core.database.entity.ReadingTimeEntity
import io.github.nimbice.fanos.core.database.entity.ReplacementEntity
import io.github.nimbice.fanos.core.database.entity.ChapterGroupRow
import io.github.nimbice.fanos.core.database.entity.ChapterEntity
import io.github.nimbice.fanos.core.database.entity.ChapterProgressEntity
import io.github.nimbice.fanos.core.database.entity.ContinueReadingRow
import io.github.nimbice.fanos.core.database.entity.DownloadEntity
import io.github.nimbice.fanos.core.database.entity.HistoryRow
import io.github.nimbice.fanos.core.database.entity.LibraryNovelRow
import io.github.nimbice.fanos.core.database.entity.NovelEntity
import io.github.nimbice.fanos.core.database.entity.NovelSectionEntity
import io.github.nimbice.fanos.core.database.entity.NovelTitleRow
import io.github.nimbice.fanos.core.database.entity.PendingDownloadRow
import io.github.nimbice.fanos.core.database.entity.QueuedNovelRow
import io.github.nimbice.fanos.core.database.entity.ReadingListEntryEntity
import io.github.nimbice.fanos.core.database.entity.RecentChapterRow
import io.github.nimbice.fanos.core.database.entity.ReadingListNovelRow
import io.github.nimbice.fanos.core.database.entity.SavedChapterRow
import io.github.nimbice.fanos.core.database.entity.SavedContentRow
import io.github.nimbice.fanos.core.database.entity.SavedNovelRow
import io.github.nimbice.fanos.core.database.entity.SectionEntity
import io.github.nimbice.fanos.core.model.NovelStatus
import kotlinx.coroutines.flow.Flow
import io.github.nimbice.fanos.core.database.entity.HighlightEntity
import io.github.nimbice.fanos.core.database.entity.HighlightRow
import io.github.nimbice.fanos.core.database.entity.ReleaseSummaryRow
import io.github.nimbice.fanos.core.database.entity.ReleaseTimeRow

/** Every write names the columns it changes, so nothing overwrites a newer value from a stale copy. */
@Dao
interface NovelDao {
    @Query("SELECT * FROM novels WHERE id = :id")
    fun observe(id: Long): Flow<NovelEntity?>

    @Query("SELECT * FROM novels WHERE id = :id")
    suspend fun get(id: Long): NovelEntity?

    @Query("SELECT * FROM novels WHERE source_id = :sourceId AND url_key = :urlKey")
    suspend fun find(sourceId: String, urlKey: String): NovelEntity?

    @Query("SELECT * FROM novels WHERE source_id = :sourceId")
    suspend fun ofSource(sourceId: String): List<NovelEntity>

    /** Puts the novel under [key], unless another novel of its source is under it. */
    @Query("UPDATE OR IGNORE novels SET url_key = :key WHERE id = :id")
    suspend fun setKey(id: Long, key: String)

    /** Deletes the novel, and with it its chapters and everything kept of them. */
    @Query("DELETE FROM novels WHERE id = :id")
    suspend fun deleteNovel(id: Long)

    /** Returns the new row id, or -1 when the novel is already stored. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(novel: NovelEntity): Long

    /**
     * Makes the novels waiting under a site's name the novels of [sourceId], the source that reads the site;
     * one the source has already stays as it is. Returns how many were moved.
     */
    @Query("UPDATE OR IGNORE novels SET source_id = :sourceId WHERE source_id = :site")
    suspend fun adopt(site: String, sourceId: String): Int

    @Query(
        """UPDATE novels SET url = :url, title = :title, author = :author, cover_url = :coverUrl,
           description = :description, genres = :genres, status = :status, details_fetched_at = :fetchedAt
           WHERE id = :id""",
    )
    suspend fun updateDetails(
        id: Long,
        url: String,
        title: String,
        author: String?,
        coverUrl: String?,
        description: String?,
        genres: List<String>,
        status: NovelStatus,
        fetchedAt: Long,
    )

    /** Adding puts the novel at the top of the library. */
    @Query(
        """UPDATE novels SET in_library = :inLibrary, added_at = :addedAt,
             library_position = CASE WHEN :inLibrary
               THEN (SELECT COALESCE(MIN(library_position), 0) - 1 FROM novels WHERE in_library = 1)
               ELSE library_position END
           WHERE id = :id""",
    )
    suspend fun setInLibrary(id: Long, inLibrary: Boolean, addedAt: Long?)

    @Query("UPDATE novels SET library_position = :position WHERE id = :id")
    suspend fun setLibraryPosition(id: Long, position: Int)

    @Query("UPDATE novels SET last_read_at = :time WHERE id = :id")
    suspend fun setLastReadAt(id: Long, time: Long)

    /** Takes the novel off the reading history; reading it again puts it back. Its place in it is kept. */
    @Query("UPDATE novels SET last_read_at = NULL WHERE id = :id")
    suspend fun clearLastReadAt(id: Long)

    /** The novels read, last read first, each with the chapter the reader was on last. */
    @Query(
        """SELECT n.id AS novel_id, COALESCE(n.custom_title, n.title) AS novel_title, COALESCE(n.custom_cover_url, n.cover_url) AS cover_url,
             n.in_library, n.last_read_at, c.id AS chapter_id, c.title AS chapter_title
           FROM novels n
           LEFT JOIN chapters c ON c.id = (SELECT p.chapter_id FROM chapter_progress p WHERE p.novel_id = n.id ORDER BY p.updated_at DESC LIMIT 1)
           WHERE n.last_read_at IS NOT NULL
           ORDER BY n.last_read_at DESC
           LIMIT :limit""",
    )
    fun observeHistory(limit: Int): Flow<List<HistoryRow>>

    /** The last [limit] novels read, last first, each with the chapter the reader was on and how many listed chapters are unread. */
    @Query(
        """SELECT n.id AS novel_id, COALESCE(n.custom_title, n.title) AS novel_title, COALESCE(n.custom_cover_url, n.cover_url) AS cover_url,
             c.id AS chapter_id, c.title AS chapter_title,
             (SELECT COUNT(*) FROM chapters u WHERE u.novel_id = n.id AND u.removed_at IS NULL AND u.read_at IS NULL
                AND COALESCE(u.group_name = n.chapter_group, 1)) AS unread_count
           FROM novels n
           LEFT JOIN chapters c ON c.id = (SELECT p.chapter_id FROM chapter_progress p WHERE p.novel_id = n.id ORDER BY p.updated_at DESC LIMIT 1)
           WHERE n.last_read_at IS NOT NULL
           ORDER BY n.last_read_at DESC
           LIMIT :limit""",
    )
    suspend fun continueReading(limit: Int): List<ContinueReadingRow>

    @Query("UPDATE novels SET chapters_fetched_at = :time WHERE id = :id")
    suspend fun setChaptersFetchedAt(id: Long, time: Long)

    /** Why the library update couldn't check the novel; null once it could. */
    @Query("UPDATE novels SET update_error = :error WHERE id = :id")
    suspend fun setUpdateError(id: Long, error: String?)

    /** The reader's own title and cover; null for either shows the site's. */
    @Query("UPDATE novels SET custom_title = :title, custom_cover_url = :coverUrl WHERE id = :id")
    suspend fun setEdits(id: Long, title: String?, coverUrl: String?)

    /** The novel's own reading settings, as JSON; null while it follows the defaults. */
    @Query("SELECT reader_settings FROM novels WHERE id = :id")
    fun observeReaderSettings(id: Long): Flow<String?>

    @Query("SELECT reader_settings FROM novels WHERE id = :id")
    suspend fun readerSettings(id: Long): String?

    @Query("UPDATE novels SET reader_settings = :json WHERE id = :id")
    suspend fun setReaderSettings(id: Long, json: String?)

    /** Follows one translation group's chapters of the novel, or all of them for null: the reader's pick. */
    @Query("UPDATE novels SET chapter_group = :group, chapter_group_chosen = 1 WHERE id = :id")
    suspend fun setChapterGroup(id: Long, group: String?)

    @Query("UPDATE novels SET chapters_newest_first = :newestFirst WHERE id = :id")
    suspend fun setChaptersNewestFirst(id: Long, newestFirst: Boolean)

    /** Follows [group], a site's default version of the novel's chapters, unless the reader picked one. */
    @Query("UPDATE novels SET chapter_group = :group WHERE id = :id AND chapter_group_chosen = 0")
    suspend fun followDefaultChapterGroup(id: Long, group: String?)

    /** Follows [group], the site's default version, from now on: the reader went back to it. */
    @Query("UPDATE novels SET chapter_group = :group, chapter_group_chosen = 0 WHERE id = :id")
    suspend fun useDefaultChapterGroup(id: Long, group: String?)

    /** Goes back to all the novel's chapters when none listed now are the chosen group's. */
    @Query(
        """UPDATE novels SET chapter_group = NULL, chapter_group_chosen = 0
           WHERE id = :id AND chapter_group IS NOT NULL
             AND NOT EXISTS (SELECT 1 FROM chapters c WHERE c.novel_id = novels.id AND c.removed_at IS NULL AND c.group_name = novels.chapter_group)""",
    )
    suspend fun dropMissingChapterGroup(id: Long)

    @Query(
        """SELECT n.*,
             (SELECT COUNT(*) FROM chapters c WHERE c.novel_id = n.id AND c.removed_at IS NULL
                AND COALESCE(c.group_name = n.chapter_group, 1)) AS chapter_count,
             (SELECT COUNT(*) FROM chapters c WHERE c.novel_id = n.id AND c.removed_at IS NULL AND c.read_at IS NULL
                AND COALESCE(c.group_name = n.chapter_group, 1)) AS unread_count,
             (SELECT GROUP_CONCAT(ns.section_id) FROM novel_sections ns WHERE ns.novel_id = n.id) AS section_ids,
             (SELECT COUNT(*) FROM chapters c JOIN chapter_content cc ON cc.chapter_id = c.id
                WHERE c.novel_id = n.id AND cc.saved != 0) AS saved_count,
             (SELECT MAX(c.added_at) FROM chapters c WHERE c.novel_id = n.id AND c.removed_at IS NULL
                AND COALESCE(c.group_name = n.chapter_group, 1)) AS latest_chapter_at
           FROM novels n
           WHERE n.in_library = 1
           ORDER BY n.library_position, COALESCE(n.last_read_at, n.added_at, 0) DESC, COALESCE(n.custom_title, n.title)""",
    )
    fun observeLibrary(): Flow<List<LibraryNovelRow>>

    /** The novels' listed chapters not yet read, whichever group's. */
    @Query("SELECT id FROM chapters WHERE novel_id IN (:novelIds) AND removed_at IS NULL AND read_at IS NULL")
    suspend fun unreadChapterIds(novelIds: List<Long>): List<Long>

    /** The library's novels in the reader's order, for checking them in that order. */
    @Query("SELECT id, COALESCE(custom_title, title) AS title, source_id FROM novels WHERE in_library = 1 ORDER BY library_position")
    suspend fun libraryTitles(): List<NovelTitleRow>

    @Query("SELECT * FROM novels WHERE in_library = 1 ORDER BY library_position")
    suspend fun library(): List<NovelEntity>

    /** The library's novels in the reader's order, all sections together. */
    @Query("SELECT id FROM novels WHERE in_library = 1 ORDER BY library_position, COALESCE(last_read_at, added_at, 0) DESC, COALESCE(custom_title, title)")
    suspend fun libraryIds(): List<Long>

    /**
     * The library's novels from [sourceIds] as their sites' reading lists should have them, as the library changes and the
     * reader reads.
     */
    @Query(
        """SELECT n.id, n.source_id, n.url,
             (SELECT GROUP_CONCAT(ns.section_id) FROM novel_sections ns WHERE ns.novel_id = n.id) AS section_ids,
             (SELECT c.url FROM chapters c WHERE c.novel_id = n.id AND c.removed_at IS NULL AND c.read_at IS NOT NULL
                ORDER BY c.source_index DESC LIMIT 1) AS bookmark_url
           FROM novels n
           WHERE n.in_library = 1 AND n.source_id IN (:sourceIds)""",
    )
    fun observeReadingListNovels(sourceIds: Collection<String>): Flow<List<ReadingListNovelRow>>
}

@Dao
interface SectionDao {
    @Query("SELECT * FROM sections ORDER BY position, id")
    fun observeAll(): Flow<List<SectionEntity>>

    @Query("SELECT * FROM sections ORDER BY position, id")
    suspend fun all(): List<SectionEntity>

    @Insert
    suspend fun insert(section: SectionEntity): Long

    @Query("UPDATE sections SET name = :name WHERE id = :id")
    suspend fun rename(id: Long, name: String)

    @Query("UPDATE sections SET position = :position WHERE id = :id")
    suspend fun setPosition(id: Long, position: Int)

    /** Deletes a section, never the default one; its novels leave it with it. */
    @Query("DELETE FROM sections WHERE id = :id AND id != ${SectionEntity.DEFAULT_ID}")
    suspend fun delete(id: Long)

    /** The sections [novelId] is in: none means only the default one. */
    @Query("SELECT section_id FROM novel_sections WHERE novel_id = :novelId")
    fun observeSectionsOf(novelId: Long): Flow<List<Long>>

    @Query("SELECT section_id FROM novel_sections WHERE novel_id = :novelId")
    suspend fun sectionsOf(novelId: Long): List<Long>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun addNovel(rows: List<NovelSectionEntity>)

    @Query("DELETE FROM novel_sections WHERE novel_id = :novelId AND section_id = :sectionId")
    suspend fun removeNovel(novelId: Long, sectionId: Long)
}

@Dao
interface ChapterDao {
    /**
     * The listed chapters the reader follows, in reading order: those of the translation group they chose, when they
     * chose one, with any the site didn't say the group of.
     */
    @Query("SELECT * FROM chapters WHERE novel_id = :novelId AND removed_at IS NULL AND COALESCE(group_name = (SELECT chapter_group FROM novels WHERE id = :novelId), 1) ORDER BY source_index")
    fun observeListed(novelId: Long): Flow<List<ChapterEntity>>

    /** The ids of the listed chapters the reader follows, in reading order. */
    @Query("SELECT id FROM chapters WHERE novel_id = :novelId AND removed_at IS NULL AND COALESCE(group_name = (SELECT chapter_group FROM novels WHERE id = :novelId), 1) ORDER BY source_index")
    fun observeListedIds(novelId: Long): Flow<List<Long>>

    /** The novel's translation groups, most chapters first, with how many of the listed chapters are theirs. */
    @Query(
        """SELECT group_name AS name, COUNT(*) AS chapters FROM chapters
           WHERE novel_id = :novelId AND removed_at IS NULL AND group_name IS NOT NULL
           GROUP BY group_name ORDER BY COUNT(*) DESC, group_name""",
    )
    fun observeGroups(novelId: Long): Flow<List<ChapterGroupRow>>

    /**
     * The chapters that arrived in library novels, newest first: those of the group the reader follows, less locked
     * ones, and less the ones a novel came with (its first list), which aren't news.
     */
    @Query(
        """SELECT c.id AS chapter_id, c.novel_id, COALESCE(n.custom_title, n.title) AS novel_title,
             COALESCE(n.custom_cover_url, n.cover_url) AS cover_url, c.title AS chapter_title, c.added_at, c.read_at,
             EXISTS(SELECT 1 FROM chapter_content cc WHERE cc.chapter_id = c.id AND cc.saved != 0) AS saved,
             EXISTS(SELECT 1 FROM download_queue q WHERE q.chapter_id = c.id AND q.failure IS NULL) AS waiting
           FROM chapters c
           JOIN novels n ON n.id = c.novel_id
           JOIN (SELECT novel_id, MIN(added_at) AS first_at FROM chapters GROUP BY novel_id) f ON f.novel_id = c.novel_id
           WHERE n.in_library = 1 AND c.removed_at IS NULL AND c.locked = 0 AND c.added_at > f.first_at
             AND COALESCE(c.group_name = n.chapter_group, 1)
           ORDER BY c.added_at DESC, c.source_index DESC
           LIMIT :limit""",
    )
    fun observeRecent(limit: Int): Flow<List<RecentChapterRow>>

    /** Each library novel: how many chapters it has, how many its site dates, and when this device first saw one of them. */
    @Query(
        """SELECT n.id AS novel_id, COALESCE(n.custom_title, n.title) AS novel_title, COALESCE(n.custom_cover_url, n.cover_url) AS cover_url,
             n.status, COUNT(c.id) AS chapters, COUNT(c.published_at) AS dated, MIN(c.added_at) AS first_added_at
           FROM novels n JOIN chapters c ON c.novel_id = n.id
           WHERE n.in_library = 1
           GROUP BY n.id""",
    )
    suspend fun releaseSummaries(): List<ReleaseSummaryRow>

    /** Library novels' chapters dated, or first seen, since [since]. */
    @Query(
        """SELECT c.novel_id, c.published_at, c.added_at FROM chapters c JOIN novels n ON n.id = c.novel_id
           WHERE n.in_library = 1 AND (c.published_at >= :since OR c.added_at >= :since)""",
    )
    suspend fun releaseTimes(since: Long): List<ReleaseTimeRow>

    /** How many of the chapters [observeRecent] lists came in after [since] and are unread. */
    @Query(
        """SELECT COUNT(*) FROM chapters c JOIN novels n ON n.id = c.novel_id
           WHERE c.added_at > :since AND c.read_at IS NULL AND n.in_library = 1 AND c.removed_at IS NULL AND c.locked = 0
             AND COALESCE(c.group_name = n.chapter_group, 1)
             AND EXISTS(SELECT 1 FROM chapters f WHERE f.novel_id = c.novel_id AND f.added_at < c.added_at)""",
    )
    fun observeUnreadRecentCount(since: Long): Flow<Int>

    /** When the latest chapter came in, epoch milliseconds; null with none. */
    @Query("SELECT MAX(added_at) FROM chapters")
    suspend fun lastAddedAt(): Long?

    /** How many chapters the novel lists, whoever's they are. */
    @Query("SELECT COUNT(*) FROM chapters WHERE novel_id = :novelId AND removed_at IS NULL")
    fun observeListedCount(novelId: Long): Flow<Int>

    /** Every chapter the novel ever had, listed or not, for reconciling a fresh list against. */
    @Query("SELECT * FROM chapters WHERE novel_id = :novelId ORDER BY source_index")
    suspend fun all(novelId: Long): List<ChapterEntity>

    @Query("SELECT * FROM chapters WHERE id = :id")
    suspend fun get(id: Long): ChapterEntity?

    @Query("SELECT * FROM chapters WHERE id = :id")
    fun observe(id: Long): Flow<ChapterEntity?>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(chapters: List<ChapterEntity>): List<Long>

    @Query(
        """UPDATE chapters SET url = :url, title = :title, source_index = :index, published_at = :publishedAt, locked = :locked,
             group_name = COALESCE(:group, group_name), removed_at = NULL
           WHERE id = :id""",
    )
    suspend fun updateListing(id: Long, url: String, title: String, index: Int, publishedAt: Long?, locked: Boolean, group: String?)

    @Query("UPDATE chapters SET removed_at = :time WHERE id IN (:ids)")
    suspend fun markRemoved(ids: List<Long>, time: Long)

    @Query("UPDATE chapters SET read_at = :time WHERE id IN (:ids) AND read_at IS NULL")
    suspend fun markRead(ids: List<Long>, time: Long)

    @Query("UPDATE chapters SET read_at = NULL WHERE id IN (:ids)")
    suspend fun markUnread(ids: List<Long>)

    // For making copies of one chapter one (core/data's ChapterRekey): moving what the reader has from one copy to another.

    @Query("SELECT cc.chapter_id FROM chapter_content cc JOIN chapters c ON c.id = cc.chapter_id WHERE c.novel_id = :novelId")
    suspend fun contentIdsOf(novelId: Long): List<Long>

    @Query("SELECT chapter_id FROM download_queue WHERE novel_id = :novelId")
    suspend fun queuedIdsOf(novelId: Long): List<Long>

    @Query("UPDATE chapter_progress SET chapter_id = :to WHERE chapter_id = :from")
    suspend fun moveProgress(from: Long, to: Long)

    @Query("UPDATE chapter_content SET chapter_id = :to WHERE chapter_id = :from")
    suspend fun moveContent(from: Long, to: Long)

    @Query("UPDATE download_queue SET chapter_id = :to WHERE chapter_id = :from")
    suspend fun moveQueued(from: Long, to: Long)

    @Query("UPDATE chapters SET removed_at = NULL WHERE id = :id")
    suspend fun clearRemoved(id: Long)

    /** Moves the chapters' bookmarks to chapter [to], but for paragraphs it has bookmarked already. */
    @Query("UPDATE OR IGNORE bookmarks SET chapter_id = :to WHERE chapter_id IN (:from)")
    suspend fun moveBookmarks(from: List<Long>, to: Long)

    /** Moves the chapters' highlights to chapter [to], but for places it has a highlight starting at already. */
    @Query("UPDATE OR IGNORE highlights SET chapter_id = :to WHERE chapter_id IN (:from)")
    suspend fun moveHighlights(from: List<Long>, to: Long)

    @Query("UPDATE chapters SET added_at = :time WHERE id = :id")
    suspend fun setAddedAt(id: Long, time: Long)

    /** Deletes the chapters, and with them their places, saved copies and queue entries. */
    @Query("DELETE FROM chapters WHERE id IN (:ids)")
    suspend fun deleteAll(ids: List<Long>)

    /** Gives the chapters keys of their own that no address has, to change their keys without two sharing one meanwhile. */
    @Query("UPDATE chapters SET url_key = 'rekey:' || id WHERE id IN (:ids)")
    suspend fun parkKeys(ids: List<Long>)

    @Query("UPDATE chapters SET url_key = :key WHERE id = :id")
    suspend fun setKey(id: Long, key: String)

    /** Marks a chapter read as of [time], when it isn't read already. */
    @Query("UPDATE chapters SET read_at = :time WHERE id = :id AND read_at IS NULL")
    suspend fun markReadAt(id: Long, time: Long)

    @Query(
        """SELECT * FROM chapters WHERE novel_id = :novelId AND removed_at IS NULL AND source_index > :index
             AND COALESCE(group_name = (SELECT chapter_group FROM novels WHERE id = :novelId), 1)
           ORDER BY source_index LIMIT 1""",
    )
    suspend fun next(novelId: Long, index: Int): ChapterEntity?

    @Query(
        """SELECT * FROM chapters WHERE novel_id = :novelId AND removed_at IS NULL AND source_index < :index
             AND COALESCE(group_name = (SELECT chapter_group FROM novels WHERE id = :novelId), 1)
           ORDER BY source_index DESC LIMIT 1""",
    )
    suspend fun previous(novelId: Long, index: Int): ChapterEntity?

    @Query(
        """SELECT * FROM chapters WHERE novel_id = :novelId AND removed_at IS NULL AND read_at IS NULL AND COALESCE(group_name = (SELECT chapter_group FROM novels WHERE id = :novelId), 1)
           ORDER BY source_index LIMIT 1""",
    )
    suspend fun firstUnread(novelId: Long): ChapterEntity?

    @Query("SELECT * FROM chapters WHERE novel_id = :novelId AND removed_at IS NULL AND COALESCE(group_name = (SELECT chapter_group FROM novels WHERE id = :novelId), 1) ORDER BY source_index LIMIT 1")
    suspend fun first(novelId: Long): ChapterEntity?

    /** Up to [count] listed chapters the reader follows after [chapterId], in list order, less locked ones. */
    @Query(
        """SELECT id FROM chapters
           WHERE novel_id = :novelId AND removed_at IS NULL AND locked = 0 AND COALESCE(group_name = (SELECT chapter_group FROM novels WHERE id = :novelId), 1)
             AND source_index > (SELECT source_index FROM chapters WHERE id = :chapterId)
           ORDER BY source_index LIMIT :count""",
    )
    suspend fun after(novelId: Long, chapterId: Long, count: Int): List<Long>

    /** The unread chapters the reader follows, in list order, that aren't saved or waiting to be, nor locked. */
    @Query(
        """SELECT id FROM chapters
           WHERE novel_id = :novelId AND removed_at IS NULL AND read_at IS NULL AND locked = 0 AND COALESCE(group_name = (SELECT chapter_group FROM novels WHERE id = :novelId), 1)
             AND id NOT IN (SELECT chapter_id FROM chapter_content WHERE saved != 0)
             AND id NOT IN (SELECT chapter_id FROM download_queue WHERE failure IS NULL)
           ORDER BY source_index""",
    )
    suspend fun unreadToDownload(novelId: Long): List<Long>
}

@Dao
interface ReplacementDao {
    /** The novel's own rules, then those for all novels, each in the order they were made. */
    @Query("SELECT * FROM replacements WHERE novel_id = :novelId OR novel_id IS NULL ORDER BY novel_id IS NULL, created_at")
    fun observeFor(novelId: Long): Flow<List<ReplacementEntity>>

    @Query("SELECT * FROM replacements WHERE novel_id = :novelId OR novel_id IS NULL ORDER BY novel_id IS NULL, created_at")
    suspend fun forNovel(novelId: Long): List<ReplacementEntity>

    @Query("SELECT * FROM replacements WHERE novel_id = :novelId")
    suspend fun ownOf(novelId: Long): List<ReplacementEntity>

    @Query("SELECT * FROM replacements WHERE novel_id IS NULL")
    suspend fun everywhere(): List<ReplacementEntity>

    @Insert
    suspend fun insert(rule: ReplacementEntity): Long

    @Query("DELETE FROM replacements WHERE id = :id")
    suspend fun delete(id: Long)
}

@Dao
interface StatsDao {
    /** Makes the day's row for the novel, when there's none yet, to add time to. */
    @Query("INSERT OR IGNORE INTO reading_time (day, novel_id, seconds) VALUES (:day, :novelId, 0)")
    suspend fun startDay(day: Long, novelId: Long)

    @Query("UPDATE reading_time SET seconds = seconds + :seconds WHERE day = :day AND novel_id = :novelId")
    suspend fun addTime(day: Long, novelId: Long, seconds: Int)

    @Query("SELECT * FROM reading_time")
    fun observeTime(): Flow<List<ReadingTimeEntity>>

    @Query("SELECT * FROM reading_time WHERE novel_id = :novelId")
    suspend fun timeOf(novelId: Long): List<ReadingTimeEntity>

    @Query("SELECT novel_id, read_at FROM chapters WHERE read_at IS NOT NULL")
    fun observeReadMarks(): Flow<List<ReadMarkRow>>

    @Query("SELECT id, COALESCE(custom_title, title) AS title, COALESCE(custom_cover_url, cover_url) AS cover_url FROM novels WHERE id IN (:ids)")
    suspend fun faces(ids: List<Long>): List<NovelFaceRow>
}

@Dao
interface BookmarkDao {
    /** The novel's bookmarks, the latest first. */
    @Query(
        """SELECT b.id, b.chapter_id, c.title AS chapter_title, b.block, b.snippet, b.created_at
           FROM bookmarks b JOIN chapters c ON c.id = b.chapter_id
           WHERE b.novel_id = :novelId ORDER BY b.created_at DESC""",
    )
    fun observeForNovel(novelId: Long): Flow<List<BookmarkRow>>

    @Query("SELECT * FROM bookmarks WHERE novel_id = :novelId")
    suspend fun forNovel(novelId: Long): List<BookmarkEntity>

    @Query("SELECT * FROM bookmarks WHERE chapter_id = :chapterId AND block = :block")
    suspend fun find(chapterId: Long, block: Int): BookmarkEntity?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(bookmark: BookmarkEntity): Long

    @Query("DELETE FROM bookmarks WHERE id = :id")
    suspend fun delete(id: Long)
}

@Dao
interface HighlightDao {
    /** The novel's highlights, in reading order. */
    @Query(
        """SELECT h.id, h.chapter_id, c.title AS chapter_title, h.block, h.start, h.`end`, h.text, h.color, h.note, h.created_at, h.end_block
           FROM highlights h JOIN chapters c ON c.id = h.chapter_id
           WHERE h.novel_id = :novelId ORDER BY c.source_index, h.block, h.start""",
    )
    fun observeForNovel(novelId: Long): Flow<List<HighlightRow>>

    @Query("SELECT * FROM highlights WHERE novel_id = :novelId")
    suspend fun forNovel(novelId: Long): List<HighlightEntity>

    /** The highlights in, or running through, paragraphs [from] to [to] of the chapter. */
    @Query("SELECT * FROM highlights WHERE chapter_id = :chapterId AND block <= :to AND MAX(block, end_block) >= :from")
    suspend fun inBlocks(chapterId: Long, from: Int, to: Int): List<HighlightEntity>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(highlight: HighlightEntity): Long

    @Query("UPDATE highlights SET color = :color WHERE id = :id")
    suspend fun setColor(id: Long, color: Int)

    @Query("UPDATE highlights SET note = :note WHERE id = :id")
    suspend fun setNote(id: Long, note: String?)

    @Query("DELETE FROM highlights WHERE id = :id")
    suspend fun delete(id: Long)
}

@Dao
interface ProgressDao {
    @Upsert
    suspend fun upsert(progress: ChapterProgressEntity)

    @Query("SELECT * FROM chapter_progress WHERE chapter_id = :chapterId")
    suspend fun get(chapterId: Long): ChapterProgressEntity?

    @Query("SELECT * FROM chapter_progress WHERE novel_id = :novelId ORDER BY updated_at DESC LIMIT 1")
    fun observeLatest(novelId: Long): Flow<ChapterProgressEntity?>

    @Query("SELECT * FROM chapter_progress WHERE novel_id = :novelId ORDER BY updated_at DESC LIMIT 1")
    suspend fun latest(novelId: Long): ChapterProgressEntity?

    @Query("SELECT * FROM chapter_progress WHERE novel_id = :novelId")
    suspend fun forNovel(novelId: Long): List<ChapterProgressEntity>
}

@Dao
interface ContentDao {
    @Query("SELECT * FROM chapter_content WHERE chapter_id = :chapterId")
    suspend fun get(chapterId: Long): ChapterContentEntity?

    @Upsert
    suspend fun upsert(content: ChapterContentEntity)

    @Query("DELETE FROM chapter_content WHERE chapter_id IN (:chapterIds)")
    suspend fun delete(chapterIds: List<Long>)

    /** The novel's chapters saved for offline reading, a book's too. */
    @Query("SELECT chapter_id FROM chapter_content WHERE saved != 0 AND chapter_id IN (SELECT id FROM chapters WHERE novel_id = :novelId)")
    fun observeSavedIds(novelId: Long): Flow<List<Long>>

    @Query("SELECT chapter_id FROM chapter_content WHERE saved = 1 AND chapter_id IN (SELECT id FROM chapters WHERE novel_id = :novelId)")
    suspend fun savedIds(novelId: Long): List<Long>

    @Query("SELECT chapter_id FROM chapter_content WHERE saved = 1")
    suspend fun allSavedIds(): List<Long>

    @Query("UPDATE chapter_content SET saved = 1 WHERE chapter_id = :chapterId")
    suspend fun markSaved(chapterId: Long)

    /** Of [chapterIds], the ones on the phone: downloaded, or a book's. */
    @Query("SELECT chapter_id FROM chapter_content WHERE saved != 0 AND chapter_id IN (:chapterIds)")
    suspend fun savedAmong(chapterIds: List<Long>): List<Long>

    /**
     * Marks the chapters a book's: saved as a download is, but no download, so deleting downloads leaves them (saved 1
     * is downloaded, 2 a book's).
     */
    @Query("UPDATE chapter_content SET saved = 2 WHERE chapter_id IN (:chapterIds)")
    suspend fun markBook(chapterIds: List<Long>)

    /** The novel's chapters with their text on this device: downloaded, a book's, or kept from reading. */
    @Query("SELECT chapter_id FROM chapter_content WHERE chapter_id IN (SELECT id FROM chapters WHERE novel_id = :novelId)")
    suspend fun storedIdsOf(novelId: Long): List<Long>

    /** Bytes the novel's stored chapters take. */
    @Query("SELECT COALESCE(SUM(LENGTH(json)), 0) FROM chapter_content WHERE chapter_id IN (SELECT id FROM chapters WHERE novel_id = :novelId)")
    suspend fun bytesOf(novelId: Long): Long

    @Query("DELETE FROM chapter_content WHERE saved = 1 AND chapter_id IN (:chapterIds)")
    suspend fun deleteSaved(chapterIds: List<Long>)

    @Query("SELECT COUNT(*) AS chapters, COALESCE(SUM(LENGTH(json)), 0) AS bytes FROM chapter_content WHERE saved = 1")
    fun observeSaved(): Flow<SavedContentRow>

    @Query(
        """SELECT n.id AS novelId, COALESCE(n.custom_title, n.title) AS title, COALESCE(n.custom_cover_url, n.cover_url) AS coverUrl,
                  COUNT(*) AS saved, SUM(c.read_at IS NOT NULL) AS read, COALESCE(SUM(LENGTH(cc.json)), 0) AS bytes
           FROM chapter_content cc JOIN chapters c ON c.id = cc.chapter_id JOIN novels n ON n.id = c.novel_id
           WHERE cc.saved = 1 GROUP BY n.id""",
    )
    fun observeSavedByNovel(): Flow<List<SavedNovelRow>>

    @Query("SELECT cc.chapter_id AS chapterId, c.novel_id AS novelId FROM chapter_content cc JOIN chapters c ON c.id = cc.chapter_id WHERE cc.saved = 1")
    suspend fun savedChapters(): List<SavedChapterRow>

    /** The novel's saved chapters; only its read ones when [readOnly]. */
    @Query(
        """SELECT cc.chapter_id FROM chapter_content cc JOIN chapters c ON c.id = cc.chapter_id
           WHERE cc.saved = 1 AND c.novel_id = :novelId AND (:readOnly = 0 OR c.read_at IS NOT NULL)""",
    )
    suspend fun savedIdsOf(novelId: Long, readOnly: Boolean): List<Long>

    @Query("SELECT cc.chapter_id FROM chapter_content cc JOIN chapters c ON c.id = cc.chapter_id WHERE cc.saved = 1 AND c.read_at IS NOT NULL")
    suspend fun allSavedReadIds(): List<Long>
}

@Dao
interface DownloadDao {
    /** Queues chapters; one already queued keeps its place. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun enqueue(downloads: List<DownloadEntity>)

    /** Puts failed chapters back in line, at the end. */
    @Query("UPDATE download_queue SET failure = NULL, queued_at = :time WHERE chapter_id IN (:chapterIds) AND failure IS NOT NULL")
    suspend fun retry(chapterIds: List<Long>, time: Long)

    @Query("UPDATE download_queue SET failure = :failure WHERE chapter_id IN (:chapterIds)")
    suspend fun fail(chapterIds: List<Long>, failure: String)

    @Query("DELETE FROM download_queue WHERE chapter_id IN (:chapterIds)")
    suspend fun remove(chapterIds: List<Long>)

    @Query("DELETE FROM download_queue")
    suspend fun clear()

    @Query("SELECT EXISTS(SELECT 1 FROM download_queue WHERE chapter_id = :chapterId AND failure IS NULL)")
    suspend fun isWaiting(chapterId: Long): Boolean

    /** The chapters waiting their turn, in order: first asked for first, and a novel's in list order. */
    @Query(
        """SELECT q.chapter_id, q.novel_id, n.source_id, COALESCE(n.custom_title, n.title) AS novel_title, c.title AS chapter_title
           FROM download_queue q JOIN novels n ON n.id = q.novel_id JOIN chapters c ON c.id = q.chapter_id
           WHERE q.failure IS NULL
           ORDER BY q.queued_at, q.novel_id, c.source_index""",
    )
    suspend fun waiting(): List<PendingDownloadRow>

    @Query("SELECT COUNT(*) FROM download_queue WHERE failure IS NULL")
    suspend fun waitingCount(): Int

    @Query("SELECT * FROM download_queue WHERE novel_id = :novelId")
    fun observeForNovel(novelId: Long): Flow<List<DownloadEntity>>

    /** Each novel with chapters on the queue, the first asked for first. */
    @Query(
        """SELECT q.novel_id AS novelId, COALESCE(n.custom_title, n.title) AS title, SUM(q.failure IS NULL) AS waiting,
                  SUM(q.failure IS NOT NULL) AS failed, MAX(q.failure) AS failure
           FROM download_queue q JOIN novels n ON n.id = q.novel_id GROUP BY q.novel_id ORDER BY MIN(q.queued_at)""",
    )
    fun observeByNovel(): Flow<List<QueuedNovelRow>>

    @Query("DELETE FROM download_queue WHERE novel_id = :novelId")
    suspend fun removeNovel(novelId: Long)

    @Query("UPDATE download_queue SET failure = NULL, queued_at = :time WHERE novel_id = :novelId AND failure IS NOT NULL")
    suspend fun retryNovel(novelId: Long, time: Long)
}

@Dao
interface ReadingListDao {
    @Query("SELECT * FROM reading_list_entries WHERE source_id = :sourceId")
    suspend fun entries(sourceId: String): List<ReadingListEntryEntity>

    @Query("SELECT * FROM reading_list_entries")
    fun observeAll(): Flow<List<ReadingListEntryEntity>>

    @Upsert
    suspend fun upsert(entry: ReadingListEntryEntity)

    @Query("DELETE FROM reading_list_entries WHERE source_id = :sourceId AND novel_url = :novelUrl")
    suspend fun delete(sourceId: String, novelUrl: String)

    /** Forgets what the app told the source's site, as when the reader stops keeping its list in step. */
    @Query("DELETE FROM reading_list_entries WHERE source_id = :sourceId")
    suspend fun clear(sourceId: String)
}
