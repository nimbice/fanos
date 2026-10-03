package io.github.nimbice.fanos.feature.reader

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.nimbice.fanos.core.common.ApplicationScope
import io.github.nimbice.fanos.core.common.Dispatcher
import io.github.nimbice.fanos.core.common.ReaderDispatchers
import io.github.nimbice.fanos.core.common.suspendRunCatching
import io.github.nimbice.fanos.core.content.ChapterContent
import io.github.nimbice.fanos.core.data.download.ChapterDownload
import io.github.nimbice.fanos.core.data.download.DownloadRepository
import io.github.nimbice.fanos.core.data.download.DownloadedImages
import io.github.nimbice.fanos.core.data.repository.ContentRepository
import io.github.nimbice.fanos.core.data.repository.NovelRepository
import io.github.nimbice.fanos.core.data.repository.ReadingRepository
import io.github.nimbice.fanos.core.data.repository.NovelSettingsRepository
import io.github.nimbice.fanos.core.data.repository.SettingsRepository
import io.github.nimbice.fanos.core.model.Chapter
import io.github.nimbice.fanos.core.model.NovelReaderSettings
import io.github.nimbice.fanos.core.model.ReaderSettings
import io.github.nimbice.fanos.core.model.ReaderSettingsSection
import io.github.nimbice.fanos.core.model.ReadingPosition
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import io.github.nimbice.fanos.core.model.ListeningSettings
import io.github.nimbice.fanos.core.model.Pronunciation
import io.github.nimbice.fanos.feature.reader.listen.ListeningState
import io.github.nimbice.fanos.feature.reader.listen.NaturalVoices
import io.github.nimbice.fanos.feature.reader.listen.Narrator
import io.github.nimbice.fanos.feature.reader.listen.StopAfter
import io.github.nimbice.fanos.feature.reader.listen.VoiceOption
import javax.inject.Inject
import io.github.nimbice.fanos.core.data.repository.BookmarkRepository
import io.github.nimbice.fanos.core.model.Bookmark
import android.os.SystemClock
import io.github.nimbice.fanos.core.data.repository.StatsRepository
import kotlinx.coroutines.delay
import io.github.nimbice.fanos.core.content.replaced
import io.github.nimbice.fanos.core.data.repository.ReplacementRepository
import io.github.nimbice.fanos.core.data.repository.rules
import io.github.nimbice.fanos.core.model.Replacement
import kotlinx.coroutines.flow.drop
import io.github.nimbice.fanos.core.data.repository.HighlightRepository
import io.github.nimbice.fanos.core.model.Highlight
import io.github.nimbice.fanos.core.data.repository.Dictionary
import io.github.nimbice.fanos.feature.reader.text.Choosing
import io.github.nimbice.fanos.core.model.RecapWait
import io.github.nimbice.fanos.core.content.text

sealed interface ReaderPage {
    data object Loading : ReaderPage

    data class Failed(val error: Throwable) : ReaderPage

    /**
     * The novel's chapters, ready to read from [openChapter] on. [loaded] holds the chapter being read
     * and those either side of it; one further off loads when it comes up. [documentId] changes only
     * when the reader opens afresh.
     */
    data class Ready(
        val documentId: Int,
        /** The novel's chapters in reading order. */
        val chapterIds: List<Long>,
        val loaded: Map<Long, LoadedChapter>,
        /** Chapters that couldn't be loaded, with why. */
        val failed: Map<Long, Throwable> = emptyMap(),
        val openChapter: Long,
        val openPosition: ReadingPosition,
    ) : ReaderPage
}

/** A chapter to read: its content, and the names of its images saved on the phone. */
data class LoadedChapter(val chapter: Chapter, val content: ChapterContent, val savedImages: Set<String>, val raw: ChapterContent = content)

/** What the view model asks of the reading view. */
internal sealed interface ReaderCommand {
    /** Over to a chapter, as a swipe goes: its page in scroll mode, its first page in page mode. */
    data class GoToChapter(val chapterId: Long) : ReaderCommand
}

/** The novel's chapters for the chapters panel, in reading order, and which are saved on the phone. */
/** The chapters panel's chapters, in the order the novel's page lists them, and the ones saved on the phone. */
data class ChapterList(val chapters: List<Chapter> = emptyList(), val saved: Set<Long> = emptySet())

