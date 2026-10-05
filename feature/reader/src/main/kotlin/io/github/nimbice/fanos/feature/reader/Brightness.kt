package io.github.nimbice.fanos.feature.reader

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import io.github.nimbice.fanos.core.designsystem.icon.ReaderIcons
import kotlin.math.roundToInt
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
 * The brightness slider, [brightness] or for null the device's own, and at its end a button back to the device's own
 * (automatic where the phone has it so), lit while that's the one in use.
 */
@Composable
internal fun BrightnessSlider(brightness: Float?, onBrightness: (Float?) -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val own = brightness == null
    // Read again on going back to the device's own, so the slider shows where the phone has it now.
    val deviceLevel = remember(context, own) { deviceBrightness(context) }
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Icon(ReaderIcons.BrightnessLow, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
        Slider(
            value = brightness ?: deviceLevel,
            onValueChange = { value -> onBrightness((value * 100).roundToInt() / 100f) },
            modifier = Modifier.weight(1f).padding(horizontal = 8.dp).semantics { contentDescription = "Brightness" },
        )
        IconButton(onClick = { onBrightness(null) }, modifier = Modifier.semantics { if (own) stateDescription = "In use" }) {
            Icon(
                ReaderIcons.BrightnessAuto,
                contentDescription = "Use this device’s brightness",
                tint = if (own) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(24.dp),
            )
        }
    }
}

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
