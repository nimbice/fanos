package io.github.nimbice.fanos.feature.reader.text

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import io.github.nimbice.fanos.core.content.Align
import io.github.nimbice.fanos.core.content.Block
import io.github.nimbice.fanos.core.content.leaves
import io.github.nimbice.fanos.core.content.text
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.graphics.Color
import androidx.compose.material3.Icon
import io.github.nimbice.fanos.core.designsystem.icon.ReaderIcons
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.findRootCoordinates
import androidx.compose.ui.layout.LocalPinnableContainer
import androidx.compose.ui.layout.PinnableContainer
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.luminance

/**
 * Where things sit in a row, for drawing it and for measuring it without drawing: the two must agree,
 * or restoring a position, the slider and the page count would land beside what is on screen.
 */
internal class RowGeometry(
    val style: ReadingStyle,
    /** In page mode, the tallest an image may be: a page's height, less a little room. */
    val imageMaxHeight: Dp? = null,
) {
    private val em = style.em

    val quoteRule: Dp = 3.dp
    val border: Dp = 1.dp
    val boxPadding: Dp = em * 0.8f
    val boxSide: Dp = em
    val quoteIndent: Dp = em
    val itemIndent: Dp = em * 1.4f
    val preTop: Dp = em * (0.6f * 0.88f)
    val preSide: Dp = em * (0.8f * 0.88f)

    /** Inside a grid table's cell, around the text. */
    val cellPadH: Dp = em * (0.5f * TABLE_SCALE)
    val cellPadV: Dp = em * (0.3f * TABLE_SCALE)

    /** Below a title: the space above its rule, and the rule. */
    val titleRuleSpace: Dp = em * (0.5f * 1.6f)
    val titleRule: Dp = 3.dp
    val titleRuleWidth: Dp = em * (2.5f * 1.6f)

    fun insetStart(wraps: List<Wrap>): Dp =
        wraps.fold(0.dp) { acc, wrap ->
            acc +
                when (wrap) {
                    is Wrap.Quote -> quoteRule + quoteIndent
                    is Wrap.Box -> border + boxSide
                    is Wrap.Item -> itemIndent
                }
        }

    fun insetEnd(wraps: List<Wrap>): Dp = wraps.fold(0.dp) { acc, wrap -> if (wrap is Wrap.Box) acc + border + boxSide else acc }

    /** The width the row's content has, in a list [width] wide. */
    fun contentWidth(row: Row, width: Dp): Dp = width - style.margin * 2 - insetStart(row.wraps) - insetEnd(row.wraps)

    /** From the top of the row to the top of its content. */
    fun contentTop(row: Row): Dp {
        val boxes = row.wraps.count { it is Wrap.Box && it.first }
        return style.ems(row.gap) + (border + boxPadding) * boxes + (if (boxes > 0) style.ems(row.boxGap) else 0.dp) +
            (if (row is TextRow && row.kind == TextKind.Pre) preTop else 0.dp)
    }

    /** From the bottom of the row's content to the bottom of the row; [end] is the chapter's space after its last row. */
    fun contentBottom(row: Row, end: Float?): Dp {
        val boxes = row.wraps.count { it is Wrap.Box && it.last }
        val below =
            when {
                row is TitleRow || (row is TextRow && row.kind == TextKind.Title) -> titleRuleSpace + titleRule
                row is TextRow && row.kind == TextKind.Pre -> preTop
                else -> 0.dp
            }
        return below + (border + boxPadding) * boxes + (end?.let { style.ems(it) } ?: 0.dp)
    }

    /** Wraps the gap goes before: the first quote or box the row opens, or the first list item it is in. */
    fun gapAt(row: Row): Int = row.wraps.indexOfFirst { it is Wrap.Item || (it.first && it.drawn) }

    /** Whether the row's gap is the first thing in it, above any frame, so a page can leave it out. */
    fun gapOnTop(row: Row): Boolean = gapAt(row).let { it == 0 || (it < 0 && row.wraps.isEmpty()) }

    /**
     * How big an image shows in a space [width] wide: its own size (an image pixel to a dp, as the
     * page drew them) up to the width, and in page mode no taller than a page. Until it arrives, a
     * frame of a likely shape.
     */
    fun imageSize(known: IntSize?, width: Dp): DpSize {
        val ratio = if (known != null && known.width > 0 && known.height > 0) known.width.toFloat() / known.height else PLACEHOLDER_RATIO
        var shownWidth = if (known != null && known.width > 0 && known.height > 0) minOf(known.width.dp, width) else width
        imageMaxHeight?.let { max -> if (shownWidth / ratio > max) shownWidth = max * ratio }
        return DpSize(shownWidth, shownWidth / ratio)
    }
}

