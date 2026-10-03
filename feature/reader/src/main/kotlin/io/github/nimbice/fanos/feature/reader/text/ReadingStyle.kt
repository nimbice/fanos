package io.github.nimbice.fanos.feature.reader.text

import android.content.res.AssetManager
import android.graphics.Typeface
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.BaselineShift
import androidx.compose.ui.text.style.Hyphens
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import io.github.nimbice.fanos.core.content.Align
import io.github.nimbice.fanos.core.content.Block
import io.github.nimbice.fanos.core.content.leaves
import io.github.nimbice.fanos.core.content.text
import io.github.nimbice.fanos.core.content.Span
import io.github.nimbice.fanos.core.model.ReaderAlignment
import io.github.nimbice.fanos.core.model.ReaderFont
import io.github.nimbice.fanos.core.model.ReaderSettings
import io.github.nimbice.fanos.feature.reader.ReadingColors
import io.github.nimbice.fanos.feature.reader.colors
import io.github.nimbice.fanos.feature.reader.spec
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import io.github.nimbice.fanos.feature.reader.HIGHLIGHT_HUES

/**
 * How the reader draws text, from the reading settings: sizes, spacing and colours. Everything that
 * places a row on screen, and everything that measures one without drawing it, reads from here, so
 * the two agree.
 */
