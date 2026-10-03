package io.github.nimbice.fanos.core.datastore

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import io.github.nimbice.fanos.core.model.AppSettings
import io.github.nimbice.fanos.core.model.BackupStatus
import io.github.nimbice.fanos.core.model.LibraryFilter
import io.github.nimbice.fanos.core.model.ListeningSettings
import io.github.nimbice.fanos.core.model.Pronunciation
import io.github.nimbice.fanos.core.model.ReaderAlignment
import io.github.nimbice.fanos.core.model.ReaderFont
import io.github.nimbice.fanos.core.model.ReaderSettings
import io.github.nimbice.fanos.core.model.ReaderTheme
import io.github.nimbice.fanos.core.model.SearchHistory
import io.github.nimbice.fanos.core.model.ThemeMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import io.github.nimbice.fanos.core.model.CustomReaderColors

private val WHITESPACE = Regex("\\s+")

/** App-wide settings. A setting that was never changed reads as the model's default. */
@Singleton
class SettingsDataSource @Inject constructor(private val store: DataStore<Preferences>) {

    private val preferences: Flow<Preferences> =
        store.data.catch { error -> if (error is IOException) emit(emptyPreferences()) else throw error }

    val appSettings: Flow<AppSettings> = preferences.map { it.appSettings() }

    val readerSettings: Flow<ReaderSettings> = preferences.map { it.readerSettings() }

    val backupStatus: Flow<BackupStatus> =
        preferences.map { BackupStatus(it[Keys.BACKUP_FOLDER], it[Keys.LAST_BACKUP_AT], it[Keys.BACKUP_FAILURE]) }

    suspend fun updateAppSettings(transform: (AppSettings) -> AppSettings) {
        store.edit { prefs -> prefs.write(transform(prefs.appSettings())) }
    }

    suspend fun updateReaderSettings(transform: (ReaderSettings) -> ReaderSettings) {
        store.edit { prefs -> prefs.write(transform(prefs.readerSettings())) }
    }

    /** A new backup folder, or none. What happened with the old folder's backups no longer applies. */
    suspend fun setBackupFolder(folder: String?) {
        store.edit { prefs ->
            if (folder == null) prefs.remove(Keys.BACKUP_FOLDER) else prefs[Keys.BACKUP_FOLDER] = folder
            prefs.remove(Keys.LAST_BACKUP_AT)
            prefs.remove(Keys.BACKUP_FAILURE)
        }
    }

    suspend fun recordBackup(time: Long) {
        store.edit { prefs ->
            prefs[Keys.LAST_BACKUP_AT] = time
            prefs.remove(Keys.BACKUP_FAILURE)
        }
    }

    suspend fun recordBackupFailure(reason: String) {
        store.edit { prefs -> prefs[Keys.BACKUP_FAILURE] = reason }
    }

    /** SHA-256 fingerprints of the keys the reader trusts extensions signed with. This phone's own: not backed up. */
    val trustedSigners: Flow<Set<String>> = preferences.map { it[Keys.TRUSTED_SIGNERS].orEmpty() }

    suspend fun trustSigners(signers: Set<String>) {
        store.edit { prefs -> prefs[Keys.TRUSTED_SIGNERS] = prefs[Keys.TRUSTED_SIGNERS].orEmpty() + signers }
    }

    suspend fun forgetSigner(signer: String) {
        store.edit { prefs -> prefs[Keys.TRUSTED_SIGNERS] = prefs[Keys.TRUSTED_SIGNERS].orEmpty() - signer }
    }

    /** The key the app's updater reads builds with, and how its checks have gone. This phone's own, never backed up. */
    val updates: Flow<UpdatesRecord> =
        preferences.map {
            UpdatesRecord(it[Keys.UPDATES_TOKEN], it[Keys.UPDATES_CHECKED_AT], it[Keys.UPDATES_NOTIFIED_VERSION], it[Keys.UPDATES_NOTIFIED_EXTENSIONS], it[Keys.UPDATES_TEST_BUILDS] ?: false)
        }