/**
 * One row of the reading list. [end] is set on a chapter's last row: the space before the next
 * chapter. [imageModel] gives what to load an image from (a saved copy, or its address). [marks] are
 * stretches of the row's text marked, with their colours (the sentence being read aloud, a search's
 * matches); [onHold] takes a long press on the text, with the character pressed and where on the screen.
 */
@Composable
internal fun ReaderRow(
    row: Row,
    geometry: RowGeometry,
    end: Float?,
    imageModel: (String) -> Any,
    imageSizes: MutableMap<String, IntSize>,
    onTextLayout: (TextLayoutResult) -> Unit,
    marks: List<Pair<IntRange, Color>> = emptyList(),
    onHold: ((Press) -> Unit)? = null,
    bookmarked: Boolean = false,
    onTap: ((offset: Int, line: androidx.compose.ui.geometry.Rect) -> Boolean)? = null,
    noted: Boolean = false,
    chapterId: Long? = null,
) {
    val style = geometry.style
    // The next chapter's card stands for no paragraph: no bookmark or note shows by it.
    val marked = row !is NextRow
    Box(Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth().padding(horizontal = style.margin)) {
            Framed(row, 0, geometry) { RowContent(row, geometry, imageModel, imageSizes, onTextLayout, marks, onHold, onTap, chapterId?.let { it to row.leaf }) }
            end?.let { Spacer(Modifier.height(style.ems(it))) }
        }
        // A pencil in the other margin beside a paragraph with a note.
        if (noted && marked) {
            Icon(
                ReaderIcons.Note,
                contentDescription = "Has a note",
                tint = style.colors.link,
                modifier =
                    Modifier
                        .align(Alignment.TopEnd)
                        .padding(end = ((style.margin - RIBBON) / 2).coerceAtLeast(0.dp), top = geometry.contentTop(row))
                        .size(RIBBON),
            )
        }
        // A ribbon in the margin beside a bookmarked paragraph, over nothing the row lays out.
        if (bookmarked && marked) {
            Icon(
                ReaderIcons.Bookmark,
                contentDescription = "Bookmarked",
                tint = style.colors.link,
                modifier = Modifier.padding(start = ((style.margin - RIBBON) / 2).coerceAtLeast(0.dp), top = geometry.contentTop(row)).size(RIBBON),
            )
        }
    }
}

/** The size of a bookmarked paragraph's ribbon. */
private val RIBBON = 14.dp

