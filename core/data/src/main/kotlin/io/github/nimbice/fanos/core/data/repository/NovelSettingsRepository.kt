package io.github.nimbice.fanos.core.data.repository

import io.github.nimbice.fanos.core.database.dao.NovelDao
import io.github.nimbice.fanos.core.model.NovelReaderSettings
import io.github.nimbice.fanos.core.model.ReaderSettings
import io.github.nimbice.fanos.core.model.ReaderSettingsSection
import io.github.nimbice.fanos.core.model.withSection
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Each novel's reading settings: its own once the reader gives it some, otherwise the defaults. Changes go
 * to whichever the novel uses, so changing the defaults leaves a novel with its own alone; a section of a
 * novel's own can be made the default.
 */
@Singleton
class NovelSettingsRepository @Inject constructor(private val novels: NovelDao, private val settings: SettingsRepository) {
    // A slider sends a change a step at a time; each reads the novel's settings and writes them back.
    private val mutex = Mutex()

    fun observe(novelId: Long): Flow<NovelReaderSettings> =
        combine(settings.readerSettings, novels.observeReaderSettings(novelId)) { defaults, stored ->
            val own = stored?.let(StoredReaderSettings::parse)?.toSettings()
            NovelReaderSettings(own?.phoneWideFrom(defaults) ?: defaults, own = own != null, defaults = defaults)
        }.distinctUntilChanged()

    /**
     * Gives the novel settings of its own, starting as the defaults are now; or drops them, and it follows
     * the defaults again.
     */
    suspend fun setOwn(novelId: Long, own: Boolean) {
        mutex.withLock {
            val stored = if (own) StoredReaderSettings.of(settings.readerSettings.first()).encode() else null
            novels.setReaderSettings(novelId, stored)
        }
    }

    /** Changes the novel's own settings, or the defaults while it has none. */
    suspend fun update(novelId: Long, transform: (ReaderSettings) -> ReaderSettings) {
        mutex.withLock {
            val own = ownSettings(novelId)
            if (own == null) {
                settings.updateReaderSettings(transform)
            } else {
                novels.setReaderSettings(novelId, StoredReaderSettings.of(transform(own)).encode())
            }
        }
    }

    /** Makes [section] of the novel's own settings the default, for every novel that follows the defaults. */
    suspend fun setAsDefault(novelId: Long, section: ReaderSettingsSection) {
        val own = mutex.withLock { ownSettings(novelId) } ?: return
        settings.updateReaderSettings { it.withSection(section, own) }
    }

    private suspend fun ownSettings(novelId: Long): ReaderSettings? = novels.readerSettings(novelId)?.let(StoredReaderSettings::parse)?.toSettings()
}

private fun ReaderSettings.phoneWideFrom(defaults: ReaderSettings) =
    copy(
        keepScreenOn = defaults.keepScreenOn,
        volumeKeysTurnPages = defaults.volumeKeysTurnPages,
        clockAndBattery = defaults.clockAndBattery,
        screenLock = defaults.screenLock,
        brightness = defaults.brightness,
        warmth = defaults.warmth,
    )
