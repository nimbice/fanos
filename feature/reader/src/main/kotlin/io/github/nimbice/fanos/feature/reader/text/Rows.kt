package io.github.nimbice.fanos.feature.reader.text

import io.github.nimbice.fanos.core.content.Align
import io.github.nimbice.fanos.core.content.Block
import io.github.nimbice.fanos.core.content.ChapterContent
import io.github.nimbice.fanos.core.content.Span
import io.github.nimbice.fanos.core.content.TableCell
import io.github.nimbice.fanos.core.content.leaves

/**
 * A chapter as the rows of the reading list: one per leaf (a paragraph, heading, credit line, image,
 * scene break or preformatted text), the chapter's title when it doesn't open with its own, and a
 * table as one row. Quotes, boxes and lists get no rows of their own: every row inside one carries
 * it as a [Wrap], drawn around the row, so a long status box scrolls, and a reading position in it
 * is restored, by the line like any other text.
 *
 * Space between rows is set in ems of the text size, and collapses as CSS margins do: to the larger
 * of the two meeting, through quotes and lists but not through a box's border.
 */
internal data class ChapterRows(
    val rows: List<Row>,
    /** Space after the last row, before whatever follows the chapter, in ems. */
    val end: Float,
)

internal sealed interface Row {
    /** The leaf the row shows, its index in [ChapterContent.leaves]; -1 for the title. */
    val leaf: Int

    /** The containers around the row, outermost first. */
    val wraps: List<Wrap>

    /**
     * Space above the row in ems: outside the outermost quote or box the row opens, else right above
     * its content.
     */
    val gap: Float

    /** For a row that opens a box: space inside the box, above the content, in ems. */
    val boxGap: Float

    /** How many leaves the row holds. */
    val leafCount: Int get() = 1
}

internal sealed interface Wrap {
    /** Whether this row is the container's first, and last. */
    val first: Boolean
    val last: Boolean

    /** Drawn: margins stop outside it rather than running on into it. */
    val drawn: Boolean get() = this !is Item

    data class Quote(override val first: Boolean, override val last: Boolean) : Wrap

    /** A frame: LitRPG status windows, system messages, letters. */
    data class Box(override val first: Boolean, override val last: Boolean) : Wrap

    /** A list item, indented; [marker] (a bullet or number) goes beside its first row. */
    data class Item(val marker: String?, val depth: Int, override val first: Boolean, override val last: Boolean) : Wrap
}

internal enum class TextKind { Body, Heading1, Heading2, Heading3, Title, Pre, Credit }

/** The chapter's title, above a chapter that doesn't open with its own. */
internal data class TitleRow(val text: String, override val gap: Float) : Row {
    override val leaf get() = -1
    override val wraps get() = emptyList<Wrap>()
    override val boxGap get() = 0f
    override val leafCount get() = 0
}

internal data class TextRow(
    override val leaf: Int,
    val spans: List<Span>,
    val kind: TextKind,
    val align: Align,
    override val wraps: List<Wrap>,
    override val gap: Float,
    override val boxGap: Float = 0f,
) : Row {
    val text: String get() = spans.joinToString("") { it.text }
}

internal data class ImageRow(
    override val leaf: Int,
    val src: String,
    val alt: String?,
    override val wraps: List<Wrap>,
    override val gap: Float,
    override val boxGap: Float = 0f,
) : Row

internal data class BreakRow(override val leaf: Int, override val wraps: List<Wrap>, override val gap: Float, override val boxGap: Float = 0f) : Row

/**
 * Under a chapter's last lines: the next chapter, what it is and whether it's downloaded. It stands for the place after
 * all the text, character [at] of the last leaf, [leaf].
 */
internal data class NextRow(override val leaf: Int, val at: Int, override val gap: Float) : Row {
    override val wraps get() = emptyList<Wrap>()
    override val boxGap get() = 0f
    override val leafCount get() = 0
}

/** A whole table of short cells, as a grid; [leaf] is its first leaf, and it holds [leafCount]. */
internal data class TableRow(
    override val leaf: Int,
    override val leafCount: Int,
    val table: Block.Table,
    override val wraps: List<Wrap>,
    override val gap: Float,
    override val boxGap: Float = 0f,
) : Row