    suspend fun setUpdatesToken(token: String?) {
        store.edit { prefs -> if (token == null) prefs.remove(Keys.UPDATES_TOKEN) else prefs[Keys.UPDATES_TOKEN] = token }
    }

    suspend fun setUpdatesTestBuilds(on: Boolean) {
        store.edit { prefs -> prefs[Keys.UPDATES_TEST_BUILDS] = on }
    }

    suspend fun updatesChecked(at: Long) {
        store.edit { prefs -> prefs[Keys.UPDATES_CHECKED_AT] = at }
    }

    suspend fun updateNotified(versionCode: Long) {
        store.edit { prefs -> prefs[Keys.UPDATES_NOTIFIED_VERSION] = versionCode }
    }

    suspend fun extensionUpdatesNotified(which: String) {
        store.edit { prefs -> prefs[Keys.UPDATES_NOTIFIED_EXTENSIONS] = which }
    }

    /**
     * How keeping each source's reading lists in step has gone on this phone, by source id: this phone's own (the sign-in
     * it goes by is), never backed up. Each source's record is under keys named for it.
     */
    val readingListRecords: Flow<Map<String, ReadingListRecord>> =
        preferences.map { prefs ->
            val ids = prefs.asMap().keys.mapNotNull { key -> key.name.takeIf { it.startsWith(RECORD_PREFIX) && ':' in it }?.substringAfter(':') }.toSet()
            ids.associateWith { id ->
                ReadingListRecord(
                    syncedAt = prefs[syncedAtKey(id)],
                    novels = prefs[novelsKey(id)] ?: 0,
                    problem = prefs[problemKey(id)],
                    signInUrl = prefs[signInKey(id)],
                )
            }
        }

    /** The source's lists were in step with the library at [at], with [novels] novels on them. */
    suspend fun recordReadingListSync(sourceId: String, at: Long, novels: Int) {
        store.edit { prefs ->
            prefs[syncedAtKey(sourceId)] = at
            prefs[novelsKey(sourceId)] = novels
            prefs.remove(problemKey(sourceId))
            prefs.remove(signInKey(sourceId))
        }
    }

    /** Keeping the source's lists in step stopped at [problem]; [signInUrl] is where to sign in, when that's what it waits for. */
    suspend fun recordReadingListProblem(sourceId: String, problem: String, signInUrl: String?) {
        store.edit { prefs ->
            prefs[problemKey(sourceId)] = problem
            if (signInUrl == null) prefs.remove(signInKey(sourceId)) else prefs[signInKey(sourceId)] = signInUrl
        }
    }

    /** Forgets how it went, as when the reader stops keeping the source's lists in step. */
    suspend fun clearReadingListRecord(sourceId: String) {
        store.edit { prefs -> listOf(syncedAtKey(sourceId), novelsKey(sourceId), problemKey(sourceId), signInKey(sourceId)).forEach { prefs.remove(it) } }
    }

    private fun syncedAtKey(sourceId: String) = longPreferencesKey("${RECORD_PREFIX}synced_at:$sourceId")

    private fun novelsKey(sourceId: String) = intPreferencesKey("${RECORD_PREFIX}novels:$sourceId")

    private fun problemKey(sourceId: String) = stringPreferencesKey("${RECORD_PREFIX}problem:$sourceId")

    private fun signInKey(sourceId: String) = stringPreferencesKey("${RECORD_PREFIX}sign_in:$sourceId")

    /** The last searches made in [history]'s boxes, newest first. */
    fun searchHistory(history: SearchHistory): Flow<List<String>> = preferences.map { it.searches(history) }

    /** Puts [words] first in [history], once (whatever their capitals), keeping the last [SearchHistory.KEPT]. */
    suspend fun rememberSearch(history: SearchHistory, words: String) {
        val search = words.trim().replace(WHITESPACE, " ")
        if (search.isEmpty()) return
        store.edit { prefs ->
            val kept = prefs.searches(history).filterNot { it.equals(search, ignoreCase = true) }
            prefs[history.key()] = (listOf(search) + kept).take(SearchHistory.KEPT).joinToString("\n")
        }
    }

