package io.github.nimbice.fanos.feature.reader.listen

import android.text.format.DateFormat
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsIgnoringVisibility
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.nimbice.fanos.core.designsystem.component.fittingStyle
import io.github.nimbice.fanos.core.designsystem.icon.ReaderIcons
import io.github.nimbice.fanos.core.model.ListeningSettings
import io.github.nimbice.fanos.core.model.Pronunciation
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.delay

/** What the listening controls do: [Narrator]'s steps, and the settings. */
internal class ListeningActions(
    val onToggle: () -> Unit,
    val onSentenceBack: () -> Unit,
    val onSentenceOn: () -> Unit,
    val onChapterBack: () -> Unit,
    val onChapterOn: () -> Unit,
    val onStopAfter: (StopAfter) -> Unit,
    val onSpeed: (Float) -> Unit,
    val onVoice: (String?) -> Unit,
    val onChime: (Boolean) -> Unit,
    val onPronunciations: (List<Pronunciation>) -> Unit,
    val onStop: () -> Unit,
    val voices: suspend () -> List<VoiceOption>,
    /** The natural voices: what's on the phone, the voices, and getting or deleting them. */
    val natural: NaturalVoicesUi,
)

/** What the voice picker shows and does of the natural voices. */
internal class NaturalVoicesUi(
    val pack: kotlinx.coroutines.flow.StateFlow<NaturalPack>,
    val voices: List<NaturalVoice>,
    val onWifi: () -> Boolean,
    val onDownload: () -> Unit,
    val onCancel: () -> Unit,
    val onDelete: () -> Unit,
)

/**
 * Listening's panel, over the lower part of the reader as the settings panel is: where it's reading and the steps back
 * and on, then how it sounds and when it stops.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ListeningPanel(
    state: ListeningState?,
    settings: ListeningSettings,
    actions: ListeningActions,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var picking by rememberSaveable { mutableStateOf(false) }
    var editing by rememberSaveable { mutableStateOf(false) }
    // The chosen voice as the picker names it.
    val voiceLabel by produceState<String?>(null, settings.voice) {
        val chosen = settings.voice
        value = NaturalVoice.sidOf(chosen)?.let { sid -> actions.natural.voices.getOrNull(sid)?.label } ?: chosen?.let { name -> actions.voices().firstOrNull { it.name == name }?.label }
    }
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shadowElevation = 8.dp,
    ) {
        Column {
            Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 4.dp, top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Listening", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                IconButton(onClick = onClose) { Icon(Icons.Filled.Close, contentDescription = "Close") }
            }
            Column(
                Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .windowInsetsPadding(WindowInsets.navigationBarsIgnoringVisibility.only(WindowInsetsSides.Bottom))
                    .padding(start = 20.dp, end = 20.dp, bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    state?.problem ?: whereAt(state),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (state?.problem != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                BoxWithConstraints(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    // The four steps share what the play button leaves, their names one line and one size at any text size.
                    val labels = fittingStyle(listOf("Chapter", "Sentence"), (maxWidth - PLAY.dp) / 4 - STEP_PADDING * 2, MaterialTheme.typography.labelSmall)
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Step(ReaderIcons.SkipPrevious, "Chapter", "Previous chapter", labels, actions.onChapterBack)
                        Step(ReaderIcons.Replay, "Sentence", "Back a sentence", labels, actions.onSentenceBack)
                        PlayButton(playing = state?.playing == true, onClick = actions.onToggle, size = PLAY)
                        Step(ReaderIcons.Forward, "Sentence", "On a sentence", labels, actions.onSentenceOn)
                        Step(ReaderIcons.SkipNext, "Chapter", "Next chapter", labels, actions.onChapterOn)
                    }
                }

                Label("Speed")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SPEEDS.forEach { speed ->
                        FilterChip(selected = settings.speed == speed, onClick = { actions.onSpeed(speed) }, label = { Text(speedLabel(speed)) })
                    }
                }
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { picking = true }.padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("Voice", style = MaterialTheme.typography.bodyLarge)
                        Text(if (settings.voice == null) "This device's own" else voiceLabel ?: "Chosen", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null)
                }
                Label("Stop after")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    StopAfter.entries.forEach { choice ->
                        FilterChip(selected = state?.stopAfter == choice, onClick = { actions.onStopAfter(choice) }, label = { Text(stopLabel(choice)) })
                    }
                }
                stopsWhen(state)?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }

                Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Chime between chapters", style = MaterialTheme.typography.bodyLarge)
                        Text("A soft two notes as the next begins", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(checked = settings.chime, onCheckedChange = actions.onChime)
                }
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { editing = true }.padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("Pronunciations", style = MaterialTheme.typography.bodyLarge)
                        Text(
                            if (settings.pronunciations.isEmpty()) "Words said your way" else "Words said your way: ${settings.pronunciations.size}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null)
                }
                if (state != null) {
                    TextButton(onClick = actions.onStop, modifier = Modifier.align(Alignment.End)) { Text("Stop listening") }
                }
            }
        }
    }
    if (picking) VoicePicker(settings.voice, actions.voices, actions.natural, onPick = { name -> actions.onVoice(name); picking = false }, onDismiss = { picking = false })
    if (editing) PronunciationsEditor(settings.pronunciations, actions.onPronunciations, onDismiss = { editing = false })
}

/**
 * The small player over the page while listening, when the reader's controls are away: back a sentence, play or pause,
 * on a sentence, where it's at, the speed (a tap goes to the next), and the way up to the panel.
 */
