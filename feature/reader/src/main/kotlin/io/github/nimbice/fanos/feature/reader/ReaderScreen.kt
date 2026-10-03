package io.github.nimbice.fanos.feature.reader

import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.nimbice.fanos.core.data.browserLabel
import io.github.nimbice.fanos.core.data.browserUrl
import io.github.nimbice.fanos.core.data.errorDetail
import io.github.nimbice.fanos.core.data.userMessage
import io.github.nimbice.fanos.core.designsystem.component.ErrorState
import io.github.nimbice.fanos.core.designsystem.icon.ReaderIcons
import io.github.nimbice.fanos.feature.reader.text.ReadingView
import kotlin.math.roundToInt
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.navigationBarsIgnoringVisibility
import androidx.compose.foundation.layout.offset
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.IntOffset
import io.github.nimbice.fanos.core.model.ReadingPosition
import io.github.nimbice.fanos.feature.reader.listen.ListeningActions
import io.github.nimbice.fanos.feature.reader.listen.ListeningPanel
import io.github.nimbice.fanos.feature.reader.listen.MiniPlayer
import io.github.nimbice.fanos.feature.reader.listen.NaturalVoicesUi
import io.github.nimbice.fanos.feature.reader.text.TextHold
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import io.github.nimbice.fanos.feature.reader.text.Follow
import io.github.nimbice.fanos.feature.reader.text.Mark
import io.github.nimbice.fanos.feature.reader.text.MarkKind
import io.github.nimbice.fanos.feature.reader.text.Marks
import io.github.nimbice.fanos.feature.reader.text.findIn
import io.github.nimbice.fanos.feature.reader.text.firstFrom
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.PlayArrow
import io.github.nimbice.fanos.feature.reader.text.AutoScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import io.github.nimbice.fanos.feature.reader.listen.Sentences
import android.view.WindowManager
import android.content.Intent
import androidx.compose.ui.platform.LocalContext
import io.github.nimbice.fanos.core.model.isBookUrl
import android.app.SearchManager
import android.content.Context
import android.widget.Toast
import android.os.SystemClock
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.width
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.runtime.produceState
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.material.icons.filled.Check
import kotlinx.coroutines.delay
import io.github.nimbice.fanos.core.content.leaves
import io.github.nimbice.fanos.core.content.text
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.statusBarsIgnoringVisibility
import androidx.compose.material.icons.filled.Share
import androidx.compose.ui.window.PopupProperties
import androidx.compose.ui.geometry.Rect
import io.github.nimbice.fanos.feature.reader.text.ShownTexts
import io.github.nimbice.fanos.feature.reader.text.ChoiceHandles
import io.github.nimbice.fanos.feature.reader.text.ChoiceEnd
import io.github.nimbice.fanos.core.designsystem.theme.fanosColors
import androidx.compose.ui.graphics.luminance
import androidx.compose.runtime.rememberUpdatedState
import io.github.nimbice.fanos.feature.reader.text.NextChapter
import io.github.nimbice.fanos.core.content.wordCount
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.changedToDown
import androidx.compose.ui.geometry.Offset
import io.github.nimbice.fanos.core.model.ScreenLock
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.material3.IconButtonDefaults
import io.github.nimbice.fanos.core.model.ScrollBar
import io.github.nimbice.fanos.core.designsystem.component.fittingStyle
import androidx.compose.foundation.layout.BoxWithConstraints
import io.github.nimbice.fanos.core.designsystem.component.FittingText

/**
 * The reader. Over a dark page (Dusk, Night, Black, or the reader's own dark colours) its controls, panels, menus and
 * cards go dark too, whatever the app's own theme: no glare round the page at night. Over a light page they're the app's.
 */
