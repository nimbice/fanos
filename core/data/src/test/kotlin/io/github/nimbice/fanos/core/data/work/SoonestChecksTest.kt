package io.github.nimbice.fanos.core.data.work

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SoonestChecksTest {

    private val minute = 60_000L

    @Test
    fun `the soonest novel, with the ones due within a minute of it`() {
        val checks = mapOf(1L to 100 * minute, 2L to 90 * minute, 3L to 91 * minute, 4L to 92 * minute)

        assertEquals(90 * minute to setOf(2L, 3L), soonestChecks(checks))
    }

    @Test
    fun `none to check`() {
        assertNull(soonestChecks(emptyMap()))
    }
}
