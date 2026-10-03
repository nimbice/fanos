package io.github.nimbice.fanos.feature.reader.text

import io.github.nimbice.fanos.core.model.ReadingPosition

/** The part of a row a page shows: from [from] to [to], in pixels down from the row's top. */
internal data class Slice(val row: Int, val from: Int, val to: Int) {
    val height: Int get() = to - from
}

/** A page: the slices of rows on it, top to bottom, and the reading position it starts at. */
internal data class Page(val slices: List<Slice>, val start: ReadingPosition)

/** A chapter cut into pages. */
internal class ChapterPages(val pages: List<Page>) {
    val count: Int get() = pages.size

    /** The page [position] is on: the last one starting at or before it. */
    fun pageOf(position: ReadingPosition): Int {
        val index = pages.indexOfLast { it.start <= position }
        return index.coerceAtLeast(0)
    }
}

internal operator fun ReadingPosition.compareTo(other: ReadingPosition): Int =
    compareValuesBy(this, other, { it.block }, { it.offset })

/**
 * What cutting a chapter into pages needs to know of a row: how tall it is, where between its lines
 * (or a table's rows) a page may end, how much of its top is space a page can do without, and where
 * in the text a page starting part way down it starts.
 */
internal class RowSpan(
    val height: Int,
    /** Where a page may end inside the row, ascending, in pixels from its top. */
    val breaks: IntArray = IntArray(0),
    /** Space at the row's top that isn't drawn at the top of a page: the gap above a paragraph. */
    val skipTop: Int = 0,
    /** A heading: it isn't left alone at the foot of a page, away from what it heads. */
    val keepWithNext: Boolean = false,
    /** The reading position at [y] down the row. */
    val positionAt: (y: Int) -> ReadingPosition,
)

/**
 * Cuts rows into pages [pageHeight] tall, as a book is set: a page ends between lines, never
 * through one; a paragraph too long for what's left of a page runs on to the next; a heading goes
 * over with the text it heads; the space above a paragraph isn't kept at the top of a page. What
 * can't be cut and is taller than a page (a huge image, a single line taller than the page) is cut
 * where it must be.
 */
internal object Paginator {

    fun paginate(rows: List<RowSpan>, pageHeight: Int): ChapterPages {
        require(pageHeight > 0) { "A page needs some height" }
        val pages = ArrayList<Page>()
        var slices = ArrayList<Slice>()
        var used = 0

        fun finish() {
            if (slices.isEmpty()) return
            // A heading at the foot of a page goes over to the next with the text it heads.
            val last = slices.last()
            val carried =
                if (slices.size > 1 && rows[last.row].keepWithNext && last.from == 0 && last.to == rows[last.row].height) slices.removeAt(slices.lastIndex) else null
            val first = slices.first()
            pages += Page(slices, rows[first.row].positionAt(first.from))
            slices = ArrayList()
            used = 0
            if (carried != null) {
                val start = rows[carried.row].skipTop
                slices += Slice(carried.row, start, carried.to)
                used = carried.to - start
            }
        }

        rows.forEachIndexed { index, row ->
            // The space above a row that starts a page (other than the chapter's first) is dropped.
            var from = if (used == 0 && index > 0) row.skipTop.coerceAtMost(row.height) else 0
            while (from < row.height) {
                val room = pageHeight - used
                if (row.height - from <= room) {
                    slices += Slice(index, from, row.height)
                    used += row.height - from
                    break
                }
                // The lowest break that leaves what's above it on this page, and no line on its own
                // on either side of it (unless on a page of its own there's no helping it).
                val below = row.breaks.filter { it > from }
                val fits = below.filter { it - from <= room }
                val cut =
                    fits.lastOrNull { at ->
                        val above = below.indexOf(at) + 1
                        above >= MIN_LINES && below.size + 1 - above >= MIN_LINES
                    } ?: fits.lastOrNull().takeIf { used == 0 }
                when {
                    cut != null -> {
                        slices += Slice(index, from, cut)
                        finish()
                        from = cut
                    }
                    used > 0 -> {
                        // Nothing of it fits here: it goes on the next page, starting it unless a
                        // heading came over with it.
                        finish()
                        if (from == 0 && used == 0) from = row.skipTop.coerceAtMost(row.height)
                    }
                    else -> {
                        // Taller than a whole page with nowhere to break: cut it where the page ends.
                        slices += Slice(index, from, from + pageHeight)
                        finish()
                        from += pageHeight
                    }
                }
            }
        }
        finish()
        if (pages.isEmpty()) pages += Page(emptyList(), ReadingPosition.Start)
        return ChapterPages(pages)
    }

    /** The fewest lines of a paragraph left at the foot of a page, or carried over to the next. */
    private const val MIN_LINES = 2
}
