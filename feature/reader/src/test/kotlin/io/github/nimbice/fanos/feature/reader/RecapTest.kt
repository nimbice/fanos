package io.github.nimbice.fanos.feature.reader

import org.junit.Test
import java.util.Locale
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RecapTest {

    private val paragraphs =
        listOf(
            "Something inside her tore.",
            "With a start, Viola sat up.",
            "She dragged in a desperate breath, but it caught in her throat. For one terrible moment, the faces remained.",
            "Then they faded.",
        )

    @Test
    fun `the last sentences before the place, back into earlier paragraphs`() {
        // Stopped at the start of "Then they faded.": the three sentences before it.
        assertEquals(
            "…With a start, Viola sat up. She dragged in a desperate breath, but it caught in her throat. For one terrible moment, the faces remained.",
            recapQuote(paragraphs, 3, 0, Locale.ENGLISH),
        )
        // Stopped part way into a paragraph: its words so far end the quote.
        assertTrue(recapQuote(paragraphs, 2, 35, Locale.ENGLISH)!!.endsWith("She dragged in a desperate breath,"))
        // At the chapter's start there's nothing to recall.
        assertNull(recapQuote(paragraphs, 0, 0, Locale.ENGLISH))
    }

    @Test
    fun `a long sentence is cut to its end`() {
        val long = List(80) { "word$it" }.joinToString(" ") + "."
        val quote = recapQuote(listOf(long, "Next."), 1, 0, Locale.ENGLISH)!!
        assertTrue(quote.startsWith("…word"))
        assertTrue(quote.length <= 281)
        assertTrue(quote.endsWith("word79."))
    }

    @Test
    fun `how long ago, in words`() {
        val hour = 60L * 60 * 1000
        assertEquals("an hour ago", awhileAgo(hour))
        assertEquals("5 hours ago", awhileAgo(5 * hour))
        assertEquals("yesterday", awhileAgo(30 * hour))
        assertEquals("5 days ago", awhileAgo(5 * 24 * hour))
        assertEquals("3 weeks ago", awhileAgo(21 * 24 * hour))
    }
}