data class ReaderUiState(
    val novelTitle: String = "",
    /** The chapter being read. */
    val chapter: Chapter? = null,
    val page: ReaderPage = ReaderPage.Loading,
    val hasPrevious: Boolean = false,
    val hasNext: Boolean = false,
)

/**
 * The reader reads on from chapter to chapter without opening anything afresh: the chapters either
 * side of the one being read are loaded ahead, and any further off as the reader comes to them.
 */
@HiltViewModel
class ReaderViewModel @Inject constructor(
    private val savedStateHandle: SavedStateHandle,
    private val reading: ReadingRepository,
    private val contents: ContentRepository,
    private val novels: NovelRepository,
    private val novelSettings: NovelSettingsRepository,
    private val downloads: DownloadRepository,
    private val images: DownloadedImages,
    private val narrator: Narrator,
    private val bookmarkRepository: BookmarkRepository,
    private val highlightRepository: HighlightRepository,
    private val dictionary: Dictionary,
    private val statsRepository: StatsRepository,
    private val replacementRepository: ReplacementRepository,
    val naturalVoices: NaturalVoices,
    private val appSettings: SettingsRepository,
    @ApplicationScope private val applicationScope: CoroutineScope,
    @Dispatcher(ReaderDispatchers.IO) private val io: CoroutineDispatcher,
) : ViewModel() {

    private val route = savedStateHandle.toRoute<ReaderRoute>()

    /** The novel's reading settings: its own, or the defaults, as the settings panel shows them. */
    val readingSettings: StateFlow<NovelReaderSettings> =
        novelSettings.observe(route.novelId)
            .stateIn(viewModelScope, SharingStarted.Eagerly, NovelReaderSettings(ReaderSettings(), own = false, defaults = ReaderSettings()))

    /** The settings the chapter is shown with. */
    val settings: StateFlow<ReaderSettings> =
        readingSettings.map { it.settings }.stateIn(viewModelScope, SharingStarted.Eagerly, ReaderSettings())

    private val _state = MutableStateFlow(ReaderUiState())
    val state: StateFlow<ReaderUiState> = _state.asStateFlow()

    val chapterList: StateFlow<ChapterList> =
        combine(
            novels.observeChapters(route.novelId),
            downloads.observe(route.novelId),
            combine(novels.observeNovel(route.novelId), appSettings.appSettings) { novel, app -> novel?.chaptersNewestFirst ?: app.chaptersNewestFirst }.distinctUntilChanged(),
        ) { chapters, saved, newestFirst ->
            ChapterList(if (newestFirst) chapters.asReversed() else chapters, saved.filterValues { it == ChapterDownload.Saved }.keys)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ChapterList())

    /** The words replaced in the novel's text: its own rules, then those for all novels. */
    val replacements: StateFlow<List<Replacement>> = replacementRepository.observe(route.novelId).stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** How fast auto-scroll goes, 1 to 10. */
    val autoScrollSpeed: StateFlow<Int> = appSettings.appSettings.map { it.autoScrollSpeed }.stateIn(viewModelScope, SharingStarted.Eagerly, 4)

    /** How fast the reader reads, in words a minute. */
    val readingSpeed: StateFlow<Int> = appSettings.appSettings.map { it.readingSpeed }.stateIn(viewModelScope, SharingStarted.Eagerly, 230)

    /** Whether the app takes its colours from the wallpaper: the reader's controls, dark over a dark page, do too. */
    val dynamicColor: StateFlow<Boolean> = appSettings.appSettings.map { it.dynamicColor }.stateIn(viewModelScope, SharingStarted.Eagerly, false)

    /** Whether the card under a chapter's last lines shows what comes next. */
    val nextChapterCard: StateFlow<Boolean> = appSettings.appSettings.map { it.nextChapterCard }.stateIn(viewModelScope, SharingStarted.Eagerly, true)

    /** What choosing text may do, as the reader set it in Settings. */
    internal val choosing: StateFlow<Choosing> =
        appSettings.appSettings.map { Choosing(it.dragToChoose, it.tapHighlightsForMenu) }.stateIn(viewModelScope, SharingStarted.Eagerly, Choosing())

    /** Auto-scroll going: the place moves on without the reader, and says nothing of their speed. */
    var autoScrolling = false

    // Where the reader's place was, and when, for their reading speed.
    private var paceFrom: Triple<Long, ReadingPosition, Long>? = null

    /** The brightness while reading, or the phone's own for null: for every novel, whatever their own settings. */
    fun setBrightness(brightness: Float?) {
        viewModelScope.launch { appSettings.updateReaderSettings { it.copy(brightness = brightness?.coerceIn(0f, 1f)) } }
    }

    /** This device's own reading settings (the screen kept on, the volume keys): for every novel, whichever it follows. */
    fun updateDeviceSettings(transform: (ReaderSettings) -> ReaderSettings) {
        viewModelScope.launch { appSettings.updateReaderSettings(transform) }
    }

    /** The warmth of the page, for every novel. */
    fun setWarmth(warmth: Float) {
        viewModelScope.launch { appSettings.updateReaderSettings { it.copy(warmth = warmth.coerceIn(-1f, 1f)) } }
    }

    fun setAutoScrollSpeed(speed: Int) {
        viewModelScope.launch { appSettings.updateAppSettings { it.copy(autoScrollSpeed = speed.coerceIn(1, 10)) } }
    }

    /** Listening pauses while auto-scroll runs: the page follows one or the other. */
    fun pauseListening() = narrator.pause()

    /** Says a word looked up aloud, in the listening voice. */
    fun sayWord(word: String) = narrator.say(word)

    private val _lookup = MutableStateFlow<Lookup?>(null)

    /** A word being looked up in the dictionary, and what it said; null while none is. */
    internal val lookup: StateFlow<Lookup?> = _lookup.asStateFlow()
    private var looking: Job? = null

    fun lookUp(word: String) {
        looking?.cancel()
        _lookup.value = Lookup.Looking(word)
        looking =
            viewModelScope.launch {
                val found = suspendRunCatching { dictionary.define(word) }
                _lookup.value = found.fold({ if (it == null) Lookup.NotFound(word) else Lookup.Found(word, it) }, { Lookup.Failed(word, lookupFailure(it)) })
            }
    }

    fun closeLookup() {
        looking?.cancel()
        _lookup.value = null
    }

    /** The novel's highlights, in reading order. */
    val highlights: StateFlow<List<Highlight>> = highlightRepository.observe(route.novelId).stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** The novel's bookmarks, the latest first. */
    val bookmarks: StateFlow<List<Bookmark>> = bookmarkRepository.observe(route.novelId).stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** Listening to this novel, while it is. */
    val listening: StateFlow<ListeningState?> =
        narrator.state.map { it?.takeIf { state -> state.novelId == route.novelId } }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val listeningSettings: StateFlow<ListeningSettings> = appSettings.listeningSettings.stateIn(viewModelScope, SharingStarted.Eagerly, ListeningSettings())

    // On screen, and when the reader last did something there: reading time counts while both say they're reading.
    private var visible = false
    private var lastActivity = SystemClock.elapsedRealtime()

    // Where the reader is in the chapter being read, for listening to start there.
    private var place: ReadingPosition? = null

    private val _commands = Channel<ReaderCommand>(Channel.UNLIMITED)
    internal val commands: Flow<ReaderCommand> = _commands.receiveAsFlow()

    private var documents = 0
    private var loading: Job? = null

    // Chapters on their way in.
    private val fetching = mutableMapOf<Long, Job>()

    /** The chapter being read; kept in saved state so the reader reopens on it after process death. */
    private var chapterId: Long
        get() = savedStateHandle[KEY_CHAPTER] ?: route.chapterId
        set(value) {
            savedStateHandle[KEY_CHAPTER] = value
        }

    init {
        viewModelScope.launch {
            novels.observeNovel(route.novelId).collect { novel -> _state.update { it.copy(novelTitle = novel?.title.orEmpty()) } }
        }
        // Chapters a check finds while the reader is open join the end.
        viewModelScope.launch {
            reading.observeChapterIds(route.novelId).collect { ids ->
                updatePage { page -> if (chapterId in ids) page.copy(chapterIds = ids) else page }
            }
        }
        load(chapterId)
        // Replacements changed: the chapters already loaded are shown with them at once.
        viewModelScope.launch {
            replacements.drop(1).collect { list ->
                val rules = list.rules()
                updatePage { page -> page.copy(loaded = page.loaded.mapValues { (_, loaded) -> loaded.copy(content = loaded.raw.replaced(rules)) }) }
            }
        }
        // Time reading, for the stats: while on screen, not idle a long while, and not listening (which counts its own).
        viewModelScope.launch {
            while (true) {
                delay(TICK_MS)
                val listeningHere = narrator.state.value?.let { it.playing && it.novelId == route.novelId } == true
                if (visible && !listeningHere && SystemClock.elapsedRealtime() - lastActivity < IDLE_MS) statsRepository.addTime(route.novelId, (TICK_MS / 1000).toInt())
            }
        }
    }

    /** The reader is on screen, or isn't. */
    fun setVisible(on: Boolean) {
        visible = on
        if (on) lastActivity = SystemClock.elapsedRealtime()
    }

    private val _recap = MutableStateFlow<Recap?>(null)

    /** Where the reader left off, coming back to the novel after a while (as long as Settings ask): until they carry on. */
    internal val recap: StateFlow<Recap?> = _recap.asStateFlow()

    // Whether the novel's been looked at for a recap: on the reader's first opening only.
    private var recapChecked = false

    fun closeRecap() {
        _recap.value = null
    }

    /** Opens the reader afresh on [id], where it was left. */
    private fun load(id: Long) {
        loading?.cancel()
        fetching.values.forEach { it.cancel() }
        fetching.clear()
        chapterId = id
        loading =
            viewModelScope.launch {
                _state.update { it.copy(page = ReaderPage.Loading) }
                // When the novel was last read, before this opening counts as reading it.
                val lastRead = if (recapChecked) null else novels.observeNovel(route.novelId).first()?.lastReadAt
                val chapter = reading.chapter(id)
                if (chapter == null) {
                    _state.update { it.copy(page = ReaderPage.Failed(NoSuchElementException("This chapter is no longer listed."))) }
                    return@launch
                }
                showChapter(chapter)
                suspendRunCatching { contents.content(id) }
                    .onSuccess { content ->
                        val position = reading.position(id)
                        val opened = LoadedChapter(chapter, content.replaced(replacements.value.rules()), withContext(io) { images.names(id) }, raw = content)
                        val ids = reading.observeChapterIds(route.novelId).first().takeIf { id in it } ?: listOf(id)
                        _state.update {
                            it.copy(page = ReaderPage.Ready(++documents, ids, mapOf(id to opened), openChapter = id, openPosition = position))
                        }
                        fill()
                        if (!recapChecked) {
                            recapChecked = true
                            val wait = appSettings.appSettings.first().recapAfter
                            val now = System.currentTimeMillis()
                            if (lastRead != null && wait != RecapWait.Off && now - lastRead >= wait.hours * HOUR_MS) {
                                val paragraphs = opened.content.leaves.map { it.text() }
                                recapQuote(paragraphs, position.block, position.offset)?.let { _recap.value = Recap(lastRead, it, chapter.title) }
                            }
                        }
                        // Opening a chapter makes it where "Continue" resumes.
                        applicationScope.launch { reading.savePosition(id, position) }
                    }.onFailure { error -> _state.update { it.copy(page = ReaderPage.Failed(error)) } }
            }
    }

    /**
     * The bars' chapter: its title, and whether there are chapters either side of it. The chapters after
     * it are saved ahead, as far as the settings ask.
     */
    private suspend fun showChapter(chapter: Chapter) {
        applicationScope.launch { suspendRunCatching { downloads.downloadAhead(route.novelId, chapter.id) } }
        val hasPrevious = reading.previous(chapter.id) != null
        val hasNext = reading.next(chapter.id) != null
        _state.update { it.copy(chapter = chapter, hasPrevious = hasPrevious, hasNext = hasNext) }
    }

    /** The chapter buttons: over to the next or previous chapter, as a swipe goes. */
    fun next() {
        val current = chapterId
        viewModelScope.launch {
            val target = reading.next(current) ?: return@launch
            finish(current)
            go(target.id)
        }
    }

    fun previous() {
        val current = chapterId
        viewModelScope.launch { reading.previous(current)?.let { go(it.id) } }
    }

    private fun go(id: Long) {
        if (_state.value.page is ReaderPage.Ready) _commands.trySend(ReaderCommand.GoToChapter(id)) else load(id)
    }

    fun retry() = load(chapterId)

    /** Opens chapter [id] from the chapters panel, where the reader left it. */
    fun open(id: Long) {
        if (id != chapterId || _state.value.page !is ReaderPage.Ready) load(id)
    }

    fun onEvent(event: ReaderEvent) {
        lastActivity = SystemClock.elapsedRealtime()
        val page = _state.value.page as? ReaderPage.Ready ?: return
        if (page.documentId != event.document) return
        when (event) {
            is ReaderEvent.ChapterChanged -> moveTo(event.chapter)
            is ReaderEvent.Position -> {
                val position = ReadingPosition(event.block, event.offset)
                if (event.chapter == chapterId) place = position
                learnSpeed(page, event.chapter, position)
                applicationScope.launch { reading.savePosition(event.chapter, position) }
            }
            is ReaderEvent.End -> finish(event.chapter)
            else -> Unit
        }
    }

    /**
     * Learns the reader's speed from their place moving on through a chapter as they read: not while it's read aloud or
     * auto-scrolled, and not from a jump (the slider, a chapter's start).
     */
    private fun learnSpeed(page: ReaderPage.Ready, chapter: Long, position: ReadingPosition) {
        val now = SystemClock.elapsedRealtime()
        val from = paceFrom
        paceFrom = Triple(chapter, position, now)
        if (from == null || from.first != chapter || autoScrolling || listening.value?.playing == true) return
        val content = page.loaded[chapter]?.content ?: return
        val words = wordsBetween(content, from.second, position)
        viewModelScope.launch {
            appSettings.updateAppSettings { settings -> readingSpeedAfter(settings.readingSpeed, words, now - from.third)?.let { settings.copy(readingSpeed = it) } ?: settings }
        }
    }

    /** The reader read on (or back) into another chapter. */
    private fun moveTo(id: Long) {
        val from = chapterId
        if (id == from) return
        chapterId = id
        viewModelScope.launch {
            val chapter = reading.chapter(id) ?: return@launch
            // Reading on into the next chapter finishes the one before, as the chapter buttons do.
            if (reading.previous(id)?.id == from) finish(from)
            if (chapterId != id) return@launch
            showChapter(chapter)
            fill()
        }
    }

    /**
     * The reader is done with chapter [id]: it's read, and its download is deleted if the settings say so.
     * The reading view keeps what it has of the chapter meanwhile.
     */
    private fun finish(id: Long) {
        applicationScope.launch {
            reading.markRead(id)
            suspendRunCatching { downloads.finishedReading(id) }
        }
    }

    /** Loads chapter [id] for the reading view, unless it's there or on its way. */
    fun need(id: Long) {
        val page = _state.value.page as? ReaderPage.Ready ?: return
        if (id in page.loaded || fetching[id]?.isActive == true) return
        updatePage { it.copy(failed = it.failed - id) }
        fetching[id] =
            viewModelScope.launch {
                val outcome =
                    suspendRunCatching {
                        val chapter = checkNotNull(reading.chapter(id)) { "No chapter $id" }
                        val raw = contents.content(id)
                        LoadedChapter(chapter, raw.replaced(replacements.value.rules()), withContext(io) { images.names(id) }, raw = raw)
                    }
                fetching.remove(id)
                updatePage { current ->
                    outcome.fold({ current.copy(loaded = current.loaded + (id to it)) }, { current.copy(failed = current.failed + (id to it)) })
                }
            }
    }

    /** Keeps the chapters either side of the one being read loaded, and lets go of those further off. */
    private fun fill() {
        val page = _state.value.page as? ReaderPage.Ready ?: return
        val at = page.chapterIds.indexOf(chapterId)
        if (at < 0) return
        val keep = (at - KEEP_AROUND..at + KEEP_AROUND).mapNotNull { page.chapterIds.getOrNull(it) }.toSet()
        fetching.keys.filter { it !in keep }.forEach { fetching.remove(it)?.cancel() }
        updatePage { it.copy(loaded = it.loaded.filterKeys { id -> id in keep }) }
        listOfNotNull(page.chapterIds.getOrNull(at + 1), page.chapterIds.getOrNull(at - 1)).forEach(::need)
    }

    private fun updatePage(change: (ReaderPage.Ready) -> ReaderPage.Ready) {
        _state.update { state ->
            val page = state.page as? ReaderPage.Ready ?: return@update state
            state.copy(page = change(page))
        }
    }

    /** Reads the chapter being read aloud from where the reader is on it; one already being listened to goes on. */
    fun listen() {
        val current = listening.value
        if (current != null) {
            if (!current.playing) narrator.play()
            return
        }
        narrator.start(route.novelId, chapterId, place)
    }

    fun addReplacement(find: String, replace: String, everywhere: Boolean, wholeWord: Boolean, matchCase: Boolean) {
        viewModelScope.launch { replacementRepository.add(route.novelId, find, replace, everywhere, wholeWord, matchCase) }
    }

    fun removeReplacement(rule: Replacement) {
        viewModelScope.launch { replacementRepository.remove(rule.id) }
    }

    /** Bookmarks paragraph [block] of [chapter], or takes its bookmark off. */
    fun toggleBookmark(chapter: Long, block: Int, text: String) {
        viewModelScope.launch { bookmarkRepository.toggle(route.novelId, chapter, block, text) }
    }

    fun removeBookmark(id: Long) {
        viewModelScope.launch { bookmarkRepository.remove(id) }
    }

    /** Highlights [range] of paragraph [block] of [chapter], whose text is [text], in [color]; a note with it when given. */
    /** Highlights [stretch] in [color], with [note] when one is given. */
    internal fun highlight(stretch: Stretch, color: Int, note: String? = null) {
        viewModelScope.launch {
            highlightRepository.add(route.novelId, stretch.chapterId, stretch.leaf, stretch.start, stretch.endLeaf, stretch.end, stretch.text, color, note)
        }
    }

    fun setHighlightNote(id: Long, note: String) {
        viewModelScope.launch { highlightRepository.setNote(id, note) }
    }

    fun removeHighlight(id: Long) {
        viewModelScope.launch { highlightRepository.remove(id) }
    }

    /** Opens the reader on a highlight: its chapter, at its text. */
    fun openHighlight(highlight: Highlight) {
        viewModelScope.launch {
            reading.savePosition(highlight.chapterId, ReadingPosition(highlight.block, highlight.start))
            load(highlight.chapterId)
        }
    }

    /** Opens the reader on a bookmark: its chapter, at its paragraph. */
    fun openBookmark(bookmark: Bookmark) {
        viewModelScope.launch {
            reading.savePosition(bookmark.chapterId, ReadingPosition(bookmark.block, 0))
            load(bookmark.chapterId)
        }
    }

    /** Back to [position] of chapter [id] (or where it was left, without one): where the reader was before a jump. */
    fun returnTo(id: Long, position: ReadingPosition?) {
        viewModelScope.launch {
            position?.let { reading.savePosition(id, it) }
            load(id)
        }
    }

    /** Reads aloud from [position] of [chapter], as a long press on a paragraph asks. */
    fun listenFrom(chapter: Long, position: ReadingPosition) = narrator.start(route.novelId, chapter, position)

    /** Play or pause; with nothing being listened to, reads from where the reader is. */
    fun toggleListening() = if (listening.value == null) listen() else narrator.toggle()

    fun sentenceBack() = narrator.sentenceBack()

    fun sentenceOn() = narrator.sentenceOn()

    fun chapterBackListening() = narrator.chapterBack()

    fun chapterOnListening() = narrator.chapterOn()

    fun stopAfter(choice: StopAfter) = narrator.setStopAfter(choice)

    fun stopListening() = narrator.stop()

    suspend fun voices(): List<VoiceOption> = narrator.voices()

    fun updateListening(transform: (ListeningSettings) -> ListeningSettings) {
        viewModelScope.launch { appSettings.updateListeningSettings(transform) }
    }

    fun setPronunciations(pronunciations: List<Pronunciation>) = updateListening { it.copy(pronunciations = pronunciations) }

    /** A saved image of a chapter, [name] as [DownloadedImages.names] has it. */
    fun savedImage(chapterId: Long, name: String): File = images.saved(chapterId, name)

    /** Changes the novel's own settings, or the defaults while it follows them. */
    fun updateSettings(transform: (ReaderSettings) -> ReaderSettings) {
        viewModelScope.launch { novelSettings.update(route.novelId, transform) }
    }

    /** Gives the novel settings of its own, or has it follow the defaults again. */
    fun setOwnSettings(own: Boolean) {
        viewModelScope.launch { novelSettings.setOwn(route.novelId, own) }
    }

    /** Makes [section] of the novel's own settings the default. */
    fun setAsDefault(section: ReaderSettingsSection) {
        viewModelScope.launch { novelSettings.setAsDefault(route.novelId, section) }
    }

    private companion object {
        const val KEY_CHAPTER = "chapter"

        /** How many chapters either side of the one being read are kept loaded. */
        const val KEEP_AROUND = 2

        const val TICK_MS = 15_000L

        /** No scrolling, page turns or taps for this long, and the reader is taken to be away. */
        const val IDLE_MS = 5 * 60_000L
    }
}

private const val HOUR_MS = 60L * 60 * 1000