@Composable
private fun Framed(row: Row, index: Int, geometry: RowGeometry, content: @Composable () -> Unit) {
    val style = geometry.style
    val gapAt = geometry.gapAt(row)
    if (index == gapAt) Spacer(Modifier.height(style.ems(row.gap)))
    if (index == row.wraps.size) {
        if (gapAt < 0) Spacer(Modifier.height(style.ems(row.gap)))
        content()
        return
    }
    val colors = style.colors
    when (val wrap = row.wraps[index]) {
        is Wrap.Quote ->
            Column(
                Modifier
                    .fillMaxWidth()
                    .drawBehind { drawRect(colors.rule, size = Size(geometry.quoteRule.toPx(), size.height)) }
                    .padding(start = geometry.quoteRule + geometry.quoteIndent),
            ) { Framed(row, index + 1, geometry, content) }
        is Wrap.Box -> {
            val opensHere = row.wraps.indexOfFirst { it is Wrap.Box && it.first } == index
            Column(
                Modifier
                    .fillMaxWidth()
                    .boxFrame(wrap.first, wrap.last, geometry)
                    .padding(
                        start = geometry.border + geometry.boxSide,
                        end = geometry.border + geometry.boxSide,
                        top = if (wrap.first) geometry.border + geometry.boxPadding else 0.dp,
                        bottom = if (wrap.last) geometry.border + geometry.boxPadding else 0.dp,
                    ),
            ) {
                if (opensHere) Spacer(Modifier.height(style.ems(row.boxGap)))
                Framed(row, index + 1, geometry, content)
            }
        }
        is Wrap.Item ->
            Row(Modifier.fillMaxWidth()) {
                Box(Modifier.width(geometry.itemIndent)) {
                    if (wrap.marker != null) Text(wrap.marker, style = style.marker, modifier = Modifier.fillMaxWidth().padding(end = style.em * 0.4f))
                }
                Column(Modifier.weight(1f)) { Framed(row, index + 1, geometry, content) }
            }
    }
}

/**
 * A box's frame on one of its rows: background, sides, and the top and bottom edges on its first and
 * last rows, so rows stacked up draw one box. Each row draws a taller frame and keeps its own slice.
 */
private fun Modifier.boxFrame(first: Boolean, last: Boolean, geometry: RowGeometry): Modifier =
    clipToBounds().drawBehind {
        val colors = geometry.style.colors
        val radius = 6.dp.toPx()
        val stroke = geometry.border.toPx()
        val top = if (first) 0f else -radius * 2
        val bottom = if (last) size.height else size.height + radius * 2
        drawRoundRect(colors.boxBackground, Offset(0f, top), Size(size.width, bottom - top), CornerRadius(radius))
        drawRoundRect(
            colors.boxBorder,
            Offset(stroke / 2, top + stroke / 2),
            Size(size.width - stroke, bottom - top - stroke),
            CornerRadius(radius),
            style = Stroke(stroke),
        )
    }

@Composable
private fun RowContent(
    row: Row,
    geometry: RowGeometry,
    imageModel: (String) -> Any,
    imageSizes: MutableMap<String, IntSize>,
    onTextLayout: (TextLayoutResult) -> Unit,
    marks: List<Pair<IntRange, Color>>,
    onHold: ((Press) -> Unit)?,
    onTap: ((Int, androidx.compose.ui.geometry.Rect) -> Boolean)?,
    shownAs: Pair<Long, Int>? = null,
) {
    val style = geometry.style
    when (row) {
        is TitleRow -> Title(row.text, Align.Start, geometry, onTextLayout, marks = marks, onHold = onHold, onTap = onTap, shownAs = shownAs)
        is TextRow ->
            when (row.kind) {
                TextKind.Title -> Title(row.text, row.align, geometry, onTextLayout, row, marks, onHold, onTap, shownAs)
                TextKind.Pre ->
                    Text(
                        row.text,
                        style = style.text(TextKind.Pre),
                        onTextLayout = onTextLayout,
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .background(style.colors.boxBackground, RoundedCornerShape(4.dp))
                                .padding(horizontal = geometry.preSide, vertical = geometry.preTop),
                    )
                else -> {
                    val text = remember(row, style) { style.annotated(row.spans) }
                    ReadableText(text.marked(marks), style.text(row.kind, row.align), onTextLayout, onHold, onTap, shownAs)
                }
            }
        is TableLineRow -> {
            val text = remember(row, style) { style.tableLine(row) }
            Text(text, style = style.tableLineStyle(row.header), onTextLayout = onTextLayout, modifier = Modifier.fillMaxWidth())
        }
        is BreakRow -> Text(SCENE_BREAK, style = style.sceneBreak, modifier = Modifier.fillMaxWidth())
        is NextRow -> NextChapterCard(shownAs?.first?.let { LocalNextChapter.current(it) }, geometry.style)
        is ImageRow -> RowImage(row, geometry, imageModel, imageSizes)
        is TableRow -> RowTable(row.table, geometry)
    }
}

