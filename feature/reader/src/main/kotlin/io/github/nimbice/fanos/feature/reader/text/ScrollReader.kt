package io.github.nimbice.fanos.feature.reader.text

import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsIgnoringVisibility
import androidx.compose.foundation.layout.statusBarsIgnoringVisibility
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListItemInfo
import androidx.compose.foundation.lazy.LazyListLayoutInfo
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFontFamilyResolver
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import io.github.nimbice.fanos.core.data.browserLabel
import io.github.nimbice.fanos.core.data.browserUrl
import io.github.nimbice.fanos.core.data.download.DownloadedImages
import io.github.nimbice.fanos.core.data.userMessage
import io.github.nimbice.fanos.core.designsystem.component.ErrorState
import io.github.nimbice.fanos.core.model.ReaderSettings
import io.github.nimbice.fanos.core.model.ReadingPosition
import io.github.nimbice.fanos.feature.reader.LoadedChapter
import io.github.nimbice.fanos.feature.reader.ReaderCommand
import io.github.nimbice.fanos.feature.reader.ReaderController
import io.github.nimbice.fanos.feature.reader.ReaderEvent
import io.github.nimbice.fanos.feature.reader.ReaderPage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.roundToInt
import androidx.compose.runtime.withFrameNanos
import io.github.nimbice.fanos.core.model.TapZones
import androidx.compose.runtime.CompositionLocalProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job

