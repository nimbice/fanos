package io.github.nimbice.fanos.feature.browse

import io.github.nimbice.fanos.core.model.Novel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MoveSearchTest {

    private val novel = Novel(id = 1, sourceId = "royalroad", url = "https://www.royalroad.com/fiction/1", title = "My Name for It", author = " Inkwell ", siteTitle = "The Lamplit Archive")

    @Test
    fun `moving many, a title site is searched for the site's title and a creators' site for the author`() {
        assertEquals("The Lamplit Archive", searchWords(novel, searchesCreators = false))
        assertEquals("Inkwell", searchWords(novel, searchesCreators = true))
    }

    @Test
    fun `a creators' site has nothing to search for when the novel has no author`() {
        assertNull(searchWords(novel.copy(author = null), searchesCreators = true))
        assertNull(searchWords(novel.copy(author = "  "), searchesCreators = true))
        assertEquals("The Lamplit Archive", searchWords(novel.copy(author = null), searchesCreators = false))
    }

    @Test
    fun `moving one, creators' sites get the author while the box holds the title, and the typed words once it doesn't`() {
        assertEquals("Inkwell", authorToSearch(novel, typed = "The Lamplit Archive"))
        assertEquals("Inkwell", authorToSearch(novel, typed = " The Lamplit Archive "))
        assertNull(authorToSearch(novel, typed = "Inkwell Writes"))
        assertNull(authorToSearch(novel.copy(author = null), typed = "The Lamplit Archive"))
    }
}
