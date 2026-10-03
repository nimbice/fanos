package io.github.nimbice.fanos.core.data.backup

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.nimbice.fanos.core.common.ApplicationScope
import io.github.nimbice.fanos.core.common.Clock
import io.github.nimbice.fanos.core.common.Dispatcher
import io.github.nimbice.fanos.core.common.ReaderDispatchers
import io.github.nimbice.fanos.core.common.suspendRunCatching
import io.github.nimbice.fanos.core.data.ChapterKeys
import io.github.nimbice.fanos.core.data.NovelKeys
import io.github.nimbice.fanos.core.data.notification.BackupNotifier
import io.github.nimbice.fanos.core.data.copyAtMost
import io.github.nimbice.fanos.core.data.repository.StoredReaderSettings
import io.github.nimbice.fanos.core.data.source.SourceRegistry
import io.github.nimbice.fanos.core.data.source.SourceUrls
import io.github.nimbice.fanos.core.database.TransactionRunner
import io.github.nimbice.fanos.core.database.dao.BookmarkDao
import io.github.nimbice.fanos.core.database.dao.ChapterDao
import io.github.nimbice.fanos.core.database.dao.NovelDao
import io.github.nimbice.fanos.core.database.dao.ProgressDao
import io.github.nimbice.fanos.core.database.dao.StatsDao
import io.github.nimbice.fanos.core.database.dao.ReplacementDao
import io.github.nimbice.fanos.core.database.dao.SectionDao
import io.github.nimbice.fanos.core.database.entity.BookmarkEntity
import io.github.nimbice.fanos.core.database.entity.ChapterEntity
import io.github.nimbice.fanos.core.database.entity.ChapterProgressEntity
import io.github.nimbice.fanos.core.database.entity.NovelEntity
import io.github.nimbice.fanos.core.database.entity.NovelSectionEntity
import io.github.nimbice.fanos.core.database.entity.ReplacementEntity
import io.github.nimbice.fanos.core.database.entity.SectionEntity
import io.github.nimbice.fanos.core.datastore.SettingsDataSource
import io.github.nimbice.fanos.core.model.BackupStatus
import io.github.nimbice.fanos.core.model.NovelStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.put
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import javax.inject.Inject
import javax.inject.Singleton
import io.github.nimbice.fanos.core.database.dao.HighlightDao
import io.github.nimbice.fanos.core.database.entity.HighlightEntity
import io.github.nimbice.fanos.core.model.HIGHLIGHT_COLORS
import io.github.nimbice.fanos.core.model.BOOK_SOURCE

/**
 * Backups of the library and settings: made on a schedule into a folder the reader picks, or to a
 * file they choose, and restored from one. One backup or restore runs at a time, in the app's scope,
 * so leaving the screen doesn't cut one short.
 */
