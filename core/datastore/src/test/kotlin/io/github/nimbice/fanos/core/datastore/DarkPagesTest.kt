package io.github.nimbice.fanos.core.datastore

import io.github.nimbice.fanos.core.model.CustomReaderColors
import io.github.nimbice.fanos.core.model.ReaderSettings
import io.github.nimbice.fanos.core.model.ReaderTheme
import io.github.nimbice.fanos.core.model.darkPages
import org.junit.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DarkPagesTest {

    @Test
    fun `the reader's pages are dark on Dusk, Night and Black, and on dark colours of its own`() {
        assertFalse(ReaderSettings(theme = ReaderTheme.Paper).darkPages())
        assertFalse(ReaderSettings(theme = ReaderTheme.Sepia).darkPages())
        assertTrue(ReaderSettings(theme = ReaderTheme.Dusk).darkPages())
        assertTrue(ReaderSettings(theme = ReaderTheme.Night).darkPages())
        assertTrue(ReaderSettings(theme = ReaderTheme.Black).darkPages())
        assertTrue(ReaderSettings(theme = ReaderTheme.Custom, customColors = CustomReaderColors(background = 0x1E2A33)).darkPages())
        assertFalse(ReaderSettings(theme = ReaderTheme.Custom, customColors = CustomReaderColors(background = 0xE8F0E3)).darkPages())
    }
}
