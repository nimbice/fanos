package io.github.nimbice.fanos.core.data.backup

import io.github.nimbice.fanos.core.data.repository.StoredReaderSettings
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class BackupCodecTest {

    private val settings =
        buildJsonObject {
            put("reader_font_size", 21)
            put("reader_line_height", 1.8)
            put("dynamic_color", true)
            put("theme_mode", "Dark")
            put("search_excluded_sources", JsonArray(listOf(JsonPrimitive("lnmtl"))))
        }

    private val novels =
        listOf(
            BackupNovel(
                source = "royalroad",
                key = "/fiction/1/a",
                url = "https://www.royalroad.com/fiction/1/a",
                title = "A",
                author = "Someone",
                genres = listOf("Fantasy", "Adventure"),
                status = "Ongoing",
                addedAt = 100,
                lastReadAt = 200,
                chaptersFetchedAt = 150,
                chapters =
                    listOf(
                        BackupChapter("/c/1", "https://www.royalroad.com/c/1", "One", 0, publishedAt = 10, readAt = 20, addedAt = 5),
                        BackupChapter("/c/2", "https://www.royalroad.com/c/2", "Two", 1, addedAt = 5, progress = BackupProgress(12, 40, 210)),
                        BackupChapter("/c/0", "https://www.royalroad.com/c/0", "Gone", 2, removedAt = 30, addedAt = 5),
                    ),
            ),
            BackupNovel(
                source = "patreon",
                key = "/collection/77",
                url = "https://www.patreon.com/collection/77",
                title = "Book 2",
                customTitle = "The Lamplit Archive 2",
                customCover = "https://covers.openlibrary.org/b/id/42-L.jpg",
                readerSettings = StoredReaderSettings(theme = "Sepia", font = "Lora", fontSize = 22, lineHeight = 1.8f, pageMode = true),
                sections = listOf("Reading", "Patreon"),
            ),
            BackupNovel(source = "novelfull", key = "/b.html", url = "https://novelfull.com/b.html", title = "B"),
        )

    private val sections = listOf(BackupSection("Reading", isDefault = true), BackupSection("Patreon"), BackupSection("Finished"))

    private fun written(settings: kotlinx.serialization.json.JsonObject? = this.settings): ByteArray {
        val out = ByteArrayOutputStream()
        BackupCodec.Writer(out, createdAt = 1_000, app = "0.1.0", settings = settings, sections = sections).use { writer ->
            novels.forEach(writer::add)
            writer.finish()
        }
        return out.toByteArray()
    }

    private fun read(bytes: ByteArray) = BackupCodec.read { ByteArrayInputStream(bytes) }

    private fun gzip(text: String): ByteArray = ByteArrayOutputStream().also { out -> GZIPOutputStream(out).use { it.write(text.toByteArray()) } }.toByteArray()

    private fun gunzip(bytes: ByteArray): String = GZIPInputStream(ByteArrayInputStream(bytes)).readBytes().decodeToString()

    @Test
    fun `a backup reads back as written`() {
        val backup = read(written())
        assertEquals(BackupCodec.FORMAT, backup.format)
        assertEquals(BackupCodec.VERSION, backup.version)
        assertEquals(1_000, backup.createdAt)
        assertEquals("0.1.0", backup.app)
        assertEquals(settings, backup.settings)
        assertEquals(sections, backup.sections)
        assertEquals(novels, backup.novels)
    }

    @Test
    fun `a backup without settings or novels reads back`() {
        val out = ByteArrayOutputStream()
        BackupCodec.Writer(out, createdAt = 5, app = null, settings = null).use { it.finish() }
        val backup = read(out.toByteArray())
        assertEquals(null, backup.settings)
        assertEquals(emptyList(), backup.novels)
    }

    @Test
    fun `nulls are left out of the file`() {
        val text = gunzip(written())
        assertTrue("null" !in text, text)
    }

    @Test
    fun `a backup something unpacked on the way still reads`() {
        assertEquals(novels, read(gunzip(written()).toByteArray()).novels)
    }

    @Test
    fun `fields this version doesn't know are skipped and missing ones filled in`() {
        val text =
            """{"format":"fanos-backup","version":1,"createdAt":7,"future":{"x":1},
               "novels":[{"source":"s","key":"/k","url":"https://s/k","title":"T","rating":5,
                 "chapters":[{"key":"/1","url":"https://s/1","title":"One","index":0,"bookmarked":true}]}]}"""
        val backup = read(gzip(text))
        val chapter = backup.novels.single().chapters.single()
        assertEquals("One", chapter.title)
        assertEquals(null, chapter.readAt)
        assertEquals(emptyList(), backup.novels.single().genres)
    }

    @Test
    fun `a file that isn't a backup is named as such`() {
        assertFailsWith<BackupException.NotABackup> { read("%PDF-1.7 not a backup".toByteArray()) }
        assertFailsWith<BackupException.NotABackup> { read(gzip("""{"format":"tachiyomi","version":1,"createdAt":1}""")) }
        assertFailsWith<BackupException.NotABackup> { read(gzip("""{"hello":"world"}""")) }
    }

    @Test
    fun `a backup from a newer version is refused, even one this version can't parse`() {
        assertFailsWith<BackupException.TooNew> { read(gzip("""{"format":"fanos-backup","version":2,"createdAt":1,"novels":[]}""")) }
        assertFailsWith<BackupException.TooNew> { read(gzip("""{"format":"fanos-backup","version":2,"createdAt":1,"novels":{"a":[]}}""")) }
    }

    @Test
    fun `a backup cut short is damaged`() {
        val whole = gunzip(written())
        assertFailsWith<BackupException.Damaged> { read(gzip(whole.substring(0, whole.length / 2))) }
        val bytes = written()
        assertFailsWith<BackupException.Damaged> { read(bytes.copyOf(bytes.size / 2)) }
    }
}