@Composable
fun ReaderScreen(
    onBack: () -> Unit,
    onOpenInBrowser: (String) -> Unit,
    viewModel: ReaderViewModel = hiltViewModel(),
) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val dynamicColor by viewModel.dynamicColor.collectAsStateWithLifecycle()
    val pageDark = settings.colors.background.luminance() < 0.5f
    val scheme = if (pageDark) fanosColors(dark = true, dynamicColor = dynamicColor) else MaterialTheme.colorScheme
    DarkSystemBars(pageDark)
    MaterialTheme(colorScheme = scheme, typography = MaterialTheme.typography, shapes = MaterialTheme.shapes) {
        ReaderContent(onBack, onOpenInBrowser, viewModel)
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun ReaderContent(
    onBack: () -> Unit,
    onOpenInBrowser: (String) -> Unit,
    viewModel: ReaderViewModel,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    var controlsVisible by rememberSaveable { mutableStateOf(false) }
    var settingsOpen by rememberSaveable { mutableStateOf(false) }
    var chaptersOpen by rememberSaveable { mutableStateOf(false) }
    var listeningOpen by rememberSaveable { mutableStateOf(false) }
    var replacementsOpen by rememberSaveable { mutableStateOf(false) }
    // Auto-scroll: on, and paused by a tap.
    var autoScrolling by rememberSaveable { mutableStateOf(false) }
    var autoPaused by rememberSaveable { mutableStateOf(false) }
    val autoScrollSpeed by viewModel.autoScrollSpeed.collectAsStateWithLifecycle()
    val readingSpeed by viewModel.readingSpeed.collectAsStateWithLifecycle()
    val replacements by viewModel.replacements.collectAsStateWithLifecycle()
    val listening by viewModel.listening.collectAsStateWithLifecycle()
    val listeningSettings by viewModel.listeningSettings.collectAsStateWithLifecycle()
    val bookmarks by viewModel.bookmarks.collectAsStateWithLifecycle()
    val highlights by viewModel.highlights.collectAsStateWithLifecycle()
    val lookup by viewModel.lookup.collectAsStateWithLifecycle()
    val recap by viewModel.recap.collectAsStateWithLifecycle()
    // A note being written, from the long-press menu.
    var noting by remember { mutableStateOf<NoteEdit?>(null) }
    // Find in chapter: the words, and which of the matches the page is at.
    var finding by rememberSaveable { mutableStateOf(false) }
    var findWords by rememberSaveable { mutableStateOf("") }
    var findAt by rememberSaveable { mutableIntStateOf(0) }
    // Where the reader is in the chapter being read, for a search to start from.
    var readingPlace by remember { mutableStateOf<ReadingPosition?>(null) }
    // A long press on the text: the menu it opens.
    var hold by remember { mutableStateOf<TextHold?>(null) }
    // A picture tapped: full screen over everything.
    var picture by remember { mutableStateOf<Picture?>(null) }
    // Words to share as a picture, from the long-press menu.
    var quoting by remember { mutableStateOf<QuoteRequest?>(null) }
    // A footnote's mark tapped: its note, by the line; and where the screen was last touched, for the card to keep clear of.
    var note by remember { mutableStateOf<OpenNote?>(null) }
    val touched = remember { mutableStateOf(Offset.Zero) }
    // A handle being dragged: the menu steps aside meanwhile.
    var draggingHandle by remember { mutableStateOf(false) }
    // The rows of text on the screen, where the handles find their places.
    val shownTexts = remember { ShownTexts() }
    val choosing by viewModel.choosing.collectAsStateWithLifecycle()
    val nextCard by viewModel.nextChapterCard.collectAsStateWithLifecycle()

    // The word looked up, and where: the card keeps clear of it, and it stays marked while the card is open.
    var lookupAt by remember { mutableStateOf<TextHold?>(null) }
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    val chapterList by viewModel.chapterList.collectAsStateWithLifecycle()
    // Where the reader is in the chapter being read, for the slider: a page of so many in page mode,
    // a fraction of so many screens in scroll mode. The page holds neighbouring chapters too, so
    // only reports about the chapter being read count.
    var pageInfo by remember { mutableStateOf<PageInfo?>(null) }
    var progress by remember { mutableStateOf<ScrollProgress?>(null) }
    var readingChapter by remember { mutableStateOf<Long?>(null) }
    val controller = remember { ReaderController() }
    // After a jump: the way back to where the reader was.
    val jumpBack = remember { JumpBack() }
    var controlsHeight by remember { mutableIntStateOf(0) }
    val colors = settings.colors
    val page = state.page
    val document = (page as? ReaderPage.Ready)?.documentId
    SystemBars(visible = controlsVisible || finding || document == null)
    KeepScreenOn(settings.keepScreenOn || (autoScrolling && !autoPaused))
    ScreenLockEffect(settings.screenLock)
    LaunchedEffect(autoScrolling) { viewModel.autoScrolling = autoScrolling }
    // When auto-scroll stops by itself: never, at the chapter's end, or at a time.
    var autoStop by rememberSaveable { mutableStateOf(AutoStop.Never) }
    var autoStopAt by rememberSaveable { mutableStateOf<Long?>(null) }
    fun stopAutoScroll() {
        autoScrolling = false
        autoStop = AutoStop.Never
        autoStopAt = null
    }
    LaunchedEffect(autoStopAt, autoScrolling) {
        val at = autoStopAt ?: return@LaunchedEffect
        if (!autoScrolling) return@LaunchedEffect
        delay((at - System.currentTimeMillis()).coerceAtLeast(0))
        stopAutoScroll()
    }
    ReaderBrightness(settings.brightness)
    LaunchedEffect(document, settings.pageMode) {
        pageInfo = null
        progress = null
    }
    BackHandler(enabled = settingsOpen) { settingsOpen = false }
    BackHandler(enabled = chaptersOpen) { chaptersOpen = false }
    BackHandler(enabled = listeningOpen) { listeningOpen = false }
    BackHandler(enabled = replacementsOpen) { replacementsOpen = false }
    BackHandler(enabled = lookup != null) { viewModel.closeLookup() }
    BackHandler(enabled = hold?.done == true) { hold = null }
    BackHandler(enabled = autoScrolling) { autoScrolling = false }
    BackHandler(enabled = finding) { finding = false }
    // On screen, for counting reading time.
    LifecycleResumeEffect(viewModel) {
        viewModel.setVisible(true)
        onPauseOrDispose { viewModel.setVisible(false) }
    }
    // The search's matches in the chapter being read; a new search starts at the first from where the reader is.
    val findChapter = readingChapter ?: state.chapter?.id
    val findIn = (page as? ReaderPage.Ready)?.loaded?.get(findChapter)
    val matches = remember(finding, findWords, findIn) { if (!finding || findIn == null) emptyList() else findIn(findIn.content, findIn.chapter.title, findWords) }
    LaunchedEffect(matches) { findAt = firstFrom(matches, readingPlace) }
    // Marked on the page: the sentence being read aloud, and the search's matches; the page follows the match the search is
    // at, else the word being said while listening plays.
    // Highlights where their text is in the chapters loaded.
    val shownHighlights =
        remember(highlights, page) {
            val ready = page as? ReaderPage.Ready
            if (ready == null) emptyList() else highlights.mapNotNull { h -> ready.loaded[h.chapterId]?.let { placeOf(h, it.content) } }
        }
    val marks =
        remember(listening, matches, findAt, findChapter, bookmarks, shownHighlights, page, hold, lookupAt, lookup != null) {
            val heard = listening
            val sentence = heard?.sentence
            val list = ArrayList<Mark>()
            // Highlights first: what's read aloud and what's found show over them.
            shownHighlights.forEach { shown ->
                val h = shown.highlight
                for (leaf in h.block..h.endBlock) {
                    list += Mark(h.chapterId, leaf, if (leaf == h.block) shown.start else 0, if (leaf == h.endBlock) shown.end else Int.MAX_VALUE, MarkKind.Highlight, h.color)
                }
            }
            if (heard != null && sentence != null) {
                list += Mark(heard.chapterId, sentence.leaf, sentence.start, sentence.end, MarkKind.Spoken)
                // The word being said, stronger in it, where the voice tells which (the device's voices do; natural ones don't).
                val chapter = (page as? ReaderPage.Ready)?.loaded?.get(heard.chapterId)
                val leafText = if (sentence.leaf < 0) chapter?.chapter?.title else chapter?.content?.leaves?.getOrNull(sentence.leaf)?.text()
                val word = heard.spokenAt?.let { at -> leafText?.let { Sentences.wordAt(it, at) } }
                if (word != null && word.first >= sentence.start && word.last < sentence.end) list += Mark(heard.chapterId, sentence.leaf, word.first, word.last + 1, MarkKind.SpokenWord)
            }
            if (findChapter != null) matches.forEachIndexed { i, m -> list += Mark(findChapter, m.leaf, m.start, m.end, if (i == findAt) MarkKind.Current else MarkKind.Found) }
            // The words pressed, while their menu or the dictionary's card is open: the words they're for.
            val pressed = hold ?: lookupAt?.takeIf { lookup != null }
            if (pressed != null && pressed.leaf >= 0) {
                pressed.chosen(leafTexts(page, pressed.chapterId))?.let { s ->
                    for (leaf in s.leaf..s.endLeaf) {
                        list += Mark(s.chapterId, leaf, if (leaf == s.leaf) s.start else 0, if (leaf == s.endLeaf) s.end else Int.MAX_VALUE, MarkKind.Pressed)
                    }
                }
            }
            val found = matches.getOrNull(findAt)
            val follow =
                when {
                    found != null && findChapter != null -> Follow(findChapter, found.leaf, found.start)
                    heard != null && sentence != null && heard.playing -> Follow(heard.chapterId, sentence.leaf, heard.spokenAt ?: sentence.start)
                    else -> null
                }
            Marks(
                list,
                follow,
                bookmarks.mapTo(HashSet()) { it.chapterId to it.block },
                shownHighlights.filter { it.highlight.note != null }.mapTo(HashSet()) { it.highlight.chapterId to it.highlight.block },
            )
        }
    val listeningActions =
        remember(viewModel) {
            ListeningActions(
                onToggle = viewModel::toggleListening,
                onSentenceBack = viewModel::sentenceBack,
                onSentenceOn = viewModel::sentenceOn,
                onChapterBack = viewModel::chapterBackListening,
                onChapterOn = viewModel::chapterOnListening,
                onStopAfter = viewModel::stopAfter,
                onSpeed = { speed -> viewModel.updateListening { it.copy(speed = speed) } },
                onVoice = { voice -> viewModel.updateListening { it.copy(voice = voice) } },
                onChime = { chime -> viewModel.updateListening { it.copy(chime = chime) } },
                onPronunciations = viewModel::setPronunciations,
                onStop = viewModel::stopListening,
                voices = viewModel::voices,
                natural =
                    NaturalVoicesUi(
                        pack = viewModel.naturalVoices.state,
                        voices = viewModel.naturalVoices.voices,
                        onWifi = viewModel.naturalVoices::onWifi,
                        onDownload = viewModel.naturalVoices::download,
                        onCancel = viewModel.naturalVoices::cancel,
                        onDelete = viewModel.naturalVoices::delete,
                    ),
            )
        }

    // Where the reader is, as a place to come back to after a jump: the page as the bar counts it, and the place in the chapter.
    fun jumping() {
        val chapter = readingChapter ?: state.chapter?.id
        val number = if (settings.pageMode) pageInfo?.let { it.page + 1 } else progress?.page
        val here =
            if (chapter == null || number == null) {
                null
            } else {
                BackPlace(
                    chapterId = chapter,
                    position = readingPlace,
                    page = number,
                    pageIndex = pageInfo?.page?.takeIf { settings.pageMode },
                    fraction = progress?.fraction?.takeIf { !settings.pageMode },
                    chapterTitle = (page as? ReaderPage.Ready)?.loaded?.get(chapter)?.chapter?.title,
                    document = document,
                )
            }
        jumpBack.jumping(here)
    }

    // Back: in the chapter showing, as the slider goes; else opening the chapter there afresh.
    fun goBack(to: BackPlace) {
        jumpBack.forget()
        val same = to.document == document && to.chapterId == (readingChapter ?: state.chapter?.id)
        when {
            same && settings.pageMode && to.pageIndex != null -> {
                pageInfo = pageInfo?.let { PageInfo(to.pageIndex, to.pageIndex, it.total) }
                controller.goToPage?.invoke(to.pageIndex)
            }
            same && !settings.pageMode && to.fraction != null -> {
                progress = progress?.copy(fraction = to.fraction)
                controller.seek?.invoke(to.fraction)
            }
            else -> viewModel.returnTo(to.chapterId, to.position)
        }
    }

    // The reading view's reports: only from the one on screen, not one it replaced a moment ago, and
    // only about the chapter being read.
    val onEvent: (ReaderEvent) -> Unit = { event ->
        when (event) {
            // With a panel open, a tap on the page puts it away.
            is ReaderEvent.CenterTap ->
                when {
                    settingsOpen -> settingsOpen = false
                    chaptersOpen -> chaptersOpen = false
                    listeningOpen -> listeningOpen = false
                    replacementsOpen -> replacementsOpen = false
                    autoScrolling -> autoPaused = !autoPaused
                    else -> controlsVisible = !controlsVisible
                }
            is ReaderEvent.ChapterChanged ->
                if (event.document == document) {
                    readingChapter = event.chapter
                    pageInfo = null
                    progress = null
                }
            is ReaderEvent.Page ->
                if (event.document == document && event.chapter == readingChapter) {
                    pageInfo = PageInfo(event.page, event.last, event.total)
                    jumpBack.at(event.chapter, event.page + 1)
                }
            is ReaderEvent.AutoScrollEnded -> if (event.document == document) stopAutoScroll()
            is ReaderEvent.Position ->
                if (event.document == document && event.chapter == readingChapter) readingPlace = ReadingPosition(event.block, event.offset)
            is ReaderEvent.Progress ->
                if (event.document == document && event.chapter == readingChapter) {
                    val now = ScrollProgress(event.permille / 1000f, event.screens)
                    progress = now
                    jumpBack.at(event.chapter, now.page)
                }
            else -> Unit
        }
        viewModel.onEvent(event)
    }

    // The foot of the page: the navigation bar's room, or more for the page number, the time and the battery.
    val navigationRoom = WindowInsets.navigationBarsIgnoringVisibility.asPaddingValues().calculateBottomPadding()
    // Its line as tall as the text size this device is set to makes it, with a little room.
    val footLine = with(LocalDensity.current) { MaterialTheme.typography.bodySmall.lineHeight.toDp() }
    val footBand = if (settings.clockAndBattery) maxOf(navigationRoom, footLine + FOOT_ROOM) else navigationRoom

    Box(
        Modifier
            .fillMaxSize()
            .background(colors.background)
            // Where each touch starts, before anything below takes it.
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        event.changes.firstOrNull { it.changedToDown() }?.let { touched.value = it.position }
                    }
                }
            },
    ) {
        CompositionLocalProvider(LocalContentColor provides colors.text) {
            when (page) {
                ReaderPage.Loading ->
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = colors.text) }
                is ReaderPage.Failed ->
                    ErrorState(
                        message = userMessage(page.error),
                        onRetry = viewModel::retry,
                        onOpenInBrowser = browserUrl(page.error)?.let { url -> { onOpenInBrowser(url) } },
                        browserLabel = browserLabel(page.error),
                        detail = errorDetail(page.error),
                    )
                is ReaderPage.Ready ->
                    ReadingView(
                        page = page,
                        settings = settings,
                        commands = viewModel.commands,
                        controller = controller,
                        onEvent = onEvent,
                        onNeed = viewModel::need,
                        savedImage = viewModel::savedImage,
                        onOpenInBrowser = onOpenInBrowser,
                        marks = marks,
                        onHold = { hold = it },
                        choosing = choosing,
                        shownTexts = shownTexts,
                        nextCard = nextCard,
                        nextChapter = { id -> nextAfter(page, id, chapterList, readingSpeed) },
                        onOpenNote = { mark, text -> note = OpenNote(noteLabel(mark), text, touched.value) },
                        onOpenPicture = { model, alt ->
                            controlsVisible = false
                            picture = Picture(model, alt)
                        },
                        topRoom = if (finding) FIND_BAR_ROOM else 0.dp,
                        bottomRoom =
                            (if ((listening != null || autoScrolling) && !finding) MINI_PLAYER_ROOM else 0.dp) + (footBand - navigationRoom) +
                                (if (settings.clockAndBattery && !settings.pageMode) FOOT_FADE else 0.dp),
                        autoScroll =
                            if (autoScrolling && !autoPaused && !controlsVisible && !settingsOpen && !chaptersOpen && !listeningOpen && !replacementsOpen && !finding) {
                                AutoScroll(autoScrollSpeed, stopAtChapterEnd = autoStop == AutoStop.ChapterEnd)
                            } else {
                                null
                            },
                    )
            }
        }
        // The page number, the time and the battery, small at the foot of the page: not while the system's bars show, as they
        // do while finding (the navigation bar's handle would sit on it).
        if (settings.clockAndBattery && page is ReaderPage.Ready && !finding) {
            PageFoot(
                place = placeText(settings.pageMode, pageInfo, progress),
                color = colors.text,
                background = colors.background,
                margin = settings.margin.dp,
                height = footBand,
                fade = !settings.pageMode,
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        }
        // Scrolling, how far through the chapter, at the page's edge.
        val scrolled = progress
        if (!settings.pageMode && settings.scrollBar != ScrollBar.Off && page is ReaderPage.Ready && scrolled != null) {
            ChapterProgress(
                kind = settings.scrollBar,
                progress = scrolled,
                colors = colors,
                top = WindowInsets.statusBarsIgnoringVisibility.asPaddingValues().calculateTopPadding() + 8.dp,
                bottom = footBand + 8.dp,
            )
        }
        // Warmth and the dimmest brightness over the page, not the controls and panels over it.
        PageFilter(settings.brightness, settings.warmth)

        AnimatedVisibility(
            visible = controlsVisible || finding,
            enter = fadeIn() + slideInVertically { -it },
            exit = fadeOut() + slideOutVertically { -it },
            modifier = Modifier.align(Alignment.TopCenter),
        ) {
            if (finding) {
                FindBar(
                    words = findWords,
                    onWords = { findWords = it },
                    count = if (findWords.isBlank()) "" else if (matches.isEmpty()) "None" else "${findAt + 1} of ${matches.size}",
                    canStep = matches.size > 1,
                    onPrevious = { findAt = (findAt - 1).mod(matches.size) },
                    onNext = { findAt = (findAt + 1).mod(matches.size) },
                    onClose = { finding = false },
                )
                return@AnimatedVisibility
            }
            TopAppBar(
                title = {
                    Column {
                        Text(state.chapter?.title.orEmpty(), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium)
                        Text(state.novelTitle, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                },
                actions = {
                    IconButton(onClick = {
                        controlsVisible = false
                        finding = true
                    }) { Icon(Icons.Filled.Search, contentDescription = "Find in chapter") }
                    // Kept as it is, or turning with the device: a tap keeps it the way it's held now, or lets it turn again.
                    val orientation = LocalConfiguration.current.orientation
                    val locked = settings.screenLock != ScreenLock.Free
                    IconButton(
                        onClick = {
                            val lock =
                                when {
                                    locked -> ScreenLock.Free
                                    orientation == Configuration.ORIENTATION_LANDSCAPE -> ScreenLock.Landscape
                                    else -> ScreenLock.Portrait
                                }
                            viewModel.updateDeviceSettings { it.copy(screenLock = lock) }
                            val said =
                                when (lock) {
                                    ScreenLock.Free -> "Turns with this device"
                                    ScreenLock.Portrait -> "Kept in portrait"
                                    ScreenLock.Landscape -> "Kept in landscape"
                                }
                            Toast.makeText(context, said, Toast.LENGTH_SHORT).show()
                        },
                        colors = if (locked) IconButtonDefaults.filledTonalIconButtonColors() else IconButtonDefaults.iconButtonColors(),
                    ) {
                        Icon(
                            if (locked) ReaderIcons.ScreenLockRotation else ReaderIcons.ScreenRotation,
                            contentDescription = if (locked) "Let the screen turn" else "Keep the screen as it is",
                        )
                    }
                    // A book's chapters have no page to open.
                    if (state.chapter?.url?.let(::isBookUrl) != true) {
                        IconButton(onClick = { state.chapter?.let { onOpenInBrowser(it.url) } }) {
                            Icon(ReaderIcons.Globe, contentDescription = "Open in browser")
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
            )
        }

        AnimatedVisibility(
            visible = controlsVisible && !finding,
            enter = fadeIn() + slideInVertically { it },
            exit = fadeOut() + slideOutVertically { it },
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
          Column(Modifier.onSizeChanged { controlsHeight = it.height }) {
            // The few settings changed most, over the bar: text size, colours, brightness.
            QuickSettings(
                settings = settings,
                onChange = viewModel::updateSettings,
                onBrightness = viewModel::setBrightness,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            )
            ReaderBottomBar(
                pageMode = settings.pageMode,
                pageInfo = pageInfo,
                progress = progress,
                minutesLeft =
                    (page as? ReaderPage.Ready)?.loaded?.get(readingChapter)?.content?.let { content ->
                        readingPlace?.let { minutesFor(wordsFrom(content, it), readingSpeed) }
                    },
                hasPrevious = state.hasPrevious,
                hasNext = state.hasNext,
                onPrevious = { viewModel.previous() },
                onNext = viewModel::next,
                onGoToPage = { target ->
                    jumping()
                    pageInfo = pageInfo?.let { PageInfo(target, target, it.total) }
                    controller.goToPage?.invoke(target)
                },
                onSeek = { fraction ->
                    jumping()
                    progress = progress?.copy(fraction = fraction)
                    controller.seek?.invoke(fraction)
                },
                onOpenSettings = {
                    controlsVisible = false
                    settingsOpen = true
                },
                onOpenChapters = {
                    controlsVisible = false
                    chaptersOpen = true
                },
                onAutoScroll = {
                    controlsVisible = false
                    autoScrolling = true
                    autoPaused = false
                    if (listening?.playing == true) viewModel.pauseListening()
                },
                listening = listening != null,
                onListen = {
                    controlsVisible = false
                    listeningOpen = true
                    viewModel.listen()
                },
            )
          }
        }

        // While listening, with nothing else over the page: the small player.
        AnimatedVisibility(
            visible = listening != null && !autoScrolling && !controlsVisible && !settingsOpen && !chaptersOpen && !listeningOpen && !finding,
            enter = fadeIn() + slideInVertically { it },
            exit = fadeOut() + slideOutVertically { it },
            modifier =
                Modifier
                    .align(Alignment.BottomCenter)
                    .windowInsetsPadding(WindowInsets.navigationBarsIgnoringVisibility.only(WindowInsetsSides.Bottom))
                    .padding(horizontal = 12.dp, vertical = 12.dp),
        ) {
            listening?.let { state ->
                MiniPlayer(
                    state = state,
                    speed = listeningSettings.speed,
                    onToggle = listeningActions.onToggle,
                    onBack = listeningActions.onSentenceBack,
                    onOn = listeningActions.onSentenceOn,
                    onSpeed = listeningActions.onSpeed,
                    onOpen = { listeningOpen = true },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }

        // Auto-scroll's own small bar: pause, slower, faster, stop.
        AnimatedVisibility(
            visible = autoScrolling && !controlsVisible && !settingsOpen && !chaptersOpen && !listeningOpen && !replacementsOpen && !finding,
            enter = fadeIn() + slideInVertically { it },
            exit = fadeOut() + slideOutVertically { it },
            modifier =
                Modifier
                    .align(Alignment.BottomCenter)
                    .windowInsetsPadding(WindowInsets.navigationBarsIgnoringVisibility.only(WindowInsetsSides.Bottom))
                    .padding(bottom = 16.dp),
        ) {
            AutoScrollBar(
                pageMode = settings.pageMode,
                paused = autoPaused,
                speed = autoScrollSpeed,
                turn = controller.autoTurn.value,
                stop = autoStop,
                stopsAt = autoStopAt,
                onTimer = { choice ->
                    autoStop = choice
                    autoStopAt = if (choice.minutes > 0) System.currentTimeMillis() + choice.minutes * 60_000L else null
                },
                onToggle = { autoPaused = !autoPaused },
                onSlower = { viewModel.setAutoScrollSpeed(autoScrollSpeed - 1) },
                onFaster = { viewModel.setAutoScrollSpeed(autoScrollSpeed + 1) },
                onStop = { stopAutoScroll() },
            )
        }

        // After a jump, for a while: back to where the reader was, over whatever's at the foot of the page.
        val backFrom = jumpBack.from
        val hereChapter = readingChapter ?: state.chapter?.id
        val hereNumber = if (settings.pageMode) pageInfo?.let { it.page + 1 } else progress?.page
        val showBack =
            backFrom != null && hereNumber != null && page is ReaderPage.Ready &&
                (hereChapter != backFrom.chapterId || hereNumber != backFrom.page) &&
                !settingsOpen && !chaptersOpen && !listeningOpen && !replacementsOpen && !finding && hold == null && lookup == null && recap == null
        LaunchedEffect(showBack, jumpBack.jumpedAt) {
            if (showBack) {
                delay(BACK_OFFERED_MS)
                jumpBack.forget()
            }
        }
        var lastBack by remember { mutableStateOf<BackPlace?>(null) }
        SideEffect { if (backFrom != null) lastBack = backFrom }
        val backRoom by animateDpAsState(
            when {
                controlsVisible -> with(LocalDensity.current) { controlsHeight.toDp() }
                listening != null || autoScrolling -> navigationRoom + MINI_PLAYER_ROOM
                else -> footBand
            } + 16.dp,
            label = "backRoom",
        )
        AnimatedVisibility(
            visible = showBack,
            enter = fadeIn() + slideInVertically { it / 2 },
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = backRoom).padding(horizontal = 24.dp),
        ) {
            (backFrom ?: lastBack)?.let { back ->
                BackChip(back, otherChapter = back.chapterId != hereChapter, onClick = { goBack(back) })
            }
        }

        AnimatedVisibility(
            visible = listeningOpen,
            enter = fadeIn() + slideInVertically { it },
            exit = fadeOut() + slideOutVertically { it },
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            ListeningPanel(
                state = listening,
                settings = listeningSettings,
                actions = listeningActions,
                onClose = { listeningOpen = false },
                modifier = Modifier.fillMaxWidth().fillMaxHeight(0.5f),
            )
        }

        AnimatedVisibility(
            visible = replacementsOpen,
            enter = fadeIn() + slideInVertically { it },
            exit = fadeOut() + slideOutVertically { it },
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            ReplacementsPanel(
                rules = replacements,
                onAdd = viewModel::addReplacement,
                onRemove = viewModel::removeReplacement,
                onClose = { replacementsOpen = false },
                modifier = Modifier.fillMaxWidth().fillMaxHeight(0.6f),
            )
        }

        // A long press on a paragraph (or a tap on a highlight): highlight the sentence or note it, read aloud from there,
        // bookmark the paragraph, or copy it.
        // Words chosen: a tap anywhere else lets them go, and handles under their ends change them.
        hold?.takeIf { it.done }?.let { pressed ->
            Box(Modifier.fillMaxSize().pointerInput(Unit) { detectTapGestures { hold = null } })
            val chosen = if (pressed.tapped || pressed.leaf < 0) null else pressed.chosen(leafTexts(page, pressed.chapterId))
            if (chosen != null) {
                ChoiceHandles(
                    start = shownTexts.caret(chosen.chapterId, chosen.leaf, chosen.start)?.let { ChoiceEnd(chosen.leaf, chosen.start, it) },
                    end = shownTexts.caret(chosen.chapterId, chosen.endLeaf, chosen.end)?.let { ChoiceEnd(chosen.endLeaf, chosen.end, it) },
                    at = { window -> shownTexts.at(chosen.chapterId, window) },
                    onMove = { isStart, leaf, offset -> hold = hold?.withEnd(isStart, leaf, offset, leafTexts(page, chosen.chapterId)) },
                    onDragging = { dragging ->
                        draggingHandle = dragging
                        // Let go of, the menu opens by the lines the choice now ends on.
                        if (!dragging) hold?.let { now -> now.lineAtEnd(shownTexts, leafTexts(page, now.chapterId))?.let { line -> hold = now.copy(line = line) } }
                    },
                )
            }
        }

        hold?.takeIf { it.done && !draggingHandle }?.let { pressed ->
            // Anchored to the lines pressed, the menu opens below them (above, near the bottom) and leaves the words in sight.
            // Below the handles, too, when the choice has them.
            val handleRoom = if (pressed.tapped || pressed.leaf < 0) 0.dp else HANDLE_ROOM
            Box(Modifier.offset { IntOffset(pressed.line.left.toInt(), pressed.line.top.toInt()) }.height(with(LocalDensity.current) { pressed.line.height.toDp() } + handleRoom)) {
                // Not taking the screen's touches: the handles under the words chosen take theirs, and a tap elsewhere ends it all.
                DropdownMenu(
                    expanded = true,
                    onDismissRequest = { hold = null },
                    properties = PopupProperties(focusable = false, dismissOnClickOutside = false),
                    // Round, as the cards and panels over the page are.
                    shape = RoundedCornerShape(16.dp),
                ) {
                    // The word pressed, or the words the finger slid across, maybe on into later paragraphs: when it slid across
                    // several, they're what Highlight, a note, Look up, Copy and Share take; a word alone is looked up, and its
                    // sentence (or the highlight it's in) highlighted and shared.
                    val chosen = if (pressed.leaf < 0) null else pressed.chosen(leafTexts(page, pressed.chapterId))
                    val words = chosen?.text?.trim()?.split(SPACES)?.filter { it.isNotEmpty() }.orEmpty()
                    val phrase = words.joinToString(" ").ifEmpty { null }
                    val several = words.size > 1
                    val here = if (several) null else shownHighlights.firstOrNull { it.highlight.chapterId == pressed.chapterId && it.covers(pressed.leaf, pressed.offset) }
                    val stretch =
                        when {
                            pressed.leaf < 0 -> null
                            several -> chosen
                            here != null -> here.stretch()
                            else ->
                                Sentences.around(pressed.text, pressed.offset)?.let {
                                    Stretch(pressed.chapterId, pressed.leaf, it.first, pressed.leaf, it.last + 1, pressed.text.substring(it.first, it.last + 1))
                                }
                        }
                    if (stretch != null) {
                        HighlightColors(chosen = here?.highlight?.color) { color ->
                            hold = null
                            viewModel.highlight(stretch, color)
                        }
                        DropdownMenuItem(
                            text = { Text(if (here?.highlight?.note != null) "Edit note" else "Add a note") },
                            leadingIcon = { Icon(ReaderIcons.Note, contentDescription = null) },
                            onClick = {
                                hold = null
                                noting = NoteEdit(stretch, here?.highlight?.id, here?.highlight?.note.orEmpty())
                            },
                        )
                        if (here != null) {
                            DropdownMenuItem(
                                text = { Text("Remove highlight") },
                                leadingIcon = { Icon(Icons.Filled.Close, contentDescription = null) },
                                onClick = {
                                    hold = null
                                    viewModel.removeHighlight(here.highlight.id)
                                },
                            )
                        }
                    }
                    // To the dictionary, and from its card to other apps.
                    if (chosen != null && phrase != null && chosen.endLeaf == chosen.leaf && words.size <= MOST_WORDS_LOOKED_UP) {
                        DropdownMenuItem(
                            text = { Text("Look up \u201c$phrase\u201d", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                            onClick = {
                                hold = null
                                lookupAt = pressed
                                viewModel.lookUp(phrase)
                            },
                        )
                    }
                    DropdownMenuItem(
                        text = { Text("Read from here") },
                        leadingIcon = { Icon(ReaderIcons.Headphones, contentDescription = null) },
                        onClick = {
                            hold = null
                            viewModel.listenFrom(pressed.chapterId, ReadingPosition(pressed.leaf.coerceAtLeast(0), if (pressed.leaf < 0) 0 else pressed.offset))
                        },
                    )
                    if (pressed.leaf >= 0) {
                        val marked = bookmarks.any { it.chapterId == pressed.chapterId && it.block == pressed.leaf }
                        DropdownMenuItem(
                            text = { Text(if (marked) "Remove bookmark" else "Bookmark") },
                            leadingIcon = { Icon(ReaderIcons.Bookmark, contentDescription = null) },
                            onClick = {
                                hold = null
                                viewModel.toggleBookmark(pressed.chapterId, pressed.leaf, pressed.text)
                            },
                        )
                    }
                    // What Copy and Share take: the words chosen when the finger slid across some; else, as Highlight, the
                    // sentence (or the highlight); else the paragraph. Footnote marks mean nothing out of the page: they're left out.
                    val loadedChapter = (page as? ReaderPage.Ready)?.loaded?.get(pressed.chapterId)
                    val taken = if (several) chosen else stretch
                    val sharedText =
                        loadedChapter?.content?.let { content ->
                            if (taken != null) {
                                unmarkedBetween(content.leaves, taken.leaf, taken.start, taken.endLeaf, taken.end)
                            } else if (pressed.leaf >= 0) {
                                unmarkedBetween(content.leaves, pressed.leaf, 0, pressed.leaf, pressed.text.length)
                            } else {
                                null
                            }
                        }?.trim() ?: (taken?.text?.trim() ?: pressed.text)
                    val chapterTitle = loadedChapter?.chapter?.title.orEmpty()
                    DropdownMenuItem(
                        text = { Text(if (several) "Copy \u201c$phrase\u201d" else "Copy", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        leadingIcon = { Icon(ReaderIcons.Copy, contentDescription = null) },
                        onClick = {
                            hold = null
                            clipboard.setText(AnnotatedString(sharedText))
                        },
                    )
                    // To another app, saying where they're from: as text, or (the button at the row's end) as a picture, a card
                    // in the reader's font.
                    DropdownMenuItem(
                        text = { Text("Share") },
                        leadingIcon = { Icon(Icons.Filled.Share, contentDescription = null) },
                        trailingIcon = {
                            IconButton(onClick = {
                                hold = null
                                quoting = QuoteRequest(sharedText, state.novelTitle, chapterTitle)
                            }) { Icon(ReaderIcons.Image, contentDescription = "Share as a picture") }
                        },
                        onClick = {
                            hold = null
                            val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, quoteText(sharedText, state.novelTitle, chapterTitle))
                            context.startActivity(Intent.createChooser(send, null))
                        },
                    )
                }
            }
        }

        // Back after a while: where the reader left off, over the page till a tap anywhere.
        recap?.takeIf { lookup == null && hold == null }?.let { back ->
            Box(Modifier.fillMaxSize().pointerInput(back) { detectTapGestures { viewModel.closeRecap() } })
            RecapCard(
                recap = back,
                now = remember(back) { System.currentTimeMillis() },
                onCarryOn = viewModel::closeRecap,
                modifier =
                    Modifier
                        .align(Alignment.BottomCenter)
                        .windowInsetsPadding(WindowInsets.navigationBarsIgnoringVisibility.only(WindowInsetsSides.Bottom))
                        .padding(12.dp),
            )
        }

        // The dictionary's card, at the bottom, or at the top when the word is in the lower half: a tap anywhere else closes it.
        lookup?.let { looked ->
            val atTop = (lookupAt?.line?.center?.y ?: 0f) > LocalView.current.height / 2f
            Box(Modifier.fillMaxSize().pointerInput(looked) { detectTapGestures { viewModel.closeLookup() } })
            DefinitionCard(
                lookup = looked,
                onOtherApps = {
                    viewModel.closeLookup()
                    lookUpElsewhere(context, looked.word)
                },
                onCopy = (looked as? Lookup.Found)?.let { found -> { clipboard.setText(AnnotatedString(found.definition.asText())) } },
                onSay = viewModel::sayWord,
                onClose = viewModel::closeLookup,
                modifier =
                    Modifier
                        .align(if (atTop) Alignment.TopCenter else Alignment.BottomCenter)
                        .windowInsetsPadding(
                            if (atTop) {
                                WindowInsets.statusBarsIgnoringVisibility.only(WindowInsetsSides.Top)
                            } else {
                                WindowInsets.navigationBarsIgnoringVisibility.only(WindowInsetsSides.Bottom)
                            },
                        )
                        .padding(12.dp),
            )
        }

        noting?.let { edit ->
            NoteDialog(
                quote = edit.quote,
                note = edit.note,
                highlighted = edit.highlightId != null,
                onSave = { text ->
                    noting = null
                    val id = edit.highlightId
                    when {
                        id != null -> viewModel.setHighlightNote(id, text)
                        text.isNotBlank() -> viewModel.highlight(edit.stretch, 0, text)
                    }
                },
                onRemove = {
                    noting = null
                    edit.highlightId?.let(viewModel::removeHighlight)
                },
                onDismiss = { noting = null },
            )
        }

        AnimatedVisibility(
            visible = chaptersOpen,
            enter = fadeIn() + slideInVertically { it },
            exit = fadeOut() + slideOutVertically { it },
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            ReaderChaptersPanel(
                list = chapterList,
                currentId = state.chapter?.id,
                onOpen = { id ->
                    chaptersOpen = false
                    if (id != (readingChapter ?: state.chapter?.id)) jumping()
                    viewModel.open(id)
                },
                bookmarks = bookmarks,
                onOpenBookmark = { bookmark ->
                    chaptersOpen = false
                    jumping()
                    viewModel.openBookmark(bookmark)
                },
                onRemoveBookmark = { viewModel.removeBookmark(it.id) },
                highlights = highlights,
                onOpenHighlight = { highlight ->
                    chaptersOpen = false
                    jumping()
                    viewModel.openHighlight(highlight)
                },
                onRemoveHighlight = { viewModel.removeHighlight(it.id) },
                onShareHighlights = {
                    val text = highlightsText(state.novelTitle, highlights)
                    val send =
                        Intent(Intent.ACTION_SEND)
                            .setType("text/plain")
                            .putExtra(Intent.EXTRA_SUBJECT, "${state.novelTitle} \u2014 highlights")
                            .putExtra(Intent.EXTRA_TEXT, text)
                    context.startActivity(Intent.createChooser(send, null))
                },
                onClose = { chaptersOpen = false },
                modifier = Modifier.fillMaxWidth().fillMaxHeight(0.55f),
            )
        }

        AnimatedVisibility(
            visible = settingsOpen,
            enter = fadeIn() + slideInVertically { it },
            exit = fadeOut() + slideOutVertically { it },
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            // The lower part of the screen only: the page stays readable above while settings change.
            val readingSettings by viewModel.readingSettings.collectAsStateWithLifecycle()
            ReaderSettingsPanel(
                settings = readingSettings,
                onChange = viewModel::updateSettings,
                onOwnChange = viewModel::setOwnSettings,
                onSetAsDefault = viewModel::setAsDefault,
                onClose = { settingsOpen = false },
                onBrightness = viewModel::setBrightness,
                onWarmth = viewModel::setWarmth,
                onDevice = viewModel::updateDeviceSettings,
                replacements = replacements.size,
                onOpenReplacements = {
                    settingsOpen = false
                    replacementsOpen = true
                },
                modifier = Modifier.fillMaxWidth().fillMaxHeight(0.45f),
            )
        }

        note?.let { open -> NoteCard(open, onClose = { note = null }) }
        quoting?.let { request -> QuoteCardDialog(request, settings, onDismiss = { quoting = null }) }

        // A picture full screen, over all the rest.
        picture?.let { shown -> PictureViewer(shown, onClose = { picture = null }) }
    }
}

/**
 * Auto-scroll's bar over the page: what it's doing, pause or go on, slower, the speed (and what it means), faster, and
 * stop. In page mode, a line filling up to the page's turn.
 */
@Composable
private fun AutoScrollBar(
    pageMode: Boolean,
    paused: Boolean,
    speed: Int,
    turn: AutoTurn?,
    stop: AutoStop,
    stopsAt: Long?,
    onTimer: (AutoStop) -> Unit,
    onToggle: () -> Unit,
    onSlower: () -> Unit,
    onFaster: () -> Unit,
    onStop: () -> Unit,
) {
    Surface(shape = RoundedCornerShape(22.dp), color = MaterialTheme.colorScheme.inverseSurface, shadowElevation = 6.dp) {
        Column(Modifier.width(IntrinsicSize.Max).padding(top = 10.dp, bottom = 6.dp)) {
            Text(
                when {
                    paused -> "Paused"
                    pageMode -> "Pages will turn automatically"
                    else -> "The page will scroll automatically"
                },
                style = MaterialTheme.typography.labelLarge,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp),
            )
            if (pageMode) {
                // How far the page is through its time: the line's full when it turns.
                val progress by produceState(0f, turn, paused) {
                    if (turn == null || paused) return@produceState
                    while (true) {
                        value = ((SystemClock.uptimeMillis() - turn.startedAt).toFloat() / turn.millis).coerceIn(0f, 1f)
                        withFrameMillis { }
                    }
                }
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 8.dp),
                    color = MaterialTheme.colorScheme.inversePrimary,
                    trackColor = MaterialTheme.colorScheme.inverseOnSurface.copy(alpha = 0.2f),
                    drawStopIndicator = {},
                )
            }
            // When it stops by itself, the minutes counting down.
            val now by produceState(System.currentTimeMillis(), stopsAt) {
                while (stopsAt != null) {
                    value = System.currentTimeMillis()
                    delay(15_000)
                }
            }
            val stopping =
                when {
                    stop == AutoStop.ChapterEnd -> "Stops at the chapter\u2019s end"
                    stopsAt != null -> "Stops in ${((stopsAt - now + 59_999) / 60_000).coerceAtLeast(1)} min"
                    else -> null
                }
            stopping?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.labelSmall,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.inverseOnSurface.copy(alpha = 0.75f),
                    modifier = Modifier.fillMaxWidth().padding(top = if (pageMode) 0.dp else 4.dp),
                )
            }
            Row(Modifier.padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onToggle) { Icon(if (paused) Icons.Filled.PlayArrow else ReaderIcons.Pause, contentDescription = if (paused) "Go on" else "Pause") }
                IconButton(onClick = onSlower, enabled = speed > 1) { Icon(ReaderIcons.Minus, contentDescription = "Slower") }
                val auto = AutoScroll(speed)
                Text(
                    if (pageMode) "Speed $speed \u00b7 every ${auto.secondsPerPage.roundToInt()} s" else "Speed $speed \u00b7 ${auto.linesPerMinute.roundToInt()} lines a minute",
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.weight(1f),
                    textAlign = TextAlign.Center,
                )
                IconButton(onClick = onFaster, enabled = speed < 10) { Icon(Icons.Filled.Add, contentDescription = "Faster") }
                Box {
                    var choosing by remember { mutableStateOf(false) }
                    IconButton(onClick = { choosing = true }) {
                        Icon(ReaderIcons.Timer, contentDescription = "Stop after", tint = if (stop != AutoStop.Never) MaterialTheme.colorScheme.inversePrimary else LocalContentColor.current)
                    }
                    DropdownMenu(expanded = choosing, onDismissRequest = { choosing = false }) {
                        Text(
                            if (pageMode) "Stop turning" else "Stop scrolling",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                        )
                        AutoStop.entries.forEach { choice ->
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        when (choice) {
                                            AutoStop.Never -> "Never"
                                            AutoStop.ChapterEnd -> "At the chapter\u2019s end"
                                            else -> if (choice.minutes < 60) "After ${choice.minutes} min" else "After ${choice.minutes / 60} h"
                                        },
                                    )
                                },
                                trailingIcon = { if (choice == stop) Icon(Icons.Filled.Check, contentDescription = null) },
                                onClick = {
                                    choosing = false
                                    onTimer(choice)
                                },
                            )
                        }
                    }
                }
                IconButton(onClick = onStop) { Icon(Icons.Filled.Close, contentDescription = "Stop auto-scroll") }
            }
        }
    }
}