/**
 * Scroll mode: a page for each chapter, side by side. Each page scrolls through its chapter from
 * the title to the end and stops there; a sideways swipe (or a chapter button) slides the next or
 * previous chapter in, and at the first or last chapter a swipe just springs back. It reports the
 * chapter on screen, the reading position, the chapter's end coming on screen, and how far through
 * the chapter the reader is.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ScrollReader(
    page: ReaderPage.Ready,
    open: Place,
    settings: ReaderSettings,
    style: ReadingStyle,
    rowsOf: (LoadedChapter) -> ChapterRows,
    imageSizes: SnapshotStateMap<String, IntSize>,
    commands: Flow<ReaderCommand>,
    controller: ReaderController,
    onEvent: (ReaderEvent) -> Unit,
    onNeed: (Long) -> Unit,
    savedImage: (chapterId: Long, name: String) -> File,
    onOpenInBrowser: (String) -> Unit,
    marks: Marks,
    onHold: (TextHold) -> Unit,
    topRoom: Dp,
    bottomRoom: Dp,
    autoScroll: AutoScroll?,
) {
    val density = LocalDensity.current
    val fontResolver = LocalFontFamilyResolver.current
    val layoutDirection = LocalLayoutDirection.current
    val geometry = remember(style) { RowGeometry(style) }
    val currentFollow by rememberUpdatedState(marks.follow)
    val insetTop = with(density) { maxOf(WindowInsets.statusBarsIgnoringVisibility.getTop(this), WindowInsets.displayCutout.getTop(this)).toDp() }
    val insetBottom = with(density) { WindowInsets.navigationBarsIgnoringVisibility.getBottom(this).toDp() }
    val document = page.documentId
    val currentPage by rememberUpdatedState(page)
    val currentOnEvent by rememberUpdatedState(onEvent)
    val currentSettings by rememberUpdatedState(settings)

    run {
        val pagerState = rememberPagerState(initialPage = page.chapterIds.indexOf(open.chapterId).coerceAtLeast(0)) { currentPage.chapterIds.size }
        val scope = rememberCoroutineScope()
        val lists = remember { HashMap<Long, LazyListState>() }
        val trackers = remember { HashMap<Long, PageTracker>() }
        val measured = remember { mutableStateMapOf<Long, ChapterHeights>() }
        val focus = remember { FocusRequester() }

        fun listFor(chapterId: Long): LazyListState = lists.getOrPut(chapterId) { LazyListState() }

        fun trackerFor(chapterId: Long): PageTracker = trackers.getOrPut(chapterId) { PageTracker(document, chapterId, density) }

        fun currentId(): Long? = currentPage.chapterIds.getOrNull(pagerState.settledPage)

        /** Slides over to the page [delta] away, if there is one. */
        fun slide(delta: Int) {
            val target = pagerState.settledPage + delta
            if (target in 0 until pagerState.pageCount) scope.launch { pagerState.animateScrollToPage(target) }
        }

        /** Most of a screen on, or back; at a chapter's end, the next chapter (its start, before it, the one before). */
        fun step(forward: Boolean) {
            val list = currentId()?.let { lists[it] } ?: return
            when {
                forward && !list.canScrollForward -> slide(1)
                !forward && !list.canScrollBackward -> slide(-1)
                else -> scope.launch { list.animateScrollBy(screenStep(list, forward)) }
            }
        }

        // The chapter on screen once a swipe settles: the chapter being read.
        LaunchedEffect(pagerState) {
            snapshotFlow { pagerState.settledPage }.collect { index ->
                currentPage.chapterIds.getOrNull(index)?.let { id ->
                    trackers[id]?.reset()
                    currentOnEvent(ReaderEvent.ChapterChanged(document, id))
                }
            }
        }

        // What the page follows moving on into another chapter: over to it, as a swipe goes.
        LaunchedEffect(pagerState) {
            snapshotFlow { currentFollow?.chapterId }.filterNotNull().distinctUntilChanged().collect { id ->
                val index = currentPage.chapterIds.indexOf(id)
                if (index >= 0 && index != pagerState.settledPage) pagerState.animateScrollToPage(index)
            }
        }

        // Auto-scroll: the chapter on screen moved on a little each frame, and over to the next at its end.
        LaunchedEffect(pagerState, autoScroll, style) {
            val auto = autoScroll ?: return@LaunchedEffect
            val perSecond = with(density) { style.em.toPx() } * currentSettings.lineHeight * auto.linesPerMinute / 60f
            var last = 0L
            while (true) {
                val now = withFrameNanos { it }
                val list = currentId()?.let { lists[it] }
                if (list != null && last != 0L && !pagerState.isScrollInProgress) {
                    if (list.canScrollForward) {
                        list.dispatchRawDelta(perSecond * (now - last) / 1_000_000_000f)
                    } else if (auto.stopAtChapterEnd) {
                        // Told to stop at the chapter's end: here it is.
                        currentOnEvent(ReaderEvent.AutoScrollEnded(document))
                        return@LaunchedEffect
                    } else if (pagerState.settledPage < pagerState.pageCount - 1) {
                        pagerState.animateScrollToPage(pagerState.settledPage + 1)
                        last = 0L
                        continue
                    }
                }
                last = now
            }
        }

        // The chapter buttons slide over, as a swipe does.
        LaunchedEffect(pagerState) {
            commands.collect { command ->
                when (command) {
                    is ReaderCommand.GoToChapter -> {
                        val index = currentPage.chapterIds.indexOf(command.chapterId)
                        if (index >= 0) pagerState.animateScrollToPage(index)
                    }
                }
            }
        }

        LaunchedEffect(Unit) { focus.requestFocus() }

        val edges = systemEdges()

        BoxWithConstraints(
            Modifier
                .fillMaxSize()
                .focusRequester(focus)
                .focusable()
                .onKeyEvent { event ->
                    if (!currentSettings.volumeKeysTurnPages || (event.key != Key.VolumeDown && event.key != Key.VolumeUp)) return@onKeyEvent false
                    if (event.type == KeyEventType.KeyDown) step(forward = event.key == Key.VolumeDown)
                    true
                }
                // Pulling the system bars down (or up) doesn't scroll the text.
                .systemSwipesIgnored(edges, sidewaysOnly = false)
                // A tap on either third moves a screen, as the tap zones have it; in the middle it shows the controls.
                .pointerInput(document) {
                    detectTaps { at ->
                        val zones = currentSettings.tapZones
                        when {
                            at.x < size.width / 3f -> step(forward = zones != TapZones.BackOn)
                            at.x > size.width * 2 / 3f -> step(forward = zones != TapZones.OnBack)
                            else -> currentOnEvent(ReaderEvent.CenterTap(document))
                        }
                    }
                },
        ) {
            val width = maxWidth
            val layoutKey = style to width

            fun measurer() = RowMeasurer(geometry, TextMeasurer(fontResolver, density, layoutDirection, 0), density, width)

            // Each loaded chapter measured, for the slider and the page count: the one on screen first,
            // and again when an image turns out another size.
            LaunchedEffect(page.loaded.keys, geometry, width, imageSizes.size) {
                val sizes = imageSizes.toMap()
                val onScreen = currentId()
                for (id in page.loaded.keys.sortedBy { if (it == onScreen) 0 else 1 }) {
                    val chapter = currentPage.loaded[id] ?: continue
                    measured[id] = withContext(Dispatchers.Default) { measurer().chapter(rowsOf(chapter), sizes) }
                }
                measured.keys.retainAll(page.loaded.keys)
            }

            // The slider: a fraction of the chapter on screen.
            DisposableEffect(controller, pagerState) {
                controller.seek = seek@{ fraction ->
                    val id = currentId() ?: return@seek
                    val list = lists[id] ?: return@seek
                    val heights = measured[id] ?: return@seek
                    scope.launch {
                        val viewport = list.layoutInfo.viewportSize.height
                        val target = (fraction.coerceIn(0f, 1f) * maxOf(0, heights.total - viewport)).roundToInt()
                        val (row, within) = heights.rowAt(target)
                        list.scrollToItem(row, within)
                    }
                }
                onDispose { controller.seek = null }
            }

            HorizontalPager(
                state = pagerState,
                beyondViewportPageCount = 1,
                key = { index -> currentPage.chapterIds.getOrElse(index) { -index.toLong() - 1 } },
                modifier = Modifier.fillMaxSize(),
            ) { index ->
                val id = page.chapterIds.getOrNull(index) ?: return@HorizontalPager
                val chapter = page.loaded[id]
                val error = page.failed[id]
                when {
                    chapter != null ->
                        ChapterPage(
                            document = document,
                            chapter = chapter,
                            rows = rowsOf(chapter),
                            listState = listFor(id),
                            tracker = trackerFor(id),
                            current = pagerState.settledPage == index,
                            open = if (id == open.chapterId) open.position else null,
                            geometry = geometry,
                            layoutKey = layoutKey,
                            measurer = ::measurer,
                            heights = measured[id],
                            imageSizes = imageSizes,
                            // Room above the text for the find bar, while it's over the page.
                            insetTop = insetTop + topRoom,
                            // While listening, room below the text for the small player.
                            insetBottom = insetBottom + bottomRoom,
                            onEvent = { currentOnEvent(it) },
                            savedImage = savedImage,
                            marks = marks,
                            onHold = onHold,
                        )
                    error != null -> ErrorState(
                            message = userMessage(error),
                            onRetry = { onNeed(id) },
                            onOpenInBrowser = browserUrl(error)?.let { url -> { onOpenInBrowser(url) } },
                            browserLabel = browserLabel(error),
                        )
                    else -> {
                        LaunchedEffect(id) { onNeed(id) }
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = style.colors.text) }
                    }
                }
            }
        }
    }
}