internal class ReadingStyle(
    val settings: ReaderSettings,
    val family: FontFamily,
    density: Density,
    /** A footnote's mark tapped: the mark, and its note. */
    private val onNote: (mark: String, note: String) -> Unit = { _, _ -> },
) {
    val colors: ReadingColors = settings.colors

    /** Behind the sentence being read aloud. */
    val spoken: Color = colors.link.copy(alpha = colors.link.alpha * SPOKEN_ALPHA)

    /** Over that, behind the word being said: the two together stronger still. */
    val spokenWord: Color = colors.link.copy(alpha = colors.link.alpha * SPOKEN_ALPHA * 1.6f)

    /** Behind highlighted text, highlight colour [color]: stronger on a light page, where it would wash out. */
    fun highlight(color: Int): Color {
        val hue = HIGHLIGHT_HUES[color.coerceIn(0, HIGHLIGHT_HUES.lastIndex)]
        return hue.copy(alpha = if (colors.background.luminance() > 0.5f) 0.5f else 0.32f)
    }

    /** Behind a search's matches, and stronger behind the one it's at: a blue that reads on every theme. */
    val found: Color = FOUND.copy(alpha = 0.3f)
    val foundCurrent: Color = FOUND.copy(alpha = 0.6f)

    /** Behind the word pressed while its menu or the dictionary's card is open: the same blue, as text selected. */
    val pressed: Color = FOUND.copy(alpha = 0.4f)

    /** An em of the body text, on screen. */
    val em: Dp = with(density) { settings.fontSize.sp.toDp() }

    /** Left and right margins of the page. */
    val margin: Dp = settings.margin.dp

    fun ems(value: Float): Dp = em * value

    /** The text's weight, as the reader set it; bold words go heavier than it still. */
    private val weight = FontWeight(settings.fontWeight.coerceIn(100, 900))
    private val bold = FontWeight((settings.fontWeight + 300).coerceIn(700, 900))

    private val base =
        TextStyle(
            color = colors.text,
            fontFamily = family,
            fontWeight = weight,
            fontSize = settings.fontSize.sp,
            lineHeight = settings.lineHeight.em,
            hyphens = Hyphens.Auto,
            lineBreak = LineBreak.Paragraph,
            // Space above and below each line split evenly, as CSS's line-height does.
            lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.None),
        )

    fun text(kind: TextKind, align: Align = Align.Start): TextStyle {
        val scale = RowLayout.scale(kind)
        val size = (settings.fontSize * scale).sp
        return when (kind) {
            TextKind.Body -> base.copy(textAlign = align.textAlign(justify = settings.alignment == ReaderAlignment.Justify))
            TextKind.Heading1, TextKind.Heading2, TextKind.Heading3 ->
                base.copy(fontSize = size, lineHeight = 1.3.em, fontWeight = FontWeight.SemiBold, textAlign = align.textAlign(justify = false))
            TextKind.Title -> base.copy(fontSize = size, lineHeight = 1.25.em, fontWeight = FontWeight.Bold, color = colors.link, textAlign = align.textAlign(justify = false))
            TextKind.Pre -> base.copy(fontFamily = FontFamily.Monospace, fontSize = size, lineHeight = 1.45.em, hyphens = Hyphens.None, lineBreak = LineBreak.Simple)
            // Translator and editor credits: there to read, quieter than the chapter.
            TextKind.Credit -> base.copy(fontSize = size, color = colors.muted, textAlign = align.textAlign(justify = false))
        }
    }

    /** The scene break's mark. */
    val sceneBreak: TextStyle = base.copy(color = colors.muted, fontSize = (settings.fontSize * 1.1f).sp, textAlign = TextAlign.Center)

    /** A grid table's cell text, and a heading cell's: a little smaller, so more fits across. */
    fun cell(header: Boolean): TextStyle =
        base.copy(fontSize = (settings.fontSize * TABLE_SCALE).sp, fontWeight = if (header) FontWeight.SemiBold else null, textAlign = TextAlign.Start)

    /** A grid cell's text, styled as the site styled it, a line a leaf. */
    fun cellText(blocks: List<Block>): AnnotatedString =
        buildAnnotatedString {
            blocks.flatMap { it.leaves() }.forEachIndexed { i, leaf ->
                if (i > 0) append("\n")
                when (leaf) {
                    is Block.Paragraph -> append(annotated(leaf.spans))
                    is Block.Heading -> append(annotated(leaf.spans))
                    is Block.Credit -> append(annotated(leaf.spans))
                    else -> append(leaf.text())
                }
            }
        }

    /** A stacked table's line of short cells, the first in bold when it names the row, "·" between. */
    fun tableLine(row: TableLineRow): AnnotatedString =
        buildAnnotatedString {
            row.parts.forEachIndexed { i, spans ->
                if (i > 0) withStyle(SpanStyle(color = colors.muted, fontWeight = FontWeight.Normal)) { append(" · ") }
                if (i == 0 && row.lead) withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append(annotated(spans)) } else append(annotated(spans))
            }
        }

    /** The line's style: the column headings' line quieter and smaller. */
    fun tableLineStyle(header: Boolean): TextStyle = if (header) base.copy(color = colors.muted, fontSize = (settings.fontSize * 0.9f).sp) else base

    /** A list item's bullet or number. */
    val marker: TextStyle = base.copy(textAlign = TextAlign.End, hyphens = Hyphens.None)

    private val linkStyles = TextLinkStyles(SpanStyle(color = colors.link, textDecoration = TextDecoration.Underline))

    /** A footnote's mark: the link's colour, bold. */
    private val noteMark = SpanStyle(color = colors.link, fontWeight = FontWeight.Bold)

    /** Spans as styled text; links open where the reader's links open, and a footnote's mark (with the word before it) its note. */
    fun annotated(spans: List<Span>): AnnotatedString =
        buildAnnotatedString {
            val plain = StringBuilder()
            for (span in spans) {
                val note = span.note
                val href = span.href?.takeIf { it.startsWith("https://", true) || it.startsWith("http://", true) || it.startsWith("mailto:", true) }
                val style = span.spanStyle()
                if (note != null) {
                    val start = length
                    withStyle(noteMark) { if (style != null) withStyle(style) { append(span.text) } else append(span.text) }
                    // The word before the mark opens the note too: a mark alone is a small thing to hit.
                    var from = start
                    if (from > 0 && plain[from - 1] == ' ') from--
                    while (from > 0 && start - from < NOTE_REACH && !plain[from - 1].isWhitespace()) from--
                    val mark = span.text.trim()
                    // Styled already: no link look over the word (Material gives links without styles of their own one).
                    addLink(LinkAnnotation.Clickable(NOTE_TAG, TextLinkStyles(SpanStyle())) { onNote(mark, note) }, from, length)
                } else if (href != null) {
                    withLink(LinkAnnotation.Url(href, linkStyles)) { if (style != null) withStyle(style) { append(span.text) } else append(span.text) }
                } else if (style != null) {
                    withStyle(style) { append(span.text) }
                } else {
                    append(span.text)
                }
                plain.append(span.text)
            }
        }

    private fun Span.spanStyle(): SpanStyle? {
        if (style == 0) return null
        val decorations = listOfNotNull(if (has(Span.UNDERLINE)) TextDecoration.Underline else null, if (has(Span.STRIKE)) TextDecoration.LineThrough else null)
        return SpanStyle(
            fontWeight = if (has(Span.BOLD)) bold else null,
            fontStyle = if (has(Span.ITALIC)) FontStyle.Italic else null,
            textDecoration = if (decorations.isEmpty()) null else TextDecoration.combine(decorations),
            baselineShift =
                when {
                    has(Span.SUPERSCRIPT) -> BaselineShift.Superscript
                    has(Span.SUBSCRIPT) -> BaselineShift.Subscript
                    else -> null
                },
            fontSize = if (has(Span.SUPERSCRIPT) || has(Span.SUBSCRIPT)) 0.75.em else if (has(Span.CODE)) 0.92.em else androidx.compose.ui.unit.TextUnit.Unspecified,
            fontFamily = if (has(Span.CODE)) FontFamily.Monospace else null,
        )
    }

    private fun Align.textAlign(justify: Boolean): TextAlign =
        when (this) {
            Align.Center -> TextAlign.Center
            Align.End -> TextAlign.End
            Align.Justify -> TextAlign.Justify
            Align.Start -> if (justify) TextAlign.Justify else TextAlign.Start
        }
}

