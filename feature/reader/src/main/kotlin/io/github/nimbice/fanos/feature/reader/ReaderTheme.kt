package io.github.nimbice.fanos.feature.reader

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import io.github.nimbice.fanos.core.model.CustomReaderColors
import io.github.nimbice.fanos.core.model.ReaderSettings
import io.github.nimbice.fanos.core.model.ReaderTheme

/** A reading theme's colours. */
internal data class ReadingColors(
    val background: Color,
    val text: Color,
    /** Quieter text: a scene break's mark, a table's column headings. */
    val muted: Color,
    /** Links, and the chapter's title. */
    val link: Color,
    /** Rules, and a table's lines. */
    val rule: Color,
    val boxBackground: Color,
    val boxBorder: Color,
)

/** The colours the reader reads with: the theme's, or with [ReaderTheme.Custom] the reader's own. */
internal val ReaderSettings.colors: ReadingColors
    get() = theme.colors(customColors)

internal fun ReaderTheme.colors(custom: CustomReaderColors): ReadingColors =
    when (this) {
        ReaderTheme.Paper ->
            ReadingColors(rgb(0xFBF8F1), rgb(0x1F1B16), rgb(0x6F665A), rgb(0x7A5A00), rgba(31, 27, 22, .18f), rgba(122, 90, 0, .06f), rgba(122, 90, 0, .35f))
        ReaderTheme.Sepia ->
            ReadingColors(rgb(0xF4ECD8), rgb(0x4B3A2A), rgb(0x7A6650), rgb(0x8A5A1E), rgba(75, 58, 42, .2f), rgba(138, 90, 30, .07f), rgba(138, 90, 30, .35f))
        ReaderTheme.Dusk ->
            ReadingColors(rgb(0x2B2621), rgb(0xE3D9CC), rgb(0xA89C8C), rgb(0xF2C46D), rgba(227, 217, 204, .2f), rgba(242, 196, 109, .07f), rgba(242, 196, 109, .35f))
        ReaderTheme.Night ->
            ReadingColors(rgb(0x15120F), rgb(0xD9CFC2), rgb(0x9C9285), rgb(0xF2C46D), rgba(217, 207, 194, .18f), rgba(242, 196, 109, .06f), rgba(242, 196, 109, .32f))
        ReaderTheme.Black ->
            ReadingColors(rgb(0x000000), rgb(0xC9C4BC), rgb(0x8E887F), rgb(0xE8B95E), rgba(201, 196, 188, .18f), rgba(232, 185, 94, .06f), rgba(232, 185, 94, .3f))
        ReaderTheme.Custom -> custom.readingColors()
    }

/** A highlight's colours, in the order the long-press menu offers them: yellow, green, blue and pink. */
internal val HIGHLIGHT_HUES = listOf(Color(0xFFF2C46D), Color(0xFF8FCB9F), Color(0xFF7FB3D5), Color(0xFFE39B7B))

/** The reader's three colours, with the quieter ones mixed from them as the set themes' are. */
private fun CustomReaderColors.readingColors(): ReadingColors {
    val background = rgb(background)
    val text = rgb(text)
    val link = rgb(link)
    return ReadingColors(
        background = background,
        text = text,
        muted = lerp(background, text, 0.65f),
        link = link,
        rule = text.copy(alpha = 0.18f),
        boxBackground = link.copy(alpha = 0.06f),
        boxBorder = link.copy(alpha = 0.33f),
    )
}

internal fun rgb(value: Int) = Color(0xFF000000 or value.toLong())

private fun rgba(red: Int, green: Int, blue: Int, alpha: Float) = Color(red, green, blue, (alpha * 255).toInt())