    /** Takes [words] out of [history]. */
    suspend fun forgetSearch(history: SearchHistory, words: String) {
        store.edit { prefs ->
            val kept = prefs.searches(history) - words
            if (kept.isEmpty()) prefs.remove(history.key()) else prefs[history.key()] = kept.joinToString("\n")
        }
    }

    val listeningSettings: Flow<ListeningSettings> = preferences.map { it.listeningSettings() }.distinctUntilChanged()

    suspend fun updateListeningSettings(transform: (ListeningSettings) -> ListeningSettings) {
        store.edit { prefs -> prefs.write(transform(prefs.listeningSettings())) }
    }

    private fun Preferences.listeningSettings(): ListeningSettings {
        val defaults = ListeningSettings()
        return ListeningSettings(
            speed = this[Keys.LISTEN_SPEED] ?: defaults.speed,
            voice = this[Keys.LISTEN_VOICE],
            chime = this[Keys.LISTEN_CHIME] ?: defaults.chime,
            // A word and what to say for it to an entry, a tab between: neither is ever more than a line.
            pronunciations =
                this[Keys.LISTEN_PRONUNCIATIONS]?.mapNotNull { entry -> entry.split('	', limit = 2).takeIf { it.size == 2 }?.let { Pronunciation(it[0], it[1]) } }
                    ?.sortedBy { it.word.lowercase() } ?: defaults.pronunciations,
        )
    }

    private fun MutablePreferences.write(settings: ListeningSettings) {
        this[Keys.LISTEN_SPEED] = settings.speed
        val voice = settings.voice
        if (voice == null) remove(Keys.LISTEN_VOICE) else this[Keys.LISTEN_VOICE] = voice
        this[Keys.LISTEN_CHIME] = settings.chime
        this[Keys.LISTEN_PRONUNCIATIONS] = settings.pronunciations.map { "${it.word}	${it.sayAs}" }.toSet()
    }

    /** When the Updates tab was last looked at, epoch milliseconds; null until it first is. */
    val newChaptersSeenAt: Flow<Long?> = preferences.map { it[Keys.NEW_CHAPTERS_SEEN_AT] }.distinctUntilChanged()

    suspend fun setNewChaptersSeenAt(time: Long) {
        store.edit { it[Keys.NEW_CHAPTERS_SEEN_AT] = time }
    }

    // One search to a line: searches are single lines, their spaces made plain when kept.
    private fun Preferences.searches(history: SearchHistory): List<String> = this[history.key()]?.split('\n')?.filter { it.isNotEmpty() }.orEmpty()

    private fun SearchHistory.key() =
        when (this) {
            SearchHistory.Novels -> Keys.SEARCH_HISTORY
            SearchHistory.Chapters -> Keys.CHAPTER_SEARCH_HISTORY
        }

    /**
     * The settings a backup carries, by name, as stored: Boolean, Int, Float, String or Set<String>.
     * Settings never changed are not stored, and so not carried; they stay at their defaults.
     */
    suspend fun backupValues(): Map<String, Any> {
        val prefs = preferences.first()
        return Keys.BACKED_UP.keys.mapNotNull { key -> prefs[key]?.let { key.name to it } }.toMap()
    }

    /**
     * Sets what a backup carried. Numbers may come as any kind of number and sets as any collection;
     * a value that doesn't fit its setting, and a name this version doesn't know, is skipped. A
     * setting the backup doesn't mention keeps its value.
     */
    suspend fun restoreValues(values: Map<String, Any?>) {
        store.edit { prefs ->
            for ((key, kind) in Keys.BACKED_UP) {
                val value = kind.from(values[key.name]) ?: continue
                @Suppress("UNCHECKED_CAST")
                prefs[key as Preferences.Key<Any>] = value
            }
        }
    }