/**
 * The short cells of a stacked table's row on one line, "Blaze L1 · Skill": [parts] are their texts,
 * a leaf each, from [leaf] on. [lead]: they open the row, so the first is its name. [header]: the
 * table's row of column headings.
 */
internal data class TableLineRow(
    override val leaf: Int,
    val parts: List<List<Span>>,
    val lead: Boolean,
    val header: Boolean,
    override val wraps: List<Wrap>,
    override val gap: Float,
    override val boxGap: Float = 0f,
) : Row {
    override val leafCount get() = parts.size
}

internal object RowLayout {
    /** Font size of a kind of text, in ems of the text size. */
    fun scale(kind: TextKind): Float =
        when (kind) {
            TextKind.Body -> 1f
            TextKind.Heading1 -> 1.4f
            TextKind.Heading2 -> 1.25f
            TextKind.Heading3 -> 1.1f
            TextKind.Title -> 1.6f
            TextKind.Pre -> 0.88f
            TextKind.Credit -> 0.85f
        }

    /** Space between chapters, in ems. */
    const val CHAPTER_SPACE = 4f

    // Margins, in ems of the text size; the title's are ems of its own size, times that size.
    const val IMAGE_MARGIN = 1f
    const val BREAK_MARGIN = 1.6f
    const val TABLE_MARGIN = 1f
    const val QUOTE_MARGIN = 1f
    const val BOX_MARGIN = 1.2f
    const val ITEM_MARGIN = 0.2f

    /** Between a stacked table's rows, each a card of its own, and between the cells in one. */
    const val CARD_MARGIN = 0.5f
    const val CELL_GAP = 0.4f
    const val TITLE_TOP = 0.3f * 1.6f
    const val TITLE_BOTTOM = 1.3f * 1.6f

    fun rows(content: ChapterContent, title: String?, paragraphSpacing: Float): ChapterRows = Flattener(content, paragraphSpacing).run(title)

    private class Flattener(private val content: ChapterContent, private val paragraphSpacing: Float) {
        private val rows = ArrayList<Row>()
        private var leaf = 0

        // The margin waiting above the next row: bottom margins of what came before, collapsed with the
        // top margins of containers opened since.
        private var pending = 0f

        // Containers entered since the last row, outermost first, with their top margins: the next row
        // opens them.
        private val opening = ArrayList<Pair<Opened, Float>>()

        // Containers the next row is inside.
        private val stack = ArrayList<Opened>()

        private class Opened(val kind: Kind, val marker: String?, val depth: Int) {
            var rows = 0
        }

        private enum class Kind { Quote, Box, Item }

        fun run(title: String?): ChapterRows {
            if (!title.isNullOrBlank() && !content.hasOwnTitle) {
                rows += TitleRow(title.trim(), TITLE_TOP)
                pending = TITLE_BOTTOM
            }
            content.blocks.forEach { block(it) }
            return ChapterRows(rows, maxOf(pending, CHAPTER_SPACE))
        }

