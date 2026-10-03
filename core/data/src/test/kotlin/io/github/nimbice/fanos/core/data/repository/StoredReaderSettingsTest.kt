package io.github.nimbice.fanos.core.data.repository

import io.github.nimbice.fanos.core.model.NovelReaderSettings
import io.github.nimbice.fanos.core.model.ReaderAlignment
import io.github.nimbice.fanos.core.model.ReaderFont
import io.github.nimbice.fanos.core.model.ReaderSettings
import io.github.nimbice.fanos.core.model.ReaderSettingsSection
import io.github.nimbice.fanos.core.model.ReaderTheme
import io.github.nimbice.fanos.core.model.withSection
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import io.github.nimbice.fanos.core.model.CustomReaderColors
import io.github.nimbice.fanos.core.model.PageTurn
import io.github.nimbice.fanos.core.model.TapZones

class StoredReaderSettingsTest {

    private val own =
        ReaderSettings(
            theme = ReaderTheme.Custom,
            customColors = CustomReaderColors(background = 0xE6EEF5, text = 0x2E3B44, link = 0x2D5F8A),
            font = ReaderFont.Lora,
            ownFont = "Palatino Linotype",
            fontWeight = 300,
            fontSize = 23,
            lineHeight = 1.9f,
            paragraphSpacing = 1.2f,
            margin = 28,
            alignment = ReaderAlignment.Justify,
            pageMode = true,
            twoPages = false,
            pageTurn = PageTurn.Instant,
            tapZones = TapZones.EitherOn,
        )

    @Test
    fun `a novel's settings read back as stored, without the phone-wide ones`() {
        val stored = StoredReaderSettings.of(own.copy(keepScreenOn = false, volumeKeysTurnPages = true)).encode()

        assertEquals(own, StoredReaderSettings.parse(stored)?.toSettings())
    }

    @Test
    fun `a choice this app no longer has, or one missing, comes back as the default`() {
        val settings = StoredReaderSettings.parse("""{"theme":"Neon","font":"Lora","margin":30,"future":1}""")!!.toSettings()

        assertEquals(ReaderSettings().theme, settings.theme)
        assertEquals(ReaderFont.Lora, settings.font)
        assertEquals(30, settings.margin)
        assertEquals(ReaderSettings().fontSize, settings.fontSize)
    }

    @Test
    fun `stored settings that can't be read leave the novel on the defaults`() {
        assertNull(StoredReaderSettings.parse("not json"))
    }

    @Test
    fun `a section is taken whole, and nothing else is`() {
        val defaults = ReaderSettings()
        val spacing = defaults.withSection(ReaderSettingsSection.TextAndSpacing, own)

        assertEquals(own.fontSize, spacing.fontSize)
        assertEquals(own.margin, spacing.margin)
        assertEquals(own.alignment, spacing.alignment)
        assertEquals(defaults.theme, spacing.theme)
        assertEquals(defaults.pageMode, spacing.pageMode)
    }

    @Test
    fun `a novel's section is the default once the defaults have it`() {
        val defaults = ReaderSettings().withSection(ReaderSettingsSection.Colours, own)
        val novel = NovelReaderSettings(own, own = true, defaults = defaults)

        assertTrue(novel.isDefault(ReaderSettingsSection.Colours))
        assertFalse(novel.isDefault(ReaderSettingsSection.Font))
    }
}
