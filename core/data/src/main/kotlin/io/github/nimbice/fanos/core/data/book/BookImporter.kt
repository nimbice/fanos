package io.github.nimbice.fanos.core.data.book

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.nimbice.fanos.core.common.Clock
import io.github.nimbice.fanos.core.common.ReaderDispatchers
import io.github.nimbice.fanos.core.common.Dispatcher
import io.github.nimbice.fanos.core.content.Block
import io.github.nimbice.fanos.core.content.ChapterContent
import io.github.nimbice.fanos.core.content.ChapterExtractor
import io.github.nimbice.fanos.core.content.EpubBook
import io.github.nimbice.fanos.core.content.text
import io.github.nimbice.fanos.core.database.TransactionRunner
import io.github.nimbice.fanos.core.database.dao.ChapterDao
import io.github.nimbice.fanos.core.database.dao.ContentDao
import io.github.nimbice.fanos.core.database.dao.NovelDao
import io.github.nimbice.fanos.core.database.entity.ChapterContentEntity
import io.github.nimbice.fanos.core.database.entity.ChapterEntity
import io.github.nimbice.fanos.core.database.entity.NovelEntity
import io.github.nimbice.fanos.core.data.download.DownloadedImages
import io.github.nimbice.fanos.core.model.BOOK_SOURCE
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.zip.ZipFile
import javax.inject.Inject
import javax.inject.Singleton

/** A book opened into the library: its novel, its title, how many chapters it has, and whether it was there already. */
data class OpenedBook(val novelId: Long, val title: String, val chapters: Int, val already: Boolean)

/** The file opened isn't a book Fanos can read. */
class NotABookException : IOException("That file isn't an EPUB book Fanos can read.")

/**
 * Books opened from files on the phone. An EPUB goes into the library as a novel of [BOOK_SOURCE], its chapters stored
 * as they'd be once downloaded, but kept apart from downloads (deleting downloads leaves them), and its pictures and
 * cover copied into the app: the file itself isn't needed after. The same file opened again finds the book it made;
 * a book taken out of the library lets its text and pictures go ([free]), and opening its file again brings them back
 * to the reading kept.
 */
