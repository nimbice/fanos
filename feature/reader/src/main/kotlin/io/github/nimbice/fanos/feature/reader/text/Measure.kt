package io.github.nimbice.fanos.feature.reader.text

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import io.github.nimbice.fanos.core.model.ReadingPosition
import kotlin.math.ceil
import kotlin.math.roundToInt

/**
 * A chapter's rows measured the way the list lays them out, without drawing them: how tall each is,
 * for where the reader is in the chapter (the slider, "page 3 of 12") and for going to a place in
 * it. Images that haven't arrived are counted at the size of their frame.
 */
internal class ChapterHeights(val heights: IntArray) {
    /** The top of each row, from the top of the chapter; one more for the chapter's bottom. */
    val tops: IntArray = IntArray(heights.size + 1).also { tops -> heights.forEachIndexed { i, h -> tops[i + 1] = tops[i] + h } }

    val total: Int get() = tops.last()

    /** The row [y] falls in, and how far into it. */
    fun rowAt(y: Int): Pair<Int, Int> {
        if (heights.isEmpty()) return 0 to 0
        val clamped = y.coerceIn(0, maxOf(0, total - 1))
        var low = 0
        var high = heights.size - 1
        while (low < high) {
            val mid = (low + high + 1) / 2
            if (tops[mid] <= clamped) low = mid else high = mid - 1
        }
        return low to clamped - tops[low]
    }
}

internal class RowMeasurer(
    private val geometry: RowGeometry,
    private val measurer: TextMeasurer,
    private val density: Density,
    /** The list's width. */
    private val width: Dp,
) {
    private val style get() = geometry.style

    fun chapter(rows: ChapterRows, imageSizes: Map<String, IntSize>): ChapterHeights =
        ChapterHeights(IntArray(rows.rows.size) { i -> height(rows.rows[i], if (i == rows.rows.lastIndex) rows.end else null, imageSizes) })

    /** The row's height in pixels, [end] being the space after it when it ends its chapter. */
    fun height(row: Row, end: Float?, imageSizes: Map<String, IntSize>): Int {
        val outside = geometry.contentTop(row) + geometry.contentBottom(row, end)
        return px(outside) + content(row, imageSizes)
    }

    /** The text of a text row as laid out on screen, for finding a line in it. */
    fun layout(row: Row): TextLayoutResult? {
        val width = geometry.contentWidth(row, width)
        return when (row) {
            is TitleRow -> measure(AnnotatedString(row.text), row, width)
            is TextRow -> measure(if (row.kind == TextKind.Pre) AnnotatedString(row.text) else style.annotated(row.spans), row, width)
            is TableLineRow ->
                measurer.measure(style.tableLine(row), style.tableLineStyle(row.header), constraints = Constraints(maxWidth = px(width).coerceAtLeast(1)), density = density)
            else -> null
        }
    }

    private fun measure(text: AnnotatedString, row: Row, width: Dp): TextLayoutResult {
        val kind = (row as? TextRow)?.kind ?: TextKind.Title
        val align = (row as? TextRow)?.align ?: io.github.nimbice.fanos.core.content.Align.Start
        val textWidth = if (kind == TextKind.Pre) width - geometry.preSide * 2 else width
        return measurer.measure(text, style.text(kind, align), constraints = Constraints(maxWidth = px(textWidth).coerceAtLeast(1)), density = density)
    }

    private fun content(row: Row, imageSizes: Map<String, IntSize>): Int {
        val width = geometry.contentWidth(row, width)
        return when (row) {
            is TitleRow, is TextRow, is TableLineRow -> checkNotNull(layout(row)).size.height
            is BreakRow -> measurer.measure("⁂", style.sceneBreak, density = density).size.height
            is ImageRow -> px(geometry.imageSize(imageSizes[row.src], width).height)
            is TableRow -> tableRows(row, width).sum()
            is NextRow -> px(style.ems(NEXT_ROW_EMS))
        }
    }

    /** The heights of a grid table's rows. */
    private fun tableRows(row: TableRow, width: Dp): List<Int> {
        val weights = row.table.columnWeights()
        val total = weights.sum().coerceAtLeast(0.01f)
        return row.table.rows.map { cells ->
            var column = 0
            val tallest =
                cells.maxOfOrNull { cell ->
                    val span = cell.colspan.coerceAtLeast(1)
                    val cellWidth = width * (weights.span(column, span) / total) - geometry.cellPadH * 2
                    column += span
                    measurer.measure(style.cellText(cell.blocks), style.cell(cell.header), constraints = Constraints(maxWidth = px(cellWidth).coerceAtLeast(1)), density = density)
                        .size.height
                } ?: 0
            tallest + px(geometry.cellPadV * 2)
        }
    }

    /**
     * The chapter's rows as page mode cuts them (see [Paginator]): each row's height without the
     * space scroll mode leaves after a chapter, where a page may end in it (between its lines, or a
     * table's rows), and what reading position a page starting part way down it starts at.
     */
    fun spans(rows: ChapterRows, imageSizes: Map<String, IntSize>): List<RowSpan> =
        rows.rows.map { row ->
            val top = px(geometry.contentTop(row))
            val height = height(row, null, imageSizes)
            val layout = layout(row)
            val breaks =
                when {
                    layout != null -> IntArray(maxOf(0, layout.lineCount - 1)) { line -> top + layout.getLineBottom(line).roundToInt() }
                    row is TableRow ->
                        tableRows(row, geometry.contentWidth(row, width)).runningFold(top) { at, h -> at + h }.drop(1).dropLast(1).toIntArray()
                    else -> IntArray(0)
                }
            val leaf = row.leaf.coerceAtLeast(0)
            RowSpan(
                height = height,
                breaks = breaks,
                skipTop = if (geometry.gapOnTop(row)) px(geometry.style.ems(row.gap)) else 0,
                keepWithNext = row is TitleRow || (row is TextRow && row.kind in HEADINGS),
                positionAt = { y ->
                    if (row is NextRow) {
                        ReadingPosition(row.leaf, row.at)
                    } else if (layout == null || row is TitleRow || row is TableLineRow || y <= top) {
                        ReadingPosition(leaf, 0)
                    } else {
                        ReadingPosition(leaf, layout.getOffsetForPosition(Offset(1f, (y - top + 1).toFloat())))
                    }
                },
            )
        }

    private fun px(value: Dp): Int = with(density) { value.toPx() }.roundToInt()

    private companion object {
        val HEADINGS = setOf(TextKind.Heading1, TextKind.Heading2, TextKind.Heading3, TextKind.Title)
    }
}

/** How many screens the chapter spans, as scroll mode counts its pages. */
internal fun screens(total: Int, viewport: Int): Int = if (viewport <= 0) 1 else maxOf(1, ceil(total.toDouble() / viewport).toInt())
