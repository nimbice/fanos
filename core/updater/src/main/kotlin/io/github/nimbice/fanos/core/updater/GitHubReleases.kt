package io.github.nimbice.fanos.core.updater

import io.github.nimbice.fanos.core.network.await
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import kotlin.coroutines.coroutineContext
import java.util.concurrent.TimeUnit

/**
 * The app's builds and extensions, as releases of a private GitHub repository read with a read-only key.
 * The latest release is the app's newest build, its update.json saying which; the release tagged
 * "extensions" holds the extensions, its index.json listing them. Public builds and extensions are read the
 * same way from public releases, without a key.
 */
internal class GitHubReleases(
    private val client: OkHttpClient,
    private val io: CoroutineDispatcher,
    private val api: String = BuildConfig.UPDATES_API,
    private val repo: String = BuildConfig.UPDATES_REPO,
    private val public: String = BuildConfig.UPDATES_PUBLIC,
    /** Where the public extensions are, read without a key: index.json and the APKs it lists. */
    val publicExtensions: String = BuildConfig.EXTENSIONS_PUBLIC,
) {
    /** For the files themselves, which take as long as the connection takes: no limit on the whole call. */
    private val downloads by lazy { client.newBuilder().callTimeout(0, TimeUnit.SECONDS).build() }

    /** A release's files, by name, with the API addresses that serve them. */
    class Release(val assets: Map<String, String>)

    /** The latest release, or the one tagged [tag]; null when there's none such (yet). */
    suspend fun release(token: String, tag: String? = null): Release? =
        withContext(io) {
            val path = if (tag == null) "releases/latest" else "releases/tags/$tag"
            call("$api/repos/$repo/$path", token, GITHUB_JSON).use { response ->
                // No such release, or no repository this key can see: GitHub says 404 to both.
                if (response.code == 404) {
                    if (repositoryVisible(token)) return@withContext null
                    throw UpdateException("The key can't open $repo. Check that it was made for that repository.")
                }
                requireSuccess(response)
                Release(JSON.decodeFromString(ReleaseJson.serializer(), response.body.string()).assets.associate { it.name to it.url })
            }
        }

    /** The text of the file at [url], such as a release's update.json. */
    suspend fun text(url: String, token: String?): String =
        withContext(io) {
            call(url, token, BINARY).use { response ->
                requireSuccess(response)
                response.body.string()
            }
        }

    /** Fetches the file at [url] into [into], telling [onProgress] how far it has got, from 0 to 1. */
    suspend fun download(url: String, token: String?, into: File, size: Long, onProgress: (Float) -> Unit = {}) {
        withContext(io) {
            call(url, token, BINARY, downloads).use { response ->
                requireSuccess(response)
                val total = response.body.contentLength().takeIf { it > 0 } ?: size
                var read = 0L
                response.body.byteStream().use { input ->
                    into.outputStream().use { output ->
                        val buffer = ByteArray(BUFFER)
                        while (true) {
                            coroutineContext.ensureActive()
                            val count = input.read(buffer)
                            if (count < 0) break
                            output.write(buffer, 0, count)
                            read += count
                            if (total > 0) onProgress((read.toFloat() / total).coerceIn(0f, 1f))
                        }
                    }
                }
            }
        }
    }

    /** The app's newest build, or null when the repository has none yet. */
    suspend fun latest(token: String): AvailableUpdate? {
        val release = release(token) ?: return null
        val manifestUrl = release.assets[MANIFEST] ?: throw UpdateException("The latest build has no $MANIFEST.")
        val text = text(manifestUrl, token)
        val manifest = runCatching { JSON.decodeFromString(Manifest.serializer(), text) }.getOrElse { throw UpdateException("The latest build's $MANIFEST can't be read.") }
        val apk = release.assets[manifest.apk] ?: throw UpdateException("The latest build's APK is missing.")
        return AvailableUpdate(manifest.versionCode, manifest.versionName, manifest.notes, manifest.size, manifest.sha256, apk, keyed = true)
    }

    /**
     * The newest public build, read without a key from the latest release's files by name; null when there's no
     * public release (yet), as GitHub says 404 to.
     */
    suspend fun latestPublic(): AvailableUpdate? =
        withContext(io) {
            call("$public/$MANIFEST", null, BINARY).use { response ->
                if (response.code == 404) return@withContext null
                requireSuccess(response)
                val text = response.body.string()
                val manifest = runCatching { JSON.decodeFromString(Manifest.serializer(), text) }.getOrElse { throw UpdateException("The latest build's $MANIFEST can't be read.") }
                AvailableUpdate(manifest.versionCode, manifest.versionName, manifest.notes, manifest.size, manifest.sha256, "$public/${manifest.apk}")
            }
        }

    /** The text of the public file at [url], or null when there's no such file: GitHub says 404 for a release not made yet. */
    suspend fun publicText(url: String): String? =
        withContext(io) {
            call(url, null, BINARY).use { response ->
                if (response.code == 404) return@withContext null
                requireSuccess(response)
                response.body.string()
            }
        }

    private suspend fun repositoryVisible(token: String): Boolean =
        call("$api/repos/$repo", token, GITHUB_JSON).use { response ->
            if (response.code == 401) throw UpdateException(REFUSED)
            response.isSuccessful
        }

    // An asset's address answers with a redirect to the file's own host; OkHttp follows it and, the
    // host being another, leaves the key behind. Public releases are read without one.
    private suspend fun call(url: String, token: String?, accept: String, via: OkHttpClient = client): Response =
        via.newCall(
            Request.Builder()
                .url(url)
                .apply { if (token != null) header("Authorization", "Bearer $token") }
                .header("Accept", accept)
                .header("X-GitHub-Api-Version", "2022-11-28")
                .header("User-Agent", "Fanos-updater")
                .header("Cache-Control", "no-store")
                .build(),
        ).await()

    private fun requireSuccess(response: Response) {
        when {
            response.isSuccessful -> Unit
            response.code == 401 -> throw UpdateException(REFUSED)
            response.code == 403 || response.code == 429 -> throw UpdateException("GitHub is limiting requests. Try again later.")
            else -> throw IOException("GitHub answered with HTTP ${response.code}")
        }
    }

    @Serializable
    private class ReleaseJson(val assets: List<Asset> = emptyList())

    /** A release's file: [url] is its API address, which serves the file itself when asked for bytes. */
    @Serializable
    private class Asset(val name: String, val url: String)

    /** What update.json, published with each build, says of it. */
    @Serializable
    private class Manifest(
        val versionCode: Long,
        val versionName: String,
        val apk: String,
        val size: Long = 0,
        val sha256: String,
        val notes: String = "",
    )

    internal companion object {
        const val MANIFEST = "update.json"
        const val GITHUB_JSON = "application/vnd.github+json"
        const val BINARY = "application/octet-stream"
        const val BUFFER = 64 * 1024
        const val REFUSED = "GitHub refused the key. It may have expired: set up updates again with a new one."

        val JSON = Json { ignoreUnknownKeys = true }
    }
}

/** Why the builds can't be read, in words for the screen. */
class UpdateException(message: String) : IOException(message)

/** The file's SHA-256, in lower-case hex, as update.json and index.json give it. */
internal fun File.sha256(): String {
    val digest = MessageDigest.getInstance("SHA-256")
    inputStream().use { input ->
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            digest.update(buffer, 0, count)
        }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}
