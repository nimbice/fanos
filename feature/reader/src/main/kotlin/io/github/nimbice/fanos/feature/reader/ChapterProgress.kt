package io.github.nimbice.fanos.feature.reader

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.nimbice.fanos.core.model.ScrollBar
import kotlinx.coroutines.delay

/**
 * How far through the chapter the reader has scrolled, at the page's edge: a line down the right edge or across the
 * top that fills as they read on, or a scrollbar's thumb that shows while they scroll. [top] and [bottom] are the room
 * the bars over the page leave a line down the edge.
 */
@Composable
internal fun ChapterProgress(kind: ScrollBar, progress: ScrollProgress, colors: ReadingColors, top: Dp, bottom: Dp, modifier: Modifier = Modifier) {
    val fraction = progress.fraction.coerceIn(0f, 1f)
    val track = colors.text.copy(alpha = TRACK_ALPHA)
    val fill = colors.link.copy(alpha = FILL_ALPHA)
    Box(modifier.fillMaxSize()) {
        when (kind) {
            ScrollBar.Off -> Unit
            ScrollBar.Edge ->
                Canvas(Modifier.align(Alignment.TopEnd).padding(top = top, bottom = bottom, end = EDGE_GAP).width(LINE).fillMaxHeight()) {
                    val round = CornerRadius(size.width / 2)
                    drawRoundRect(track, cornerRadius = round)
                    drawRoundRect(fill, size = Size(size.width, size.height * fraction), cornerRadius = round)
                }
            ScrollBar.Top ->
                Canvas(Modifier.align(Alignment.TopStart).fillMaxWidth().height(LINE)) {
                    drawRect(track)
                    drawRect(fill, size = Size(size.width * fraction, size.height))
                }
            ScrollBar.Thumb -> {
                // Shown while the chapter scrolls, and a moment after.
                var moving by remember { mutableStateOf(false) }
                LaunchedEffect(progress.fraction) {
                    moving = true
                    delay(THUMB_LINGER_MS)
                    moving = false
                }
                val alpha by animateFloatAsState(if (moving) 1f else 0f, tween(THUMB_FADE_MS), label = "thumb")
                Canvas(Modifier.align(Alignment.TopEnd).padding(top = top, bottom = bottom, end = THUMB_GAP).width(THUMB).fillMaxHeight()) {
                    // As long as a screen is of the chapter, and never too short to see.
                    val length = (size.height / progress.screens.coerceAtLeast(1)).coerceIn(THUMB_MIN.toPx(), size.height)
                    drawRoundRect(
                        colors.text.copy(alpha = THUMB_ALPHA * alpha),
                        topLeft = Offset(0f, (size.height - length) * fraction),
                        size = Size(size.width, length),
                        cornerRadius = CornerRadius(size.width / 2),
                    )
                }
            }
        }
    }
}

private val LINE = 3.dp
private val EDGE_GAP = 4.dp
private val THUMB = 5.dp
private val THUMB_GAP = 3.dp
private val THUMB_MIN = 28.dp
private const val TRACK_ALPHA = 0.08f
private const val FILL_ALPHA = 0.6f
private const val THUMB_ALPHA = 0.4f
private const val THUMB_LINGER_MS = 1200L
private const val THUMB_FADE_MS = 300