/**
 * The reading font as a family with real bold and italic: the bundled fonts are variable, so each
 * weight is an instance of the one file (their italics are files of their own), and the phone's own
 * fonts come from the system.
 */
@OptIn(ExperimentalTextApi::class)
internal fun ReaderFont.family(assets: AssetManager): FontFamily {
    val file = spec.file
    return when {
        file != null -> {
            val fonts =
                // Each weight the text can be, and bold words heavier still: instances of the one variable file.
                listOf(FontWeight.Light, FontWeight.Normal, FontWeight.Medium, FontWeight.SemiBold, FontWeight.Bold, FontWeight.ExtraBold, FontWeight.Black).flatMap { weight ->
                    listOfNotNull(
                        Font("reader/fonts/$file.ttf", assets, weight, FontStyle.Normal),
                        if (spec.italic) Font("reader/fonts/$file-italic.ttf", assets, weight, FontStyle.Italic) else null,
                    )
                }
            FontFamily(fonts)
        }
        this == ReaderFont.Serif -> FontFamily.Serif
        this == ReaderFont.Monospace -> FontFamily.Monospace
        this == ReaderFont.Condensed -> FontFamily(Typeface.create("sans-serif-condensed", Typeface.NORMAL))
        else -> FontFamily.SansSerif
    }
}

/** A grid table's text size, in ems of the text size. */
internal const val TABLE_SCALE = 0.92f

/** How strongly the sentence being read aloud is marked, of the link colour. */
private const val SPOKEN_ALPHA = 0.24f

private val FOUND = Color(0xFF3FA9D6)

/** What a footnote's mark's link is called. */
private const val NOTE_TAG = "note"

/** How far back from a footnote's mark the word before it may start, for the tap that opens the note. */
private const val NOTE_REACH = 24