@Composable
private fun Title(
    text: String,
    align: Align,
    geometry: RowGeometry,
    onTextLayout: (TextLayoutResult) -> Unit,
    row: TextRow? = null,
    marks: List<Pair<IntRange, Color>> = emptyList(),
    onHold: ((Press) -> Unit)? = null,
    onTap: ((Int, androidx.compose.ui.geometry.Rect) -> Boolean)? = null,
    shownAs: Pair<Long, Int>? = null,
) {
    val style = geometry.style
    val annotated = remember(row, text, style) { if (row != null) style.annotated(row.spans) else AnnotatedString(text) }
    Column(Modifier.fillMaxWidth()) {
        ReadableText(annotated.marked(marks), style.text(TextKind.Title, align), onTextLayout, onHold, onTap, shownAs)
        Spacer(Modifier.height(geometry.titleRuleSpace))
        val placement =
            when (align) {
                Align.Center -> Alignment.CenterHorizontally
                Align.End -> Alignment.End
                else -> Alignment.Start
            }
        Box(
            Modifier
                .align(placement)
                .width(geometry.titleRuleWidth)
                .height(geometry.titleRule)
                .background(style.colors.link.copy(alpha = style.colors.link.alpha * 0.75f), RoundedCornerShape(2.dp)),
        )
    }
}

/**
 * A row's text, taking a long press when [onHold] is given: the character under the finger, the one it slides to, and
 * where those are on the screen. [onTap] has taps too, and takes those it answers true to from the page. [shownAs] is
 * the chapter and leaf it shows, so a press begun in another paragraph can slide on into it.
 */