    private fun Preferences.appSettings(): AppSettings {
        val defaults = AppSettings()
        return AppSettings(
            themeMode = enumOf(this[Keys.THEME_MODE], defaults.themeMode),
            dynamicColor = this[Keys.DYNAMIC_COLOR] ?: defaults.dynamicColor,
            libraryDisplay = enumOf(this[Keys.LIBRARY_DISPLAY], defaults.libraryDisplay),
            chaptersNewestFirst = this[Keys.CHAPTERS_NEWEST_FIRST] ?: defaults.chaptersNewestFirst,
            searchExcludedSources = this[Keys.SEARCH_EXCLUDED_SOURCES] ?: defaults.searchExcludedSources,
            libraryUpdateInterval = enumOf(this[Keys.LIBRARY_UPDATE_INTERVAL], defaults.libraryUpdateInterval),
            newChapterNotifications = this[Keys.NEW_CHAPTER_NOTIFICATIONS] ?: defaults.newChapterNotifications,
            backupInterval = enumOf(this[Keys.BACKUP_INTERVAL], defaults.backupInterval),
            downloadNewChapters = this[Keys.DOWNLOAD_NEW_CHAPTERS] ?: defaults.downloadNewChapters,
            downloadOnlyOnWifi = this[Keys.DOWNLOAD_ONLY_ON_WIFI] ?: defaults.downloadOnlyOnWifi,
            downloadAhead = this[Keys.DOWNLOAD_AHEAD] ?: defaults.downloadAhead,
            deleteAfterReading = this[Keys.DELETE_AFTER_READING] ?: defaults.deleteAfterReading,
            dailyGoalMinutes = this[Keys.DAILY_GOAL_MINUTES] ?: defaults.dailyGoalMinutes,
            autoScrollSpeed = this[Keys.AUTO_SCROLL_SPEED] ?: defaults.autoScrollSpeed,
            readingSpeed = this[Keys.READING_SPEED] ?: defaults.readingSpeed,
            lookUpOnline = this[Keys.LOOK_UP_ONLINE] ?: defaults.lookUpOnline,
            dragToChoose = this[Keys.DRAG_TO_CHOOSE] ?: defaults.dragToChoose,
            tapHighlightsForMenu = this[Keys.TAP_HIGHLIGHTS_FOR_MENU] ?: defaults.tapHighlightsForMenu,
            recapAfter = enumOf(this[Keys.RECAP_AFTER], defaults.recapAfter),
            nextChapterCard = this[Keys.NEXT_CHAPTER_CARD] ?: defaults.nextChapterCard,
            checkForUpdates = this[Keys.CHECK_FOR_UPDATES] ?: defaults.checkForUpdates,
            readingListSources = this[Keys.READING_LIST_SOURCES] ?: defaults.readingListSources,
            libraryFilters = this[Keys.LIBRARY_FILTERS]?.mapNotNull { name -> LibraryFilter.entries.firstOrNull { it.name == name } }?.toSet() ?: defaults.libraryFilters,
            librarySort = enumOf(this[Keys.LIBRARY_SORT], defaults.librarySort),
        )
    }

    private fun MutablePreferences.write(settings: AppSettings) {
        this[Keys.THEME_MODE] = settings.themeMode.name
        this[Keys.DYNAMIC_COLOR] = settings.dynamicColor
        this[Keys.LIBRARY_DISPLAY] = settings.libraryDisplay.name
        this[Keys.CHAPTERS_NEWEST_FIRST] = settings.chaptersNewestFirst
        this[Keys.SEARCH_EXCLUDED_SOURCES] = settings.searchExcludedSources
        this[Keys.LIBRARY_UPDATE_INTERVAL] = settings.libraryUpdateInterval.name
        this[Keys.NEW_CHAPTER_NOTIFICATIONS] = settings.newChapterNotifications
        this[Keys.BACKUP_INTERVAL] = settings.backupInterval.name
        this[Keys.DOWNLOAD_NEW_CHAPTERS] = settings.downloadNewChapters
        this[Keys.DOWNLOAD_ONLY_ON_WIFI] = settings.downloadOnlyOnWifi
        this[Keys.DOWNLOAD_AHEAD] = settings.downloadAhead
        this[Keys.DELETE_AFTER_READING] = settings.deleteAfterReading
        this[Keys.DAILY_GOAL_MINUTES] = settings.dailyGoalMinutes
        this[Keys.AUTO_SCROLL_SPEED] = settings.autoScrollSpeed
        this[Keys.READING_SPEED] = settings.readingSpeed
        this[Keys.LOOK_UP_ONLINE] = settings.lookUpOnline
        this[Keys.DRAG_TO_CHOOSE] = settings.dragToChoose
        this[Keys.TAP_HIGHLIGHTS_FOR_MENU] = settings.tapHighlightsForMenu
        this[Keys.RECAP_AFTER] = settings.recapAfter.name
        this[Keys.NEXT_CHAPTER_CARD] = settings.nextChapterCard
        this[Keys.CHECK_FOR_UPDATES] = settings.checkForUpdates
        this[Keys.READING_LIST_SOURCES] = settings.readingListSources
        this[Keys.LIBRARY_FILTERS] = settings.libraryFilters.map { it.name }.toSet()
        this[Keys.LIBRARY_SORT] = settings.librarySort.name
    }

