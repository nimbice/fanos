package io.github.nimbice.fanos.feature.reader.text

import io.github.nimbice.fanos.core.model.ReadingPosition
import org.junit.Test
import kotlin.test.assertEquals

class PaginatorTest {

    /** A paragraph [lines] lines of [line] pixels, under [gap] pixels of space; its position is the line a y falls in. */
    private fun paragraph(leaf: Int, lines: Int, line: Int = 10, gap: Int = 0, heading: Boolean = false) =
        RowSpan(
            height = gap + lines * line,
            breaks = IntArray(lines - 1) { gap + (it + 1) * line },
            skipTop = gap,
            keepWithNext = heading,
            positionAt = { y -> ReadingPosition(leaf, ((y - gap).coerceAtLeast(0) / line) * 100) },
        )

    private fun block(leaf: Int, height: Int, gap: Int = 0) = RowSpan(height, skipTop = gap, positionAt = { ReadingPosition(leaf, 0) })

    @Test
    fun `rows that fit share a page`() {
        val pages = Paginator.paginate(listOf(paragraph(0, 3), paragraph(1, 4, gap = 5)), pageHeight = 100)
        assertEquals(1, pages.count)
        assertEquals(listOf(Slice(0, 0, 30), Slice(1, 0, 45)), pages.pages[0].slices)
    }

    @Test
    fun `a paragraph runs on to the next page, cut between lines`() {
        val pages = Paginator.paginate(listOf(paragraph(0, 5), paragraph(1, 10, gap = 5)), pageHeight = 100)
        assertEquals(2, pages.count)
        // 50 + 5 of space leaves room for four lines of the second paragraph.
        assertEquals(listOf(Slice(0, 0, 50), Slice(1, 0, 45)), pages.pages[0].slices)
        assertEquals(listOf(Slice(1, 45, 105)), pages.pages[1].slices)
        assertEquals(ReadingPosition(1, 400), pages.pages[1].start)
    }

    @Test
    fun `no line of a paragraph is left on its own at either side of a page break`() {
        // Five lines fit after the first paragraph, of six: one would go over alone, so two do.
        val alone = Paginator.paginate(listOf(paragraph(0, 5), paragraph(1, 6)), pageHeight = 100)
        assertEquals(listOf(Slice(0, 0, 50), Slice(1, 0, 40)), alone.pages[0].slices)
        assertEquals(listOf(Slice(1, 40, 60)), alone.pages[1].slices)
        // One line fits of three: rather than leave it alone at the foot, the paragraph goes over whole.
        val orphan = Paginator.paginate(listOf(paragraph(0, 9), paragraph(1, 3)), pageHeight = 100)
        assertEquals(listOf(Slice(0, 0, 90)), orphan.pages[0].slices)
        assertEquals(listOf(Slice(1, 0, 30)), orphan.pages[1].slices)
    }

    @Test
    fun `a row that doesn't fit starts the next page, without the space above it`() {
        val pages = Paginator.paginate(listOf(paragraph(0, 9), block(1, 40, gap = 8)), pageHeight = 100)
        assertEquals(listOf(Slice(0, 0, 90)), pages.pages[0].slices)
        assertEquals(listOf(Slice(1, 8, 40)), pages.pages[1].slices)
    }

    @Test
    fun `a heading goes over with the text it heads`() {
        val pages = Paginator.paginate(listOf(paragraph(0, 8), paragraph(1, 1, heading = true, gap = 4), block(2, 50, gap = 4)), pageHeight = 100)
        assertEquals(listOf(Slice(0, 0, 80)), pages.pages[0].slices)
        assertEquals(listOf(Slice(1, 4, 14), Slice(2, 0, 50)), pages.pages[1].slices)
        assertEquals(ReadingPosition(1, 0), pages.pages[1].start)
    }

    @Test
    fun `what can't be cut and is taller than a page is cut where the page ends`() {
        val pages = Paginator.paginate(listOf(block(0, 250)), pageHeight = 100)
        assertEquals(listOf(listOf(Slice(0, 0, 100)), listOf(Slice(0, 100, 200)), listOf(Slice(0, 200, 250))), pages.pages.map { it.slices })
    }

    @Test
    fun `a position is found on the page it starts on or after`() {
        val pages = Paginator.paginate(listOf(paragraph(0, 25), paragraph(1, 25)), pageHeight = 100)
        assertEquals(5, pages.count)
        assertEquals(0, pages.pageOf(ReadingPosition(0, 0)))
        assertEquals(1, pages.pageOf(ReadingPosition(0, 1000)))
        assertEquals(1, pages.pageOf(ReadingPosition(0, 1950)))
        assertEquals(2, pages.pageOf(ReadingPosition(0, 2000)))
        assertEquals(2, pages.pageOf(ReadingPosition(1, 0)))
        assertEquals(4, pages.pageOf(ReadingPosition(Int.MAX_VALUE, 0)))
    }

    @Test
    fun `an empty chapter is one empty page`() {
        assertEquals(1, Paginator.paginate(emptyList(), pageHeight = 100).count)
    }
}
