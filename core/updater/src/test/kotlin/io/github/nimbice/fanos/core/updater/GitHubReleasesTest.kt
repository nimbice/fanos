package io.github.nimbice.fanos.core.updater

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GitHubReleasesTest {

    private val server = MockWebServer()
    private val repo = "nimbice/fanos-builds"
    private val requests = mutableListOf<RecordedRequest>()

    @Before
    fun start() = server.start()

    @After
    fun stop() = server.close()

    private fun releases() = GitHubReleases(OkHttpClient(), Dispatchers.IO, api = server.url("").toString().trimEnd('/'), repo = repo)

    private fun asset(id: Int) = server.url("/repos/$repo/releases/assets/$id").toString()

    private val manifest = """{"versionCode": 27, "versionName": "0.1.0-test.27", "apk": "Fanos-0.1.0-test.27.apk", "size": 5, "sha256": "abc", "notes": "Chapters panel"}"""

    private fun latestRelease() =
        """{"tag_name": "test.27", "assets": [
             {"name": "update.json", "url": "${asset(1)}", "size": 120},
             {"name": "Fanos-0.1.0-test.27.apk", "url": "${asset(2)}", "size": 5}]}"""

    @Test
    fun `the latest release's update json describes the build, and its APK comes by way of a redirect`() =
        runTest {
            server.enqueue(MockResponse.Builder().body(latestRelease()).build())
            server.enqueue(MockResponse.Builder().code(302).addHeader("Location", server.url("/files/update.json")).build())
            server.enqueue(MockResponse.Builder().body(manifest).build())

            val update = releases().latest("secret")!!

            assertEquals(27L, update.versionCode)
            assertEquals("0.1.0-test.27", update.versionName)
            assertEquals("Chapters panel", update.notes)
            assertEquals(asset(2), update.apkUrl)
            val first = server.takeRequest()
            assertEquals("/repos/$repo/releases/latest", first.target)
            assertEquals("Bearer secret", first.headers["Authorization"])
            assertEquals("application/octet-stream", server.takeRequest().headers["Accept"])

            server.enqueue(MockResponse.Builder().body("12345").build())
            val file = File.createTempFile("update", ".apk").apply { deleteOnExit() }
            val progress = mutableListOf<Float>()
            releases().download(update.apkUrl, "secret", file, update.size) { progress += it }
            assertEquals("12345", file.readText())
            assertEquals(1f, progress.last())
        }

    @Test
    fun `a repository without releases has no update, and one the key can't see says so`() =
        runTest {
            server.enqueue(MockResponse.Builder().code(404).build())
            server.enqueue(MockResponse.Builder().body("""{"name": "fanos-builds"}""").build())
            assertNull(releases().latest("secret"))

            server.enqueue(MockResponse.Builder().code(404).build())
            server.enqueue(MockResponse.Builder().code(404).build())
            val hidden = assertFailsWith<UpdateException> { releases().latest("secret") }
            assertTrue("can't open" in hidden.message.orEmpty())
        }

    @Test
    fun `the extensions are the release tagged extensions, and its files are read as text`() =
        runTest {
            server.enqueue(
                MockResponse.Builder().body("""{"tag_name": "extensions", "assets": [{"name": "index.json", "url": "${asset(7)}", "size": 30}]}""").build(),
            )
            server.enqueue(MockResponse.Builder().body("""{"extensions": []}""").build())

            val release = releases().release("secret", "extensions")!!
            assertEquals(mapOf("index.json" to asset(7)), release.assets)
            assertEquals("""{"extensions": []}""", releases().text(release.assets.getValue("index.json"), "secret"))
            assertEquals("/repos/$repo/releases/tags/extensions", server.takeRequest().target)
            assertEquals("application/octet-stream", server.takeRequest().headers["Accept"])
        }

    @Test
    fun `a refused key asks for a new one`() =
        runTest {
            server.enqueue(MockResponse.Builder().code(401).build())
            val refused = assertFailsWith<UpdateException> { releases().latest("stale") }
            assertTrue("refused the key" in refused.message.orEmpty())
        }
}
