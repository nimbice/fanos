package io.github.nimbice.fanos.core.datastore

import io.github.nimbice.fanos.core.model.AppSettings
import io.github.nimbice.fanos.core.model.BackupInterval
import io.github.nimbice.fanos.core.model.LibraryDisplay
import io.github.nimbice.fanos.core.model.ReaderAlignment
import io.github.nimbice.fanos.core.model.ReaderFont
import io.github.nimbice.fanos.core.model.ReaderSettings
import io.github.nimbice.fanos.core.model.ReaderTheme
import io.github.nimbice.fanos.core.model.ThemeMode
import io.github.nimbice.fanos.core.model.UpdateInterval
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertEquals
import io.github.nimbice.fanos.core.model.CustomReaderColors
import io.github.nimbice.fanos.core.model.PageTurn
import io.github.nimbice.fanos.core.model.TapZones

class SettingsBackupTest {

    // Every setting away from its default, so a setting a backup fails to carry shows.
    private val app =
        AppSettings(
            themeMode = ThemeMode.Dark,
            dynamicColor = true,
            libraryDisplay = LibraryDisplay.List,
            chaptersNewestFirst = true,
            searchExcludedSources = setOf("lnmtl", "ranobes"),
            libraryUpdateInterval = UpdateInterval.Daily,
            newChapterNotifications = false,
            backupInterval = BackupInterval.Weekly,
            downloadNewChapters = true,
            downloadOnlyOnWifi = false,
            downloadAhead = 5,
            deleteAfterReading = true,
            checkForUpdates = false,
            readingSpeed = 310,
        )
    private val reader =
        ReaderSettings(
            theme = ReaderTheme.Sepia,
            customColors = CustomReaderColors(background = 0x101418, text = 0xC4D2DE, link = 0x8FCB9F),
            font = ReaderFont.Lora,
            ownFont = "Palatino Linotype",
            fontWeight = 600,
            fontSize = 23,
            lineHeight = 1.85f,
            paragraphSpacing = 1.3f,
            margin = 28,
            alignment = ReaderAlignment.Justify,
            pageMode = true,
            twoPages = false,
            pageTurn = PageTurn.Fade,
            tapZones = TapZones.OnBack,
            keepScreenOn = false,
            volumeKeysTurnPages = true,
            brightness = 0.4f,
            warmth = 0.3f,
        )

    @Test
    fun `a backup carries every setting, and only this phone's own stay behind`() =
        runTest {
            val phone = MemoryStore()
            val settings = SettingsDataSource(phone)
            settings.updateAppSettings { app }
            settings.updateReaderSettings { reader }
            settings.setBackupFolder("content://tree/primary%3ADocuments")
            settings.recordBackup(123)

            val carried = settings.backupValues()
            val stored = phone.data.first().asMap().keys.map { it.name }.toSet()
            assertEquals(stored - setOf("backup_folder", "last_backup_at"), carried.keys)

            // As a backup file gives them back: numbers as doubles, sets as lists.
            val fromFile =
                carried.mapValues { (_, value) ->
                    when (value) {
                        is Number -> value.toDouble()
                        is Set<*> -> value.toList()
                        else -> value
                    }
                }
            val restored = SettingsDataSource(MemoryStore())
            restored.restoreValues(fromFile)
            assertEquals(app, restored.appSettings.first())
            assertEquals(reader, restored.readerSettings.first())
            assertEquals(null, restored.backupStatus.first().folder)
        }

    @Test
    fun `values that don't fit their setting, and unknown names, are skipped`() =
        runTest {
            val settings = SettingsDataSource(MemoryStore())
            settings.updateReaderSettings { it.copy(fontSize = 20) }
            settings.restoreValues(
                mapOf(
                    "reader_font_size" to "huge",
                    "reader_margin" to 20.5,
                    "dynamic_color" to 1.0,
                    "search_excluded_sources" to listOf("a", 2),
                    "from_a_newer_version" to true,
                    "reader_line_height" to 2,
                ),
            )
            val readerNow = settings.readerSettings.first()
            assertEquals(20, readerNow.fontSize)
            assertEquals(ReaderSettings().margin, readerNow.margin)
            assertEquals(2f, readerNow.lineHeight)
            assertEquals(AppSettings(), settings.appSettings.first())
        }
}