@Composable
private fun ReadableText(
    text: AnnotatedString,
    style: TextStyle,
    onTextLayout: (TextLayoutResult) -> Unit,
    onHold: ((Press) -> Unit)?,
    onTap: ((Int, androidx.compose.ui.geometry.Rect) -> Boolean)? = null,
    shownAs: Pair<Long, Int>? = null,
) {
    val layout = remember { arrayOfNulls<TextLayoutResult>(1) }
    val placed = remember { arrayOfNulls<LayoutCoordinates>(1) }
    val currentOnHold by rememberUpdatedState(onHold)
    val currentOnTap by rememberUpdatedState(onTap)
    val texts = LocalShownTexts.current
    val currentShownAs by rememberUpdatedState(shownAs)
    val currentTurner by rememberUpdatedState(LocalPageTurner.current)
    val currentScroller by rememberUpdatedState(LocalEdgeScroller.current)
    val currentChoosing by rememberUpdatedState(LocalChoosing.current)
    // Its list item (or page) kept while a press begun here goes on, should it scroll or turn away.
    val currentPinnable by rememberUpdatedState(LocalPinnableContainer.current)
    if (texts != null && shownAs != null) {
        DisposableEffect(texts, shownAs) {
            val entry = ShownText(shownAs.first, shownAs.second, { layout[0] }, { placed[0] })
            texts.add(entry)
            onDispose { texts.remove(entry) }
        }
    }
    Text(
        text,
        style = style,
        onTextLayout = {
            layout[0] = it
            onTextLayout(it)
        },
        modifier =
            Modifier
                .fillMaxWidth()
                .onGloballyPositioned { placed[0] = it }
                .then(
                    if (onHold == null) {
                        Modifier
                    } else {
                        Modifier.pointerInput(Unit) {
                            // The character pressed and the one the finger has slid to: the words between are chosen. -1 while
                            // nothing is pressed.
                            var start = -1
                            var end = -1
                            // The paragraph the finger is in, when it has slid out of this one into another.
                            var endLeaf: Int? = null
                            var first = androidx.compose.ui.geometry.Rect.Zero
                            var lines = first
                            // Where it was pressed; whether the finger has slid off from there since (only then does holding it at the
                            // screen's side turn the page); and whether a page has turned under it (the menu then keeps to the page shown).
                            var pressedAt = Offset.Zero
                            var slid = false
                            var turned = false
                            // Where the finger is on the screen, for following it as the text scrolls under it.
                            var fingerAt = Offset.Zero
                            var pinned: PinnableContainer.PinnedHandle? = null

                            // The finger at [at]: the character it's on, maybe in another paragraph, told on when that's somewhere new.
                            fun follow(at: Offset) {
                                    val laidOut = layout[0]
                                    val where = placed[0]
                                    if (start >= 0 && laidOut != null && where != null) {
                                        val window = where.localToWindow(at)
                                        val shown = where.boundsInWindow()
                                        val book = currentShownAs
                                        // Off this paragraph, the finger is on another of the chapter's on the screen, or between two.
                                        val hit =
                                            if ((window.y >= shown.top && window.y <= shown.bottom) || book == null || texts == null) {
                                                Hit(null, laidOut.getOffsetForPosition(at), lineAt(laidOut, where, at))
                                            } else {
                                                texts.at(book.first, window)?.let { if (it.leaf == book.second) Hit(null, it.offset, it.line) else it }
                                            }
                                        if (hit != null && (hit.leaf != endLeaf || hit.offset != end)) {
                                            end = hit.offset
                                            endLeaf = hit.leaf
                                            val line = hit.line
                                            lines = if (turned) line else androidx.compose.ui.geometry.Rect(line.left, minOf(first.top, line.top), line.left, maxOf(first.bottom, line.bottom))
                                            currentOnHold?.invoke(Press(start, end, lines, done = false, endLeaf = endLeaf))
                                        }
                                    }
                            }
                            detectHold(
                                onHold = { at ->
                                    val laidOut = layout[0] ?: return@detectHold
                                    val where = placed[0] ?: return@detectHold
                                    start = laidOut.getOffsetForPosition(at)
                                    end = start
                                    endLeaf = null
                                    first = lineAt(laidOut, where, at)
                                    lines = first
                                    pressedAt = at
                                    slid = false
                                    turned = false
                                    fingerAt = where.localToWindow(at)
                                    pinned = currentPinnable?.pin()
                                    currentOnHold?.invoke(Press(start, end, lines, done = false))
                                },
                                onMove = move@{ at ->
                                    if (!currentChoosing.drag) return@move
                                    if ((at - pressedAt).getDistance() > SLID.toPx()) slid = true
                                    placed[0]?.let { fingerAt = it.localToWindow(at) }
                                    follow(at)
                                    // In scroll mode, near the screen's top or bottom after sliding there: the text scrolls on under the
                                    // finger, and the choosing follows it.
                                    val where = placed[0]
                                    val scroller = currentScroller
                                    if (start >= 0 && where != null && scroller != null) {
                                        val height = where.findRootCoordinates().size.height
                                        val zone = EDGE_ZONE.toPx()
                                        val direction =
                                            when {
                                                !slid -> 0
                                                fingerAt.y > height - zone -> 1
                                                fingerAt.y < zone -> -1
                                                else -> 0
                                            }
                                        // Scrolled, the lines first pressed have moved: the menu then keeps to the finger's.
                                        if (direction != 0) turned = true
                                        scroller.scroll(direction) { placed[0]?.let { now -> follow(now.windowToLocal(fingerAt)) } }
                                    }
                                },
                                // Held still at the screen's side, after sliding there: the page turns, and the choosing goes on over it.
                                // The finger is taken where it is on the screen: the text it pressed may have moved off with a page turned.
                                onStill = still@{
                                    if (!currentChoosing.drag) return@still
                                    val where = placed[0]
                                    where?.let { follow(it.windowToLocal(fingerAt)) }
                                    val turnPage = currentTurner
                                    if (start >= 0 && slid && where != null && turnPage != null) {
                                        val x = fingerAt.x
                                        val width = where.findRootCoordinates().size.width
                                        val side = SIDE.toPx()
                                        when {
                                            x > width - side -> {
                                                turned = true
                                                turnPage(1)
                                            }
                                            x < side -> {
                                                turned = true
                                                turnPage(-1)
                                            }
                                        }
                                    }
                                },
                                onRelease = {
                                    currentScroller?.scroll(0) {}
                                    pinned?.release()
                                    pinned = null
                                    if (start >= 0) currentOnHold?.invoke(Press(start, end, lines, done = true, endLeaf = endLeaf))
                                    start = -1
                                },
                                onTap = { at ->
                                    val laidOut = layout[0]
                                    val where = placed[0]
                                    val tap = currentOnTap
                                    currentChoosing.tapHighlights && laidOut != null && where != null && tap != null && tap(laidOut.getOffsetForPosition(at), lineAt(laidOut, where, at))
                                },
                            )
                        }
                    },
                ),
    )
}

