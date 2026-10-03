package io.github.nimbice.fanos.core.updater

import io.github.nimbice.fanos.core.common.Dispatcher
import io.github.nimbice.fanos.core.common.ReaderDispatchers
import io.github.nimbice.fanos.core.datastore.SettingsDataSource
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import okhttp3.OkHttpClient
import java.io.File
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext

/** A published extension, as index.json describes it. */
data class PublishedExtension(
    /** Its package name, which is also an installed extension's id. */
    val id: String,
    val name: String,
    val versionCode: Long,
    val versionName: String,
    /** The source API level it was built against: newer than the app's, it can't be installed yet. */
    val apiLevel: Int,
    /** Its sources' language, as the extensions repository files it ("en", "all"). */
    val lang: String,
    val size: Long,
    val sha256: String,
    internal val url: String,
    /** Published privately beside the app's builds, so read with the updates key; the public ones need none. */
    internal val keyed: Boolean = false,
)

/**
 * The published extensions: the public ones, in the extensions repository's release tagged "extensions", read
 * without a key, and, with the app-updates key, those published privately beside the app's builds, in the builds
 * repository's release of the same tag. One in both is taken as the newer of the two.
 */
@Singleton
class ExtensionCatalog @Inject constructor(
    @ApplicationContext context: Context,
    private val settings: SettingsDataSource,
    client: OkHttpClient,
    @Dispatcher(ReaderDispatchers.IO) private val io: CoroutineDispatcher,
) {
    private val releases = GitHubReleases(client, io)

    /** What was last fetched, kept for [DAY_MS]: the screens listing extensions read this rather than asking GitHub each time. */
    private val cache = File(context.cacheDir, "extension-catalogue.json")

    /**
     * The published extensions, public and (with a key) private; empty when nothing is published yet. Fetched afresh
     * unless what was fetched within [maxAge] (with the same key or none) will do.
     */
    suspend fun published(maxAge: Long = 0): List<PublishedExtension> {
        val token = settings.updates.first().token
        if (maxAge > 0) cached(maxAge, withKey = token != null)?.let { return it }
        return saying("Couldn't reach GitHub. Check your connection.") {
            val public = publicIndex()
            val private = if (token != null) privateIndex(token) else emptyList()
            // The public one first, so it's the one kept of two at the same version.
            (public + private).groupBy { it.id }.values.map { same -> same.maxBy { it.versionCode } }
        }.also { remember(it, withKey = token != null) }
    }

    private suspend fun cached(maxAge: Long, withKey: Boolean): List<PublishedExtension>? =
        withContext(io) {
            runCatching {
                if (!cache.isFile) return@withContext null
                val kept = GitHubReleases.JSON.decodeFromString(Cached.serializer(), cache.readText())
                if (kept.withKey != withKey || System.currentTimeMillis() - kept.at !in 0..maxAge) return@withContext null
                kept.extensions.map { it.extension() }
            }.getOrNull()
        }

    private suspend fun remember(extensions: List<PublishedExtension>, withKey: Boolean) {
        withContext(io) {
            runCatching {
                cache.writeText(GitHubReleases.JSON.encodeToString(Cached.serializer(), Cached(System.currentTimeMillis(), withKey, extensions.map { CachedExtension(it) })))
            }
        }
    }

    private suspend fun publicIndex(): List<PublishedExtension> {
        val base = releases.publicExtensions
        val text = releases.publicText("$base/$INDEX") ?: return emptyList()
        return index(text).extensions.map { entry -> entry.published("$base/${entry.file}", keyed = false) }
    }

    private suspend fun privateIndex(token: String): List<PublishedExtension> {
        val release = releases.release(token, TAG) ?: return emptyList()
        val indexUrl = release.assets[INDEX] ?: throw UpdateException("The published extensions have no $INDEX.")
        return index(releases.text(indexUrl, token)).extensions.mapNotNull { entry ->
            val url = release.assets[entry.file] ?: return@mapNotNull null
            entry.published(url, keyed = true)
        }
    }

    private fun index(text: String): Index =
        runCatching { GitHubReleases.JSON.decodeFromString(Index.serializer(), text) }.getOrElse { throw UpdateException("The extensions' $INDEX can't be read.") }

    private fun Entry.published(url: String, keyed: Boolean) = PublishedExtension(packageName, name, versionCode, versionName, apiLevel, lang, size, sha256, url, keyed)

    /** Fetches [extension]'s APK into [into], and makes sure it's the file index.json describes. */
    suspend fun download(extension: PublishedExtension, into: File) {
        val token = if (extension.keyed) settings.updates.first().token ?: throw UpdateException("Set up app updates in Settings first.") else null
        saying("The download of ${extension.name} stopped. Check your connection and try again.") { releases.download(extension.url, token, into, extension.size) }
        val whole = withContext(io) { into.sha256().equals(extension.sha256, ignoreCase = true) }
        if (!whole) {
            into.delete()
            throw UpdateException("The download of ${extension.name} was damaged. Try again.")
        }
    }

    /** Runs [block], saying [message] when the connection fails; what GitHub itself says is kept. */
    private inline fun <T> saying(message: String, block: () -> T): T =
        try {
            block()
        } catch (e: UpdateException) {
            throw e
        } catch (e: IOException) {
            throw UpdateException(message)
        }

    /** The last fetch, as kept on disk. */
    @Serializable
    private class Cached(val at: Long, val withKey: Boolean, val extensions: List<CachedExtension>)

    @Serializable
    private class CachedExtension(
        val id: String,
        val name: String,
        val versionCode: Long,
        val versionName: String,
        val apiLevel: Int,
        val lang: String,
        val size: Long,
        val sha256: String,
        val url: String,
        val keyed: Boolean,
    ) {
        constructor(e: PublishedExtension) : this(e.id, e.name, e.versionCode, e.versionName, e.apiLevel, e.lang, e.size, e.sha256, e.url, e.keyed)

        fun extension() = PublishedExtension(id, name, versionCode, versionName, apiLevel, lang, size, sha256, url, keyed)
    }

    @Serializable
    private class Index(val extensions: List<Entry> = emptyList())

    @Serializable
    private class Entry(
        @SerialName("package") val packageName: String,
        val name: String,
        val versionCode: Long,
        val versionName: String,
        val apiLevel: Int,
        val lang: String = "",
        val file: String,
        val size: Long = 0,
        val sha256: String,
    )

    companion object {
        private const val TAG = "extensions"
        private const val INDEX = "index.json"

        /** How long a fetched catalogue serves the screens: the daily check fetches afresh. */
        const val DAY_MS = 24 * 60 * 60 * 1000L
    }
}