    private fun Preferences.readerSettings(): ReaderSettings {
        val defaults = ReaderSettings()
        return ReaderSettings(
            theme = enumOf(this[Keys.READER_THEME], defaults.theme),
            customColors =
                CustomReaderColors(
                    background = this[Keys.CUSTOM_BACKGROUND]?.and(RGB) ?: defaults.customColors.background,
                    text = this[Keys.CUSTOM_TEXT]?.and(RGB) ?: defaults.customColors.text,
                    link = this[Keys.CUSTOM_LINK]?.and(RGB) ?: defaults.customColors.link,
                ),
            font = enumOf(this[Keys.READER_FONT], defaults.font),
            ownFont = this[Keys.OWN_FONT],
            fontWeight = (this[Keys.FONT_WEIGHT] ?: defaults.fontWeight).coerceIn(300, 700),
            fontSize = this[Keys.FONT_SIZE] ?: defaults.fontSize,
            lineHeight = this[Keys.LINE_HEIGHT] ?: defaults.lineHeight,
            paragraphSpacing = this[Keys.PARAGRAPH_SPACING] ?: defaults.paragraphSpacing,
            margin = this[Keys.MARGIN] ?: defaults.margin,
            alignment = enumOf(this[Keys.ALIGNMENT], defaults.alignment),
            pageMode = this[Keys.PAGE_MODE] ?: defaults.pageMode,
            twoPages = this[Keys.TWO_PAGES] ?: defaults.twoPages,
            pageTurn = enumOf(this[Keys.PAGE_TURN], defaults.pageTurn),
            scrollBar = enumOf(this[Keys.SCROLL_BAR], defaults.scrollBar),
            tapZones = enumOf(this[Keys.TAP_ZONES], defaults.tapZones),
            keepScreenOn = this[Keys.KEEP_SCREEN_ON] ?: defaults.keepScreenOn,
            volumeKeysTurnPages = this[Keys.VOLUME_KEYS] ?: defaults.volumeKeysTurnPages,
            clockAndBattery = this[Keys.CLOCK_AND_BATTERY] ?: defaults.clockAndBattery,
            screenLock = enumOf(this[Keys.SCREEN_LOCK], defaults.screenLock),
            brightness = this[Keys.BRIGHTNESS]?.coerceIn(0f, 1f),
            warmth = (this[Keys.WARMTH] ?: defaults.warmth).coerceIn(-1f, 1f),
        )
    }

