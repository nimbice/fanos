package io.github.nimbice.fanos.core.data.repository

import io.github.nimbice.fanos.core.common.Dispatcher
import io.github.nimbice.fanos.core.common.ReaderDispatchers
import io.github.nimbice.fanos.core.model.CoverResults
import io.github.nimbice.fanos.core.model.FoundCover
import io.github.nimbice.fanos.core.network.await
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import io.github.nimbice.fanos.core.common.AppIdentity

/**
 * Finds book covers in Open Library's catalogue, which has most published books and their covers and
 * asks for no key or account. A book that isn't out yet has none there: then the rest of its series is
 * searched ("He Who Fights With Monsters 14" becomes "He Who Fights With Monsters"), whose covers do too.
 */
@Singleton
class CoverSearch @Inject constructor(
    private val client: OkHttpClient,
    private val json: Json,
    private val identity: AppIdentity,
    @Dispatcher(ReaderDispatchers.IO) private val io: CoroutineDispatcher,
) {
    suspend fun search(title: String, author: String): CoverResults {
        val found = query(title.trim(), author.trim())
        if (found.isNotEmpty()) return CoverResults(found)
        val series = seriesOf(title) ?: return CoverResults(emptyList())
        return CoverResults(query(series, author.trim()).sortedBy { bookNumber(it.title) }, series)
    }

    private suspend fun query(title: String, author: String): List<FoundCover> {
        if (title.isEmpty()) return emptyList()
        val url =
            SEARCH.toHttpUrl().newBuilder()
                .addQueryParameter("title", title)
                .apply { if (author.isNotEmpty()) addQueryParameter("author", author) }
                .addQueryParameter("fields", "title,author_name,cover_i,first_publish_year")
                .addQueryParameter("limit", LIMIT.toString())
                .build()
        // Open Library asks the apps that use it to say who they are.
        val request = Request.Builder().url(url).header("User-Agent", identity.userAgent).build()
        val body =
            withContext(io) {
                client.newCall(request).await().use { response ->
                    if (!response.isSuccessful) throw IOException("Open Library answered with HTTP ${response.code}")
                    response.body.string()
                }
            }
        return json.decodeFromString(SearchReply.serializer(), body).docs.mapNotNull { book ->
            val cover = book.cover ?: return@mapNotNull null
            FoundCover(
                title = book.title?.trim().orEmpty(),
                author = book.authors.firstOrNull(),
                year = book.year,
                thumbnailUrl = "$COVERS/$cover-M.jpg",
                coverUrl = "$COVERS/$cover-L.jpg",
            )
        }
    }

    @Serializable
    private class SearchReply(val docs: List<Book> = emptyList())

    @Serializable
    private class Book(
        val title: String? = null,
        @SerialName("author_name") val authors: List<String> = emptyList(),
        @SerialName("cover_i") val cover: Long? = null,
        @SerialName("first_publish_year") val year: Int? = null,
    )

    internal companion object {
        const val SEARCH = "https://openlibrary.org/search.json"
        const val COVERS = "https://covers.openlibrary.org/b/id"
        const val LIMIT = 40

        // A book's number in its series at the end of its title: "… 14", "…, Book 14", "…: Volume 3".
        private val BOOK_NUMBER = Regex("""^(.*?)[\s,:.\-–—]*(?:(?:book|vol\.?|volume|part|no\.?|#)\s*)?(\d+)\s*$""", RegexOption.IGNORE_CASE)

        /** The series a numbered book belongs to, or null when [title] has no number to take off. */
        fun seriesOf(title: String): String? = BOOK_NUMBER.matchEntire(title.trim())?.groupValues?.get(1)?.trim()?.ifEmpty { null }

        /** A book's number in its series; the first book often has none. */
        fun bookNumber(title: String): Int = BOOK_NUMBER.matchEntire(title.trim())?.groupValues?.get(2)?.toIntOrNull() ?: 1
    }
}
