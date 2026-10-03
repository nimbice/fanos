package io.github.nimbice.fanos.feature.reader

import android.content.res.AssetManager
import android.graphics.Typeface
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import io.github.nimbice.fanos.core.model.ReaderFont

/**
 * A reading font: its name in the settings and, for a bundled one (SIL Open Font License, licences in
 * assets/reader/fonts/licenses), its variable font files in assets/reader/fonts. The rest are the
 * phone's own.
 */
internal data class FontSpec(
    val label: String,
    /** For bundled fonts: <file>.ttf and, when [italic], <file>-italic.ttf. */
    val file: String? = null,
    val italic: Boolean = true,
)

internal val ReaderFont.spec: FontSpec
    get() =
        when (this) {
            ReaderFont.Serif -> FontSpec("Serif")
            ReaderFont.SansSerif -> FontSpec("Sans")
            ReaderFont.Literata -> FontSpec("Literata", "literata")
            ReaderFont.Lora -> FontSpec("Lora", "lora")
            ReaderFont.CrimsonPro -> FontSpec("Crimson Pro", "crimson-pro")
            ReaderFont.EbGaramond -> FontSpec("EB Garamond", "eb-garamond")
            ReaderFont.Bitter -> FontSpec("Bitter", "bitter")
            ReaderFont.AtkinsonHyperlegible -> FontSpec("Atkinson", "atkinson-hyperlegible-next")
            ReaderFont.Lexend -> FontSpec("Lexend", "lexend", italic = false)
            ReaderFont.Condensed -> FontSpec("Condensed")
            ReaderFont.Monospace -> FontSpec("Mono")
        }

/** The font for the font's own button in the reader settings. */
@OptIn(ExperimentalTextApi::class)
internal fun ReaderFont.previewFamily(assets: AssetManager): FontFamily {
    val file = spec.file
    return when {
        file != null -> FontFamily(Font("reader/fonts/$file.ttf", assets))
        this == ReaderFont.Serif -> FontFamily.Serif
        this == ReaderFont.Monospace -> FontFamily.Monospace
        this == ReaderFont.Condensed -> FontFamily(Typeface.create("sans-serif-condensed", Typeface.NORMAL))
        else -> FontFamily.SansSerif
    }
}
