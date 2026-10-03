package io.github.nimbice.fanos.core.data.repository

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CoverSearchTest {

    @Test
    fun `a numbered book's series is its title less the number`() {
        assertEquals("He Who Fights With Monsters", CoverSearch.seriesOf("He Who Fights With Monsters 14"))
        assertEquals("The Lamplit Archive", CoverSearch.seriesOf("The Lamplit Archive, Book 3"))
        assertEquals("The Lamplit Archive", CoverSearch.seriesOf("The Lamplit Archive: Volume 2"))
        assertEquals("The Lamplit Archive", CoverSearch.seriesOf("The Lamplit Archive #4"))
    }

    @Test
    fun `a title with no number, or nothing but one, has no series to search`() {
        assertNull(CoverSearch.seriesOf("He Who Fights With Monsters"))
        assertNull(CoverSearch.seriesOf("Book 14"))
        assertNull(CoverSearch.seriesOf("  "))
    }

    @Test
    fun `books sort by their number, the first often having none`() {
        val titles = listOf("He Who Fights with Monsters 12", "He Who Fights with Monsters", "He Who Fights with Monsters 2", "He Who Fights With Monsters 10")

        assertEquals(
            listOf("He Who Fights with Monsters", "He Who Fights with Monsters 2", "He Who Fights With Monsters 10", "He Who Fights with Monsters 12"),
            titles.sortedBy(CoverSearch::bookNumber),
        )
    }
}