/** A row's text on the screen: the chapter and leaf it shows, and how and where it's laid out (null until it is). */
internal class ShownText(val chapterId: Long, val leaf: Int, val layout: () -> TextLayoutResult?, val where: () -> LayoutCoordinates?)

/** Under a finger: character [offset] of leaf [leaf] (null for the one pressed), on [line]. */
internal class Hit(val leaf: Int?, val offset: Int, val line: androidx.compose.ui.geometry.Rect)

/** The rows of text on the screen, so a long press begun in one paragraph can slide on into the next. Main thread only. */
internal class ShownTexts {
    private val shown = ArrayList<ShownText>()

    fun add(text: ShownText) {
        shown += text
    }

    fun remove(text: ShownText) {
        shown -= text
    }

    /** Where on the screen the caret before character [offset] of paragraph [leaf] of chapter [chapterId] is; null when it isn't showing. */
    fun caret(chapterId: Long, leaf: Int, offset: Int): androidx.compose.ui.geometry.Rect? {
        for (text in shown) {
            if (text.chapterId != chapterId || text.leaf != leaf) continue
            val where = text.where()?.takeIf { it.isAttached } ?: continue
            val layout = text.layout() ?: continue
            val bounds = where.boundsInWindow()
            if (bounds.width <= 0f || bounds.height <= 0f) continue
            val cursor = layout.getCursorRect(offset.coerceIn(0, layout.layoutInput.text.length))
            val top = where.localToWindow(cursor.topLeft)
            val bottom = where.localToWindow(cursor.bottomLeft)
            // Only on the part of it showing: a page shows a slice of a paragraph begun or ended on another.
            if (top.y < bounds.top - 1f || bottom.y > bounds.bottom + 1f || top.x < bounds.left - 1f || top.x > bounds.right + 1f) continue
            return androidx.compose.ui.geometry.Rect(top.x, top.y, top.x, bottom.y)
        }
        return null
    }

    /** The paragraph of chapter [chapterId] under [window] on the screen, and its character there; null when there's none. */
    fun at(chapterId: Long, window: Offset): Hit? {
        for (text in shown) {
            if (text.chapterId != chapterId || text.leaf < 0) continue
            val where = text.where()?.takeIf { it.isAttached } ?: continue
            val layout = text.layout() ?: continue
            val bounds = where.boundsInWindow()
            if (bounds.width <= 0f || bounds.height <= 0f) continue
            // Its own part of the screen: a page beside this one is a screen's width away, past the slack.
            if (window.y < bounds.top || window.y > bounds.bottom || window.x < bounds.left - SIDE_SLACK || window.x > bounds.right + SIDE_SLACK) continue
            val local = where.windowToLocal(window)
            return Hit(text.leaf, layout.getOffsetForPosition(local), lineAt(layout, where, local))
        }
        return null
    }

    private companion object {
        const val SIDE_SLACK = 200f
    }
}

/** What choosing text may do, as the reader has it: slide on to more words ([drag]), and open a highlight's menu on a tap. */
internal data class Choosing(val drag: Boolean = true, val tapHighlights: Boolean = true)

