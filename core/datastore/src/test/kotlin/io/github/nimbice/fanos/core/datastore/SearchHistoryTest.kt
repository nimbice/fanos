package io.github.nimbice.fanos.core.datastore

import io.github.nimbice.fanos.core.model.SearchHistory
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SearchHistoryTest {

    private val settings = SettingsDataSource(MemoryStore())

    private suspend fun novels() = settings.searchHistory(SearchHistory.Novels).first()

    @Test
    fun `the last five searches are kept, newest first`() =
        runTest {
            listOf("one", "two", "three", "four", "five", "six").forEach { settings.rememberSearch(SearchHistory.Novels, it) }
            assertEquals(listOf("six", "five", "four", "three", "two"), novels())
        }

    @Test
    fun `searching again moves the words to the top, once, in their latest capitals and plain spaces`() =
        runTest {
            listOf("Mother of Learning", "Shirtaloon", "  mother   of learning ").forEach { settings.rememberSearch(SearchHistory.Novels, it) }
            assertEquals(listOf("mother of learning", "Shirtaloon"), novels())
        }

    @Test
    fun `blank searches aren't kept, and a search can be forgotten`() =
        runTest {
            settings.rememberSearch(SearchHistory.Novels, "   ")
            assertEquals(emptyList(), novels())
            listOf("azalea", "Shirtaloon").forEach { settings.rememberSearch(SearchHistory.Novels, it) }
            settings.forgetSearch(SearchHistory.Novels, "azalea")
            assertEquals(listOf("Shirtaloon"), novels())
        }

    @Test
    fun `chapter searches keep a history of their own, and neither goes in backups`() =
        runTest {
            settings.rememberSearch(SearchHistory.Novels, "Shirtaloon")
            settings.rememberSearch(SearchHistory.Chapters, "66")
            assertEquals(listOf("Shirtaloon"), novels())
            assertEquals(listOf("66"), settings.searchHistory(SearchHistory.Chapters).first())
            assertTrue(settings.backupValues().keys.none { "search" in it })
        }
}
