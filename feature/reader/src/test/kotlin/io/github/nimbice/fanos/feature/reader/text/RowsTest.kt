package io.github.nimbice.fanos.feature.reader.text

import io.github.nimbice.fanos.core.content.Block
import io.github.nimbice.fanos.core.content.ChapterContent
import io.github.nimbice.fanos.core.content.Span
import io.github.nimbice.fanos.core.content.TableCell
import org.junit.Test
import kotlin.test.assertEquals

class RowsTest {

    private fun p(text: String) = Block.Paragraph(listOf(Span(text)))

    private fun rows(vararg blocks: Block, title: String? = "Chapter 1", ownTitle: Boolean = false) =
        RowLayout.rows(ChapterContent(blocks.toList(), hasOwnTitle = ownTitle), title, paragraphSpacing = 0.9f)

    @Test
    fun `a row per leaf, numbered as the reader's positions number them, under the chapter's title`() {
        val layout = rows(p("One"), Block.SceneBreak, Block.Image("https://x/y.png"), Block.Heading(2, listOf(Span("Two"))), p("Three"))
        assertEquals(listOf(-1, 0, 1, 2, 3, 4), layout.rows.map { it.leaf })
        assertEquals(TitleRow("Chapter 1", RowLayout.TITLE_TOP), layout.rows.first())
        assertEquals(TextKind.Heading2, (layout.rows[4] as TextRow).kind)
    }

    @Test
    fun `a chapter that opens with its own title shows that one, styled as the title`() {
        val layout = rows(p("Chapter 1: The Start"), p("Text"), ownTitle = true)
        assertEquals(listOf(0, 1), layout.rows.map { it.leaf })
        assertEquals(TextKind.Title, (layout.rows[0] as TextRow).kind)
        assertEquals(RowLayout.TITLE_BOTTOM, layout.rows[1].gap)
    }

    @Test
    fun `a credit line is a quiet row of its own, and the title below it is still the title`() {
        val credit = Block.Credit(listOf(Span("Translator: Bob")))
        val layout = rows(credit, p("Chapter 1: The Start"), p("Text"), ownTitle = true)
        assertEquals(listOf(0, 1, 2), layout.rows.map { it.leaf })
        assertEquals(listOf(TextKind.Credit, TextKind.Title, TextKind.Body), layout.rows.map { (it as TextRow).kind })
        assertEquals(listOf(0f, 0.9f, RowLayout.TITLE_BOTTOM), layout.rows.map { it.gap })
    }

    @Test
    fun `margins between rows collapse to the larger`() {
        val layout = rows(p("A"), p("B"), Block.Heading(2, listOf(Span("H"))), p("C"), Block.SceneBreak, p("D"), title = null)
        assertEquals(listOf(0f, 0.9f, 1.1f * 1.25f, 0.6f * 1.25f, 1.6f, 1.6f), layout.rows.map { it.gap })
        assertEquals(RowLayout.CHAPTER_SPACE, layout.end)
    }

    @Test
    fun `rows in a box carry it, with its margins outside and the space between them inside`() {
        val layout = rows(p("Before"), Block.Box(listOf(Block.Heading(3, listOf(Span("Status"))), p("Level 5"), p("Class: Mage"))), p("After"), title = null)
        val (before, heading, level, mage, after) = layout.rows
        assertEquals(emptyList(), before.wraps)
        assertEquals(listOf(Wrap.Box(first = true, last = false)), heading.wraps)
        assertEquals(RowLayout.BOX_MARGIN, heading.gap)
        assertEquals(1.1f * 1.1f, heading.boxGap)
        assertEquals(listOf(Wrap.Box(first = false, last = false)), level.wraps)
        assertEquals(0.6f * 1.1f, level.gap)
        assertEquals(listOf(Wrap.Box(first = false, last = true)), mage.wraps)
        assertEquals(0.9f, mage.gap)
        // The box's last paragraph's margin stays inside it; the box's own comes after.
        assertEquals(RowLayout.BOX_MARGIN, after.gap)
        assertEquals(listOf(0, 1, 2, 3, 4), layout.rows.map { it.leaf })
    }

