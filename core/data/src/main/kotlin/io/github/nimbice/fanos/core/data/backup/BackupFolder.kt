package io.github.nimbice.fanos.core.data.backup

import android.content.ContentResolver
import android.content.ContentUris
import android.content.ContentValues
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import android.provider.MediaStore
import androidx.annotation.RequiresApi
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Where automatic backups go: the folder the reader picked, or the default one. */
internal interface BackupPlace {
    /** What to call it on the screen. */
    fun label(): String

    /** Whether the app can still write there. */
    fun writable(): Boolean

    /** A new, empty file called [name]. */
    fun create(name: String): Uri

    /** Makes a file written in full visible to other apps. */
    fun finish(file: Uri) = Unit

    /**
     * The backups there, newest first: by the time in their names, then, for two made in the same
     * minute (which get told apart as "name (1)"), by when they were written.
     */
    fun backups(): List<Uri>

    fun delete(file: Uri)

    class Found(val uri: Uri, val name: String, val modified: Long)

    companion object {
        fun newestFirst(found: List<Found>): List<Uri> =
            found.filter { BackupFiles.isBackup(it.name) }
                .sortedWith(compareByDescending<Found> { BackupFiles.madeAt(it.name) }.thenByDescending { it.modified })
                .map { it.uri }
    }
}

/**
 * A folder the reader picked: a document tree Android lets the app keep writing to, on the phone or in
 * any app that offers folders.
 */
internal class PickedFolder(private val resolver: ContentResolver, private val tree: Uri) : BackupPlace {

    private val rootId: String = DocumentsContract.getTreeDocumentId(tree)
    private val root: Uri = DocumentsContract.buildDocumentUriUsingTree(tree, rootId)

    // The grant goes if the reader takes it back, or the folder's app goes away.
    override fun writable(): Boolean = resolver.persistedUriPermissions.any { it.uri == tree && it.isWritePermission }

    override fun create(name: String): Uri =
        DocumentsContract.createDocument(resolver, root, BackupFiles.MIME_TYPE, name) ?: throw IOException("The folder didn't take a new file")

    override fun backups(): List<Uri> {
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, rootId)
        val found = ArrayList<BackupPlace.Found>()
        val columns = arrayOf(Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_LAST_MODIFIED)
        resolver.query(children, columns, null, null, null)?.use { rows ->
            while (rows.moveToNext()) {
                val id = rows.getString(0) ?: continue
                val name = rows.getString(1) ?: continue
                val modified = if (rows.isNull(2)) 0L else rows.getLong(2)
                found += BackupPlace.Found(DocumentsContract.buildDocumentUriUsingTree(tree, id), name, modified)
            }
        }
        return BackupPlace.newestFirst(found)
    }

    override fun delete(file: Uri) {
        DocumentsContract.deleteDocument(resolver, file)
    }

    /** Its path on the phone's own storage, else its name. */
    override fun label(): String {
        if (tree.authority == EXTERNAL_STORAGE) {
            val path = rootId.substringAfter(':', "")
            return path.ifEmpty { if (rootId.startsWith("primary")) "Internal storage" else "Memory card" }
        }
        return runCatching {
            resolver.query(root, arrayOf(Document.COLUMN_DISPLAY_NAME), null, null, null)?.use { rows ->
                if (rows.moveToFirst()) rows.getString(0) else null
            }
        }.getOrNull() ?: "Backup folder"
    }

    private companion object {
        const val EXTERNAL_STORAGE = "com.android.externalstorage.documents"
    }
}

/**
 * The default folder, Documents/Fanos on the phone's own storage, so backups work without anything to
 * set up. Android 10 and later let an app keep its own files there through MediaStore without any
 * permission; they stay when the app is uninstalled, which is the point of a backup. A reinstalled
 * app can't see the old install's files (Android ties them to the install), so those aren't counted
 * among the newest kept; they are still there to restore from.
 */
@RequiresApi(Build.VERSION_CODES.Q)
internal class DocumentsFolder(private val resolver: ContentResolver) : BackupPlace {

    private val collection: Uri = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)

    override fun label() = LABEL

    override fun writable() = true

    override fun create(name: String): Uri {
        val values =
            ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, BackupFiles.MIME_TYPE)
                put(MediaStore.MediaColumns.RELATIVE_PATH, PATH)
                // Hidden from other apps until it's written in full.
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
        return resolver.insert(collection, values) ?: throw IOException("Android didn't make a file in $LABEL")
    }

    override fun finish(file: Uri) {
        resolver.update(file, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
    }

    override fun backups(): List<Uri> {
        val found = ArrayList<BackupPlace.Found>()
        val columns = arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.DATE_MODIFIED)
        val where = "${MediaStore.MediaColumns.RELATIVE_PATH} = ?"
        resolver.query(collection, columns, where, arrayOf(PATH), null)?.use { rows ->
            while (rows.moveToNext()) {
                val name = rows.getString(1) ?: continue
                found += BackupPlace.Found(ContentUris.withAppendedId(collection, rows.getLong(0)), name, rows.getLong(2))
            }
        }
        return BackupPlace.newestFirst(found)
    }

    override fun delete(file: Uri) {
        resolver.delete(file, null, null)
    }

    companion object {
        const val PATH = "Documents/Fanos/"
        const val LABEL = "Documents/Fanos"
    }
}

/** Backup file names: `fanos_2026-09-25_14-30.fanosbackup`, which sort by when they were made. */
internal object BackupFiles {
    /** No standard type fits, and a gzip one invites apps along the way to unpack the file. */
    const val MIME_TYPE = "application/octet-stream"

    private const val PREFIX = "fanos_"
    private const val EXTENSION = ".fanosbackup"

    private const val STAMP = "yyyy-MM-dd_HH-mm"

    fun name(time: Long): String = PREFIX + SimpleDateFormat(STAMP, Locale.US).format(Date(time)) + EXTENSION

    fun isBackup(name: String): Boolean = name.startsWith(PREFIX) && name.endsWith(EXTENSION)

    /** When a backup was made, as its name says, in a form that sorts. */
    fun madeAt(name: String): String = name.removePrefix(PREFIX).take(STAMP.length)
}
