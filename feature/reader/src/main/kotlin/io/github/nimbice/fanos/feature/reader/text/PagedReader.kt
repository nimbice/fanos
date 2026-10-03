package io.github.nimbice.fanos.feature.reader.text

import androidx.compose.animation.core.EaseOut
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsIgnoringVisibility
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsIgnoringVisibility
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFontFamilyResolver
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import io.github.nimbice.fanos.core.content.ChapterContent
import io.github.nimbice.fanos.core.data.browserLabel
import io.github.nimbice.fanos.core.data.browserUrl
import io.github.nimbice.fanos.core.data.download.DownloadedImages
import io.github.nimbice.fanos.core.data.userMessage
import io.github.nimbice.fanos.core.designsystem.component.ErrorState
import io.github.nimbice.fanos.core.model.ReaderSettings
import io.github.nimbice.fanos.feature.reader.LoadedChapter
import io.github.nimbice.fanos.feature.reader.ReaderCommand
import io.github.nimbice.fanos.feature.reader.ReaderController
import io.github.nimbice.fanos.feature.reader.ReaderEvent
import io.github.nimbice.fanos.feature.reader.ReaderPage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlinx.coroutines.flow.distinctUntilChanged
import io.github.nimbice.fanos.core.model.ReadingPosition
import kotlinx.coroutines.delay
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.animation.core.Animatable
import androidx.compose.ui.graphics.graphicsLayer
import io.github.nimbice.fanos.core.model.PageTurn
import io.github.nimbice.fanos.core.model.TapZones
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import android.os.SystemClock
import io.github.nimbice.fanos.feature.reader.AutoTurn
import androidx.compose.runtime.CompositionLocalProvider

/**
 * A turn in the reader's row of pages: page [index] of the [count] a chapter has, and with pages side by
 * side ([span] 2), the page after it too. A chapter not in pages yet (still loading, or failed) is one
 * stand-in, [count] 0, which takes the key of the turn it will become: its first when it comes after the
 * chapter being read, its last before it.
 */
private data class PageRef(val chapterId: Long, val index: Int, val count: Int, val key: String, val span: Int = 1) {
    /** The chapter's pages it shows. */
    val shown: IntRange get() = index until minOf(index + span, count)
}

/** A turn's key: turns of two pages are keyed apart from single pages, so neither is ever taken for the other. */
private fun keyOf(chapterId: Long, turn: Int, turns: Int, span: Int): String {
    val two = if (span == 2) "two:" else ""
    return when (turn) {
        0 -> "$chapterId:${two}0"
        turns - 1 -> "$chapterId:${two}end"
        else -> "$chapterId:$two$turn"
    }
}

/** What chapters are cut into pages for: the text's look, a page's size, and how many pages a turn shows. */
private data class PageLayout(val style: ReadingStyle, val pageWidth: Dp, val contentHeight: Dp, val span: Int)

/** A chapter cut into pages, and what it was cut for: when any of that changes, it's cut again. */
private class Paginated(val content: ChapterContent, val layout: PageLayout, val images: Int, val pages: ChapterPages)

