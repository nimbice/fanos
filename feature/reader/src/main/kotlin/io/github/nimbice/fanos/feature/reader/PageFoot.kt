package io.github.nimbice.fanos.feature.reader

import android.content.Context
import android.os.BatteryManager
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsIgnoringVisibility
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.TextStyle

/**
 * Small at the foot of the page: where the reader is in the chapter ([place], as the bar puts it), the time and the
 * battery. Over the page's own colour [background], so in scroll mode the text runs on under it unseen.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun PageFoot(place: String, color: Color, background: Color, margin: Dp, height: Dp, fade: Boolean, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    // The time and the battery, again at each minute's turn.
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = System.currentTimeMillis()
            delay(MINUTE_MS - now % MINUTE_MS)
        }
    }
    val time = remember(now) { clockText(context, now) }
    val battery = remember(now) { batteryPercent(context) }
    val faint = color.copy(alpha = FAINT)
    val style = MaterialTheme.typography.bodySmall.copy(color = faint, fontFeatureSettings = "tnum")
    // Scrolling, the text fades into the page above the foot rather than stopping at its edge.
    Column(modifier.fillMaxWidth()) {
        if (fade) Box(Modifier.fillMaxWidth().height(FOOT_FADE).background(Brush.verticalGradient(listOf(background.copy(alpha = 0f), background))))
        FootRow(place, time, battery, style, faint, background, margin, height)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FootRow(place: String, time: String, battery: Int?, style: TextStyle, faint: Color, background: Color, margin: Dp, height: Dp) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = height)
            .background(background)
            .windowInsetsPadding(WindowInsets.navigationBarsIgnoringVisibility.union(WindowInsets.displayCutout).only(WindowInsetsSides.Horizontal))
            .padding(horizontal = margin),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(place, style = style, maxLines = 1, modifier = Modifier.weight(1f))
        Text(time, style = style, maxLines = 1)
        if (battery != null) {
            Spacer(Modifier.width(10.dp))
            BatteryIcon(battery, faint)
            Spacer(Modifier.width(3.dp))
            Text("$battery%", style = style, maxLines = 1)
        }
    }
}

/** A battery standing up, filled as far as it's charged. */
@Composable
private fun BatteryIcon(percent: Int, color: Color) {
    Canvas(Modifier.size(width = 7.dp, height = 12.dp)) {
        val line = 1.dp.toPx()
        val nub = 1.5.dp.toPx()
        drawRect(color, topLeft = Offset(size.width * 0.28f, 0f), size = Size(size.width * 0.44f, nub))
        drawRoundRect(
            color,
            topLeft = Offset(line / 2, nub + line / 2),
            size = Size(size.width - line, size.height - nub - line),
            cornerRadius = CornerRadius(line, line),
            style = Stroke(line),
        )
        val inside = size.height - nub - line * 3
        val filled = inside * percent.coerceIn(0, 100) / 100f
        drawRect(color, topLeft = Offset(line * 1.5f, size.height - line * 1.5f - filled), size = Size(size.width - line * 3, filled))
    }
}

/** The time as the status bar shows it: 24-hour or not, as this device is set. */
private fun clockText(context: Context, millis: Long): String {
    val pattern = if (android.text.format.DateFormat.is24HourFormat(context)) "HH:mm" else "h:mm"
    return SimpleDateFormat(pattern, Locale.getDefault()).format(Date(millis))
}

/** How charged the battery is, in percent; null when this device doesn't say. */
private fun batteryPercent(context: Context): Int? =
    context.getSystemService(BatteryManager::class.java)
        ?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        ?.takeIf { it in 0..100 }

/** The place in the chapter as the bar and the foot of the page put it: "Page 6 of 14", "Pages 6–7 of 14"; scrolling, "Screen 6 of 14". */
internal fun placeText(pageMode: Boolean, pageInfo: PageInfo?, progress: ScrollProgress?): String =
    when {
        pageMode && pageInfo != null && pageInfo.last > pageInfo.page -> "Pages ${pageInfo.page + 1}–${pageInfo.last + 1} of ${pageInfo.total}"
        pageMode && pageInfo != null -> "Page ${pageInfo.page + 1} of ${pageInfo.total}"
        !pageMode && progress != null -> "Screen ${progress.page} of ${progress.screens}"
        else -> ""
    }

/** Room above and below the foot of the page's line, together. */
internal val FOOT_ROOM = 8.dp

/** How tall the fade above the foot is, in scroll mode. */
internal val FOOT_FADE = 16.dp

private const val MINUTE_MS = 60_000L

/** How strongly the foot of the page shows against the text's own colour. */
private const val FAINT = 0.6f
