package io.github.nimbice.fanos.feature.reader

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsIgnoringVisibility
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import io.github.nimbice.fanos.core.model.NovelReaderSettings
import io.github.nimbice.fanos.core.model.ReaderAlignment
import io.github.nimbice.fanos.core.model.ReaderFont
import io.github.nimbice.fanos.core.model.ReaderSettings
import io.github.nimbice.fanos.core.model.ReaderSettingsSection
import io.github.nimbice.fanos.core.model.ReaderTheme
import java.util.Locale
import kotlin.math.roundToInt
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.selection.selectable
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import kotlinx.coroutines.launch
import android.content.Context
import android.provider.Settings
import io.github.nimbice.fanos.core.model.PageTurn
import io.github.nimbice.fanos.core.model.TapZones
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.layout.height
import kotlin.math.abs
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.SecondaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.unit.Dp
import io.github.nimbice.fanos.core.model.CustomReaderColors
import io.github.nimbice.fanos.core.model.ScrollBar

/**
 * Reading settings, applied to the open chapter as they change. A panel over the lower part of the
 * reader that scrolls, rather than a sheet that grows over the page: the text stays in view above
 * it, undimmed, so each change shows as it is made.
 *
 * At the top, whether the novel follows the defaults or keeps its own settings. With its own, each
 * section can be made the default for the novels that follow it; going back to the defaults drops them.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ReaderSettingsPanel(
    settings: NovelReaderSettings,
    onChange: ((ReaderSettings) -> ReaderSettings) -> Unit,
    onOwnChange: (Boolean) -> Unit,
    onSetAsDefault: (ReaderSettingsSection) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    replacements: Int = 0,
    onOpenReplacements: () -> Unit = {},
    onBrightness: (Float?) -> Unit = {},
    onWarmth: (Float) -> Unit = {},
    onDevice: ((ReaderSettings) -> ReaderSettings) -> Unit = {},
) {
    val shown = settings.settings
    val section: @Composable (String, ReaderSettingsSection) -> Unit = { title, which ->
        SectionHeader(title, own = settings.own, isDefault = settings.isDefault(which), onSetAsDefault = { onSetAsDefault(which) })
    }
    val context = LocalContext.current
    val assets = context.assets
    val fontFamilies = remember(assets) { ReaderFont.entries.associateWith { it.previewFamily(assets) } }
    // The fonts the reader added, each shown in itself; one in use that isn't here any more reads as the font before it.
    var ownFonts by remember { mutableStateOf(OwnFonts.list(context)) }
    val ownFamilies = remember(ownFonts) { ownFonts.associate { it.name to ownFontFamily(it) } }
    val ownFont = shown.ownFont?.takeIf { name -> ownFonts.any { it.name == name } }
    var removing by remember { mutableStateOf<OwnFont?>(null) }
    val scope = rememberCoroutineScope()
    val pickFont =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) {
                scope.launch {
                    val name = OwnFonts.add(context, uri)
                    if (name == null) {
                        Toast.makeText(context, "That file isn't a font this device can read", Toast.LENGTH_LONG).show()
                    } else {
                        ownFonts = OwnFonts.list(context)
                        onChange { it.copy(ownFont = name) }
                    }
                }
            }
        }
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shadowElevation = 8.dp,
    ) {
        Column {
            Row(
                Modifier.fillMaxWidth().padding(start = 20.dp, end = 4.dp, top = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Reading settings", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                IconButton(onClick = onClose) { Icon(Icons.Filled.Close, contentDescription = "Close") }
            }
            // The defaults or the novel's own, then the settings in three tabs: their row stays at the top as they scroll.
            var tab by rememberSaveable { mutableIntStateOf(0) }
            LazyColumn(Modifier.fillMaxWidth().weight(1f)) {
                item(key = "whose") {
                    Column(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(top = 4.dp)) {
                            SegmentedButton(selected = !settings.own, onClick = { onOwnChange(false) }, shape = SegmentedButtonDefaults.itemShape(0, 2)) {
                                Text("Defaults")
                            }
                            SegmentedButton(selected = settings.own, onClick = { onOwnChange(true) }, shape = SegmentedButtonDefaults.itemShape(1, 2)) {
                                Text("This novel")
                            }
                        }
                        Text(
                            if (settings.own) {
                                "Changes here are this novel's own. Defaults changed later leave it alone."
                            } else {
                                "Changes here change the defaults, for every novel that uses them."
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                }
                stickyHeader(key = "tabs") {
                    SecondaryTabRow(selectedTabIndex = tab, containerColor = MaterialTheme.colorScheme.surfaceContainerLow) {
                        listOf("Look", "Text", "Page").forEachIndexed { index, name ->
                            Tab(selected = tab == index, onClick = { tab = index }, text = { Text(name) })
                        }
                    }
                }
                item(key = "settings") {
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .windowInsetsPadding(WindowInsets.navigationBarsIgnoringVisibility.only(WindowInsetsSides.Bottom))
                            .padding(start = 20.dp, end = 20.dp, bottom = 20.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        when (tab) {
                            // Look: the colours, then the brightness and warmth over them.
                            0 -> {
                                section("Colours", ReaderSettingsSection.Colours)
                                FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                    ReaderTheme.entries.forEach { theme ->
                                        ThemeSwatch(theme, shown.customColors, selected = shown.theme == theme, size = 44.dp) { onChange { it.copy(theme = theme) } }
                                    }
                                }
                                if (shown.theme == ReaderTheme.Custom) {
                                    val custom = shown.customColors
                                    Text(
                                        "The last is your own: its colours below.",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.padding(top = 4.dp),
                                    )
                                    Label("Background")
                                    Swatches("Background", BACKGROUNDS, custom.background) { color -> onChange { it.copy(theme = ReaderTheme.Custom, customColors = it.customColors.copy(background = color)) } }
                                    Label("Text")
                                    Swatches("Text", TEXTS, custom.text) { color -> onChange { it.copy(theme = ReaderTheme.Custom, customColors = it.customColors.copy(text = color)) } }
                                    Label("Titles and links")
                                    Swatches("Titles and links", LINKS, custom.link) { color -> onChange { it.copy(theme = ReaderTheme.Custom, customColors = it.customColors.copy(link = color)) } }
                                }
                                // Brightness and warmth are the phone's, whichever settings the novel follows.
                                SectionHeader("Brightness", own = false, isDefault = true, onSetAsDefault = {})
                                BrightnessSlider(shown.brightness, onBrightness)
                                Text(
                                    "Only while reading. Below this device\u2019s lowest, the page dims further.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Label("Warmth")
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text("Cold", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    Slider(
                                        value = shown.warmth,
                                        // Near the middle it settles there, the page's own colours, so they're easy to come back to.
                                        onValueChange = { value -> onWarmth(if (abs(value) < NEUTRAL) 0f else (value * 100).roundToInt() / 100f) },
                                        valueRange = -1f..1f,
                                        modifier = Modifier.weight(1f).padding(horizontal = 8.dp).semantics { contentDescription = "Warmth" },
                                    )
                                    Text("Warm", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                Text(
                                    "A blue or amber tint over the page; in the middle, none. Warm is easier on the eyes at night.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            // Text: the font, its size and spacing, and words replaced in it.
                            1 -> {
                                section("Font", ReaderSettingsSection.Font)
                                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    ReaderFont.entries.forEach { font ->
                                        FilterChip(
                                            selected = ownFont == null && shown.font == font,
                                            onClick = { onChange { it.copy(font = font, ownFont = null) } },
                                            label = { Text(font.spec.label, fontFamily = fontFamilies.getValue(font)) },
                                        )
                                    }
                                    ownFonts.forEach { font ->
                                        FilterChip(
                                            selected = ownFont == font.name,
                                            onClick = { onChange { it.copy(ownFont = font.name) } },
                                            label = { Text(font.name, fontFamily = ownFamilies[font.name]) },
                                            modifier = Modifier.onLongPress { removing = font },
                                        )
                                    }
                                    AssistChip(
                                        onClick = { pickFont.launch(arrayOf("font/*", "application/*")) },
                                        label = { Text("Add font") },
                                        leadingIcon = { Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(AssistChipDefaults.IconSize)) },
                                    )
                                }
                                Text(
                                    if (ownFonts.isEmpty()) {
                                        "A .ttf or .otf file from this device; it's kept in the app."
                                    } else {
                                        "A .ttf or .otf file from this device; it's kept in the app. Long-press one you added to remove it."
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )

                                Label("Weight: ${weightName(shown.fontWeight)}")
                                Slider(
                                    value = shown.fontWeight.toFloat(),
                                    onValueChange = { value -> onChange { it.copy(fontWeight = ((value / 100).roundToInt() * 100).coerceIn(300, 700)) } },
                                    valueRange = 300f..700f,
                                    steps = 3,
                                    modifier = Modifier.semantics { contentDescription = "Font weight" },
                                )
                                section("Text size and spacing", ReaderSettingsSection.TextAndSpacing)
                                Label("Text size: ${shown.fontSize}")
                                Slider(
                                    value = shown.fontSize.toFloat(),
                                    onValueChange = { value -> onChange { it.copy(fontSize = value.roundToInt()) } },
                                    valueRange = 12f..32f,
                                    steps = 19,
                                )

                                Label("Line spacing: ${decimal(shown.lineHeight)}")
                                Slider(
                                    value = shown.lineHeight,
                                    onValueChange = { value -> onChange { it.copy(lineHeight = (value * 10).roundToInt() / 10f) } },
                                    valueRange = 1.2f..2.2f,
                                    steps = 9,
                                )

                                Label("Paragraph spacing: ${decimal(shown.paragraphSpacing)}")
                                Slider(
                                    value = shown.paragraphSpacing,
                                    onValueChange = { value -> onChange { it.copy(paragraphSpacing = (value * 10).roundToInt() / 10f) } },
                                    valueRange = 0f..2f,
                                    steps = 19,
                                )

                                Label("Margins: ${shown.margin}")
                                Slider(
                                    value = shown.margin.toFloat(),
                                    onValueChange = { value -> onChange { it.copy(margin = value.roundToInt()) } },
                                    valueRange = 0f..48f,
                                    steps = 11,
                                )

                                Label("Alignment")
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    ReaderAlignment.entries.forEach { alignment ->
                                        FilterChip(
                                            selected = shown.alignment == alignment,
                                            onClick = { onChange { it.copy(alignment = alignment) } },
                                            label = { Text(if (alignment == ReaderAlignment.Start) "Left" else "Justified") },
                                        )
                                    }
                                }
                                Row(
                                    Modifier.fillMaxWidth().padding(top = 12.dp).clip(RoundedCornerShape(12.dp)).clickable(onClick = onOpenReplacements).padding(vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Column(Modifier.weight(1f)) {
                                        Text("Word replacements", style = MaterialTheme.typography.bodyLarge)
                                        Text(
                                            if (replacements == 0) "Fix names and typos in the text" else "${replacements} in use",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null)
                                }
                            }
                            // Page: turning pages or scrolling, and taps.
                            else -> {
                                section("Page mode", ReaderSettingsSection.PageMode)
                                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                    Column(Modifier.weight(1f)) {
                                        Text("Turn pages sideways", style = MaterialTheme.typography.bodyLarge)
                                        Text("Instead of scrolling", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                    Switch(checked = shown.pageMode, onCheckedChange = { on -> onChange { it.copy(pageMode = on) } })
                                }
                                // Scrolling, how far through the chapter, at the page's edge.
                                if (!shown.pageMode) {
                                    Label("Progress in the chapter")
                                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        ScrollBar.entries.forEach { bar ->
                                            FilterChip(
                                                selected = shown.scrollBar == bar,
                                                onClick = { onChange { it.copy(scrollBar = bar) } },
                                                label = {
                                                    Text(
                                                        when (bar) {
                                                            ScrollBar.Off -> "Off"
                                                            ScrollBar.Edge -> "Down the edge"
                                                            ScrollBar.Top -> "Across the top"
                                                            ScrollBar.Thumb -> "While scrolling"
                                                        },
                                                    )
                                                },
                                            )
                                        }
                                    }
                                }
                                if (shown.pageMode) {
                                    Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                                        Column(Modifier.weight(1f)) {
                                            Text("Two pages side by side", style = MaterialTheme.typography.bodyLarge)
                                            Text("When the screen is wide", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        }
                                        Switch(checked = shown.twoPages, onCheckedChange = { on -> onChange { it.copy(twoPages = on) } })
                                    }
                                    Label("A page turns with")
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        PageTurn.entries.forEach { turn ->
                                            FilterChip(
                                                selected = shown.pageTurn == turn,
                                                onClick = { onChange { it.copy(pageTurn = turn) } },
                                                label = {
                                                    Text(
                                                        when (turn) {
                                                            PageTurn.Slide -> "Slide"
                                                            PageTurn.Fade -> "Fade"
                                                            PageTurn.Instant -> "No animation"
                                                        },
                                                    )
                                                },
                                            )
                                        }
                                    }
                                    Text(
                                        "Swipes, taps, volume keys and auto-scroll alike. With Fade or No animation the page doesn't follow the finger: it turns as the finger lifts.",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                // Turning pages sideways or scrolling, the sides of the page go to the previous page or screen and the next.
                                Label("Taps on the page")
                                TapZonesPicture(shown.tapZones)
                                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    TapZones.entries.forEach { zones ->
                                        FilterChip(
                                            selected = shown.tapZones == zones,
                                            onClick = { onChange { it.copy(tapZones = zones) } },
                                            label = {
                                                Text(
                                                    when (zones) {
                                                        TapZones.BackOn -> "Previous \u00b7 Next"
                                                        TapZones.OnBack -> "Next \u00b7 Previous"
                                                        TapZones.EitherOn -> "Next \u00b7 Next"
                                                    },
                                                )
                                            },
                                        )
                                    }
                                }
                                Text(
                                    "Next \u00b7 Previous suits the left hand; Next \u00b7 Next, one hand either way. Scrolling, a tap moves a screen.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )

                                // This device's, for every novel, whichever settings it follows.
                                SectionHeader("On this device", own = false, isDefault = true, onSetAsDefault = {})
                                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                    Column(Modifier.weight(1f)) {
                                        Text("Keep the screen on", style = MaterialTheme.typography.bodyLarge)
                                        Text("While a chapter is open", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                    Switch(checked = shown.keepScreenOn, onCheckedChange = { on -> onDevice { it.copy(keepScreenOn = on) } })
                                }
                                Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Column(Modifier.weight(1f)) {
                                        Text("Volume keys turn pages", style = MaterialTheme.typography.bodyLarge)
                                        Text("Or scroll a screen", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                    Switch(checked = shown.volumeKeysTurnPages, onCheckedChange = { on -> onDevice { it.copy(volumeKeysTurnPages = on) } })
                                }
                                Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Column(Modifier.weight(1f)) {
                                        Text("Clock and battery", style = MaterialTheme.typography.bodyLarge)
                                        Text("At the foot of the page, with the page number", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                    Switch(checked = shown.clockAndBattery, onCheckedChange = { on -> onDevice { it.copy(clockAndBattery = on) } })
                                }
                                Text(
                                    "For every novel, whichever settings it follows.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    removing?.let { font ->
        AlertDialog(
            onDismissRequest = { removing = null },
            title = { Text("Remove ${font.name}?") },
            text = { Text("It's taken out of the app. The file you added it from stays where it is.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        OwnFonts.remove(context, font.name)
                        ownFonts = OwnFonts.list(context)
                        if (shown.ownFont == font.name) onChange { it.copy(ownFont = null) }
                        removing = null
                    },
                ) { Text("Remove") }
            },
            dismissButton = { TextButton(onClick = { removing = null }) { Text("Cancel") } },
        )
    }
}

/** The page in three, each part saying what a tap there does. */
@Composable
private fun TapZonesPicture(zones: TapZones) {
    val outline = MaterialTheme.colorScheme.outline
    val sides = MaterialTheme.colorScheme.secondaryContainer
    Row(Modifier.fillMaxWidth().height(72.dp).clip(RoundedCornerShape(12.dp)).border(1.dp, outline, RoundedCornerShape(12.dp))) {
        val left = if (zones == TapZones.BackOn) "Previous" else "Next"
        val right = if (zones == TapZones.OnBack) "Previous" else "Next"
        for ((index, label) in listOf(left, "Controls", right).withIndex()) {
            Box(
                Modifier.weight(1f).fillMaxHeight().background(if (index == 1) Color.Transparent else sides),
                contentAlignment = Alignment.Center,
            ) { Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
}

/** A font weight's name, as type pickers say it. */
private fun weightName(weight: Int): String =
    when {
        weight <= 300 -> "Light"
        weight <= 400 -> "Regular"
        weight <= 500 -> "Medium"
        weight <= 600 -> "Semibold"
        else -> "Bold"
    }

/** How near the middle the warmth slider settles in it. */
private const val NEUTRAL = 0.06f

/** Where this device's own brightness sits on the brightness slider, for it to start from. */
internal fun deviceBrightness(context: Context): Float {
    val level = runCatching { Settings.System.getInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS) }.getOrDefault(128)
    return sliderOf(level.coerceIn(0, 255) / 255f)
}

/** A row of colours to choose a custom reading colour from, [chosen] ringed. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Swatches(name: String, colors: List<Int>, chosen: Int, onChoose: (Int) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(7.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
        colors.forEachIndexed { index, color ->
            val on = color == chosen
            val shape = RoundedCornerShape(8.dp)
            Box(
                Modifier
                    .size(30.dp)
                    .clip(shape)
                    .background(rgb(color))
                    .border(if (on) BorderStroke(3.dp, MaterialTheme.colorScheme.primary) else BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant), shape)
                    .semantics { contentDescription = "$name ${index + 1}" }
                    .selectable(selected = on, role = Role.RadioButton) { onChoose(color) },
            )
        }
    }
}

/**
 * [onLongPress] for a long press anywhere on this, seen before what's inside it: the lift that ends the press is
 * used up, so a chip inside doesn't take it for a tap as well.
 */
private fun Modifier.onLongPress(onLongPress: () -> Unit): Modifier =
    pointerInput(onLongPress) {
        awaitEachGesture {
            awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            val lifted = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) { waitForUpOrCancellation(PointerEventPass.Initial) ?: Unit }
            if (lifted != null) return@awaitEachGesture
            onLongPress()
            do {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                event.changes.forEach { it.consume() }
            } while (event.changes.any { it.pressed })
        }
    }

/** A reading theme to choose: its page colour with "Aa" in its text colour, ringed when it's the one chosen. */
@Composable
internal fun ThemeSwatch(theme: ReaderTheme, custom: CustomReaderColors, selected: Boolean, size: Dp, onClick: () -> Unit) {
    val colors = theme.colors(custom)
    Box(
        Modifier
            .size(size)
            .clip(CircleShape)
            .background(colors.background)
            .border(if (selected) BorderStroke(size / 15, MaterialTheme.colorScheme.primary) else BorderStroke(1.dp, MaterialTheme.colorScheme.outline), CircleShape)
            .semantics(mergeDescendants = true) { contentDescription = theme.label }
            .clickable(role = Role.RadioButton, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            "Aa",
            color = colors.text,
            style = if (size < 40.dp) MaterialTheme.typography.labelSmall else MaterialTheme.typography.labelLarge,
            modifier = Modifier.clearAndSetSemantics {},
        )
    }
}

private val ReaderTheme.label: String
    get() = if (this == ReaderTheme.Custom) "Your own colours" else name

// Colours for the reader's own: light backgrounds then dark, dark text then light, and titles and links for each.
private val BACKGROUNDS = listOf(0xFFFFFF, 0xFBF8F1, 0xF4ECD8, 0xE8F0E3, 0xE6EEF5, 0x3A3530, 0x2B2621, 0x1E2A33, 0x1B2420, 0x15120F, 0x101418, 0x000000)
private val TEXTS = listOf(0x1F1B16, 0x3A2E22, 0x4B3A2A, 0x2E3B44, 0x5A5047, 0xE8E2D0, 0xD9CFC2, 0xC9C4BC, 0xBFD3C0, 0xC4D2DE)
private val LINKS = listOf(0x7A5A00, 0x8A5A1E, 0xA0432E, 0x2E6B4F, 0x2D5F8A, 0xF2C46D, 0xE8B95E, 0xE39B7B, 0x8FCB9F, 0x7FB3D5)

/**
 * A section's name, and with the novel's own settings, a way to make the section the default: once it's
 * the same as the default, the button says so instead.
 */
@Composable
private fun SectionHeader(title: String, own: Boolean, isDefault: Boolean, onSetAsDefault: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(top = 14.dp).heightIn(min = 40.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
        if (own) {
            TextButton(onClick = onSetAsDefault, enabled = !isDefault) { Text(if (isDefault) "The default" else "Set as default") }
        }
    }
}

/** A setting's name within a section, with its value. */
@Composable
private fun Label(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 6.dp),
    )
}

private fun decimal(value: Float): String = String.format(Locale.ROOT, "%.1f", value)