/**
 * Page mode: the chapters cut into pages a screen tall and laid side by side in one row, so the last
 * page of a chapter turns to the first of the next like any other page. A swipe turns a page, and a
 * tap on either side of the screen does too; at the first and last page of the book a pull just
 * springs back. It reports the page ("page 3 of 12"), the position at its top, and a chapter's end
 * when its last page comes up. On a wide screen two pages show side by side, and a turn moves both.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun PagedReader(
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
    val currentFollow by rememberUpdatedState(marks.follow)
    val insetTop = with(density) { maxOf(WindowInsets.statusBarsIgnoringVisibility.getTop(this), WindowInsets.displayCutout.getTop(this)).toDp() }
    val insetBottom = with(density) { WindowInsets.navigationBarsIgnoringVisibility.getBottom(this).toDp() }
    // Sideways, the camera's cutout and the navigation bar when they're at a side (a phone turned on its side).
    val insetLeft =
        with(density) { maxOf(WindowInsets.displayCutout.getLeft(this, layoutDirection), WindowInsets.navigationBarsIgnoringVisibility.getLeft(this, layoutDirection)) }
    val insetRight =
        with(density) { maxOf(WindowInsets.displayCutout.getRight(this, layoutDirection), WindowInsets.navigationBarsIgnoringVisibility.getRight(this, layoutDirection)) }
    // Room above the text for a bar over it (the find bar), so nothing it marks is under that.
    val pageTop = insetTop + PAGE_PADDING + topRoom
    // While listening, room below the text for the small player, so no line is read from under it.
    val pageBottom = insetBottom + PAGE_PADDING + bottomRoom
    val document = page.documentId
    val currentOnEvent by rememberUpdatedState(onEvent)
    val currentSettings by rememberUpdatedState(settings)

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val contentHeight = maxHeight - pageTop - pageBottom
        val geometry = remember(style, contentHeight) { RowGeometry(style, imageMaxHeight = contentHeight * IMAGE_SHARE) }
        val pageHeight = with(density) { contentHeight.roundToPx() }.coerceAtLeast(1)
        // Two pages side by side when the screen is wide (a phone on its side, a tablet, a phone unfolded), a whole
        // number of pixels each, so each is laid out just as it was measured.
        val widthPx = constraints.maxWidth - insetLeft - insetRight
        val span = if (settings.twoPages && maxWidth > maxHeight && with(density) { widthPx.toDp() } >= TWO_PAGES_WIDTH) 2 else 1
        val pageWidth = with(density) { (if (span == 2) (widthPx - GUTTER.roundToPx()) / 2 else widthPx).coerceAtLeast(1).toDp() }
        val layout = PageLayout(style, pageWidth, contentHeight, span)
        val paginated = remember { mutableStateMapOf<Long, Paginated>() }
        val reading = remember { mutableLongStateOf(open.chapterId) }
        val images = imageSizes.size

        // The loaded chapters cut into pages, the one being read first; again when the text or the
        // screen changes, or an image turns out another size.
        LaunchedEffect(page.loaded, layout, images) {
            val sizes = imageSizes.toMap()
            for ((id, chapter) in page.loaded.entries.sortedBy { if (it.key == reading.longValue) 0 else 1 }) {
                val done = paginated[id]
                if (done != null && done.content === chapter.content && done.layout == layout && done.images == images) continue
                val pages =
                    withContext(Dispatchers.Default) {
                        val measurer = RowMeasurer(geometry, TextMeasurer(fontResolver, density, layoutDirection, 0), density, layout.pageWidth)
                        Paginator.paginate(measurer.spans(rowsOf(chapter), sizes), pageHeight)
                    }
                paginated[id] = Paginated(chapter.content, layout, images, pages)
            }
            paginated.keys.retainAll(page.loaded.keys)
        }

        // The row of pages: the loaded chapters' pages, with a stand-in for a chapter either side of
        // them, so turning on past the last loaded page shows it coming.
        val pages =
            run {
                val ids = page.chapterIds
                val loaded = page.loaded.keys.map { ids.indexOf(it) }.filter { it >= 0 }
                if (loaded.isEmpty()) return@run emptyList()
                val at = ids.indexOf(reading.longValue)
                buildList {
                    for (i in (loaded.min() - 1).coerceAtLeast(0)..(loaded.max() + 1).coerceAtMost(ids.lastIndex)) {
                        val id = ids[i]
                        val done = paginated[id]
                        if (done != null) {
                            // In turns as it was cut for: pages cut for one screen keep their turns until cut again.
                            val count = done.pages.count
                            val by = done.layout.span
                            val turns = (count + by - 1) / by
                            for (t in 0 until turns) add(PageRef(id, t * by, count, keyOf(id, t, turns, by), by))
                        } else {
                            add(PageRef(id, -1, 0, keyOf(id, if (i < at) 1 else 0, 2, span)))
                        }
                    }
                }
            }
        val currentPages by rememberUpdatedState(pages)
        val openPages = paginated[open.chapterId]?.pages

        if (openPages == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = style.colors.text) }
            return@BoxWithConstraints
        }

        // Not saved with the activity, as the pager's own state would be: the screen turning cuts the pages
        // afresh, and the page number it saved would be another page. The place it opens at is saved instead.
        val pagerState =
            remember {
                PagerState(currentPage = pages.indexOfFirst { it.chapterId == open.chapterId && openPages.pageOf(open.position) in it.shown }.coerceAtLeast(0)) {
                    currentPages.size
                }
            }
        val scope = rememberCoroutineScope()
        val focus = remember { FocusRequester() }
        val anchor = remember { arrayOfNulls<Place>(1) }
        val settledKey = remember { arrayOfNulls<String>(1) }
        val announced = remember { arrayOfNulls<Long>(1) }
        val keepPlace = remember { booleanArrayOf(false) }
        val opened = remember { booleanArrayOf(false) }

        // Page turns under way from taps or volume keys, and the page the last of them is heading for.
        // While one is under way the pager can't be dragged: a touch would catch the sliding page and
        // stop it, and the tap meant to turn the next page would be lost.
        var turns by remember { mutableIntStateOf(0) }
        val turningTo = remember { intArrayOf(0) }
        // The page's fade, for pages that turn by fading.
        val shown = remember { Animatable(1f) }

        /** Turns [delta] pages on from the page being turned to, so taps in quick succession each turn one. */
        fun turn(delta: Int) {
            val target = (if (turns > 0) turningTo[0] else pagerState.currentPage) + delta
            if (target !in 0 until pagerState.pageCount) return
            turningTo[0] = target
            turns++
            scope.launch {
                try {
                    // The pager is locked on the next frame; the page moves only once it is, or a tap
                    // landing in between would still catch it.
                    withFrameNanos {}
                    when (currentSettings.pageTurn) {
                        PageTurn.Slide -> pagerState.slideTo(target)
                        PageTurn.Instant -> pagerState.scrollToPage(target)
                        PageTurn.Fade -> {
                            shown.animateTo(0f, FADE_OUT)
                            pagerState.scrollToPage(target)
                            shown.animateTo(1f, FADE_IN)
                        }
                    }
                } finally {
                    turns--
                    // A fade cut short by the next turn leaves the page showing all the same.
                    if (turns == 0 && shown.value < 1f) scope.launch { shown.snapTo(1f) }
                }
            }
        }

        // For a long press held at the screen's side: the page turns, and the words chosen run on over it.
        val turnNow by rememberUpdatedState<(Int) -> Unit> { delta -> turn(delta) }
        val pageTurner = remember { { delta: Int -> turnNow(delta) } }

        /**
         * Tells of the page [ref]: the chapter (said on opening too, as the controls take their
         * chapter from it), the page number, and when the reader [turned] to it, where they are and
         * whether the chapter's end has come.
         */
        fun report(ref: PageRef?, turned: Boolean) {
            if (ref == null || ref.count == 0) return
            settledKey[0] = ref.key
            reading.longValue = ref.chapterId
            if (ref.chapterId != announced[0]) {
                announced[0] = ref.chapterId
                currentOnEvent(ReaderEvent.ChapterChanged(document, ref.chapterId))
            }
            currentOnEvent(ReaderEvent.Page(document, ref.chapterId, ref.index, ref.count, last = ref.shown.last))
            if (!turned) return
            val cut = paginated[ref.chapterId]?.pages ?: return
            // The page it opens on: the place it opened at, which is on it, rather than the page's top. Turning
            // the screen and back (the pages cut afresh each time) then comes back to the same page, not one before.
            val opening = !opened[0] && ref.chapterId == open.chapterId && cut.pageOf(open.position) in ref.shown
            opened[0] = true
            val start = if (opening) open.position else cut.pages.getOrNull(ref.index)?.start ?: return
            anchor[0] = Place(ref.chapterId, start)
            currentOnEvent(ReaderEvent.Position(document, ref.chapterId, start.block, start.offset))
            if (ref.shown.last == ref.count - 1) currentOnEvent(ReaderEvent.End(document, ref.chapterId))
        }

        // The page settled on. A page put in front after the text was cut again isn't a turn: the
        // place stays where the reader was, or each change of text size would walk them back to the
        // top of a page a little earlier.
        LaunchedEffect(pagerState) {
            snapshotFlow { pagerState.settledPage }.collect { index ->
                val ref = currentPages.getOrNull(index)
                // Nor is the same page under another number, as when a chapter before it is cut into pages.
                val turned = !keepPlace[0] && (ref == null || ref.key != settledKey[0])
                keepPlace[0] = false
                report(ref, turned)
            }
        }

        // The row of pages changing under the reader. The chapter being read cut again (another text
        // size, say): the words they were at stay in front of them, on whichever page they now fall.
        // Pages coming and going around theirs (a neighbour cut, a far chapter let go): the same page.
        LaunchedEffect(pagerState) {
            var seenCut: ChapterPages? = null
            snapshotFlow { currentPages }.collect { list ->
                val cut = paginated[reading.longValue]?.pages
                val place = anchor[0]
                val recut = cut != null && seenCut != null && cut !== seenCut && place != null && place.chapterId == reading.longValue
                val target =
                    if (recut) {
                        list.indexOfFirst { it.chapterId == place.chapterId && cut.pageOf(place.position) in it.shown }
                    } else {
                        settledKey[0]?.let { key -> list.indexOfFirst { it.key == key } } ?: -1
                    }
                seenCut = cut ?: seenCut
                if (target >= 0 && target != pagerState.settledPage && !pagerState.isScrollInProgress) {
                    keepPlace[0] = true
                    pagerState.scrollToPage(target)
                } else {
                    // The page count may have changed all the same.
                    report(list.getOrNull(pagerState.settledPage), turned = false)
                }
            }
        }

        // The chapter buttons turn to a chapter's first page, as a page turn would.
        LaunchedEffect(pagerState) {
            commands.collect { command ->
                when (command) {
                    is ReaderCommand.GoToChapter -> {
                        val target = currentPages.indexOfFirst { it.chapterId == command.chapterId }
                        if (target >= 0) pagerState.animateScrollToPage(target)
                    }
                }
            }
        }

        // The page follows what it's asked to (the word being said, a search's match), turning when that moves on to another
        // page (the next chapter's too). A page turned back by hand to look at something stays until then.
        LaunchedEffect(pagerState) {
            snapshotFlow {
                val said = currentFollow ?: return@snapshotFlow null
                val cut = paginated[said.chapterId]?.pages ?: return@snapshotFlow null
                val at = cut.pageOf(ReadingPosition(said.leaf.coerceAtLeast(0), if (said.leaf < 0) 0 else said.at))
                currentPages.indexOfFirst { it.chapterId == said.chapterId && at in it.shown }.takeIf { it >= 0 }
            }.distinctUntilChanged().collect { target ->
                if (target != null && target != pagerState.currentPage && turns == 0) pagerState.animateScrollToPage(target)
            }
        }

        // Auto-scroll: a page turned on a timer, from a page turned by hand too; the bar shows the time going.
        LaunchedEffect(autoScroll, pagerState.settledPage) {
            val auto = autoScroll
            if (auto == null) {
                controller.autoTurn.value = null
                return@LaunchedEffect
            }
            val millis = (auto.secondsPerPage * 1000).toLong()
            controller.autoTurn.value = AutoTurn(SystemClock.uptimeMillis(), millis)
            delay(millis)
            // Told to stop at the chapter's end: the last page's time up, no turning into the next.
            val here = currentPages.getOrNull(pagerState.currentPage)
            val next = currentPages.getOrNull(pagerState.currentPage + 1)
            if (auto.stopAtChapterEnd && here != null && next?.chapterId != here.chapterId) {
                currentOnEvent(ReaderEvent.AutoScrollEnded(document))
            } else {
                turn(1)
            }
        }
        DisposableEffect(controller) { onDispose { controller.autoTurn.value = null } }

        // The slider: a page of the chapter being read.
        DisposableEffect(controller, pagerState) {
            controller.goToPage = { target ->
                val index = currentPages.indexOfFirst { it.chapterId == reading.longValue && target in it.shown }
                if (index >= 0) scope.launch { pagerState.scrollToPage(index) }
            }
            onDispose { controller.goToPage = null }
        }

        LaunchedEffect(Unit) { focus.requestFocus() }

        val edges = systemEdges()

        Box(
            Modifier
                .fillMaxSize()
                .focusRequester(focus)
                .focusable()
                .onKeyEvent { event ->
                    if (!currentSettings.volumeKeysTurnPages || (event.key != Key.VolumeDown && event.key != Key.VolumeUp)) return@onKeyEvent false
                    if (event.type == KeyEventType.KeyDown) turn(if (event.key == Key.VolumeDown) 1 else -1)
                    true
                }
                // Pulling the system bars down (or up) turns no page.
                .systemSwipesIgnored(edges, sidewaysOnly = true)
                // Pages that fade or don't move don't follow the finger either: a swipe turns the page as it lifts.
                .pointerInput(document, settings.pageTurn) {
                    if (settings.pageTurn == PageTurn.Slide) return@pointerInput
                    var dragged = 0f
                    detectHorizontalDragGestures(
                        onDragStart = { dragged = 0f },
                        onDragEnd = {
                            when {
                                dragged <= -SWIPE.toPx() -> turn(1)
                                dragged >= SWIPE.toPx() -> turn(-1)
                            }
                        },
                        onHorizontalDrag = { change, amount ->
                            dragged += amount
                            change.consume()
                        },
                    )
                }
                // A tap on either third turns a page, as the tap zones have it; in the middle it shows the controls.
                .pointerInput(document) {
                    detectTaps { at ->
                        val zones = currentSettings.tapZones
                        when {
                            at.x < size.width / 3f -> turn(if (zones == TapZones.BackOn) -1 else 1)
                            at.x > size.width * 2 / 3f -> turn(if (zones == TapZones.OnBack) -1 else 1)
                            else -> currentOnEvent(ReaderEvent.CenterTap(document))
                        }
                    }
                },
        ) {
            HorizontalPager(
                state = pagerState,
                beyondViewportPageCount = 1,
                userScrollEnabled = turns == 0 && settings.pageTurn == PageTurn.Slide,
                key = { index -> currentPages.getOrNull(index)?.key ?: "gone:$index" },
                modifier = Modifier.fillMaxSize().graphicsLayer { alpha = shown.value },
            ) { index ->
                val ref = currentPages.getOrNull(index) ?: return@HorizontalPager
                val chapter = page.loaded[ref.chapterId]
                val done = paginated[ref.chapterId]
                val cut = done?.pages
                val error = page.failed[ref.chapterId]
                when {
                    ref.count > 0 && chapter != null && cut != null ->
                        Row(Modifier.fillMaxSize().padding(start = with(density) { insetLeft.toDp() }, end = with(density) { insetRight.toDp() })) {
                            for (index in ref.shown) {
                                if (index > ref.index) {
                                    // Between two pages, a hairline down the middle.
                                    Box(Modifier.width(GUTTER).fillMaxHeight().padding(top = pageTop, bottom = pageBottom), contentAlignment = Alignment.Center) {
                                        Box(Modifier.width(1.dp).fillMaxHeight().background(style.colors.rule))
                                    }
                                }
                                CompositionLocalProvider(LocalPageTurner provides pageTurner) {
                                    PageView(
                                        chapter, rowsOf(chapter), cut.pages[index], geometry, imageSizes, savedImage, pageTop, pageBottom, marks, onHold,
                                        Modifier.width(done.layout.pageWidth).fillMaxHeight(),
                                    )
                                }
                            }
                        }
                    error != null -> ErrorState(
                            message = userMessage(error),
                            onRetry = { onNeed(ref.chapterId) },
                            onOpenInBrowser = browserUrl(error)?.let { url -> { onOpenInBrowser(url) } },
                            browserLabel = browserLabel(error),
                        )
                    else -> {
                        LaunchedEffect(ref.chapterId) { onNeed(ref.chapterId) }
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = style.colors.text) }
                    }
                }
            }
        }
    }
}