/** Find in chapter: the words, how many matches and which the page is at, and steps between them. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FindBar(
    words: String,
    onWords: (String) -> Unit,
    count: String,
    canStep: Boolean,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onClose: () -> Unit,
) {
    val focus = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    LaunchedEffect(Unit) { focus.requestFocus() }
    Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
        Row(
            Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.statusBars).padding(start = 12.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextField(
                value = words,
                onValueChange = onWords,
                placeholder = { FittingText("Find in chapter") },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                trailingIcon = { if (count.isNotEmpty()) Text(count, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(end = 12.dp)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { focusManager.clearFocus() }),
                shape = MaterialTheme.shapes.extraLarge,
                colors =
                    TextFieldDefaults.colors(
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent,
                    ),
                modifier = Modifier.weight(1f).focusRequester(focus),
            )
            IconButton(onClick = onPrevious, enabled = canStep) { Icon(Icons.Filled.KeyboardArrowUp, contentDescription = "Previous match") }
            IconButton(onClick = onNext, enabled = canStep) { Icon(Icons.Filled.KeyboardArrowDown, contentDescription = "Next match") }
            IconButton(onClick = onClose) { Icon(Icons.Filled.Close, contentDescription = "Close find") }
        }
    }
}

/** Chapter buttons around a slider through the chapter, and the ways into the chapters and the reading settings. */
@Composable
private fun ReaderBottomBar(
    pageMode: Boolean,
    pageInfo: PageInfo?,
    progress: ScrollProgress?,
    minutesLeft: Int?,
    hasPrevious: Boolean,
    hasNext: Boolean,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onGoToPage: (Int) -> Unit,
    onSeek: (Float) -> Unit,
    onOpenSettings: () -> Unit,
    onOpenChapters: () -> Unit,
    onAutoScroll: () -> Unit,
    listening: Boolean,
    onListen: () -> Unit,
) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
        Column(Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.navigationBars)) {
            Row(Modifier.padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onPrevious, enabled = hasPrevious) {
                    Icon(ReaderIcons.SkipPrevious, contentDescription = "Previous chapter")
                }
                val sliderModifier = Modifier.weight(1f).semantics { contentDescription = "Position in chapter" }
                if (pageMode) {
                    val total = pageInfo?.total ?: 1
                    Slider(
                        value = (pageInfo?.page ?: 0).toFloat(),
                        onValueChange = { onGoToPage(it.roundToInt()) },
                        valueRange = 0f..maxOf(1, total - 1).toFloat(),
                        // A notch per page while there are few enough to tell apart.
                        steps = if (total in 3..30) total - 2 else 0,
                        enabled = total > 1,
                        modifier = sliderModifier,
                    )
                } else {
                    Slider(value = progress?.fraction ?: 0f, onValueChange = onSeek, enabled = progress != null, modifier = sliderModifier)
                }
                IconButton(onClick = onNext, enabled = hasNext) {
                    Icon(ReaderIcons.SkipNext, contentDescription = "Next chapter")
                }
            }
            Row(Modifier.padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                val where = placeText(pageMode, pageInfo, progress)
                // The time left in the chapter, at the reader's own speed.
                val left =
                    when (minutesLeft) {
                        null, 0 -> null
                        1 -> "1 min left"
                        else -> "$minutesLeft min left"
                    }
                // A size smaller when it wouldn't fit beside the buttons (a large text size), rather than cut short.
                BoxWithConstraints(Modifier.weight(1f)) {
                    val label = if (where.isNotEmpty() && left != null) "$where \u00b7 $left" else where
                    Text(
                        label,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = fittingStyle(listOf(label), maxWidth, MaterialTheme.typography.bodyMedium),
                    )
                }
                IconButton(onClick = onOpenChapters) { Icon(Icons.AutoMirrored.Filled.List, contentDescription = "Chapters") }
                IconButton(onClick = onAutoScroll) { Icon(ReaderIcons.AutoScroll, contentDescription = "Auto-scroll") }
                IconButton(onClick = onListen) {
                    Icon(
                        ReaderIcons.Headphones,
                        contentDescription = if (listening) "Listening" else "Listen",
                        tint = if (listening) MaterialTheme.colorScheme.primary else LocalContentColor.current,
                    )
                }
                IconButton(onClick = onOpenSettings) { Icon(Icons.Filled.Settings, contentDescription = "Reader settings") }
            }
        }
    }
}

