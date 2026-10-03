package io.github.nimbice.fanos.core.data.download

import io.github.nimbice.fanos.core.common.Dispatcher
import io.github.nimbice.fanos.core.common.ReaderDispatchers
import io.github.nimbice.fanos.core.common.suspendRunCatching
import io.github.nimbice.fanos.core.content.Block
import io.github.nimbice.fanos.core.data.repository.ContentRepository
import io.github.nimbice.fanos.core.network.await
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Saves one chapter for reading offline: its text, then its images. Images come through the app's
 * own client (its cookies, its pace per site) with the chapter as referrer, as sites that guard
 * their images expect. An image that can't be had leaves a gap offline rather than failing the
 * chapter: hotlinked images often outlive their hosts.
 */
@Singleton
class ChapterSaver @Inject constructor(
    private val content: ContentRepository,
    private val images: DownloadedImages,
    private val client: OkHttpClient,
    @Dispatcher(ReaderDispatchers.IO) private val io: CoroutineDispatcher,
) : ChapterSaving {
    override suspend fun save(chapterId: Long): Boolean {
        val saved = content.save(chapterId)
        val sources = saved.content.leaves.filterIsInstance<Block.Image>().map { it.src }.filter(::isWebUrl).distinct()
        var fetched = saved.fetched
        for (src in sources) {
            val file = images.file(chapterId, src)
            if (file.isFile) continue
            fetched = true
            // A second try for a dropped connection; a refusal (gone, forbidden) isn't asked again.
            val first = suspendRunCatching { fetchImage(src, saved.url, file) }
            if (first.exceptionOrNull() is IOException) suspendRunCatching { fetchImage(src, saved.url, file) }
        }
        return fetched
    }

    private suspend fun fetchImage(src: String, referrer: String, into: File) {
        val request =
            Request.Builder()
                .url(src)
                .header("Referer", referrer)
                .header("Accept", "image/avif,image/webp,image/*,*/*;q=0.8")
                .build()
        client.newCall(request).await().use { response ->
            if (!response.isSuccessful) return
            val body = response.body
            if (body.contentLength() > MAX_IMAGE_BYTES) return
            withContext(io) {
                into.parentFile?.mkdirs()
                val part = File(into.parentFile, into.name + ".part")
                try {
                    var copied = 0L
                    body.byteStream().use { input ->
                        part.outputStream().use { output ->
                            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                            while (true) {
                                val read = input.read(buffer)
                                if (read < 0) break
                                copied += read
                                if (copied > MAX_IMAGE_BYTES) return@withContext
                                output.write(buffer, 0, read)
                            }
                        }
                    }
                    if (copied > 0) part.renameTo(into)
                } finally {
                    part.delete()
                }
            }
        }
    }

    private fun isWebUrl(url: String) = url.startsWith("https://", ignoreCase = true) || url.startsWith("http://", ignoreCase = true)

    private companion object {
        const val MAX_IMAGE_BYTES = 20L * 1024 * 1024
    }
}

/** Saves one chapter for reading offline. Returns whether any site was asked for anything, which a download paces itself by. */
fun interface ChapterSaving {
    suspend fun save(chapterId: Long): Boolean
}
