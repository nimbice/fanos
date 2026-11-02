package io.github.nimbice.fanos.core.common

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MemoryLedgerTest {

    @Test
    fun `tells what's held and under way, until each is done`() {
        var pages = 12
        val held = MemoryLedger.hold("page mode") { "2 chapters cut, $pages pages" }
        val report =
            MemoryLedger.during("library update") {
                pages = 30
                MemoryLedger.report()
            }

        assertTrue("- page mode: 2 chapters cut, 30 pages" in report, report)
        assertTrue("- library update (0 s)" in report, report)
        assertFalse("library update" in MemoryLedger.report())

        held.close()
        assertFalse("page mode" in MemoryLedger.report())
    }

    @Test
    fun `asks what can let go to, past one that fails`() {
        var images = 40
        val failing = MemoryLedger.hold("broken", shed = { error("no") }) { "?" }
        val cache = MemoryLedger.hold("images", shed = { images = 0 }) { "$images MB" }
        val keeps = MemoryLedger.hold("reader") { "1 chapter" }

        assertEquals(listOf("broken", "images"), MemoryLedger.shed())
        assertEquals(0, images)

        listOf(failing, cache, keeps).forEach { it.close() }
    }

    @Test
    fun `one that can't tell doesn't spoil the report`() {
        val held = MemoryLedger.hold("odd") { error("gone") }

        assertTrue("- odd: (couldn't tell: java.lang.IllegalStateException: gone)" in MemoryLedger.report())

        held.close()
    }
}