@Singleton
class BackupRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val database: TransactionRunner,
    private val novels: NovelDao,
    private val sections: SectionDao,
    private val chapters: ChapterDao,
    private val progress: ProgressDao,
    private val bookmarks: BookmarkDao,
    private val highlights: HighlightDao,
    private val stats: StatsDao,
    private val replacements: ReplacementDao,
    private val settings: SettingsDataSource,
    private val sources: SourceRegistry,
    private val chapterKeys: ChapterKeys,
    private val novelKeys: NovelKeys,
    private val notifier: BackupNotifier,
    private val clock: Clock,
    @ApplicationScope private val scope: CoroutineScope,
    @Dispatcher(ReaderDispatchers.IO) private val io: CoroutineDispatcher,
) {
    private val resolver get() = context.contentResolver
    private val lock = Mutex()
    private val running = MutableStateFlow<BackupActivity?>(null)

    /** The backup or restore under way, if any. */
    val activity: StateFlow<BackupActivity?> = running.asStateFlow()

    val status: Flow<BackupStatus> = settings.backupStatus

    /**
     * Makes [tree], a folder from Android's picker, the backup folder: keeps Android's grant to it,
     * which lets backups go on writing there, and hands back the grant for the folder before.
     */
    suspend fun chooseFolder(tree: Uri) {
        try {
            resolver.takePersistableUriPermission(tree, FOLDER_ACCESS)
        } catch (e: SecurityException) {
            // Some phones won't let apps keep folders.
            throw BackupFolderException("Android wouldn't let Fanos keep using that folder. Try another one.", e)
        }
        val old = settings.backupStatus.first().folder
        settings.setBackupFolder(tree.toString())
        notifier.dismissFailure()
        if (old != null && old != tree.toString()) {
            runCatching { resolver.releasePersistableUriPermission(Uri.parse(old), FOLDER_ACCESS) }
        }
    }

    /**
     * Backs up to the default folder again (see [DocumentsFolder]) rather than the one picked, handing
     * back the grant for that one.
     */
    suspend fun useDefaultFolder() {
        val old = settings.backupStatus.first().folder ?: return
        settings.setBackupFolder(null)
        notifier.dismissFailure()
        runCatching { resolver.releasePersistableUriPermission(Uri.parse(old), FOLDER_ACCESS) }
    }

    /** Whether there is a folder to back up to without one being picked (Android 10 and later). */
    val hasDefaultFolder: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q

    /** What to call the backup folder on the screen, [folder] being the one picked, if any; null when there's none. */
    suspend fun folderLabel(folder: String?): String? = withContext(io) { runCatching { place(folder)?.label() }.getOrNull() }

    /** Whether the app can write to the backup folder, [folder] being the one picked, if any. */
    fun canWriteTo(folder: String?): Boolean = runCatching { place(folder)?.writable() == true }.getOrDefault(false)

    /** Where backups go: the folder picked, or else the default one where there is one. */
    private fun place(folder: String?): BackupPlace? =
        when {
            folder != null -> PickedFolder(resolver, Uri.parse(folder))
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q -> DocumentsFolder(resolver)
            else -> null
        }

    /**
     * Backs up into the backup folder, keeping the newest [KEEP] backups there. The outcome is kept
     * for the settings screen; a failure also throws, with a message for the screen.
     */
    suspend fun backUpToFolder(): Unit = exclusive(BackupActivity.BackingUp) { backUpToFolderNow() }

    /**
     * The scheduled backup: as [backUpToFolder], unless one was made in the last hour (by hand, or on
     * choosing the folder, which also starts the schedule), or the library is empty. Returns whether
     * it backed up.
     */
    suspend fun backUpToFolderIfDue(): Boolean =
        exclusive(BackupActivity.BackingUp) {
            // Checked once any backup before this one is done, so the two can't both go ahead.
            val last = settings.backupStatus.first().lastBackupAt
            if (last != null && clock.now() - last < RECENT_MS) return@exclusive false
            // Nothing to keep yet. On a new phone, an empty backup would be the newest one there, just
            // when the reader looks for the one to restore.
            if (novels.libraryTitles().isEmpty()) return@exclusive false
            backUpToFolderNow()
            true
        }

    private suspend fun backUpToFolderNow() {
        val folder = settings.backupStatus.first().folder
        val place = place(folder) ?: throw BackupFolderException("Choose a backup folder first.")
        val now = clock.now()
        try {
            if (!place.writable()) throw SecurityException("No longer allowed to write to $folder")
            val file = place.create(BackupFiles.name(now))
            try {
                writeChecked(file)
                place.finish(file)
            } catch (e: Throwable) {
                runCatching { place.delete(file) }
                throw e
            }
            place.backups().drop(KEEP).forEach { old -> runCatching { place.delete(old) } }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val reason = folderFailure(e)
            settings.recordBackupFailure(reason)
            throw BackupFolderException(reason, e)
        }
        settings.recordBackup(now)
        notifier.dismissFailure()
    }

    /** A name for a backup file made now. */
    fun fileName(): String = BackupFiles.name(clock.now())

    /** Backs up to [file], one the reader made with Android's picker. */
    suspend fun backUpTo(file: Uri): Unit = exclusive(BackupActivity.BackingUp) { writeChecked(file) }

    /**
     * Reads the backup in [file] and says what restoring it would do. The file is copied first: what
     * the picker hands over may not stay readable (a cloud file, a temporary grant).
     */
    suspend fun inspect(file: Uri): RestorePreview =
        withContext(io) {
            val now = clock.now()
            val copies = File(context.cacheDir, "restore").apply { mkdirs() }
            // Left by backups looked at and then abandoned, the app closed before Cancel.
            copies.listFiles()?.filter { now - it.lastModified() > STALE_COPY_MS }?.forEach { it.delete() }
            val copy = copies.resolve("$now.fanosbackup")
            try {
                val input = resolver.openInputStream(file) ?: throw FileNotFoundException("Can't open $file")
                // A look at the first byte, and a cap, before a picked video is copied whole.
                input.buffered().use { from ->
                    from.mark(1)
                    if (!BackupCodec.startsLikeBackup(from.read())) throw BackupException.NotABackup()
                    from.reset()
                    copy.outputStream().use { to -> from.copyAtMost(to, BackupCodec.MAX_FILE_BYTES) { BackupException.NotABackup() } }
                }
                val backup = BackupCodec.read { copy.inputStream() }
                // A book comes back only onto the book opened again: its text isn't in the backup.
                val joining =
                    backup.novels.count { novel ->
                        val there = novelKeys.find(novel.source, novel.url, novel.key)
                        there?.inLibrary != true && (novel.source != BOOK_SOURCE || there != null)
                    }
                RestorePreview(
                    createdAt = backup.createdAt,
                    app = backup.app,
                    novels = backup.novels.size,
                    joining = joining,
                    unavailable =
                        backup.novels.filter {
                            if (it.source == BOOK_SOURCE) novelKeys.find(it.source, it.url, it.key) == null else sources.info(it.source) == null
                        }.map { it.title },
                    hasSettings = !backup.settings.isNullOrEmpty(),
                    file = copy,
                )
            } catch (e: Throwable) {
                copy.delete()
                throw e
            }
        }

    /** Lets go of a backup that was looked at but not restored. */
    fun discard(preview: RestorePreview) {
        preview.file.delete()
    }

    /**
     * Merges the backup [preview] describes into the library (see [BackupMerger]), and its settings
     * over the current ones when [withSettings]. Each novel is restored whole or not at all; one that
     * fails doesn't stop the rest.
     */
    suspend fun restore(preview: RestorePreview, withSettings: Boolean): RestoreResult =
        try {
            restore(withContext(io) { BackupCodec.read { preview.file.inputStream() } }, withSettings)
        } finally {
            preview.file.delete()
        }

    /** Merges [backup] into the library, as read from a file or made from another app's (a NovelLibrary import). */
    internal suspend fun restore(backup: Backup, withSettings: Boolean): RestoreResult =
        exclusive(BackupActivity.Restoring(0, backup.novels.size)) {
            val now = clock.now()
            val failed = ArrayList<String>()
            var joined = 0
            val sectionIds = database.transaction { sectionsFor(backup.sections) }
            // Replacements for all novels the phone hasn't got.
            database.transaction { addReplacements(null, backup.replacements, replacements.everywhere()) }
            // A novel kept under its site's name, waiting for an extension to read that site, is that
            // extension's once one is installed (see WaitingNovels).
            val sites = sources.sites()
            // Last first: each novel that joins the library goes on top of it, so the backup's
            // first novel ends up first.
            backup.novels.asReversed().forEachIndexed { done, saved ->
                val novel = sites[saved.source]?.let { saved.copy(source = it) } ?: saved
                suspendRunCatching { database.transaction { merge(novel, now, sectionIds) } }
                    .onSuccess { if (it) joined++ }
                    .onFailure { failed += novel.title }
                running.value = BackupActivity.Restoring(done + 1, backup.novels.size)
            }
            if (withSettings) backup.settings?.let { settings.restoreValues(it.toValues()) }
            RestoreResult(restored = backup.novels.size - failed.size, joined = joined, failed = failed.asReversed())
        }

    /**
     * The phone's section for each of the backup's, by the backup's names: the backup's default section is
     * the phone's, and the others are the phone's of the same name, made after the rest where there's none.
     */
    /** Adds the [backedUp] rules that aren't among [stored] already, to the novel [novelId] or, null, to all novels. */
    private suspend fun addReplacements(novelId: Long?, backedUp: List<BackupReplacement>, stored: List<ReplacementEntity>) {
        val have = stored.mapTo(HashSet()) { listOf(it.find, it.replace, it.wholeWord, it.matchCase) }
        backedUp.filter { listOf(it.find, it.replace, it.wholeWord, it.matchCase) !in have }.forEach {
            replacements.insert(ReplacementEntity(novelId = novelId, find = it.find, replace = it.replace, wholeWord = it.wholeWord, matchCase = it.matchCase, createdAt = it.createdAt))
        }
    }

    private suspend fun sectionsFor(backup: List<BackupSection>): Map<String, Long> {
        val phone = sections.all()
        val byName = phone.associateBy { it.name.lowercase() }
        var position = phone.maxOfOrNull { it.position } ?: -1
        return backup.associate { section ->
            section.name to
                when {
                    section.isDefault -> SectionEntity.DEFAULT_ID
                    else -> byName[section.name.lowercase()]?.id ?: sections.insert(SectionEntity(name = section.name, position = ++position))
                }
        }
    }

    /** Merges one novel in; true when it joins the library. [sectionIds] are the phone's sections by the backup's names. */
    private suspend fun merge(backup: BackupNovel, now: Long, sectionIds: Map<String, Long>): Boolean {
        // Under the key its source gives it now, when the source is here to ask: the backup may be from before it did.
        val source = runCatching { sources[backup.source] }.getOrNull()
        val stored = novelKeys.find(backup.source, backup.url, backup.key)
        // A book's text isn't in the backup: its reading comes back onto the book once it's opened again, not before.
        if (backup.source == BOOK_SOURCE && stored == null) return false
        val key = novelKeys.keyOf(backup.source, backup.url, backup.key)
        val novelId = stored?.id ?: novels.insert(backup.copy(key = key).toEntity(now)).also { check(it > 0) { "${backup.title} is stored already" } }
        if (stored != null) {
            // The phone's details are the site's newer word, unless it never fetched any.
            if (stored.detailsFetchedAt == null && backup.detailsFetchedAt != null) {
                novels.updateDetails(
                    novelId,
                    backup.url,
                    backup.title,
                    backup.author,
                    backup.cover,
                    backup.description,
                    backup.genres,
                    backup.novelStatus(),
                    backup.detailsFetchedAt,
                )
            }
            val lastReadAt = backup.lastReadAt
            if (lastReadAt != null && lastReadAt > (stored.lastReadAt ?: 0)) novels.setLastReadAt(novelId, lastReadAt)
            // The reader's edits on the phone stay; the backup's fill in where there are none.
            val title = stored.customTitle ?: backup.customTitle
            val cover = stored.customCoverUrl ?: backup.customCover
            if (title != stored.customTitle || cover != stored.customCoverUrl) novels.setEdits(novelId, title, cover)
            if (stored.readerSettings == null && backup.readerSettings != null) novels.setReaderSettings(novelId, backup.readerSettings.encode())
            if (stored.chapterGroup == null && backup.chapterGroup != null && backup.chapterGroupChosen) novels.setChapterGroup(novelId, backup.chapterGroup)
            if (stored.chaptersNewestFirst == null && backup.chaptersNewestFirst != null) novels.setChaptersNewestFirst(novelId, backup.chaptersNewestFirst)
        }

        // Both lists under the keys the source gives chapters now, when it is here to ask: a backup made before it knew
        // chapters by the site's own numbers has their addresses as keys.
        val storedChapters = if (source == null) chapters.all(novelId) else chapterKeys.rekey(novelId) { SourceUrls.chapterKey(source, it) }
        val backedUp = if (source == null) backup.chapters else backup.chapters.map { it.copy(key = SourceUrls.chapterKey(source, it.url)) }
        val plan = BackupMerger.chapters(storedChapters, backedUp, now)
        if (plan.inserts.isNotEmpty()) chapters.insertAll(plan.inserts.map { it.toEntity(novelId, now) })
        plan.reads.forEach { (chapterId, readAt) -> chapters.markReadAt(chapterId, readAt) }
        // The backup's list became the novel's: later checks see what is new since the backup.
        if (storedChapters.isEmpty() && plan.inserts.isNotEmpty() && stored?.chaptersFetchedAt == null) {
            backup.chaptersFetchedAt?.let { novels.setChaptersFetchedAt(novelId, it) }
        }

        addReplacements(novelId, backup.replacements, replacements.ownOf(novelId))

        // Days the phone has no reading time for, from the backup.
        if (backup.readingTime.isNotEmpty()) {
            val known = stats.timeOf(novelId).mapTo(HashSet()) { it.day }
            backup.readingTime.filterKeys { it !in known }.forEach { (day, seconds) ->
                stats.startDay(day, novelId)
                stats.addTime(day, novelId, seconds)
            }
        }

        // Bookmarks the phone hasn't got, on the chapters it has.
        val marked = backedUp.filter { it.bookmarks.isNotEmpty() }
        if (marked.isNotEmpty()) {
            val ids = chapters.all(novelId).associate { it.urlKey to it.id }
            for (chapter in marked) {
                val chapterId = ids[chapter.key] ?: continue
                chapter.bookmarks.forEach { bookmarks.insert(BookmarkEntity(novelId = novelId, chapterId = chapterId, block = it.block, snippet = it.snippet, createdAt = it.createdAt)) }
            }
        }

        // Highlights the phone hasn't got (one starting at the same place it keeps), on the chapters it has.
        val highlighted = backedUp.filter { it.highlights.isNotEmpty() }
        if (highlighted.isNotEmpty()) {
            val ids = chapters.all(novelId).associate { it.urlKey to it.id }
            for (chapter in highlighted) {
                val chapterId = ids[chapter.key] ?: continue
                chapter.highlights.forEach {
                    highlights.insert(
                        HighlightEntity(
                            novelId = novelId,
                            chapterId = chapterId,
                            block = it.block,
                            start = it.start,
                            end = it.end,
                            text = it.text,
                            color = it.color.coerceIn(0, HIGHLIGHT_COLORS - 1),
                            note = it.note,
                            createdAt = it.createdAt,
                            endBlock = maxOf(it.block, it.endBlock),
                        ),
                    )
                }
            }
        }

        val positions = backedUp.filter { it.progress != null }
        if (positions.isNotEmpty()) {
            val ids = chapters.all(novelId).associate { it.urlKey to it.id }
            val wanted = positions.mapNotNull { chapter -> ids[chapter.key]?.let { id -> id to checkNotNull(chapter.progress) } }.toMap()
            val storedPositions = progress.forNovel(novelId).associateBy { it.chapterId }
            BackupMerger.positions(storedPositions, wanted).forEach { (chapterId, position) ->
                progress.upsert(ChapterProgressEntity(chapterId, novelId, position.block, position.offset, position.updatedAt))
            }
        }

        val joins = stored?.inLibrary != true
        if (joins) novels.setInLibrary(novelId, true, backup.addedAt ?: now)
        // A novel new to the library goes in the backup's sections; one already there keeps its own as well.
        val inSections = backup.sections.mapNotNull { sectionIds[it] }.toSet()
        if (inSections.isNotEmpty()) {
            val current = if (!joins) sections.sectionsOf(novelId).toSet().ifEmpty { setOf(SectionEntity.DEFAULT_ID) } else emptySet()
            sections.addNovel((current + inSections).map { NovelSectionEntity(novelId, it) })
        }
        return joins
    }

    /** Writes a backup to [file], then reads it back: a backup that can't be restored is worse than none, since it looks like one. */
    private suspend fun writeChecked(file: Uri) {
        openForWriting(file).use { out -> write(out) }
        BackupCodec.read { resolver.openInputStream(file) ?: throw FileNotFoundException("Can't read $file back") }
    }

    private suspend fun write(out: OutputStream) {
        val library = novels.library()
        val sectionList = sections.all()
        val sectionNames = sectionList.associate { it.id to it.name }
        val backupSections = sectionList.map { BackupSection(it.name, isDefault = it.id == SectionEntity.DEFAULT_ID) }
        val everywhere = replacements.everywhere().map { it.toBackup() }
        BackupCodec.Writer(out, clock.now(), appVersion(), settings.backupValues().toJson(), backupSections, everywhere).use { writer ->
            for (novel in library) {
                val positions = progress.forNovel(novel.id).associateBy { it.chapterId }
                val marked = bookmarks.forNovel(novel.id).groupBy { it.chapterId }
                val highlighted = highlights.forNovel(novel.id).groupBy { it.chapterId }
                val inSections = sections.sectionsOf(novel.id).mapNotNull { sectionNames[it] }
                val time = stats.timeOf(novel.id).associate { it.day to it.seconds }
                val own = replacements.ownOf(novel.id).map { it.toBackup() }
                writer.add(novel.toBackup(chapters.all(novel.id), positions, marked, highlighted).copy(sections = inSections, readingTime = time, replacements = own))
            }
            writer.finish()
        }
    }

    // "t" empties a file being written over; not every app that offers files takes it.
    private fun openForWriting(file: Uri): OutputStream {
        val out =
            try {
                resolver.openOutputStream(file, "wt")
            } catch (e: IllegalArgumentException) {
                resolver.openOutputStream(file, "w")
            } catch (e: UnsupportedOperationException) {
                resolver.openOutputStream(file, "w")
            }
        return out ?: throw FileNotFoundException("Can't write to $file")
    }

    /** Runs [block] after any backup or restore before it, in the app's scope, showing [activity] meanwhile. */
    private suspend fun <T> exclusive(activity: BackupActivity, block: suspend () -> T): T =
        scope.async(io) {
            lock.withLock {
                running.value = activity
                try {
                    block()
                } finally {
                    running.value = null
                }
            }
        }.await()

    private fun appVersion(): String? =
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull()

    private fun folderFailure(error: Exception): String =
        when (error) {
            // The folder, or the app that offered it, is gone, or the reader took the app's access back.
            is SecurityException, is FileNotFoundException, is IllegalArgumentException, is IllegalStateException ->
                "Fanos can't write to the backup folder any more. Choose it again."
            is BackupException -> "The backup didn't read back as written. The folder may be failing, or full."
            is IOException -> "Couldn't write the backup: ${error.message ?: "the folder refused it"}."
            else -> error.message ?: error.javaClass.simpleName
        }

    companion object {
        /** How many backups the folder keeps; older ones are deleted as new ones are made. */
        const val KEEP = 5

        /** The type to give a backup file made with Android's picker. */
        const val FILE_TYPE = BackupFiles.MIME_TYPE

        /** The folder backups go to until another is picked. */
        const val DEFAULT_FOLDER = DocumentsFolder.LABEL

        private const val FOLDER_ACCESS = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        private const val STALE_COPY_MS = 24 * 60 * 60 * 1000L
        private const val RECENT_MS = 60 * 60 * 1000L
    }
}