internal val LocalChoosing = staticCompositionLocalOf { Choosing() }

/** The chapter after one: its title (null at the latest chapter), whether it's on this device, and its reading time when known. */
internal data class NextChapter(val title: String?, val downloaded: Boolean, val minutes: Int?)

/** Opens a picture full screen, from what the page loaded it from and its description; null where pictures don't open. */
internal val LocalOpenPicture = compositionLocalOf<((Any, String?) -> Unit)?> { null }

/** The chapter after the one given, for the card under a chapter's last lines. */
internal val LocalNextChapter = compositionLocalOf<(Long) -> NextChapter?> { { null } }

/** How tall the next chapter's card is, in ems of the text. */
internal const val NEXT_ROW_EMS = 5.2f

/** How strongly the next chapter's card is tinted with the accent colour, on a light page and a dark one. */
private const val LIGHT_TINT = 0.08f
private const val DARK_TINT = 0.1f

/**
 * Under a chapter's last lines, a card of its own tinted with the page's accent colour, so it doesn't read as more of the
 * chapter: what comes next, whether it's downloaded and about how long it takes to read; or, at the latest chapter, that
 * it is.
 */
@Composable
private fun NextChapterCard(next: NextChapter?, style: ReadingStyle) {
    if (next == null) return
    val size = style.settings.fontSize
    val muted = style.colors.text.copy(alpha = 0.62f)
    val tint = style.colors.link.copy(alpha = if (style.colors.background.luminance() < 0.5f) DARK_TINT else LIGHT_TINT)
    Box(Modifier.fillMaxWidth().height(style.ems(NEXT_ROW_EMS))) {
        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(tint)
                .padding(horizontal = style.ems(0.85f), vertical = style.ems(0.75f)),
        ) {
            Text(
                if (next.title == null) "UP TO DATE" else "NEXT",
                style = TextStyle(fontFamily = style.family, fontSize = (size * 0.6f).sp, letterSpacing = (size * 0.06f).sp, color = style.colors.link),
            )
            Text(
                next.title ?: "That\u2019s the latest chapter",
                style = TextStyle(fontFamily = style.family, fontSize = (size * 0.92f).sp, fontWeight = FontWeight.Medium, color = style.colors.text),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = style.ems(0.2f)),
            )
            if (next.title != null) {
                val time = next.minutes?.let { " \u00b7 about ${minutesText(it)}" }.orEmpty()
                Text(
                    if (next.downloaded) "Downloaded$time" else "On the site \u00b7 loads as you go on",
                    style = TextStyle(fontFamily = style.family, fontSize = (size * 0.7f).sp, color = muted),
                    modifier = Modifier.padding(top = style.ems(0.25f)),
                )
            }
        }
    }
}

/** A reading time in words: "12 min", "1 h 5 min". */
private fun minutesText(minutes: Int): String = if (minutes < 60) "$minutes min" else "${minutes / 60} h" + if (minutes % 60 > 0) " ${minutes % 60} min" else ""

/** Turns the page by so many, in page mode, for a long press held at the screen's side; null in scroll mode. */
internal val LocalPageTurner = staticCompositionLocalOf<((Int) -> Unit)?> { null }

/**
 * Scrolls the text on while a long press is near the screen's top or bottom, in scroll mode: [scroll] with direction 1
 * goes on down the chapter, -1 back up and 0 stops, calling [onScrolled] after each step.
 */
internal fun interface EdgeScroller {
    fun scroll(direction: Int, onScrolled: () -> Unit)
}

/** The text's scroller for presses near the screen's top or bottom; null in page mode. */
internal val LocalEdgeScroller = staticCompositionLocalOf<EdgeScroller?> { null }

/** How near the screen's top or bottom a press, slid there, scrolls the text. */
private val EDGE_ZONE = 72.dp

/** How far the finger slides from where it pressed before holding it at the screen's side turns the page. */
private val SLID = 24.dp

