package io.github.nimbice.fanos.feature.novel

import io.github.nimbice.fanos.core.model.Chapter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ChapterSearchTest {

    private fun chapters(vararg titles: String) = titles.mapIndexed { i, title -> Chapter(id = i + 1L, novelId = 1, url = "u$i", title = title, index = i) }

    @Test
    fun `a number goes to the chapter its title numbers so`() {
        val list = chapters("Prologue", "Chapter 25: 250 soldiers", "Chapter 249", "Chapter 250.5", "Chapter 250 – The Door", "Ch. 251", "c252", "253: Ashes")

        assertEquals("Chapter 250 – The Door", findChapter(list, "250")?.title)
        assertEquals("Ch. 251", findChapter(list, " 251 ")?.title)
        assertEquals("c252", findChapter(list, "252")?.title)
        assertEquals("253: Ashes", findChapter(list, "253")?.title)
        assertEquals("Chapter 250.5", findChapter(list, "250.5")?.title)
        assertEquals("Chapter 25: 250 soldiers", findChapter(list, "025")?.title)
    }

    @Test
    fun `a number no title has is the chapter at that place, and words go to no chapter`() {
        val list = chapters("The Start", "The Middle", "The End")

        assertEquals("The Middle", findChapter(list, "2")?.title)
        assertNull(findChapter(list, "4"))
        assertNull(findChapter(list, "middle"))
        assertNull(findChapter(list, "."))
    }
}