    private fun MutablePreferences.write(settings: ReaderSettings) {
        this[Keys.READER_THEME] = settings.theme.name
        this[Keys.CUSTOM_BACKGROUND] = settings.customColors.background
        this[Keys.CUSTOM_TEXT] = settings.customColors.text
        this[Keys.CUSTOM_LINK] = settings.customColors.link
        this[Keys.READER_FONT] = settings.font.name
        val ownFont = settings.ownFont
        if (ownFont == null) remove(Keys.OWN_FONT) else this[Keys.OWN_FONT] = ownFont
        this[Keys.FONT_WEIGHT] = settings.fontWeight
        this[Keys.FONT_SIZE] = settings.fontSize
        this[Keys.LINE_HEIGHT] = settings.lineHeight
        this[Keys.PARAGRAPH_SPACING] = settings.paragraphSpacing
        this[Keys.MARGIN] = settings.margin
        this[Keys.ALIGNMENT] = settings.alignment.name
        this[Keys.PAGE_MODE] = settings.pageMode
        this[Keys.TWO_PAGES] = settings.twoPages
        this[Keys.PAGE_TURN] = settings.pageTurn.name
        this[Keys.SCROLL_BAR] = settings.scrollBar.name
        this[Keys.TAP_ZONES] = settings.tapZones.name
        this[Keys.KEEP_SCREEN_ON] = settings.keepScreenOn
        this[Keys.VOLUME_KEYS] = settings.volumeKeysTurnPages
        this[Keys.CLOCK_AND_BATTERY] = settings.clockAndBattery
        this[Keys.SCREEN_LOCK] = settings.screenLock.name
        val brightness = settings.brightness
        if (brightness == null) remove(Keys.BRIGHTNESS) else this[Keys.BRIGHTNESS] = brightness
        this[Keys.WARMTH] = settings.warmth
    }

    /** A colour's red, green and blue: what's kept of a custom reading colour. */
    private val RGB = 0xFFFFFF

    private inline fun <reified E : Enum<E>> enumOf(name: String?, default: E): E =
        enumValues<E>().firstOrNull { it.name == name } ?: default

    /** What a setting is stored as, and how to take a value from a backup as one. */
    private enum class Kind(val from: (Any?) -> Any?) {
        Flag({ it as? Boolean }),
        Whole({ value -> (value as? Number)?.takeIf { it.toDouble() % 1.0 == 0.0 }?.toInt() }),
        Decimal({ (it as? Number)?.toFloat() }),
        Text({ it as? String }),
        TextSet({ value -> (value as? Collection<*>)?.takeIf { items -> items.all { it is String } }?.map { it as String }?.toSet() }),
    }