@Composable
internal fun MiniPlayer(state: ListeningState, speed: Float, onToggle: () -> Unit, onBack: () -> Unit, onOn: () -> Unit, onSpeed: (Float) -> Unit, onOpen: () -> Unit, modifier: Modifier = Modifier) {
    Surface(modifier = modifier, shape = RoundedCornerShape(22.dp), color = MaterialTheme.colorScheme.inverseSurface, shadowElevation = 6.dp) {
        Row(Modifier.padding(horizontal = 6.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(ReaderIcons.Replay, contentDescription = "Back a sentence") }
            PlayButton(playing = state.playing, onClick = onToggle, size = 44)
            IconButton(onClick = onOn) { Icon(ReaderIcons.Forward, contentDescription = "On a sentence") }
            Column(Modifier.weight(1f).padding(horizontal = 4.dp)) {
                Text(state.chapterTitle, style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val below =
                    // The time left first, where it isn't cut off.
                    state.problem ?: listOfNotNull(stopsWhen(state, short = true), if (state.count > 0) "Sentence ${state.index + 1} of ${state.count}" else null)
                        .joinToString(" · ").ifEmpty { null }
                if (below != null) Text(below, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Text(
                speedLabel(speed),
                style = MaterialTheme.typography.labelMedium,
                modifier =
                    Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { onSpeed(SPEEDS[(SPEEDS.indexOf(speed) + 1).mod(SPEEDS.size)]) }
                        .padding(horizontal = 8.dp, vertical = 6.dp),
            )
            IconButton(onClick = onOpen) { Icon(Icons.Filled.KeyboardArrowUp, contentDescription = "Listening settings") }
        }
    }
}

@Composable
private fun PlayButton(playing: Boolean, onClick: () -> Unit, size: Int) {
    Box(
        Modifier.size(size.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary).clickable(role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            if (playing) ReaderIcons.Pause else Icons.Filled.PlayArrow,
            contentDescription = if (playing) "Pause" else "Play",
            tint = MaterialTheme.colorScheme.onPrimary,
            modifier = Modifier.size((size * 0.55f).dp),
        )
    }
}

@Composable
private fun RowScope.Step(icon: ImageVector, label: String, description: String, style: TextStyle, onClick: () -> Unit) {
    Column(
        Modifier.weight(1f).clip(RoundedCornerShape(12.dp)).clickable(onClick = onClick).padding(horizontal = STEP_PADDING, vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(icon, contentDescription = description)
        Text(label, style = style, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, softWrap = false)
    }
}

@Composable
private fun Label(text: String) {
    Text(text, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 8.dp))
}

/** The phone's voices for the language, to pick one of; they load when the picker opens. */
@Composable
private fun VoicePicker(chosen: String?, voices: suspend () -> List<VoiceOption>, natural: NaturalVoicesUi, onPick: (String?) -> Unit, onDismiss: () -> Unit) {
    var options by remember { mutableStateOf<List<VoiceOption>?>(null) }
    val pack by natural.pack.collectAsState()
    var asking by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { options = voices() }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Voice") },
        text = {
            LazyColumn(Modifier.heightIn(max = 460.dp)) {
                item { VoiceRow("This device's own", "Whichever voice this device's settings choose", chosen == null) { onPick(null) } }
                item { Section("Natural voices \u00b7 offline") }
                when (val state = pack) {
                    NaturalPack.Ready -> {
                        items(natural.voices, key = { it.id }) { voice -> VoiceRow(voice.label, "Natural \u00b7 on this device", chosen == voice.id) { onPick(voice.id) } }
                        item { TextButton(onClick = { deleting = true }) { Text("Delete natural voices") } }
                    }
                    is NaturalPack.Downloading ->
                        item {
                            val fraction = state.fraction
                            PackRow(if (fraction == null) "Unpacking\u2026" else "Downloading\u2026 ${(fraction * 100).toInt()}%") {
                                TextButton(onClick = natural.onCancel) { Text("Cancel") }
                            }
                            if (fraction == null) {
                                LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 8.dp))
                            } else {
                                LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp))
                            }
                        }
                    else ->
                        item {
                            val wifi = remember { natural.onWifi() }
                            val detail =
                                when {
                                    state is NaturalPack.Failed -> "${state.reason}. 11 voices, US and UK \u00b7 103 MB"
                                    !wifi -> "Connect to Wi-Fi to download \u00b7 11 voices, US and UK \u00b7 103 MB"
                                    else -> "11 voices, US and UK \u00b7 103 MB"
                                }
                            PackRow(detail) {
                                OutlinedButton(onClick = { asking = true }, enabled = wifi) { Text(if (state is NaturalPack.Failed) "Try again" else "Download") }
                            }
                            Text(
                                "Kokoro, read on this device, offline. Downloads on Wi-Fi only, and is checked before use. With a natural voice the page follows sentence by sentence. " +
                                    "The voices are hexgrad's Kokoro (Apache-2.0), packed for sherpa-onnx by k2-fsa with eSpeak NG's data (GPL-3.0).",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                            )
                        }
                }
                item { Section("This device's voices") }
                val list = options
                if (list == null) {
                    item { Text("Looking for voices…", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(12.dp)) }
                } else {
                    items(list, key = { it.name }) { voice -> VoiceRow(voice.label, voice.detail, chosen == voice.name) { onPick(voice.name) } }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
    )
    if (asking) {
        AlertDialog(
            onDismissRequest = { asking = false },
            title = { Text("Download natural voices?") },
            text = { Text("103 MB, over Wi-Fi. Once on this device (about 120 MB) they read offline.") },
            confirmButton = {
                TextButton(onClick = {
                    asking = false
                    natural.onDownload()
                }) { Text("Download") }
            },
            dismissButton = { TextButton(onClick = { asking = false }) { Text("Cancel") } },
        )
    }
    if (deleting) {
        AlertDialog(
            onDismissRequest = { deleting = false },
            title = { Text("Delete natural voices?") },
            text = { Text("Listening goes back to this device's voices. They can be downloaded again.") },
            confirmButton = {
                TextButton(onClick = {
                    deleting = false
                    if (NaturalVoice.sidOf(chosen) != null) onPick(null)
                    natural.onDelete()
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { deleting = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun Section(title: String) {
    Text(title.uppercase(), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(start = 8.dp, top = 12.dp, bottom = 2.dp))
}

/** The natural voices' pack, before it's on the phone: its name, where it's at, and what can be done. */
@Composable
private fun PackRow(detail: String, action: @Composable () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("Kokoro English", style = MaterialTheme.typography.bodyLarge)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        action()
    }
}

@Composable
private fun VoiceRow(label: String, detail: String, selected: Boolean, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable(onClick = onClick).padding(horizontal = 8.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (selected) Icon(Icons.Filled.Check, contentDescription = "Chosen", tint = MaterialTheme.colorScheme.primary)
    }
}

/** Words said the reader's way: the list, and a word to add with what to say for it. */
@Composable
private fun PronunciationsEditor(pronunciations: List<Pronunciation>, onChange: (List<Pronunciation>) -> Unit, onDismiss: () -> Unit) {
    var word by rememberSaveable { mutableStateOf("") }
    var sayAs by rememberSaveable { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Pronunciations") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("The voice says each word the way written beside it, whatever its capitals.", style = MaterialTheme.typography.bodySmall)
                LazyColumn(Modifier.heightIn(max = 220.dp)) {
                    items(pronunciations, key = { it.word }) { entry ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("${entry.word}  →  ${entry.sayAs}", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                            IconButton(onClick = { onChange(pronunciations - entry) }) { Icon(Icons.Filled.Close, contentDescription = "Take ${entry.word} off") }
                        }
                    }
                }
                OutlinedTextField(value = word, onValueChange = { word = it.replace("\t", "").replace("\n", "") }, label = { Text("Word") }, singleLine = true)
                OutlinedTextField(value = sayAs, onValueChange = { sayAs = it.replace("\t", "").replace("\n", "") }, label = { Text("Say it as") }, singleLine = true)
                Row {
                    Spacer(Modifier.weight(1f))
                    TextButton(
                        enabled = word.isNotBlank() && sayAs.isNotBlank(),
                        onClick = {
                            val entry = Pronunciation(word.trim(), sayAs.trim())
                            onChange(pronunciations.filterNot { it.word.equals(entry.word, ignoreCase = true) } + entry)
                            word = ""
                            sayAs = ""
                        },
                    ) { Text("Add") }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
    )
}

private fun whereAt(state: ListeningState?): String =
    when {
        state == null -> "Plays from where you are on the page"
        state.loading -> "${state.chapterTitle} · getting the chapter…"
        state.count > 0 -> "${state.chapterTitle} · sentence ${state.index + 1} of ${state.count} · the page follows along"
        else -> state.chapterTitle
    }

/** When listening stops, if it's set to: the minutes left (counting down), and but for [short], the time. */
@Composable
private fun stopsWhen(state: ListeningState?, short: Boolean = false): String? {
    val context = LocalContext.current
    val stopsAt = state?.stopsAt
    val now by produceState(System.currentTimeMillis(), stopsAt) {
        while (stopsAt != null) {
            value = System.currentTimeMillis()
            delay(TICK_MS)
        }
    }
    return when {
        state == null -> null
        state.stopAfter == StopAfter.Chapter -> "Stops at the end of the chapter"
        stopsAt != null -> {
            val minutes = ((stopsAt - now + 59_999) / 60_000).coerceAtLeast(1)
            if (short) "Stops in $minutes min" else "Stops in $minutes min, at ${DateFormat.getTimeFormat(context).format(Date(stopsAt))}"
        }
        else -> null
    }
}

/** How often the minutes left are counted again. */
private const val TICK_MS = 15_000L

private fun stopLabel(choice: StopAfter): String =
    when (choice) {
        StopAfter.Off -> "Off"
        StopAfter.Chapter -> "This chapter"
        StopAfter.Quarter -> "15 min"
        StopAfter.HalfHour -> "30 min"
        StopAfter.ThreeQuarters -> "45 min"
        StopAfter.Hour -> "1 h"
    }

private fun speedLabel(speed: Float): String = if (speed % 1f == 0f) "${speed.toInt()}×" else "${String.format(Locale.ROOT, "%.2f", speed).trimEnd('0')}×"

private val SPEEDS = listOf(0.8f, 1f, 1.25f, 1.5f, 2f)

/** The play button's size in the panel, in dp, and the room a step keeps either side of its name. */
private const val PLAY = 60
private val STEP_PADDING = 4.dp
