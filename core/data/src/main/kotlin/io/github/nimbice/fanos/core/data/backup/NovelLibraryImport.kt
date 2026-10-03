package io.github.nimbice.fanos.core.data.backup

import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.nimbice.fanos.core.common.Clock
import io.github.nimbice.fanos.core.common.Dispatcher
import io.github.nimbice.fanos.core.common.ReaderDispatchers
import io.github.nimbice.fanos.core.data.NovelKeys
import io.github.nimbice.fanos.core.data.copyAtMost
import io.github.nimbice.fanos.core.data.source.SourceRegistry
import io.github.nimbice.fanos.core.data.source.SourceUrls
import io.github.nimbice.fanos.core.model.SourceInfo
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import java.io.File
import java.io.FileNotFoundException
import java.net.URI
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import java.util.zip.ZipInputStream
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Brings a NovelLibrary backup into the library: its novels in their sections, with the chapter the reader
 * was on and which chapters are read. The backup is read into a Fanos backup and restored as one is (see
 * [BackupRepository.restore]), so a novel already here is merged, not doubled. A novel from a site no
 * extension reads comes in all the same, ready for one.
 */
@Singleton
class NovelLibraryImporter @Inject constructor(
    @ApplicationContext private val context: Context,
    private val sources: SourceRegistry,
    private val novelKeys: NovelKeys,
    private val backups: BackupRepository,
    private val clock: Clock,
    @Dispatcher(ReaderDispatchers.IO) private val io: CoroutineDispatcher,
) {
    /**
     * Reads the NovelLibrary backup in [file] and says what importing it would do. The file is copied and
     * unpacked first: what the picker hands over may not stay readable.
     */
    suspend fun inspect(file: Uri): NovelLibraryPreview =
        withContext(io) {
            val work = File(context.cacheDir, "novellibrary").apply { deleteRecursively() }
            work.mkdirs()
            try {
                val copy = File(work, "backup.zip")
                val input = context.contentResolver.openInputStream(file) ?: throw FileNotFoundException("Can't open $file")
                input.buffered().use { from ->
                    from.mark(2)
                    val zip = from.read() == 'P'.code && from.read() == 'K'.code
                    if (!zip) throw NotNovelLibrary()
                    from.reset()
                    copy.outputStream().use { to -> from.copyAtMost(to, MAX_ZIP_BYTES) { NotNovelLibrary() } }
                }
                val data = NovelLibraryReader.read(copy, work)
                val installed = sources.all.first()
                val backup = data.toBackup(clock.now(), installed)
                val readable = backup.novels.filter { novel -> installed.any { it.id == novel.source } }
                NovelLibraryPreview(
                    novels = backup.novels.size,
                    sections = data.sections.size,
                    chaptersRead = backup.novels.sumOf { novel -> novel.chapters.count { it.readAt != null } },
                    readableSites = readable.mapNotNull { novel -> installed.firstOrNull { it.id == novel.source }?.name }.distinct().sorted(),
                    readable = readable.size,
                    unreadableSites = backup.novels.filter { it !in readable }.map { it.source }.distinct().sorted(),
                    inLibrary = backup.novels.count { novel -> novelKeys.find(novel.source, novel.url, novel.key)?.inLibrary == true },
                    backup = backup,
                )
            } finally {
                work.deleteRecursively()
            }
        }

    /** Imports what [preview] read, as a restore. */
    suspend fun import(preview: NovelLibraryPreview): RestoreResult = backups.restore(preview.backup, withSettings = false)

    private companion object {
        // A library's database with its chapter lists, and the text list: far less than this.
        const val MAX_ZIP_BYTES = 512L * 1024 * 1024
    }
}

/** What importing a NovelLibrary backup would do. */
class NovelLibraryPreview internal constructor(
    val novels: Int,
    /** NovelLibrary's own sections, besides "Currently Reading". */
    val sections: Int,
    val chaptersRead: Int,
    /** How many of the novels are from sites an installed extension reads, and those sites' names. */
    val readable: Int,
    val readableSites: List<String>,
    /** The sites of the other novels, which come in without an extension. */
    val unreadableSites: List<String>,
    /** How many of the novels are in the library already: their progress is merged. */
    val inLibrary: Int,
    internal val backup: Backup,
)