/** Most of a screen, the way the volume keys move. */
private fun screenStep(list: LazyListState, forward: Boolean): Float {
    val info = list.layoutInfo
    val step = (info.viewportSize.height - info.beforeContentPadding - info.afterContentPadding) * SCREEN_STEP
    return if (forward) step else -step
}

/**
 * One chapter's page: its rows in a list that ends where the chapter does. Only the page on screen
 * reports; its position is kept through a change of text size or font.
 */
@OptIn(FlowPreview::class)
@Composable
private fun ChapterPage(
    document: Int,
    chapter: LoadedChapter,
    rows: ChapterRows,
    listState: LazyListState,
    tracker: PageTracker,
    current: Boolean,
    open: ReadingPosition?,
    geometry: RowGeometry,
    layoutKey: Any,
    measurer: () -> RowMeasurer,
    heights: ChapterHeights?,
    imageSizes: MutableMap<String, IntSize>,
    insetTop: Dp,
    insetBottom: Dp,
    onEvent: (ReaderEvent) -> Unit,
    savedImage: (chapterId: Long, name: String) -> File,
    marks: Marks,
    onHold: (TextHold) -> Unit,
) {
    val chapterId = chapter.chapter.id
    val currentFollow by rememberUpdatedState(marks.follow?.takeIf { it.chapterId == chapterId })
    val layouts = remember(chapterId) { HashMap<Int, TextLayoutResult>() }
    val currentRows by rememberUpdatedState(rows)
    val currentHeights by rememberUpdatedState(heights)
    val currentLayoutKey by rememberUpdatedState(layoutKey)
    val currentOnEvent by rememberUpdatedState(onEvent)

    // Opening: straight to where the reader was, before anything is reported. Other chapters start at the top.
    LaunchedEffect(chapterId) {
        if (!tracker.restored) {
            if (open != null) restore(listState, currentRows, open, geometry, measurer, tracker)
            tracker.laidOutFor = currentLayoutKey
            tracker.restored = true
        }
    }

    // The same words at the top after the text changes size, font or spacing: the place read under
    // the old layout, put back under the new one. Until then the list's own idea of where it is (the
    // old scroll offset, in rows of new heights) isn't taken for a place.
    LaunchedEffect(layoutKey) {
        if (!tracker.restored || tracker.laidOutFor == layoutKey) return@LaunchedEffect
        tracker.anchor?.let { restore(listState, currentRows, it, geometry, measurer, tracker) }
        tracker.laidOutFor = layoutKey
    }

    // Where the reader is, while this is the page on screen.
    LaunchedEffect(current, heights) {
        if (!current) return@LaunchedEffect
        snapshotFlow { listState.layoutInfo }.collect { info ->
            if (tracker.restored && tracker.laidOutFor == currentLayoutKey) tracker.track(info, currentRows, layouts, currentHeights, currentOnEvent, geometry)
        }
    }
    LaunchedEffect(current) {
        if (!current) return@LaunchedEffect
        tracker.positions.filterNotNull().debounce(POSITION_DELAY_MS).distinctUntilChanged().collect { position ->
            currentOnEvent(ReaderEvent.Position(document, chapterId, position.block, position.offset))
        }
    }

    // The line with what the page follows (the word being said, a search's match) kept in view, while this is the chapter on screen.
    LaunchedEffect(current) {
        if (!current) return@LaunchedEffect
        snapshotFlow { currentFollow?.let { it.leaf to it.at } }.filterNotNull().collect { (leaf, at) ->
            if (tracker.restored) follow(listState, currentRows, leaf, at, geometry, measurer, tracker)
        }
    }

    // A press slid near the screen's top or bottom scrolls the chapter on under it.
    val edgeScope = rememberCoroutineScope()
    val edgeStep = with(LocalDensity.current) { EDGE_STEP.toPx() }
    val edgeScroller = remember(listState, edgeScope, edgeStep) { ListEdgeScroller(listState, edgeScope, edgeStep) }
    LazyColumn(
        state = listState,
        contentPadding = PaddingValues(top = insetTop + TOP_SPACE, bottom = insetBottom + BOTTOM_SPACE),
        modifier = Modifier.fillMaxSize(),
    ) {
        itemsIndexed(rows.rows, key = { index, _ -> index }, contentType = { _, row -> row::class }) { index, row ->
            CompositionLocalProvider(LocalEdgeScroller provides edgeScroller) {
            ReaderRow(
                row = row,
                geometry = geometry,
                end = if (index == rows.rows.lastIndex) rows.end else null,
                imageModel = { src ->
                    val name = DownloadedImages.nameOf(src)
                    if (name in chapter.savedImages) savedImage(chapterId, name) else src
                },
                imageSizes = imageSizes,
                onTextLayout = { layouts[index] = it },
                marks = marks.inRow(chapterId, row, geometry.style),
                onHold = holdOn(chapterId, row, onHold),
                bookmarked = marks.isBookmarked(chapterId, row.leaf),
                onTap = tapOn(chapterId, row, marks, onHold),
                noted = marks.hasNote(chapterId, row.leaf),
                chapterId = chapterId,
            )
            }
        }
    }
}