        private fun block(block: Block) {
            when (block) {
                is Block.Paragraph -> {
                    val title = leaf == content.titleLeaf
                    add(
                        top = if (title) TITLE_TOP else 0f,
                        bottom = if (title) TITLE_BOTTOM else paragraphSpacing,
                    ) { wraps, gap, boxGap ->
                        TextRow(leaf, block.spans, if (title) TextKind.Title else TextKind.Body, block.align, wraps, gap, boxGap)
                    }
                    leaf++
                }
                is Block.Heading -> {
                    val title = leaf == content.titleLeaf
                    val kind =
                        when {
                            title -> TextKind.Title
                            block.level <= 1 -> TextKind.Heading1
                            block.level == 2 -> TextKind.Heading2
                            else -> TextKind.Heading3
                        }
                    val scale = scale(kind)
                    add(
                        top = if (title) TITLE_TOP else 1.1f * scale,
                        bottom = if (title) TITLE_BOTTOM else 0.6f * scale,
                    ) { wraps, gap, boxGap -> TextRow(leaf, block.spans, kind, Align.Start, wraps, gap, boxGap) }
                    leaf++
                }
                is Block.Credit -> {
                    add(top = 0f, bottom = paragraphSpacing) { wraps, gap, boxGap ->
                        TextRow(leaf, block.spans, TextKind.Credit, block.align, wraps, gap, boxGap)
                    }
                    leaf++
                }
                is Block.Image -> {
                    add(IMAGE_MARGIN, IMAGE_MARGIN) { wraps, gap, boxGap -> ImageRow(leaf, block.src, block.alt, wraps, gap, boxGap) }
                    leaf++
                }
                Block.SceneBreak -> {
                    add(BREAK_MARGIN, BREAK_MARGIN) { wraps, gap, boxGap -> BreakRow(leaf, wraps, gap, boxGap) }
                    leaf++
                }
                is Block.Preformatted -> {
                    val margin = scale(TextKind.Pre)
                    add(margin, margin) { wraps, gap, boxGap -> TextRow(leaf, listOf(Span(block.text)), TextKind.Pre, Align.Start, wraps, gap, boxGap) }
                    leaf++
                }
                is Block.Table if block.stacks -> stacked(block)
                is Block.Table -> {
                    val count = block.rows.sumOf { cells -> cells.sumOf { cell -> cell.blocks.sumOf { it.leaves().size } } }
                    add(TABLE_MARGIN, TABLE_MARGIN) { wraps, gap, boxGap -> TableRow(leaf, count, block, wraps, gap, boxGap) }
                    leaf += count
                }
                is Block.Quote -> container(Kind.Quote, QUOTE_MARGIN, QUOTE_MARGIN) { block.blocks.forEach(::block) }
                is Block.Box -> container(Kind.Box, BOX_MARGIN, BOX_MARGIN) { block.blocks.forEach(::block) }
                is Block.ListBlock -> {
                    val depth = stack.count { it.kind == Kind.Item }
                    block.items.forEachIndexed { i, item ->
                        val marker = if (block.ordered) "${i + 1}." else BULLETS[depth % BULLETS.size]
                        container(Kind.Item, ITEM_MARGIN, ITEM_MARGIN, marker, depth) { item.forEach(::block) }
                    }
                    pending = maxOf(pending, paragraphSpacing)
                }
            }
        }

        /**
         * A table with prose in it, as the page lays it out: a card a row, the row's short cells on its
         * first line and the rest below at full width; the headings' row above the cards, plain.
         */
        private fun stacked(table: Block.Table) {
            pending = maxOf(pending, TABLE_MARGIN)
            for (cells in table.rows) {
                if (cells.isEmpty()) continue
                if (cells.all { it.header } && cells.all { it.isShort }) {
                    line(cells, lead = false, header = true)
                    continue
                }
                container(Kind.Box, CARD_MARGIN, CARD_MARGIN) {
                    var i = 0
                    while (i < cells.size) {
                        if (cells[i].isShort) {
                            val run = cells.drop(i).takeWhile { it.isShort }
                            line(run, lead = i == 0, header = false)
                            i += run.size
                        } else {
                            cells[i].blocks.forEach(::block)
                            i++
                        }
                        pending = CELL_GAP
                    }
                }
            }
            pending = maxOf(pending, TABLE_MARGIN)
        }

        /** Short cells, a leaf each, on one line. */
        private fun line(cells: List<TableCell>, lead: Boolean, header: Boolean) {
            val parts = cells.map { cell -> (cell.blocks.flatMap { it.leaves() }.single()).spans() }
            add(0f, 0f) { wraps, gap, boxGap -> TableLineRow(leaf, parts, lead, header, wraps, gap, boxGap) }
            leaf += parts.size
        }

        private fun Block.spans(): List<Span> =
            when (this) {
                is Block.Paragraph -> spans
                is Block.Heading -> spans
                is Block.Credit -> spans
                else -> emptyList()
            }

        private fun container(kind: Kind, top: Float, bottom: Float, marker: String? = null, depth: Int = 0, children: () -> Unit) {
            val opened = Opened(kind, marker, depth)
            opening += opened to top
            stack += opened
            children()
            // A container with no rows in it (an empty quote, say) leaves nothing, not even its margins.
            if (opened.rows == 0) {
                opening.removeAll { it.first === opened }
                stack.remove(opened)
                return
            }
            markLast(opened)
            stack.remove(opened)
            // Inside a quote or box the last child has no bottom margin: only the container's follows.
            pending = if (kind == Kind.Item) maxOf(pending, bottom) else bottom
        }

