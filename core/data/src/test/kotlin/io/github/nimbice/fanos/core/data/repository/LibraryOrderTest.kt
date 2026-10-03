package io.github.nimbice.fanos.core.data.repository

import org.junit.Test
import kotlin.test.assertEquals

class LibraryOrderTest {

    @Test
    fun `a section reordered keeps the places its novels had among the rest`() {
        // 2, 4 and 5 are one section's novels; the reader drags 5 to its top.
        assertEquals(listOf(1L, 5, 3, 2, 4, 6), mergeOrder(listOf(1L, 2, 3, 4, 5, 6), listOf(5L, 2, 4)))
    }

    @Test
    fun `the whole library reordered is just the new order`() {
        assertEquals(listOf(3L, 1, 2), mergeOrder(listOf(1L, 2, 3), listOf(3L, 1, 2)))
    }

    @Test
    fun `a novel gone from the library meanwhile is left out`() {
        assertEquals(listOf(4L, 2, 1), mergeOrder(listOf(1L, 2, 4), listOf(4L, 3, 1)))
    }
}