    /**
     * A key keeps its type for good: a setting that needs another type gets a new key, since backups
     * and older installs still hold the old one. New keys go in [BACKED_UP] unless they only make
     * sense on this phone. Retired, and not to be used again: "reader_native_text" (a flag).
     */
    private object Keys {
        val THEME_MODE = stringPreferencesKey("theme_mode")
        val DYNAMIC_COLOR = booleanPreferencesKey("dynamic_color")
        val LIBRARY_DISPLAY = stringPreferencesKey("library_display")
        val CHAPTERS_NEWEST_FIRST = booleanPreferencesKey("chapters_newest_first")
        val SEARCH_EXCLUDED_SOURCES = stringSetPreferencesKey("search_excluded_sources")
        val LIBRARY_UPDATE_INTERVAL = stringPreferencesKey("library_update_interval")
        val NEW_CHAPTER_NOTIFICATIONS = booleanPreferencesKey("new_chapter_notifications")
        val READER_THEME = stringPreferencesKey("reader_theme")
        val READER_FONT = stringPreferencesKey("reader_font")
        val FONT_SIZE = intPreferencesKey("reader_font_size")
        val LINE_HEIGHT = floatPreferencesKey("reader_line_height")
        val PARAGRAPH_SPACING = floatPreferencesKey("reader_paragraph_spacing")
        val MARGIN = intPreferencesKey("reader_margin")
        val ALIGNMENT = stringPreferencesKey("reader_alignment")
        val PAGE_MODE = booleanPreferencesKey("reader_page_mode")
        val KEEP_SCREEN_ON = booleanPreferencesKey("reader_keep_screen_on")
        val VOLUME_KEYS = booleanPreferencesKey("reader_volume_keys")
        val CLOCK_AND_BATTERY = booleanPreferencesKey("reader_clock_and_battery")
        val SCREEN_LOCK = stringPreferencesKey("reader_screen_lock")
        val CUSTOM_BACKGROUND = intPreferencesKey("reader_custom_background")
        val CUSTOM_TEXT = intPreferencesKey("reader_custom_text")
        val CUSTOM_LINK = intPreferencesKey("reader_custom_link")
        val OWN_FONT = stringPreferencesKey("reader_own_font")
        val FONT_WEIGHT = intPreferencesKey("reader_font_weight")
        val TWO_PAGES = booleanPreferencesKey("reader_two_pages")
        val PAGE_TURN = stringPreferencesKey("reader_page_turn")
        val SCROLL_BAR = stringPreferencesKey("reader_scroll_bar")
        val TAP_ZONES = stringPreferencesKey("reader_tap_zones")
        val BRIGHTNESS = floatPreferencesKey("reader_brightness")
        val WARMTH = floatPreferencesKey("reader_warmth")
        val BACKUP_INTERVAL = stringPreferencesKey("backup_interval")
        val DOWNLOAD_NEW_CHAPTERS = booleanPreferencesKey("download_new_chapters")
        val DOWNLOAD_ONLY_ON_WIFI = booleanPreferencesKey("download_only_on_wifi")
        val DOWNLOAD_AHEAD = intPreferencesKey("download_ahead")
        val DELETE_AFTER_READING = booleanPreferencesKey("delete_after_reading")
        val DAILY_GOAL_MINUTES = intPreferencesKey("daily_goal_minutes")
        val AUTO_SCROLL_SPEED = intPreferencesKey("auto_scroll_speed")
        val READING_SPEED = intPreferencesKey("reading_speed")
        val LOOK_UP_ONLINE = booleanPreferencesKey("look_up_online")
        val DRAG_TO_CHOOSE = booleanPreferencesKey("drag_to_choose")
        val TAP_HIGHLIGHTS_FOR_MENU = booleanPreferencesKey("tap_highlights_for_menu")
        val RECAP_AFTER = stringPreferencesKey("recap_after")
        val NEXT_CHAPTER_CARD = booleanPreferencesKey("next_chapter_card")
        val CHECK_FOR_UPDATES = booleanPreferencesKey("check_for_updates")
        val READING_LIST_SOURCES = stringSetPreferencesKey("reading_list_sources")
        val LIBRARY_FILTERS = stringSetPreferencesKey("library_filters")
        val LIBRARY_SORT = stringPreferencesKey("library_sort")

        // This phone's own: the folder is a grant from Android, and the rest is about that folder.
        val BACKUP_FOLDER = stringPreferencesKey("backup_folder")
        val LAST_BACKUP_AT = longPreferencesKey("last_backup_at")
        val BACKUP_FAILURE = stringPreferencesKey("backup_failure")

        // Also this phone's own: trusting a key is a decision about what may run on it.
        val TRUSTED_SIGNERS = stringSetPreferencesKey("trusted_extension_signers")

        // And the updater's key, a secret that opens the builds, with how its checks went.
        val UPDATES_TOKEN = stringPreferencesKey("updates_token")
        val UPDATES_CHECKED_AT = longPreferencesKey("updates_checked_at")
        val UPDATES_NOTIFIED_VERSION = longPreferencesKey("updates_notified_version")
        val UPDATES_NOTIFIED_EXTENSIONS = stringPreferencesKey("updates_notified_extensions")
        val UPDATES_TEST_BUILDS = booleanPreferencesKey("updates_test_builds")

        // What was searched for lately stays on this phone as well.
        val SEARCH_HISTORY = stringPreferencesKey("search_history")
        val CHAPTER_SEARCH_HISTORY = stringPreferencesKey("chapter_search_history")

        // So does when the Updates tab was last looked at.
        val NEW_CHAPTERS_SEEN_AT = longPreferencesKey("new_chapters_seen_at")

        val LISTEN_SPEED = floatPreferencesKey("listen_speed")
        val LISTEN_CHIME = booleanPreferencesKey("listen_chime")
        val LISTEN_PRONUNCIATIONS = stringSetPreferencesKey("listen_pronunciations")