    @Test
    fun `a quote's margins meet its first row's outside it`() {
        val layout = rows(p("Before"), Block.Quote(listOf(p("Said"), p("More"))), title = null)
        assertEquals(RowLayout.QUOTE_MARGIN, layout.rows[1].gap)
        assertEquals(0f, layout.rows[1].boxGap)
        assertEquals(listOf(Wrap.Quote(first = true, last = false)), layout.rows[1].wraps)
        assertEquals(listOf(Wrap.Quote(first = false, last = true)), layout.rows[2].wraps)
    }

    @Test
    fun `list items are marked, bullets by depth and numbers in order`() {
        val nested = Block.ListBlock(ordered = false, items = listOf(listOf(p("Inner"))))
        val layout =
            rows(
                Block.ListBlock(ordered = true, items = listOf(listOf(p("First")), listOf(p("Second"), nested))),
                title = null,
            )
        val markers = layout.rows.map { row -> row.wraps.filterIsInstance<Wrap.Item>().map { it.marker } }
        assertEquals(listOf(listOf("1."), listOf("2."), listOf(null, "◦")), markers)
    }

    @Test
    fun `a table is one row holding all its leaves`() {
        val table = Block.Table(listOf(listOf(TableCell(listOf(p("A"))), TableCell(listOf(p("B")))), listOf(TableCell(listOf(p("C")), colspan = 2))))
        val layout = rows(table, p("After"), title = null)
        val row = layout.rows[0] as TableRow
        assertEquals(0, row.leaf)
        assertEquals(3, row.leafCount)
        assertEquals(3, layout.rows[1].leaf)
    }

    @Test
    fun `a table with prose stacks, headings on a line, then a card a row with its short cells leading`() {
        val description = "Upon activation, causes the user to forcefully erupt with the accumulated heat stored within the body."
        val table =
            Block.Table(
                listOf(
                    listOf(TableCell(listOf(p("Skill")), header = true), TableCell(listOf(p("Type")), header = true), TableCell(listOf(p("Effect")), header = true)),
                    listOf(TableCell(listOf(p("Blaze L1"))), TableCell(listOf(p("Skill"))), TableCell(listOf(p(description), p("It cracks.")))),
                    listOf(TableCell(listOf(p("Ember"))), TableCell(listOf(p("Spell"))), TableCell(listOf(p(description)))),
                ),
            )
        val layout = rows(p("Before"), table, p("After"), title = null)
        val (before, headings, blaze, effect, cracks, ember, effect2, after) = layout.rows.toTypedArray().let { Rows8(it) }

        // The headings' line holds three leaves, each card's first line two.
        assertEquals(listOf(0, 1, 4, 6, 7, 8, 10, 11), layout.rows.map { it.leaf })
        assertEquals(TableLineRow(1, listOf(listOf(Span("Skill")), listOf(Span("Type")), listOf(Span("Effect"))), lead = false, header = true, emptyList(), RowLayout.TABLE_MARGIN), headings)
        assertEquals(3, headings.leafCount)
        assertEquals(listOf(Wrap.Box(first = true, last = false)), blaze.wraps)
        assertEquals(true, (blaze as TableLineRow).lead)
        assertEquals(RowLayout.CARD_MARGIN, blaze.gap)
        assertEquals(RowLayout.CELL_GAP, effect.gap)
        assertEquals(listOf(Wrap.Box(first = false, last = true)), cracks.wraps)
        assertEquals(listOf(Wrap.Box(first = true, last = false)), ember.wraps)
        assertEquals(listOf(Wrap.Box(first = false, last = true)), effect2.wraps)
        assertEquals(RowLayout.TABLE_MARGIN, after.gap)
        assertEquals(emptyList(), before.wraps)
    }

    private class Rows8(val rows: Array<Row>) {
        operator fun component1() = rows[0]
        operator fun component2() = rows[1]
        operator fun component3() = rows[2]
        operator fun component4() = rows[3]
        operator fun component5() = rows[4]
        operator fun component6() = rows[5]
        operator fun component7() = rows[6]
        operator fun component8() = rows[7]
    }

    @Test
    fun `an empty quote leaves nothing behind`() {
        val layout = rows(p("A"), Block.Quote(emptyList()), p("B"), title = null)
        assertEquals(2, layout.rows.size)
        assertEquals(0.9f, layout.rows[1].gap)
        assertEquals(emptyList(), layout.rows[1].wraps)
    }
}
