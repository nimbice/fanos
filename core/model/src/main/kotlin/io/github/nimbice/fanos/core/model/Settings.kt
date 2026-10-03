package io.github.nimbice.fanos.core.model

/** The app's theme: the system's, light, dark, or [Reader]: dark while the reader's pages are dark, light while they're light. */
enum class ThemeMode { System, Light, Dark, Reader }

enum class LibraryDisplay { Grid, List }

/** What the library can be narrowed to: novels with unread chapters, with downloaded ones, or completed ones. */
enum class LibraryFilter { Unread, Downloaded, Completed }

/**
 * The order the library shows novels in: the reader's own (the one they drag novels into), or by when they last read
 * them, when chapters last arrived, title, or how many are unread.
 */
enum class LibrarySort { Yours, LastRead, NewChapters, Title, Unread }

/** How often the library is checked for new chapters in the background. */
enum class UpdateInterval(val hours: Int) {
    SixHours(6),
    TwelveHours(12),
    Daily(24),
    Off(0),
}

/** How many hours away from a novel before the reader shows where it left off, the last lines read: [Off] never. */
enum class RecapWait(val hours: Int) {
    H1(1),
    H3(3),
    H6(6),
    H12(12),
    H24(24),
    H48(48),
    H72(72),
    Off(0),
}

/** How often the library is backed up to the backup folder. */
enum class BackupInterval(val hours: Int) {
    Daily(24),
    ThreeDays(72),
    Weekly(168),
    Off(0),
}

data class AppSettings(
    val themeMode: ThemeMode = ThemeMode.System,
    val dynamicColor: Boolean = false,
    val libraryDisplay: LibraryDisplay = LibraryDisplay.Grid,
    /** Show a novel's chapters newest first. */
    val chaptersNewestFirst: Boolean = false,
    /** Sources left out of the search across all sources, by id. New sources are searched until unticked. */
    val searchExcludedSources: Set<String> = emptySet(),
    val libraryUpdateInterval: UpdateInterval = UpdateInterval.SixHours,
    /** Post a notification when a background check finds new chapters. */
    val newChapterNotifications: Boolean = true,
    /** Automatic backups; they only run once a backup folder is chosen. */
    val backupInterval: BackupInterval = BackupInterval.Daily,
    /** Save the new chapters a check finds in library novels for reading offline. */
    val downloadNewChapters: Boolean = false,
    /** Downloads wait for an unmetered connection. */
    val downloadOnlyOnWifi: Boolean = true,
    /** How many chapters after the one being read to keep saved, for library novels; 0 for none. */
    val downloadAhead: Int = 2,
    /** Delete a chapter's download once the reader finishes the chapter. */
    val deleteAfterReading: Boolean = false,
    /** Look for new builds of the app daily and when it opens. */
    val checkForUpdates: Boolean = true,
    /** The sources whose reading lists on their sites are kept in step with the library, by id. */
    val readingListSources: Set<String> = emptySet(),
    /** The library shows only novels that pass every one of these. */
    val libraryFilters: Set<LibraryFilter> = emptySet(),
    val librarySort: LibrarySort = LibrarySort.Yours,
    /** Minutes of reading a day to aim for; 0 for no goal. */
    val dailyGoalMinutes: Int = 0,
    /** How fast auto-scroll goes, 1 to 10. */
    val autoScrollSpeed: Int = 4,
    /** How fast the reader reads, in words a minute: learned as they read, for the time left in a chapter. */
    val readingSpeed: Int = 230,
    /** Words the built-in dictionary hasn't got are looked up online, at dictionaryapi.dev or Wiktionary. */
    val lookUpOnline: Boolean = true,
    /** Holding on a word and sliding chooses more words, on into other paragraphs and pages. */
    val dragToChoose: Boolean = true,
    /** A tap on highlighted text opens its menu, rather than doing what a tap anywhere else does. */
    val tapHighlightsForMenu: Boolean = true,
    /** Coming back to a novel after this long, the reader shows the last lines read. */
    val recapAfter: RecapWait = RecapWait.H72,
    /** Under a chapter's last lines: what comes next, whether it's downloaded, about how long it takes to read. */
    val nextChapterCard: Boolean = true,
)

/** How listening (text-to-speech) sounds. */
data class ListeningSettings(
    /** How fast, 1 being the voice's own pace. */
    val speed: Float = 1f,
    /** The phone's voice to read with, by name; null for its default. Tied to the phone, so backups leave it out. */
    val voice: String? = null,
    /** A short chime between one chapter and the next. */
    val chime: Boolean = true,
    /** Words said the reader's way. */
    val pronunciations: List<Pronunciation> = emptyList(),
)

/** A word (or a few), and what to say for it. */
data class Pronunciation(val word: String, val sayAs: String)

/**
 * Where this phone keeps its automatic backups and how the last one went. Tied to the phone (the
 * folder is a grant from Android), so backups leave it out.
 */
data class BackupStatus(
    /** The backup folder's tree URI, or null before one is chosen. */
    val folder: String? = null,
    val lastBackupAt: Long? = null,
    /** Why the last backup failed; null once one succeeds. */
    val failure: String? = null,
)

/** Background and text colours of the reading page: five set ones, and the reader's own ([ReaderSettings.customColors]). */
enum class ReaderTheme { Paper, Sepia, Dusk, Night, Black, Custom }

/** Whether the reader's pages are dark: Dusk, Night and Black are, and the reader's own colours when their background is. */
fun ReaderSettings.darkPages(): Boolean =
    when (theme) {
        ReaderTheme.Paper, ReaderTheme.Sepia -> false
        ReaderTheme.Dusk, ReaderTheme.Night, ReaderTheme.Black -> true
        ReaderTheme.Custom -> luminance(customColors.background) < 0.5
    }