/** Page mode's place in the chapter: the [page] showing (from 0) of [total], to [last] with two side by side. */
internal data class PageInfo(val page: Int, val last: Int, val total: Int)

/** Scroll mode's place in the chapter, told as pages: a page is a screenful. */
internal data class ScrollProgress(val fraction: Float, val screens: Int) {
    val page: Int get() = (fraction * (screens - 1)).roundToInt() + 1
}

/** Lets the controls around the text move through it: the reading view sets these while it shows. */
internal class ReaderController {
    /** Scroll mode: to a fraction of the chapter being read. */
    var seek: ((Float) -> Unit)? = null

    /** Page mode: to a page of the chapter being read, counted from 0. */
    var goToPage: ((Int) -> Unit)? = null

    /** Page mode with auto-scroll going: when the page on screen started its time, and how long it has, till it turns. */
    val autoTurn = mutableStateOf<AutoTurn?>(null)
}

/** When auto-scroll stops by itself: never, at the end of the chapter being read, or after [minutes]. */
internal enum class AutoStop(val minutes: Int = 0) { Never, ChapterEnd, Quarter(15), HalfHour(30), Hour(60) }

/** A page's time on screen under auto-scroll: from [startedAt] (uptime, milliseconds) for [millis]. */
internal data class AutoTurn(val startedAt: Long, val millis: Long)

