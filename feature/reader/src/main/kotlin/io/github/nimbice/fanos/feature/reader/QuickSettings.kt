package io.github.nimbice.fanos.feature.reader

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.nimbice.fanos.core.designsystem.icon.ReaderIcons
import io.github.nimbice.fanos.core.model.ReaderSettings
import io.github.nimbice.fanos.core.model.ReaderTheme
import kotlin.math.roundToInt

/**
 * Over the bottom bar while the controls show: the text size, the colours and the brightness, to change in passing.
 * The rest are in the reading settings.
 */
@Composable
internal fun QuickSettings(settings: ReaderSettings, onChange: ((ReaderSettings) -> ReaderSettings) -> Unit, onBrightness: (Float?) -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val deviceLevel = remember(context) { deviceBrightness(context) }
    Surface(modifier = modifier, shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surfaceContainerLow, shadowElevation = 6.dp) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                FilledTonalIconButton(onClick = { onChange { it.copy(fontSize = (it.fontSize - 1).coerceAtLeast(MIN_SIZE)) } }, enabled = settings.fontSize > MIN_SIZE) {
                    Text("A−", style = MaterialTheme.typography.labelLarge, modifier = Modifier.semantics { contentDescription = "Smaller text" })
                }
                Text("${settings.fontSize}", style = MaterialTheme.typography.labelLarge, textAlign = TextAlign.Center, modifier = Modifier.width(32.dp))
                FilledTonalIconButton(onClick = { onChange { it.copy(fontSize = (it.fontSize + 1).coerceAtMost(MAX_SIZE)) } }, enabled = settings.fontSize < MAX_SIZE) {
                    Text("A+", style = MaterialTheme.typography.labelLarge, modifier = Modifier.semantics { contentDescription = "Larger text" })
                }
                Spacer(Modifier.weight(1f))
                Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    ReaderTheme.entries.forEach { theme ->
                        ThemeSwatch(theme, settings.customColors, selected = settings.theme == theme, size = 30.dp) { onChange { it.copy(theme = theme) } }
                    }
                }
            }
            Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(ReaderIcons.BrightnessLow, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
                Slider(
                    value = settings.brightness ?: deviceLevel,
                    onValueChange = { value -> onBrightness((value * 100).roundToInt() / 100f) },
                    modifier = Modifier.weight(1f).padding(horizontal = 8.dp).semantics { contentDescription = "Brightness" },
                )
                Icon(ReaderIcons.BrightnessHigh, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(22.dp))
            }
        }
    }
}

private const val MIN_SIZE = 12
private const val MAX_SIZE = 32