@Singleton
class BookImporter @Inject constructor(
    @ApplicationContext private val context: Context,
    private val database: TransactionRunner,
    private val novels: NovelDao,
    private val chapters: ChapterDao,
    private val contents: ContentDao,
    private val images: DownloadedImages,
    private val extractor: ChapterExtractor,
    private val json: Json,
    private val clock: Clock,
    @Dispatcher(ReaderDispatchers.IO) private val io: CoroutineDispatcher,
) {
    suspend fun open(uri: Uri): OpenedBook =
        withContext(io) {
            val copy = File(context.cacheDir, "opening.epub")
            try {
                val key = copy(uri, copy)
                val url = BOOK_URL + key
                val known = novels.find(BOOK_SOURCE, url)
                val knownChapters = known?.let { chapters.all(it.id) }.orEmpty()
                if (known != null && knownChapters.isNotEmpty() && contents.savedAmong(knownChapters.map { it.id }).size == knownChapters.size) {
                    if (!known.inLibrary) novels.setInLibrary(known.id, true, clock.now())
                    return@withContext OpenedBook(known.id, known.customTitle ?: known.title, knownChapters.size, already = true)
                }
                val zip = runCatching { ZipFile(copy) }.getOrElse { throw NotABookException() }
                zip.use { file ->
                    val read: (String) -> ByteArray? = { path ->
                        file.getEntry(path)?.takeIf { it.size in 0..MAX_ENTRY }?.let { entry -> file.getInputStream(entry).use { it.readBytes() } }
                    }
                    val book = runCatching { EpubBook.open(read) }.getOrNull() ?: throw NotABookException()
                    val parts =
                        book.chapters.mapNotNull { chapter ->
                            runCatching { book.content(chapter, extractor, read) }.getOrNull()?.takeIf { it.leaves.isNotEmpty() }?.let { chapter.title to it }
                        }
                    if (parts.isEmpty()) throw NotABookException()
                    val title = book.title ?: displayName(uri)?.substringBeforeLast('.') ?: "Book"
                    val now = clock.now()
                    val cover = book.coverPath?.let { path -> read(path)?.let { saveCover(key, path, it) } }
                    val (novelId, ids) =
                        database.transaction {
                            // A book let go before keeps its novel, and with it the reading: its chapters get their text back.
                            val novelId =
                                known?.id ?: novels.insert(
                                    NovelEntity(
                                        sourceId = BOOK_SOURCE,
                                        urlKey = url,
                                        url = url,
                                        title = title,
                                        author = book.author,
                                        coverUrl = cover,
                                        description = book.description,
                                        createdAt = now,
                                        detailsFetchedAt = now,
                                        chaptersFetchedAt = now,
                                    ),
                                )
                            if (known?.inLibrary != true) novels.setInLibrary(novelId, true, now)
                            val kept = knownChapters.associateBy { it.urlKey }
                            val ids =
                                parts.mapIndexed { index, (chapterTitle, content) ->
                                    kept["$url/$index"]?.id
                                        ?: chapters.insertAll(
                                            listOf(
                                                ChapterEntity(
                                                    novelId = novelId,
                                                    urlKey = "$url/$index",
                                                    url = "$url/$index",
                                                    title = chapterTitle ?: titleOf(content) ?: "Part ${index + 1}",
                                                    sourceIndex = index,
                                                    addedAt = now,
                                                ),
                                            ),
                                        ).single()
                                }
                            ids.zip(parts).forEach { (id, part) ->
                                contents.upsert(ChapterContentEntity(id, part.second.extractorVersion, json.encodeToString(ChapterContent.serializer(), part.second), now, true))
                            }
                            contents.markBook(ids)
                            novelId to ids
                        }
                    // The pictures, where the reader looks for a chapter's saved ones.
                    ids.zip(parts).forEach { (id, part) ->
                        part.second.leaves.filterIsInstance<Block.Image>().map { it.src }.distinct().forEach { src ->
                            val bytes = EpubBook.pathOf(src)?.let(read) ?: return@forEach
                            images.saveBookImage(id, src, bytes)
                        }
                    }
                    OpenedBook(novelId, known?.customTitle ?: title, parts.size, already = false)
                }
            } finally {
                copy.delete()
            }
        }

    /**
     * Takes the book out of the library, and lets its text, pictures and cover go to free the space; its reading (place,
     * bookmarks, highlights) stays, for when its file is opened again.
     */
    suspend fun free(novelId: Long) =
        withContext(io) {
            val novel = novels.get(novelId)?.takeIf { it.sourceId == BOOK_SOURCE } ?: return@withContext
            val ids = chapters.all(novelId).map { it.id }
            database.transaction {
                ids.chunked(CHUNK).forEach { contents.delete(it) }
                novels.setInLibrary(novelId, false, null)
            }
            ids.forEach(images::deleteBook)
            novel.coverUrl?.let { Uri.parse(it).path }?.let(::File)?.delete()
        }

    /** Bytes the book takes on the phone: its text, pictures and cover. */
    suspend fun size(novelId: Long): Long =
        withContext(io) {
            val ids = chapters.all(novelId).map { it.id }
            val cover = novels.get(novelId)?.coverUrl?.let { Uri.parse(it).path }?.let(::File)?.length() ?: 0L
            contents.bytesOf(novelId) + ids.sumOf(images::bookBytes) + cover
        }

    /** Copies the file at [uri] to [to], up to [MAX_BOOK]; the copy's key, from what's in it. */
    private fun copy(uri: Uri, to: File): String {
        val digest = MessageDigest.getInstance("SHA-1")
        val input = context.contentResolver.openInputStream(uri) ?: throw NotABookException()
        input.use { from ->
            to.outputStream().use { out ->
                val buffer = ByteArray(64 * 1024)
                var total = 0L
                while (true) {
                    val read = from.read(buffer)
                    if (read < 0) break
                    total += read
                    if (total > MAX_BOOK) throw IOException("That book is too big to open (over ${MAX_BOOK / (1024 * 1024)} MB).")
                    digest.update(buffer, 0, read)
                    out.write(buffer, 0, read)
                }
            }
        }
        return digest.digest().take(KEY_BYTES).joinToString("") { "%02x".format(it) }
    }

    private fun saveCover(key: String, path: String, bytes: ByteArray): String {
        val folder = File(context.filesDir, "books/covers").apply { mkdirs() }
        val extension = path.substringAfterLast('.', "jpg").lowercase().take(4)
        val file = File(folder, "$key.$extension")
        file.writeBytes(bytes)
        return Uri.fromFile(file).toString()
    }

    /** The file's name, without its extension: what to call it while it's being opened. */
    fun nameOf(uri: Uri): String? = displayName(uri)?.substringBeforeLast('.')

    private fun displayName(uri: Uri): String? =
        runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { if (it.moveToFirst()) it.getString(0) else null }
        }.getOrNull()

    /** A chapter's title from its own text, for a book without contents: its first heading's. */
    private fun titleOf(content: ChapterContent): String? =
        content.leaves.firstOrNull { it is Block.Heading }?.text()?.trim()?.takeIf { it.isNotEmpty() && it.length <= MAX_TITLE }

    private companion object {
        const val BOOK_URL = "book:"
        const val KEY_BYTES = 12
        const val MAX_BOOK = 300L * 1024 * 1024
        const val MAX_ENTRY = 60L * 1024 * 1024
        const val MAX_TITLE = 120
        const val CHUNK = 500
    }
}
