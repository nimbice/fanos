package io.github.nimbice.fanos.core.designsystem.component

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.isSpecified
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier

/**
 * [style], made smaller when the longest of [labels] wouldn't fit [room] on one line at the phone's text size: so each
 * label keeps one line, and all of them one size. Labels side by side under icons, as a tab bar's, use it.
 */
@Composable
fun fittingStyle(labels: List<String>, room: Dp, style: TextStyle): TextStyle {
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    return remember(labels, room, style, density) {
        val fits = with(density) { room.toPx() }
        fun widest(of: TextStyle) = labels.maxOf { measurer.measure(it, of, maxLines = 1, softWrap = false).size.width }
        // Text doesn't shrink quite in step with its size (letter spacing set in sp, rounding): measured again until it fits.
        var shown = style
        repeat(TRIES) {
            val width = widest(shown)
            if (width <= fits) return@remember shown
            shown = shown.scaled(fits / width * SLACK)
        }
        shown
    }
}

private fun TextStyle.scaled(by: Float): TextStyle =
    copy(
        fontSize = fontSize * by,
        lineHeight = if (lineHeight.isSpecified) lineHeight * by else lineHeight,
        letterSpacing = if (letterSpacing.isSpecified) letterSpacing * by else letterSpacing,
    )

private const val TRIES = 5

/** A little under the exact fit, for what the measure rounds off. */
private const val SLACK = 0.98f

/** [text] on one line, a size smaller when it wouldn't fit the room it's given: a search field's hint, with large text. */
@Composable
fun FittingText(text: String, modifier: Modifier = Modifier) {
    BoxWithConstraints(modifier) {
        Text(text, style = fittingStyle(listOf(text), maxWidth, LocalTextStyle.current), maxLines = 1)
    }
}