sealed interface BackupActivity {
    data object BackingUp : BackupActivity

    /** [done] of [total] novels restored. */
    data class Restoring(val done: Int, val total: Int) : BackupActivity
}

/** What restoring a backup would do. */
class RestorePreview internal constructor(
    val createdAt: Long,
    /** The version of the app that made it. */
    val app: String?,
    val novels: Int,
    /** How many of the novels aren't in the library now. */
    val joining: Int,
    /** Titles of novels from sources this version doesn't have: they are restored, but can't be updated. */
    val unavailable: List<String>,
    val hasSettings: Boolean,
    internal val file: File,
)

data class RestoreResult(
    val restored: Int,
    /** How many novels joined the library. */
    val joined: Int,
    /** Titles of novels that couldn't be restored. */
    val failed: List<String>,
)

/** A backup folder problem, with a message for the screen. */
class BackupFolderException(message: String, cause: Throwable? = null) : Exception(message, cause)

private fun NovelEntity.toBackup(
    chapters: List<ChapterEntity>,
    positions: Map<Long, ChapterProgressEntity>,
    bookmarks: Map<Long, List<BookmarkEntity>>,
    highlights: Map<Long, List<HighlightEntity>>,
) =
    BackupNovel(
        source = sourceId,
        key = urlKey,
        url = url,
        title = title,
        author = author,
        cover = coverUrl,
        description = description,
        genres = genres,
        status = status.name,
        addedAt = addedAt,
        lastReadAt = lastReadAt,
        detailsFetchedAt = detailsFetchedAt,
        chaptersFetchedAt = chaptersFetchedAt,
        customTitle = customTitle,
        customCover = customCoverUrl?.takeIf { it.startsWith("https://") || it.startsWith("http://") },
        readerSettings = readerSettings?.let(StoredReaderSettings::parse),
        chapterGroup = chapterGroup,
        chapterGroupChosen = chapterGroupChosen,
        chaptersNewestFirst = chaptersNewestFirst,
        chapters =
            chapters.map { chapter ->
                BackupChapter(
                    key = chapter.urlKey,
                    url = chapter.url,
                    title = chapter.title,
                    index = chapter.sourceIndex,
                    publishedAt = chapter.publishedAt,
                    readAt = chapter.readAt,
                    removedAt = chapter.removedAt,
                    addedAt = chapter.addedAt,
                    progress = positions[chapter.id]?.let { BackupProgress(it.block, it.offset, it.updatedAt) },
                    bookmarks = bookmarks[chapter.id].orEmpty().map { BackupBookmark(it.block, it.snippet, it.createdAt) },
                    highlights = highlights[chapter.id].orEmpty().map { BackupHighlight(it.block, it.start, it.end, it.text, it.color, it.note, it.createdAt, maxOf(it.block, it.endBlock)) },
                    group = chapter.group,
                )
            },
    )

