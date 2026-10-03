package io.github.nimbice.fanos.core.data.repository

import org.junit.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HighlightOverlapTest {

    @Test
    fun `stretches overlap when they share text, across paragraphs too`() {
        assertTrue(overlaps(0, 5, 0, 10, 0, 8, 0, 12))
        // Only touching.
        assertFalse(overlaps(0, 5, 0, 10, 0, 10, 0, 12))
        // A highlight in the paragraph between the two ends of another.
        assertTrue(overlaps(0, 20, 2, 5, 1, 0, 1, 3))
        assertFalse(overlaps(0, 20, 2, 5, 2, 5, 2, 9))
    }
}