        // The voice is one of this phone's.
        val LISTEN_VOICE = stringPreferencesKey("listen_voice")

        val BACKED_UP: Map<Preferences.Key<*>, Kind> =
            mapOf(
                THEME_MODE to Kind.Text,
                DYNAMIC_COLOR to Kind.Flag,
                LIBRARY_DISPLAY to Kind.Text,
                CHAPTERS_NEWEST_FIRST to Kind.Flag,
                SEARCH_EXCLUDED_SOURCES to Kind.TextSet,
                LIBRARY_UPDATE_INTERVAL to Kind.Text,
                NEW_CHAPTER_NOTIFICATIONS to Kind.Flag,
                BACKUP_INTERVAL to Kind.Text,
                DOWNLOAD_NEW_CHAPTERS to Kind.Flag,
                DOWNLOAD_ONLY_ON_WIFI to Kind.Flag,
                DOWNLOAD_AHEAD to Kind.Whole,
                DELETE_AFTER_READING to Kind.Flag,
                DAILY_GOAL_MINUTES to Kind.Whole,
                AUTO_SCROLL_SPEED to Kind.Whole,
                READING_SPEED to Kind.Whole,
                LOOK_UP_ONLINE to Kind.Flag,
                DRAG_TO_CHOOSE to Kind.Flag,
                TAP_HIGHLIGHTS_FOR_MENU to Kind.Flag,
                RECAP_AFTER to Kind.Text,
                NEXT_CHAPTER_CARD to Kind.Flag,
                CHECK_FOR_UPDATES to Kind.Flag,
                READING_LIST_SOURCES to Kind.TextSet,
                LIBRARY_FILTERS to Kind.TextSet,
                LIBRARY_SORT to Kind.Text,
                READER_THEME to Kind.Text,
                READER_FONT to Kind.Text,
                FONT_SIZE to Kind.Whole,
                LINE_HEIGHT to Kind.Decimal,
                PARAGRAPH_SPACING to Kind.Decimal,
                MARGIN to Kind.Whole,
                ALIGNMENT to Kind.Text,
                PAGE_MODE to Kind.Flag,
                KEEP_SCREEN_ON to Kind.Flag,
                VOLUME_KEYS to Kind.Flag,
                CLOCK_AND_BATTERY to Kind.Flag,
                SCREEN_LOCK to Kind.Text,
                CUSTOM_BACKGROUND to Kind.Whole,
                CUSTOM_TEXT to Kind.Whole,
                CUSTOM_LINK to Kind.Whole,
                // The font file stays on the phone; elsewhere the name finds none, and the font chosen before it reads.
                OWN_FONT to Kind.Text,
                FONT_WEIGHT to Kind.Whole,
                TWO_PAGES to Kind.Flag,
                PAGE_TURN to Kind.Text,
                SCROLL_BAR to Kind.Text,
                TAP_ZONES to Kind.Text,
                BRIGHTNESS to Kind.Decimal,
                WARMTH to Kind.Decimal,
                LISTEN_SPEED to Kind.Decimal,
                LISTEN_CHIME to Kind.Flag,
                LISTEN_PRONUNCIATIONS to Kind.TextSet,
            )
    }
}

// A reading-list record's keys: "reading_list_<what>:<source id>".
private const val RECORD_PREFIX = "reading_list_"

/**
 * How keeping a source's reading lists in step has gone: when they were last in step and with how many novels, and
 * what stopped it since, if anything; [signInUrl] when it waits for the reader to sign in there.
 */
data class ReadingListRecord(val syncedAt: Long?, val novels: Int, val problem: String?, val signInUrl: String?)

/**
 * The updater's key (null until the reader sets one), when it last checked, the build it last announced,
 * the extension updates it last announced, and whether app updates follow the private test builds (with a
 * key) rather than the public releases.
 */
data class UpdatesRecord(val token: String?, val checkedAt: Long?, val notifiedVersion: Long?, val notifiedExtensions: String? = null, val testBuilds: Boolean = false)