        /** Adds a row with [top] and [bottom] margins; [make] gets its wraps and gaps. */
        private fun add(top: Float, bottom: Float, make: (List<Wrap>, Float, Float) -> Row) {
            val opens = opening.map { it.first }.toSet()
            val firstDrawn = opening.firstOrNull { it.first.kind != Kind.Item }?.first
            val gap: Float
            val boxGap: Float
            if (firstDrawn == null) {
                // Nothing drawn opens here: every margin meets above the content.
                gap = opening.fold(pending) { acc, (_, margin) -> maxOf(acc, margin) }.let { maxOf(it, top) }
                boxGap = 0f
            } else {
                // Margins outside the first quote or box opened meet above it; a quote lets its
                // children's margins through, a box's border keeps them inside.
                val index = opening.indexOfFirst { it.first === firstDrawn }
                var outside = pending
                for (i in 0..index) outside = maxOf(outside, opening[i].second)
                var inside = 0f
                val boxAt = opening.indexOfFirst { it.first.kind == Kind.Box }
                for (i in index + 1 until opening.size) {
                    if (boxAt in 0 until i) inside = maxOf(inside, opening[i].second) else outside = maxOf(outside, opening[i].second)
                }
                if (boxAt >= 0) inside = maxOf(inside, top) else outside = maxOf(outside, top)
                gap = outside
                boxGap = inside
            }
            val wraps =
                stack.map { opened ->
                    val first = opened in opens
                    when (opened.kind) {
                        Kind.Quote -> Wrap.Quote(first, last = false)
                        Kind.Box -> Wrap.Box(first, last = false)
                        Kind.Item -> Wrap.Item(if (first) opened.marker else null, opened.depth, first, last = false)
                    }
                }
            stack.forEach { it.rows++ }
            opening.clear()
            rows += make(wraps, gap, boxGap)
            pending = bottom
        }

        /** Marks the last row inside [opened] as its last. */
        private fun markLast(opened: Opened) {
            val position = stack.indexOf(opened)
            val index = rows.indexOfLast { it.wraps.size > position }
            if (index < 0) return
            val row = rows[index]
            val wraps =
                row.wraps.mapIndexed { i, wrap ->
                    if (i != position) {
                        wrap
                    } else {
                        when (wrap) {
                            is Wrap.Quote -> wrap.copy(last = true)
                            is Wrap.Box -> wrap.copy(last = true)
                            is Wrap.Item -> wrap.copy(last = true)
                        }
                    }
                }
            rows[index] =
                when (row) {
                    is TextRow -> row.copy(wraps = wraps)
                    is ImageRow -> row.copy(wraps = wraps)
                    is BreakRow -> row.copy(wraps = wraps)
                    is TableRow -> row.copy(wraps = wraps)
                    is TableLineRow -> row.copy(wraps = wraps)
                    is TitleRow, is NextRow -> row
                }
        }

        private companion object {
            val BULLETS = listOf("•", "◦", "▪")
        }
    }
}

/**
 * How wide each column of a grid table is, relative to the others: as wide as its longest line of
 * text, within limits, roughly as a browser's automatic table layout shares the width out.
 */
internal fun Block.Table.columnWeights(): FloatArray {
    val weights = FloatArray(columns.coerceAtLeast(1)) { MIN_COLUMN }
    for (cells in rows) {
        var column = 0
        for (cell in cells) {
            val span = cell.colspan.coerceAtLeast(1)
            if (span == 1 && column < weights.size) {
                val longest = cell.text().lines().maxOfOrNull { it.length } ?: 0
                weights[column] = maxOf(weights[column], longest.toFloat().coerceIn(MIN_COLUMN, MAX_COLUMN))
            }
            column += span
        }
    }
    return weights
}

/** The share of the width a cell spanning [span] columns from [column] gets. */
internal fun FloatArray.span(column: Int, span: Int): Float = (column until minOf(size, column + span)).sumOf { this[it].toDouble() }.toFloat()

private const val MIN_COLUMN = 3f
private const val MAX_COLUMN = 40f