/** How near the screen's side a held finger turns the page. */
private val SIDE = 36.dp

/** The rows of text on the screen, for presses that run on from one paragraph into another; none outside the reader. */
internal val LocalShownTexts = staticCompositionLocalOf<ShownTexts?> { null }

/** The line of [layout] at [at], on the screen: from its top to its bottom, at [at]'s x. */
private fun lineAt(layout: TextLayoutResult, where: LayoutCoordinates, at: Offset): androidx.compose.ui.geometry.Rect {
    val line = layout.getLineForVerticalPosition(at.y)
    val top = where.localToWindow(Offset(at.x, layout.getLineTop(line)))
    val bottom = where.localToWindow(Offset(at.x, layout.getLineBottom(line)))
    return androidx.compose.ui.geometry.Rect(top.x, top.y, top.x, bottom.y)
}

/** The text with each of [marks] behind its stretch, later ones over earlier; the layout is the same either way. */
private fun AnnotatedString.marked(marks: List<Pair<IntRange, Color>>): AnnotatedString {
    if (marks.isEmpty()) return this
    return AnnotatedString.Builder(this).apply {
        for ((range, color) in marks) {
            val start = range.first.coerceIn(0, length)
            val end = (range.last + 1).coerceIn(start, length)
            if (end > start) addStyle(SpanStyle(background = color), start, end)
        }
    }.toAnnotatedString()
}

/**
 * An image at its own size (an image pixel to a dp, as the page draws them) up to the width of the
 * text; until it arrives, a frame of a likely size keeps the text below from jumping far.
 */
@Composable
private fun RowImage(row: ImageRow, geometry: RowGeometry, imageModel: (String) -> Any, imageSizes: MutableMap<String, IntSize>) {
    val known = imageSizes[row.src]
    val open = LocalOpenPicture.current
    val model = remember(row.src) { imageModel(row.src) }
    BoxWithConstraints(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        // A tap opens it full screen (and turns no page).
        val modifier =
            Modifier
                .size(geometry.imageSize(known, maxWidth))
                .then(if (open == null) Modifier else Modifier.pointerInput(model) { detectTapGestures(onTap = { open(model, row.alt) }) })
        AsyncImage(
            model = model,
            contentDescription = row.alt,
            contentScale = ContentScale.Fit,
            onSuccess = { state ->
                val size = state.painter.intrinsicSize
                if (size.width > 0 && size.height > 0) imageSizes[row.src] = IntSize(size.width.toInt(), size.height.toInt())
            },
            modifier = modifier,
        )
    }
}

/**
 * A grid table: cells side by side, each column as wide as its text asks (see [columnWeights]), a
 * row as tall as its tallest cell.
 */
@Composable
private fun RowTable(table: Block.Table, geometry: RowGeometry) {
    val style = geometry.style
    val rule = style.colors.rule
    val weights = remember(table) { table.columnWeights() }
    Column(Modifier.fillMaxWidth().drawBehind { drawRect(rule, style = Stroke(1.dp.toPx())) }) {
        for (cells in table.rows) {
            Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
                var column = 0
                for (cell in cells) {
                    val span = cell.colspan.coerceAtLeast(1)
                    Column(
                        Modifier
                            .weight(weights.span(column, span).coerceAtLeast(0.01f))
                            .fillMaxHeight()
                            .drawBehind { drawRect(rule, style = Stroke(1.dp.toPx())) }
                            .padding(horizontal = geometry.cellPadH, vertical = geometry.cellPadV),
                    ) {
                        val text = remember(cell, style) { style.cellText(cell.blocks) }
                        Text(text, style = style.cell(cell.header))
                    }
                    column += span
                }
                // A row shorter than the table is wide leaves the rest of it empty.
                if (column < weights.size) Spacer(Modifier.weight(weights.span(column, weights.size - column)))
            }
        }
    }
}

private const val SCENE_BREAK = "⁂"

/** Width over height of the frame an image takes until it arrives. */
internal const val PLACEHOLDER_RATIO = 1.5f