/** Scrolls [list] on, a step a frame, while a long press rests near the screen's top or bottom. */
private class ListEdgeScroller(private val list: LazyListState, private val scope: CoroutineScope, private val step: Float) : EdgeScroller {
    private var going = 0
    private var job: Job? = null

    override fun scroll(direction: Int, onScrolled: () -> Unit) {
        if (direction == going) return
        going = direction
        job?.cancel()
        job =
            if (direction == 0) {
                null
            } else {
                scope.launch {
                    while (true) {
                        withFrameNanos {}
                        val moved = list.scrollBy(direction * step)
                        if (moved == 0f) break
                        onScrolled()
                    }
                }
            }
    }
}

/** Puts [position] at the top of the text: its line in its row, or the chapter's end. */
private suspend fun restore(
    list: LazyListState,
    rows: ChapterRows,
    position: ReadingPosition,
    geometry: RowGeometry,
    measurer: () -> RowMeasurer,
    tracker: PageTracker,
) {
    val leaves = rows.rows.maxOfOrNull { it.leaf + it.leafCount } ?: 0
    if (position.block >= leaves && leaves > 0) {
        toEnd(list, rows, geometry, tracker)
        return
    }
    if (position.block <= 0 && position.offset <= 0) {
        list.scrollToItem(0)
        return
    }
    val index = rows.rows.indexOfLast { it.leaf in 0..position.block }.coerceAtLeast(0)
    val row = rows.rows[index]
    var offset = tracker.px(geometry.contentTop(row))
    if (position.offset > 0 && (row is TextRow || row is TitleRow)) {
        measurer().layout(row)?.let { layout ->
            val line = layout.getLineForOffset(position.offset.coerceIn(0, layout.layoutInput.text.length))
            offset += layout.getLineTop(line).roundToInt()
        }
    }
    list.scrollToItem(index, offset)
}