/**
 * One page: the slices of rows on it, each row drawn whole and cut to its slice, so a paragraph that
 * runs over from the page before starts at the line it was cut at.
 */
@Composable
private fun PageView(
    chapter: LoadedChapter,
    rows: ChapterRows,
    page: Page,
    geometry: RowGeometry,
    imageSizes: SnapshotStateMap<String, IntSize>,
    savedImage: (chapterId: Long, name: String) -> File,
    pageTop: androidx.compose.ui.unit.Dp,
    pageBottom: androidx.compose.ui.unit.Dp,
    marks: Marks,
    onHold: (TextHold) -> Unit,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val chapterId = chapter.chapter.id
    Column(modifier.padding(top = pageTop, bottom = pageBottom)) {
        for (slice in page.slices) {
            val row = rows.rows.getOrNull(slice.row) ?: continue
            Box(Modifier.fillMaxWidth().height(with(density) { slice.height.toDp() }).clipToBounds()) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .wrapContentHeight(align = Alignment.Top, unbounded = true)
                        .offset { IntOffset(0, -slice.from) },
                ) {
                    ReaderRow(
                        row = row,
                        geometry = geometry,
                        end = null,
                        imageModel = { src ->
                            val name = DownloadedImages.nameOf(src)
                            if (name in chapter.savedImages) savedImage(chapterId, name) else src
                        },
                        imageSizes = imageSizes,
                        onTextLayout = {},
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
}

/** Space between the system bars and the text, above and below it. */
private val PAGE_PADDING = 16.dp

/** How much of a page an image may fill. */
private const val IMAGE_SHARE = 0.9f

/** The narrowest screen two pages show side by side on: each at least as wide as a phone's page. */
private val TWO_PAGES_WIDTH = 600.dp

/** How far a swipe goes to turn a page that doesn't slide. */
private val SWIPE = 40.dp

/** Between two pages side by side, besides their margins. */
private val GUTTER = 16.dp

/**
 * Slides to [page] from wherever the pager is, part way through another slide too. (The pager's own
 * animateScrollToPage jumps ahead when the page is a few away, and does nothing when asked for the
 * page it happens to be passing, which lost quick taps.)
 */
private suspend fun PagerState.slideTo(page: Int) {
    scroll {
        val distance = (page - currentPage - currentPageOffsetFraction) * (layoutInfo.pageSize + layoutInfo.pageSpacing)
        var moved = 0f
        animate(0f, distance, animationSpec = TURN) { value, _ -> moved += scrollBy(value - moved) }
    }
}

/** A page turned by a tap or a volume key: a quick slide, so the next page is there as soon as it's asked for. */
private val TURN = tween<Float>(durationMillis = 140, easing = EaseOut)

/** A page turned by fading: the page going, quickly, then the next coming. */
private val FADE_OUT = tween<Float>(durationMillis = 80)
private val FADE_IN = tween<Float>(durationMillis = 140)