/** The file isn't a NovelLibrary backup. */
class NotNovelLibrary : BackupException("This file isn't a NovelLibrary backup.")

/** A NovelLibrary library as its backup has it. */
internal data class NovelLibraryData(val sections: List<NlSection>, val novels: List<NlNovel>)

internal data class NlSection(val id: Long, val name: String, val order: Int?)

internal data class NlNovel(
    val id: Long,
    val name: String,
    val url: String,
    val imageUrl: String? = null,
    val description: String? = null,
    val metadata: Map<String, String> = emptyMap(),
    /** The chapter the reader was on, by its address. */
    val bookmark: String? = null,
    val order: Int? = null,
    /** -1 for "Currently Reading", which has no row of its own. */
    val sectionId: Long = CURRENTLY_READING,
    val chapters: List<NlChapter> = emptyList(),
)

internal data class NlChapter(val url: String, val title: String, val order: Int, val read: Boolean)

internal const val CURRENTLY_READING = -1L

/**
 * The library as a Fanos backup: NovelLibrary's "Currently Reading" is the default section and its own
 * sections follow in its order; each novel's site is the installed extension's whose address it's on, or
 * else its host, so it comes in waiting for one. The chapter the reader was on becomes where "Continue"
 * opens (at its start: NovelLibrary kept a pixel offset, which means nothing here).
 */
internal fun NovelLibraryData.toBackup(now: Long, installed: List<SourceInfo>): Backup {
    val sectionOrder = sections.sortedWith(compareBy<NlSection>({ it.order ?: Int.MAX_VALUE }, { it.id }))
    val names = sectionOrder.associate { it.id to it.name }
    val placeOfSection = sectionOrder.withIndex().associate { (index, section) -> section.id to index + 1 }
    val ordered = novels.sortedWith(compareBy<NlNovel>({ placeOfSection[it.sectionId] ?: 0 }, { it.order ?: Int.MAX_VALUE }, { it.id }))
    return Backup(
        format = BackupCodec.FORMAT,
        version = BackupCodec.VERSION,
        createdAt = now,
        app = "NovelLibrary",
        sections = listOf(BackupSection("Currently Reading", isDefault = true)) + sectionOrder.map { BackupSection(it.name) },
        novels = ordered.map { it.toBackup(now, installed, names) },
    )
}

private fun NlNovel.toBackup(now: Long, installed: List<SourceInfo>, sectionNames: Map<Long, String>): BackupNovel {
    val host = runCatching { URI(url).host }.getOrNull()
    val site = SourceUrls.siteOf(url)
    val source = site?.let { installed.firstOrNull { source -> SourceUrls.siteOf(source.baseUrl) == it } }
    val base = source?.baseUrl ?: host?.let { "https://$it" } ?: url
    val lastRead = metadata["lastReadDate"]?.let(::readDate)
    val sorted = chapters.sortedBy { it.order }
    return BackupNovel(
        source = source?.id ?: site ?: url,
        key = SourceUrls.key(base, url),
        url = url,
        title = name,
        author = metadata["Author(s)"]?.let(::plainText)?.ifEmpty { null },
        cover = imageUrl?.ifEmpty { null },
        description = description?.ifEmpty { null },
        lastReadAt = lastRead,
        sections = sectionNames[sectionId]?.let(::listOf).orEmpty(),
        chapters =
            sorted.mapIndexed { index, chapter ->
                BackupChapter(
                    key = SourceUrls.key(base, chapter.url),
                    url = chapter.url,
                    title = chapter.title,
                    index = index,
                    readAt = if (chapter.read) lastRead ?: now else null,
                    progress = if (chapter.url == bookmark) BackupProgress(block = 0, offset = 0, updatedAt = lastRead ?: now) else null,
                )
            },
    )
}

