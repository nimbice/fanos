package io.github.nimbice.fanos.core.data.download

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The images of chapters saved for reading offline, a folder per chapter under the app's own files:
 * `downloads/<chapter id>/<SHA-1 of the image's address>`. Deleting a chapter's download deletes its
 * folder; nothing else points into it. A book's pictures are kept the same way under `books/`, apart, so deleting
 * downloads leaves them.
 */
@Singleton
class DownloadedImages @Inject constructor(@ApplicationContext context: Context) {

    private val root = File(context.filesDir, "downloads")
    private val books = File(context.filesDir, "books/pictures")

    fun folder(chapterId: Long): File = File(root, chapterId.toString())

    fun file(chapterId: Long, src: String): File = File(folder(chapterId), nameOf(src))

    /** The names of the chapter's saved images (see [nameOf]), a book's too. */
    fun names(chapterId: Long): Set<String> =
        listOf(folder(chapterId), File(books, chapterId.toString())).flatMapTo(HashSet()) { folder -> folder.list()?.filter { NAME.matches(it) }.orEmpty() }

    /** The chapter's saved image named [name]: a book's picture, or a downloaded one. */
    fun saved(chapterId: Long, name: String): File = File(File(books, chapterId.toString()), name).takeIf { it.exists() } ?: File(folder(chapterId), name)

    /** Deletes the pictures of a book's chapter. */
    fun deleteBook(chapterId: Long) {
        File(books, chapterId.toString()).deleteRecursively()
    }

    /** Bytes a book's chapter's pictures take. */
    fun bookBytes(chapterId: Long): Long = File(books, chapterId.toString()).walkBottomUp().filter { it.isFile }.sumOf { it.length() }

    /** Keeps [bytes] as the picture at [src] in a book's chapter. */
    fun saveBookImage(chapterId: Long, src: String, bytes: ByteArray) {
        val folder = File(books, chapterId.toString()).apply { mkdirs() }
        File(folder, nameOf(src)).writeBytes(bytes)
    }

    /** Moves the images saved for chapter [from] to chapter [to], which has none: its saved copy went there. */
    fun move(from: Long, to: Long) {
        val source = folder(from)
        if (source.exists() && !folder(to).exists()) source.renameTo(folder(to))
    }

    fun delete(chapterId: Long) {
        folder(chapterId).deleteRecursively()
    }

    fun deleteAll() {
        root.deleteRecursively()
    }

    /** Bytes the images take. */
    fun size(): Long = root.walkBottomUp().filter { it.isFile }.sumOf { it.length() }

    /** Bytes each chapter's images take, by chapter id, for the chapters that have some. */
    fun sizes(): Map<Long, Long> =
        root.listFiles().orEmpty().mapNotNull { folder ->
            folder.name.toLongOrNull()?.let { id -> id to folder.walkBottomUp().filter { it.isFile }.sumOf { it.length() } }
        }.toMap()

    companion object {
        private val NAME = Regex("[0-9a-f]{40}")

        fun nameOf(src: String): String =
            MessageDigest.getInstance("SHA-1").digest(src.toByteArray()).joinToString("") { "%02x".format(it) }
    }
}