/**
 * Light icons on the status and navigation bars while the page, and so the controls over it, are [dark]; after, the
 * app's own theme's (which may have turned dark meanwhile, following the reader).
 */
@Composable
private fun DarkSystemBars(dark: Boolean) {
    val view = LocalView.current
    val window = LocalActivity.current?.window ?: return
    val appDark by rememberUpdatedState(MaterialTheme.colorScheme.background.luminance() < 0.5f)
    DisposableEffect(dark) {
        if (!dark) return@DisposableEffect onDispose {}
        val controller = WindowCompat.getInsetsController(window, view)
        controller.isAppearanceLightStatusBars = false
        controller.isAppearanceLightNavigationBars = false
        onDispose {
            controller.isAppearanceLightStatusBars = !appDark
            controller.isAppearanceLightNavigationBars = !appDark
        }
    }
}

/** Hides the status and navigation bars while reading; a swipe from the edge shows them for a moment. */
@Composable
private fun SystemBars(visible: Boolean) {
    val view = LocalView.current
    val window = LocalActivity.current?.window ?: return
    DisposableEffect(visible) {
        val controller = WindowCompat.getInsetsController(window, view)
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        if (visible) controller.show(WindowInsetsCompat.Type.systemBars()) else controller.hide(WindowInsetsCompat.Type.systemBars())
        onDispose { controller.show(WindowInsetsCompat.Type.systemBars()) }
    }
}