/** NovelLibrary's "lastReadDate", day month year in words ("28 Sep 2026"), as noon that day. */
private fun readDate(text: String): Long? =
    listOf(Locale.ENGLISH, Locale.getDefault()).firstNotNullOfOrNull { locale ->
        runCatching {
            SimpleDateFormat("d MMM yyyy", locale).apply { timeZone = TimeZone.getDefault() }.parse(text)?.time?.plus(12 * 3_600_000L)
        }.getOrNull()
    }

/** NovelUpdates keeps authors as links: just their names. */
private fun plainText(html: String): String =
    html.replace(Regex("<[^>]*>"), "")
        .replace("&amp;", "&")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&#39;", "'")
        .replace(Regex("\\s+"), " ")
        .trim()

/** Reads a NovelLibrary backup zip: its database, or where the backup was made without one, its text list. */
internal object NovelLibraryReader {
    private val json = Json { ignoreUnknownKeys = true }

    fun read(zip: File, work: File): NovelLibraryData {
        var simple: String? = null
        ZipInputStream(zip.inputStream().buffered()).use { input ->
            while (true) {
                val entry = input.nextEntry ?: break
                val name = entry.name.replace('\\', '/')
                when {
                    entry.isDirectory -> Unit
                    // The database, with its journal or write-ahead log beside it, as it was copied.
                    name.startsWith("databases/bnr_db") ->
                        File(work, name.substringAfterLast('/')).outputStream().use { out -> input.copyAtMost(out, MAX_ENTRY_BYTES) { NotNovelLibrary() } }
                    name == "SimpleNovelBackup.txt" -> simple = input.readBytesAtMost(MAX_ENTRY_BYTES).decodeToString()
                }
            }
        }
        val database = File(work, "bnr_db")
        return when {
            database.isFile -> readDatabase(database)
            simple != null -> readSimple(checkNotNull(simple))
            else -> throw NotNovelLibrary()
        }
    }

