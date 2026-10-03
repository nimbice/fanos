package io.github.nimbice.fanos.core.data.backup

import io.github.nimbice.fanos.core.model.SourceInfo
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class NovelLibraryImportTest {

    private val royalRoad = SourceInfo("royalroad", "Royal Road", "en", "https://www.royalroad.com", supportsPopular = true, supportsLatest = true)

    private val library =
        NovelLibraryData(
            sections = listOf(NlSection(7, "Finished", order = 1), NlSection(3, "On hold", order = 0)),
            novels =
                listOf(
                    NlNovel(
                        id = 1,
                        name = "Mother of Learning",
                        url = "https://www.royalroad.com/fiction/21220/mother-of-learning",
                        imageUrl = "https://www.royalroadcdn.com/public/covers-large/21220.jpg",
                        metadata = mapOf("lastReadDate" to "28 Sep 2026"),
                        bookmark = "https://www.royalroad.com/fiction/21220/mother-of-learning/chapter/301781/2-lifes-little-problems",
                        sectionId = 7,
                        chapters =
                            listOf(
                                NlChapter("https://www.royalroad.com/fiction/21220/mother-of-learning/chapter/301781/2-lifes-little-problems", "2. Life's Little Problems", 1, read = false),
                                NlChapter("https://www.royalroad.com/fiction/21220/mother-of-learning/chapter/301778/1-good-morning-brother", "1. Good Morning Brother", 0, read = true),
                            ),
                    ),
                    NlNovel(
                        id = 2,
                        name = "Lord of the Mysteries",
                        url = "https://www.novelupdates.com/series/lord-of-the-mysteries/",
                        metadata = mapOf("Author(s)" to "<a class=\"genre\" href=\"x\">Cuttlefish That Loves Diving</a>"),
                        chapters = listOf(NlChapter("https://www.novelupdates.com/extnu/3452/", "c1", 0, read = true)),
                    ),
                ),
        )

    private val backup = library.toBackup(now = 5_000, installed = listOf(royalRoad))

    @Test
    fun `Currently Reading is the default section, and NovelLibrary's own follow in its order`() {
        assertEquals(listOf(BackupSection("Currently Reading", isDefault = true), BackupSection("On hold"), BackupSection("Finished")), backup.sections)
        assertEquals(listOf("Finished"), backup.novels.first { it.title == "Mother of Learning" }.sections)
        assertEquals(emptyList(), backup.novels.first { it.title == "Lord of the Mysteries" }.sections)
    }

    @Test
    fun `a novel on an installed extension's site is that source's, keyed as it keys them`() {
        val novel = backup.novels.first { it.title == "Mother of Learning" }

        assertEquals("royalroad", novel.source)
        assertEquals("/fiction/21220/mother-of-learning", novel.key)
        assertEquals(listOf("1. Good Morning Brother", "2. Life's Little Problems"), novel.chapters.map { it.title })
        assertEquals("/fiction/21220/mother-of-learning/chapter/301778/1-good-morning-brother", novel.chapters[0].key)
        assertEquals(listOf(0, 1), novel.chapters.map { it.index })
    }

    @Test
    fun `read chapters stay read, and the chapter the reader was on is where Continue opens`() {
        val chapters = backup.novels.first { it.title == "Mother of Learning" }.chapters
        val lastRead = assertNotNull(chapters[0].readAt)

        assertNull(chapters[1].readAt)
        assertEquals(BackupProgress(block = 0, offset = 0, updatedAt = lastRead), chapters[1].progress)
        assertNull(chapters[0].progress)
    }

    @Test
    fun `a novel from a site no extension reads comes in under its host, its author as plain text`() {
        val novel = backup.novels.first { it.title == "Lord of the Mysteries" }

        assertEquals("novelupdates.com", novel.source)
        assertEquals("/series/lord-of-the-mysteries/", novel.key)
        assertEquals("Cuttlefish That Loves Diving", novel.author)
        assertEquals(5_000, novel.chapters.single().readAt)
    }

    @Test
    fun `the library comes in section by section, as NovelLibrary shows it`() {
        // Currently Reading first, then On hold (order 0), then Finished (order 1).
        assertEquals(listOf("Lord of the Mysteries", "Mother of Learning"), backup.novels.map { it.title })
    }
}