/**
 * Looks [word] up in an app that looks words up (a dictionary, a translator): straight in the one there is, a choice of
 * them when there are several, and a web search for its meaning when there's none.
 */
private fun lookUpElsewhere(context: Context, word: String) {
    val process = Intent(Intent.ACTION_PROCESS_TEXT).setType("text/plain").putExtra(Intent.EXTRA_PROCESS_TEXT, word).putExtra(Intent.EXTRA_PROCESS_TEXT_READONLY, true)
    val apps = context.packageManager.queryIntentActivities(process, 0)
    val intent =
        when (apps.size) {
            0 -> Intent(Intent.ACTION_WEB_SEARCH).putExtra(SearchManager.QUERY, "define $word")
            1 -> process.setClassName(apps[0].activityInfo.packageName, apps[0].activityInfo.name)
            else -> Intent.createChooser(process, "Look up with")
        }
    runCatching { context.startActivity(intent) }.onFailure { Toast.makeText(context, "No app on this device looks words up", Toast.LENGTH_SHORT).show() }
}

/** The screen's brightness while the reader shows: the reader's own, or for null the phone's. */
@Composable
private fun ReaderBrightness(brightness: Float?) {
    val window = LocalActivity.current?.window ?: return
    DisposableEffect(brightness) {
        window.attributes = window.attributes.apply { screenBrightness = if (brightness == null) WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE else screenOf(brightness) }
        onDispose { window.attributes = window.attributes.apply { screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE } }
    }
}