    private fun readDatabase(file: File): NovelLibraryData {
        // Opened for writing, so a write-ahead log copied beside it is played back.
        val db =
            runCatching { SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READWRITE) }
                .getOrElse { throw NotNovelLibrary() }
        db.use {
            if (!db.hasTable("novel")) throw NotNovelLibrary()
            val sections =
                if (db.hasTable("novel_section")) {
                    db.rawQuery("SELECT * FROM novel_section", null).use { cursor ->
                        cursor.rows { NlSection(long("id") ?: 0, string("name").orEmpty(), int("order_id")) }
                    }.filter { it.name.isNotBlank() }
                } else {
                    emptyList()
                }
            val chapters = readChapters(db).groupBy({ it.first }, { it.second })
            val novels =
                db.rawQuery("SELECT * FROM novel", null).use { cursor ->
                    cursor.rows {
                        val id = long("id") ?: 0
                        NlNovel(
                            id = id,
                            name = string("name").orEmpty(),
                            url = string("url").orEmpty(),
                            imageUrl = string("image_url"),
                            description = string("long_description") ?: string("short_description"),
                            metadata = string("metadata")?.let(::stringMap).orEmpty(),
                            bookmark = string("current_web_page_url"),
                            order = int("order_id"),
                            sectionId = long("novel_section_id") ?: CURRENTLY_READING,
                            chapters = chapters[id].orEmpty(),
                        )
                    }
                }.filter { it.url.isNotBlank() && it.name.isNotBlank() }
            // A section NovelLibrary lost track of leaves its novels in "Currently Reading".
            val known = sections.map { it.id }.toSet()
            return NovelLibraryData(sections, novels.map { if (it.sectionId in known) it else it.copy(sectionId = CURRENTLY_READING) })
        }
    }

    private fun readChapters(db: SQLiteDatabase): List<Pair<Long, NlChapter>> {
        val settings = db.hasTable("web_page_settings")
        val sql =
            if (settings) {
                """SELECT w.novel_id AS novel_id, w.url AS url, w.chapter AS chapter, w.order_id AS order_id, ws.is_read AS is_read
                   FROM web_page w LEFT JOIN web_page_settings ws ON ws.url = w.url
                   WHERE w.novel_id IN (SELECT id FROM novel)"""
            } else {
                "SELECT novel_id, url, chapter, order_id FROM web_page WHERE novel_id IN (SELECT id FROM novel)"
            }
        return db.rawQuery(sql, null).use { cursor ->
            cursor.rows {
                (long("novel_id") ?: 0) to NlChapter(string("url").orEmpty(), string("chapter").orEmpty(), int("order_id") ?: 0, (int("is_read") ?: 0) == 1)
            }
        }.filter { it.second.url.isNotBlank() }
    }

    /** The backup's text list: novels and sections, with the chapter each was on, but no chapters. */
    private fun readSimple(text: String): NovelLibraryData {
        val root = runCatching { json.parseToJsonElement(text).jsonObject }.getOrElse { throw NotNovelLibrary() }
        val sections =
            (root["novelSections"] as? JsonArray).orEmpty().mapNotNull { element ->
                val section = element as? JsonObject ?: return@mapNotNull null
                NlSection(section.long("id") ?: return@mapNotNull null, section.string("name") ?: return@mapNotNull null, section.long("orderId")?.toInt())
            }
        val known = sections.map { it.id }.toSet()
        val novels =
            (root["novels"] as? JsonArray).orEmpty().mapNotNull { element ->
                val novel = element as? JsonObject ?: return@mapNotNull null
                val section = novel.long("novelSectionId") ?: CURRENTLY_READING
                NlNovel(
                    id = novel.long("id") ?: 0,
                    name = novel.string("name") ?: return@mapNotNull null,
                    url = novel.string("url") ?: return@mapNotNull null,
                    imageUrl = novel.string("imageUrl"),
                    description = novel.string("longDescription") ?: novel.string("shortDescription"),
                    metadata = (novel["metaData"] as? JsonObject)?.let(::stringMap).orEmpty(),
                    bookmark = novel.string("currentlyReading"),
                    order = novel.long("orderId")?.toInt(),
                    sectionId = if (section in known) section else CURRENTLY_READING,
                )
            }
        if (novels.isEmpty() && root["novels"] == null) throw NotNovelLibrary()
        return NovelLibraryData(sections, novels)
    }

    private fun stringMap(text: String): Map<String, String> =
        runCatching { stringMap(json.parseToJsonElement(text).jsonObject) }.getOrDefault(emptyMap())

    private fun stringMap(values: JsonObject): Map<String, String> =
        values.mapNotNull { (key, value) -> (value as? JsonPrimitive)?.contentOrNull?.let { key to it } }.toMap()

    private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

    private fun JsonObject.long(key: String): Long? = (this[key] as? JsonPrimitive)?.longOrNull

    private fun SQLiteDatabase.hasTable(name: String): Boolean =
        rawQuery("SELECT name FROM sqlite_master WHERE type = 'table' AND name = ?", arrayOf(name)).use { it.moveToFirst() }

    private fun <T> Cursor.rows(read: Cursor.() -> T): List<T> = buildList { while (moveToNext()) add(read()) }

    private fun Cursor.string(column: String): String? = getColumnIndex(column).takeIf { it >= 0 && !isNull(it) }?.let(::getString)

    private fun Cursor.long(column: String): Long? = getColumnIndex(column).takeIf { it >= 0 && !isNull(it) }?.let(::getLong)

    private fun Cursor.int(column: String): Int? = getColumnIndex(column).takeIf { it >= 0 && !isNull(it) }?.let(::getInt)

    private fun java.io.InputStream.readBytesAtMost(limit: Long): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        copyAtMost(out, limit) { NotNovelLibrary() }
        return out.toByteArray()
    }

    private const val MAX_ENTRY_BYTES = 512L * 1024 * 1024
}
