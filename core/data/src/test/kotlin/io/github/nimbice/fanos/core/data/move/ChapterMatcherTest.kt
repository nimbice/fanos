package io.github.nimbice.fanos.core.data.move

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ChapterMatcherTest {

    @Test
    fun `numbers are read the ways sites write them`() {
        val titles =
            mapOf(
                "Chapter 12: The Duel" to 12.0,
                "Ch. 7 - Rain" to 7.0,
                "chapter 12.5" to 12.5,
                "Volume 2 Chapter 3" to 3.0,
                "c41" to 41.0,
                "v2c3" to 3.0,
                "Chapter 4-5 (end)" to 4.0,
                "12. The Duel" to 12.0,
                "108 - Homecoming" to 108.0,
                "Episode 9" to 9.0,
                "第12章 决斗" to 12.0,
            )
        titles.forEach { (title, number) -> assertEquals(title, number, ChapterMatcher.number(title)!!, 0.0) }
        listOf("Prologue", "Side Story: Beach Day", "12th Street", "The C4 Plan", "Research 5").forEach { assertNull(it, ChapterMatcher.number(it)) }
    }

    @Test
    fun `chapters match by number across the sites' different titles, and by title where there's no number`() {
        val from = listOf("Prologue", "c1", "c2", "c3")
        val to = listOf("Prologue", "Chapter 1: Dawn", "Chapter 2: Noon", "Chapter 4: Night")

        assertEquals(listOf(0, 1, 2, null), ChapterMatcher.match(from, to))
    }

    @Test
    fun `a number that comes twice is matched in turn`() {
        val from = listOf("Chapter 12 (1)", "Chapter 12 (2)", "Chapter 13")
        val to = listOf("12. Part one", "12. Part two", "13. Next")

        assertEquals(listOf(0, 1, 2), ChapterMatcher.match(from, to))
    }

    @Test
    fun `titles without letters or digits match nothing`() {
        assertEquals(listOf<Int?>(null), ChapterMatcher.match(listOf("***"), listOf("***")))
    }
}