@Composable
private fun ScreenLockEffect(lock: ScreenLock) {
    val activity = LocalActivity.current ?: return
    // While the reader shows: kept upright, or on its side either way round; back to turning freely after.
    DisposableEffect(lock) {
        activity.requestedOrientation =
            when (lock) {
                ScreenLock.Free -> ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                ScreenLock.Portrait -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                ScreenLock.Landscape -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            }
        onDispose { activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED }
    }
}

@Composable
private fun KeepScreenOn(on: Boolean) {
    val view = LocalView.current
    DisposableEffect(on) {
        view.keepScreenOn = on
        onDispose { view.keepScreenOn = false }
    }
}

/** Room the find bar takes at the top of the page. */
private val FIND_BAR_ROOM = 64.dp

/** Room the small player takes at the bottom of the page while listening. */
private val MINI_PLAYER_ROOM = 84.dp

/** How long the way back is offered after a jump. */
private const val BACK_OFFERED_MS = 10_000L

/**
 * The words from the one pressed to the one the finger slid to, perhaps in a later paragraph (or an earlier: it may have
 * slid back up), as a stretch: the one word for a plain press. [leafText] gives the other paragraphs' text.
 */
private fun TextHold.chosen(leafText: (Int) -> String?): Stretch? {
    val forward = endLeaf > leaf || (endLeaf == leaf && end >= offset)
    val fromLeaf = if (forward) leaf else endLeaf
    val toLeaf = if (forward) endLeaf else leaf
    val fromAt = if (forward) offset else end
    val toAt = if (forward) end else offset
    val texts = { at: Int -> if (at == leaf) text else leafText(at) }
    val fromText = texts(fromLeaf) ?: return null
    val toText = texts(toLeaf) ?: return null
    if (fromLeaf == toLeaf) {
        val first = wordNear(fromText, fromAt) ?: wordNear(fromText, toAt) ?: return null
        val last = wordNear(fromText, toAt) ?: first
        val stop = maxOf(first.last, last.last) + 1
        return Stretch(chapterId, fromLeaf, first.first, fromLeaf, stop, fromText.substring(first.first, stop))
    }
    val start = wordNear(fromText, fromAt)?.first ?: fromAt.coerceIn(0, fromText.length)
    val stop = wordNear(toText, toAt)?.let { it.last + 1 } ?: toAt.coerceIn(0, toText.length)
    return textBetween(fromLeaf, start, toLeaf, stop, texts)?.let { Stretch(chapterId, fromLeaf, start, toLeaf, stop, it) }
}