/** A 0xRRGGBB colour's relative luminance, 0 for black to 1 for white. */
private fun luminance(rgb: Int): Double {
    fun channel(c: Int): Double {
        val v = c / 255.0
        return if (v <= 0.03928) v / 12.92 else Math.pow((v + 0.055) / 1.055, 2.4)
    }
    return 0.2126 * channel(rgb shr 16 and 0xFF) + 0.7152 * channel(rgb shr 8 and 0xFF) + 0.0722 * channel(rgb and 0xFF)
}

/** The reader's own reading colours, for [ReaderTheme.Custom], each as 0xRRGGBB. */
data class CustomReaderColors(
    val background: Int = 0x1E2A33,
    val text: Int = 0xE8E2D0,
    /** Titles and links. */
    val link: Int = 0x7FB3D5,
)

/** Reading fonts, in the order the reader offers them: the phone's own, then ones the app bundles. */
enum class ReaderFont {
    Serif,
    SansSerif,
    Literata,
    Lora,
    CrimsonPro,
    EbGaramond,
    Bitter,
    AtkinsonHyperlegible,
    Lexend,
    Condensed,
    Monospace,
}

enum class ReaderAlignment { Start, Justify }

/**
 * What a tap on either side of the page does in page mode: the left goes back and the right on, the other way about
 * (for the left hand), or either side goes on (one hand either way; a swipe goes back).
 */
enum class TapZones { BackOn, OnBack, EitherOn }

/** How the reader's screen turns: with the device, or kept upright or on its side whichever way the device is held. */
enum class ScreenLock { Free, Portrait, Landscape }

/**
 * How far through the chapter the reader has scrolled, shown at the page's edge in scroll mode: not at all, a line
 * down the right edge or across the top that fills as they read on, or a scrollbar's thumb while they scroll.
 */
enum class ScrollBar { Off, Edge, Top, Thumb }

/** How a page turns in page mode, at a tap, a volume key or auto-scroll's timer: a swipe always slides. */
enum class PageTurn { Slide, Fade, Instant }

data class ReaderSettings(
    val theme: ReaderTheme = ReaderTheme.Night,
    /** The colours [ReaderTheme.Custom] reads with. */
    val customColors: CustomReaderColors = CustomReaderColors(),
    val font: ReaderFont = ReaderFont.Serif,
    /** A font the reader added, by name, read with instead of [font]; null for [font]. */
    val ownFont: String? = null,
    /** How heavy the text is: 300 (light) to 700 (bold), 400 regular. */
    val fontWeight: Int = 400,
    /** Text size in sp. */
    val fontSize: Int = 19,
    val lineHeight: Float = 1.6f,
    /** Space between paragraphs, in multiples of the text size. */
    val paragraphSpacing: Float = 0.9f,
    /** Left and right margins, in dp. */
    val margin: Int = 20,
    val alignment: ReaderAlignment = ReaderAlignment.Start,
    /** Turn pages sideways instead of scrolling. */
    val pageMode: Boolean = false,
    /** In page mode on a wide screen, two pages side by side. */
    val twoPages: Boolean = true,
    val pageTurn: PageTurn = PageTurn.Slide,
    /** Scroll mode: how far through the chapter, at the page's edge. */
    val scrollBar: ScrollBar = ScrollBar.Edge,
    val tapZones: TapZones = TapZones.BackOn,
    val keepScreenOn: Boolean = true,
    val volumeKeysTurnPages: Boolean = false,
    /** The page number, the time and the battery, small at the foot of the page. */
    val clockAndBattery: Boolean = true,
    /** Whether the reader's screen turns with the device. */
    val screenLock: ScreenLock = ScreenLock.Free,
    /** The screen's brightness while reading, 0 to 1, the lowest part dimming past the screen's own lowest; null for the phone's. */
    val brightness: Float? = null,
    /** A tint over the page: -1 (cold, blue) to 1 (warm, amber); 0 for none. */
    val warmth: Float = 0f,
)

/** The parts of the reading settings a novel can keep of its own, as the reader's settings panel groups them. */
enum class ReaderSettingsSection { Colours, Font, TextAndSpacing, PageMode }

/** These settings, with [section] taken from [from]. */
fun ReaderSettings.withSection(section: ReaderSettingsSection, from: ReaderSettings): ReaderSettings =
    when (section) {
        ReaderSettingsSection.Colours -> copy(theme = from.theme, customColors = from.customColors)
        ReaderSettingsSection.Font -> copy(font = from.font, ownFont = from.ownFont, fontWeight = from.fontWeight)
        ReaderSettingsSection.TextAndSpacing ->
            copy(
                fontSize = from.fontSize,
                lineHeight = from.lineHeight,
                paragraphSpacing = from.paragraphSpacing,
                margin = from.margin,
                alignment = from.alignment,
            )
        ReaderSettingsSection.PageMode ->
            copy(pageMode = from.pageMode, twoPages = from.twoPages, pageTurn = from.pageTurn, scrollBar = from.scrollBar, tapZones = from.tapZones)
    }

/**
 * A novel's reading [settings]: its own while it has some ([own]), otherwise the [defaults]. Keeping the
 * screen on and turning pages with the volume keys always follow the defaults: they're about the phone,
 * not the novel.
 */
data class NovelReaderSettings(val settings: ReaderSettings, val own: Boolean, val defaults: ReaderSettings) {
    /** Whether [section] is as the defaults have it. */
    fun isDefault(section: ReaderSettingsSection): Boolean = defaults.withSection(section, settings) == defaults
}
