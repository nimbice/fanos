package io.github.nimbice.fanos.feature.reader

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import kotlin.math.sqrt
import kotlin.math.abs

/**
 * The reader's brightness slider runs from 0 to 1: up to [DIM] the screen is at its lowest and a shade over the page dims
 * it further; above, the screen's brightness rises on a curve, as eyes see brightness rather than as the screen gives it.
 */
private const val DIM = 0.15f

/** The screen's brightness for slider position [slider]. */
internal fun screenOf(slider: Float): Float {
    if (slider <= DIM) return LOWEST
    val above = (slider - DIM) / (1 - DIM)
    return (above * above).coerceIn(LOWEST, 1f)
}

/** Where the screen's brightness [screen] sits on the slider. */
internal fun sliderOf(screen: Float): Float = DIM + (1 - DIM) * sqrt(screen.coerceIn(0f, 1f))

/** How dark the shade over the page is at slider position [slider]: none from [DIM] up. */
internal fun shadeOf(slider: Float): Float = if (slider >= DIM) 0f else (DIM - slider.coerceAtLeast(0f)) / DIM * DARKEST

/**
 * Over the page: a tint for [warmth], amber for warm and blue for cold, and a shade for [brightness] below the screen's
 * lowest. Takes no touches.
 */
@Composable
internal fun PageFilter(brightness: Float?, warmth: Float, modifier: Modifier = Modifier) {
    val shade = brightness?.let(::shadeOf) ?: 0f
    if (warmth == 0f && shade <= 0f) return
    Box(
        modifier.fillMaxSize().drawBehind {
            // Multiplied in: white turns amber (or blue), black stays black.
            if (warmth != 0f) drawRect(lerp(Color.White, if (warmth > 0f) AMBER else COOL, abs(warmth)), blendMode = BlendMode.Multiply)
            if (shade > 0f) drawRect(Color.Black.copy(alpha = shade))
        },
    )
}

/** The screen's lowest brightness that still shows: zero turns some screens off. */
private const val LOWEST = 0.01f

/** The shade at the slider's very bottom. */
private const val DARKEST = 0.6f

/** The tint at full warmth. */
private val AMBER = Color(0xFFFFB066)

/** The tint at full cold. */
private val COOL = Color(0xFFA9C6FF)