/** The paragraphs' texts of chapter [chapterId], as loaded on [page]. */
private fun leafTexts(page: ReaderPage, chapterId: Long): (Int) -> String? =
    { leaf -> (page as? ReaderPage.Ready)?.loaded?.get(chapterId)?.content?.leaves?.getOrNull(leaf)?.text() }

/**
 * The word at [offset] of [text], or the one just before it: a press on a word's last letter gives the place after it,
 * which is the space beyond.
 */
private fun wordNear(text: String, offset: Int): IntRange? = Sentences.wordAt(text, offset) ?: if (offset > 0) Sentences.wordAt(text, offset - 1) else null

private val SPACES = Regex("\\s+")

/** A look-up takes this many words at most: a phrase, not a sentence. */
private const val MOST_WORDS_LOOKED_UP = 6

/**
 * The hold with one end of its choice moved to character [offset] of paragraph [leaf]: its start, or its end. The choice
 * is put in reading order first, the handles being at its start and end whichever way the finger slid.
 */
private fun TextHold.withEnd(isStart: Boolean, leaf: Int, offset: Int, leafText: (Int) -> String?): TextHold {
    val stretch = chosen(leafText) ?: return this
    val inOrder = copy(leaf = stretch.leaf, offset = stretch.start, endLeaf = stretch.endLeaf, end = stretch.end, text = leafText(stretch.leaf) ?: text)
    return if (isStart) inOrder.copy(leaf = leaf, offset = offset, text = leafText(leaf) ?: inOrder.text) else inOrder.copy(endLeaf = leaf, end = offset)
}

/** The line the choice ends on, on the screen, for its menu to open by; null when that's not showing. */
private fun TextHold.lineAtEnd(texts: ShownTexts, leafText: (Int) -> String?): Rect? {
    val stretch = chosen(leafText) ?: return null
    return texts.caret(stretch.chapterId, stretch.endLeaf, stretch.end) ?: texts.caret(stretch.chapterId, stretch.leaf, stretch.start)
}

/** Room under the words chosen for the handles hanging there, which the menu opens below. */
private val HANDLE_ROOM = 28.dp

/** The chapter after [chapterId] in reading order: its title, whether it's on this device, and its reading time once loaded. */
private fun nextAfter(page: ReaderPage, chapterId: Long, chapters: ChapterList, wordsPerMinute: Int): NextChapter? {
    val ready = page as? ReaderPage.Ready ?: return null
    val at = ready.chapterIds.indexOf(chapterId)
    if (at < 0) return null
    val next = ready.chapterIds.getOrNull(at + 1) ?: return NextChapter(null, downloaded = false, minutes = null)
    val title = chapters.chapters.firstOrNull { it.id == next }?.title ?: ready.loaded[next]?.chapter?.title ?: return null
    val minutes = ready.loaded[next]?.content?.let { minutesFor(it.wordCount(), wordsPerMinute) }
    return NextChapter(title, downloaded = next in chapters.saved, minutes = minutes)
}
