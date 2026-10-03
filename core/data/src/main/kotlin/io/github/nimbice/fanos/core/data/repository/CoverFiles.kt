package io.github.nimbice.fanos.core.data.repository

import android.content.Context
import android.net.Uri
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.nimbice.fanos.core.common.Dispatcher
import io.github.nimbice.fanos.core.common.ReaderDispatchers
import io.github.nimbice.fanos.core.data.copyAtMost
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Covers the reader picked from their phone: a copy of each is kept among the app's own files, so it
 * stays when the original is moved or deleted.
 */
@Singleton
class CoverFiles @Inject constructor(
    @ApplicationContext private val context: Context,
    @Dispatcher(ReaderDispatchers.IO) private val io: CoroutineDispatcher,
) {
    private val dir = File(context.filesDir, "covers")

    /** Keeps a copy of the picture at [uri] for novel [novelId]; returns the copy's address. */
    suspend fun keep(novelId: Long, uri: Uri): String =
        withContext(io) {
            dir.mkdirs()
            val file = File(dir, "$novelId-${System.currentTimeMillis()}")
            try {
                val input = context.contentResolver.openInputStream(uri) ?: throw IOException("Couldn't open the picture")
                input.use { from -> file.outputStream().use { to -> from.copyAtMost(to, MAX_BYTES) { IOException("That picture is too large for a cover") } } }
            } catch (e: IOException) {
                file.delete()
                throw e
            }
            Uri.fromFile(file).toString()
        }

    /** Deletes the novel's kept pictures other than [cover], the one it shows now, if it's one of them. */
    suspend fun tidy(novelId: Long, cover: String?) {
        withContext(io) {
            dir.listFiles { file -> file.name.startsWith("$novelId-") && Uri.fromFile(file).toString() != cover }?.forEach { it.delete() }
        }
    }

    private companion object {
        const val MAX_BYTES = 20L * 1024 * 1024
    }
}