/** Scrolls the line with character [at] of [leaf] into view, a quarter of the way down, unless it's in view already. */
private suspend fun follow(
    list: LazyListState,
    rows: ChapterRows,
    leaf: Int,
    at: Int,
    geometry: RowGeometry,
    measurer: () -> RowMeasurer,
    tracker: PageTracker,
) {
    val index = (if (leaf < 0) rows.rows.indexOfFirst { it is TitleRow } else rows.rows.indexOfLast { it.leaf in 0..leaf }).coerceAtLeast(0)
    val row = rows.rows.getOrNull(index) ?: return
    val top = tracker.px(geometry.contentTop(row))
    var lineTop = top
    var lineBottom = top
    if (row is TextRow || row is TitleRow) {
        measurer().layout(row)?.let { layout ->
            val line = layout.getLineForOffset(at.coerceIn(0, layout.layoutInput.text.length))
            lineTop = top + layout.getLineTop(line).roundToInt()
            lineBottom = top + layout.getLineBottom(line).roundToInt()
        }
    }
    val info = list.layoutInfo
    val shown = info.visibleItemsInfo.firstOrNull { it.index == index }
    val viewTop = info.viewportStartOffset + info.beforeContentPadding
    val viewBottom = info.viewportEndOffset - info.afterContentPadding
    if (shown != null && shown.offset + lineTop >= viewTop && shown.offset + lineBottom <= viewBottom) return
    list.animateScrollToItem(index, (lineTop - (viewBottom - viewTop) / 4).coerceAtLeast(0))
}

