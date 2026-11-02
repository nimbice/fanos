package io.github.nimbice.fanos.feature.reader

import org.junit.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WithinReachTest {

    // A novel's chapters in reading order, their ids out of order as updates add them.
    private val ids = listOf(4698L, 4699, 5441, 5442, 5443, 6384, 6385)

    @Test
    fun `chapters either side of the one being read, up to the keep`() {
        assertTrue(withinReach(ids, 5442, 5442, 2))
        assertTrue(withinReach(ids, 5442, 4699, 2))
        assertTrue(withinReach(ids, 5442, 6384, 2))
        assertFalse(withinReach(ids, 5442, 4698, 2))
        assertFalse(withinReach(ids, 5442, 6385, 2))
    }

    @Test
    fun `nothing that isn't listed, nor anything from a chapter that isn't`() {
        assertFalse(withinReach(ids, 5442, 9999, 2))
        assertFalse(withinReach(ids, 9999, 5442, 2))
        assertTrue(withinReach(ids, 9999, 9999, 2))
    }
}