private fun BackupNovel.novelStatus(): NovelStatus = NovelStatus.entries.firstOrNull { it.name == status } ?: NovelStatus.Unknown

private fun BackupNovel.toEntity(now: Long) =
    NovelEntity(
        sourceId = source,
        urlKey = key,
        url = url,
        title = title,
        author = author,
        coverUrl = cover,
        description = description,
        genres = genres,
        status = novelStatus(),
        lastReadAt = lastReadAt,
        detailsFetchedAt = detailsFetchedAt,
        createdAt = now,
        customTitle = customTitle,
        customCoverUrl = customCover,
        readerSettings = readerSettings?.encode(),
        chapterGroup = chapterGroup,
        chapterGroupChosen = chapterGroupChosen,
        chaptersNewestFirst = chaptersNewestFirst,
    )

private fun BackupChapter.toEntity(novelId: Long, now: Long) =
    ChapterEntity(
        novelId = novelId,
        urlKey = key,
        url = url,
        title = title,
        sourceIndex = index,
        publishedAt = publishedAt,
        readAt = readAt,
        removedAt = removedAt,
        addedAt = addedAt ?: now,
        group = group,
    )

/** Settings as a backup carries them: numbers, text, switches and lists of text, by name. */
private fun Map<String, Any>.toJson(): JsonObject =
    buildJsonObject {
        for ((name, value) in this@toJson) {
            when (value) {
                is Boolean -> put(name, value)
                is Number -> put(name, value)
                is String -> put(name, value)
                is Collection<*> -> put(name, JsonArray(value.filterIsInstance<String>().map(::JsonPrimitive)))
            }
        }
    }

private fun JsonObject.toValues(): Map<String, Any?> =
    mapValues { (_, element) ->
        when (element) {
            is JsonNull -> null
            is JsonPrimitive -> if (element.isString) element.content else element.booleanOrNull ?: element.doubleOrNull
            is JsonArray -> element.filterIsInstance<JsonPrimitive>().filter { it.isString }.map { it.content }
            is JsonObject -> null
        }
    }

private fun ReplacementEntity.toBackup() = BackupReplacement(find, replace, wholeWord, matchCase, createdAt)