/** The end of the chapter at the bottom of the screen. */
private suspend fun toEnd(list: LazyListState, rows: ChapterRows, geometry: RowGeometry, tracker: PageTracker) {
    val last = rows.rows.lastIndex
    if (last < 0) return
    list.scrollToItem(last)
    val info = list.layoutInfo.visibleItemsInfo.firstOrNull { it.index == last } ?: return
    val end = tracker.px(geometry.style.ems(rows.end))
    list.scrollBy((info.offset + info.size - end - list.layoutInfo.viewportEndOffset).toFloat())
}

/** Follows one chapter's page for what to report, reporting each thing once when it changes. */
private class PageTracker(private val document: Int, private val chapterId: Long, private val density: Density) {
    var restored = false

    /** The layout (style and width) the page was last put in place for. */
    var laidOutFor: Any? = null

    /** The place at the top of the text, kept for putting it back after a change of style. */
    var anchor: ReadingPosition? = null
    val positions = MutableStateFlow<ReadingPosition?>(null)
    private var ended = false
    private var progressKey = ""

    fun px(value: Dp): Int = with(density) { value.toPx() }.roundToInt()

    /** Coming back on screen, the page says again where it is. */
    fun reset() {
        progressKey = ""
    }

    fun track(
        info: LazyListLayoutInfo,
        rows: ChapterRows,
        layouts: Map<Int, TextLayoutResult>,
        heights: ChapterHeights?,
        onEvent: (ReaderEvent) -> Unit,
        geometry: RowGeometry,
    ) {
        val top = info.visibleItemsInfo.firstOrNull { it.offset + it.size > READING_LINE } ?: info.visibleItemsInfo.lastOrNull() ?: return
        val row = rows.rows.getOrNull(top.index) ?: return
        val position = positionIn(top, row, layouts[top.index], geometry)
        anchor = position
        positions.value = position

        // The end of the chapter on screen: it's been read.
        info.visibleItemsInfo.firstOrNull { it.index == rows.rows.lastIndex }?.let { item ->
            val end = px(geometry.style.ems(rows.end))
            if (!ended && item.offset + item.size - end <= info.viewportEndOffset + END_SLACK) {
                ended = true
                onEvent(ReaderEvent.End(document, chapterId))
            }
        }

        // How far through the chapter, and how many screens it is.
        heights ?: return
        val y = heights.tops.getOrElse(top.index) { 0 } + (READING_LINE - top.offset)
        val viewport = info.viewportSize.height
        val max = heights.total - viewport
        val permille = if (max > 0) (y.toFloat() / max).coerceIn(0f, 1f).times(1000).roundToInt() else 1000
        val screens = screens(heights.total, viewport)
        val key = "$permille/$screens"
        if (key != progressKey) {
            progressKey = key
            onEvent(ReaderEvent.Progress(document, chapterId, permille, screens))
        }
    }

    /** The leaf and character at the reading line in [item]. */
    private fun positionIn(item: LazyListItemInfo, row: Row, layout: TextLayoutResult?, geometry: RowGeometry): ReadingPosition {
        if (row is TitleRow) return ReadingPosition(0, 0)
        if (row is NextRow) return ReadingPosition(row.leaf, row.at)
        val leaf = row.leaf.coerceAtLeast(0)
        if (row !is TextRow || layout == null) return ReadingPosition(leaf, 0)
        val y = READING_LINE - item.offset - px(geometry.contentTop(row))
        if (y <= 0) return ReadingPosition(leaf, 0)
        return ReadingPosition(leaf, layout.getOffsetForPosition(Offset(2f, y.toFloat())))
    }
}

/** How far below the top of the text the reading line is, in pixels. */
private const val READING_LINE = 2
private const val END_SLACK = 8
private const val POSITION_DELAY_MS = 250L
private const val SCREEN_STEP = 0.9f
private val TOP_SPACE = 16.dp
private val BOTTOM_SPACE = 56.dp

/** How far the text scrolls each frame while a press rests near the screen's top or bottom: about 400 dp a second. */
private val EDGE_STEP = 7.dp
