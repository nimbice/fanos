package io.github.nimbice.fanos.feature.settings

import android.content.Context
import android.net.Uri
import android.text.format.DateUtils
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.nimbice.fanos.core.common.suspendRunCatching
import io.github.nimbice.fanos.core.data.backup.BackupActivity
import io.github.nimbice.fanos.core.data.backup.BackupRepository
import io.github.nimbice.fanos.core.data.backup.NovelLibraryImporter
import io.github.nimbice.fanos.core.data.backup.NovelLibraryPreview
import io.github.nimbice.fanos.core.data.backup.RestorePreview
import io.github.nimbice.fanos.core.data.backup.RestoreResult
import io.github.nimbice.fanos.core.data.repository.SettingsRepository
import io.github.nimbice.fanos.core.designsystem.component.countOf
import io.github.nimbice.fanos.core.model.BackupInterval
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.IOException
import javax.inject.Inject

/** The backup settings as the screen shows them. */
data class BackupUi(
    /** False until the folder has been looked at, so nothing flashes "no folder" first. */
    val loaded: Boolean = false,
    val interval: BackupInterval = BackupInterval.Daily,
    /** The backup folder's name; null when there is none (before Android 10, until one is picked). */
    val folder: String? = null,
    /** The folder was picked, rather than the default one. */
    val picked: Boolean = false,
    /** Backups can go back to the default folder. */
    val canUseDefault: Boolean = false,
    val writable: Boolean = true,
    val lastBackupAt: Long? = null,
    val failure: String? = null,
)

/** Where an import from NovelLibrary is: from reading the file the reader picked to what came of it. */
sealed interface ImportStep {
    data object Reading : ImportStep

    data class Ready(val preview: NovelLibraryPreview) : ImportStep

    data object Importing : ImportStep

    data class Done(val result: RestoreResult) : ImportStep

    data class Failed(val message: String) : ImportStep
}

/** Where a restore is: from reading the file the reader picked to what came of it. */
sealed interface RestoreStep {
    data object Reading : RestoreStep

    data class Ready(val preview: RestorePreview) : RestoreStep

    data object Restoring : RestoreStep

    data class Done(val result: RestoreResult) : RestoreStep

    data class Failed(val message: String) : RestoreStep
}

@HiltViewModel
class BackupViewModel @Inject constructor(
    private val backups: BackupRepository,
    private val settings: SettingsRepository,
    private val novelLibrary: NovelLibraryImporter,
) : ViewModel() {

    // Android's grant to the folder can go while the screen is away (taken back in Android's settings).
    private val recheck = MutableStateFlow(0)

    val ui: StateFlow<BackupUi> =
        combine(settings.appSettings, backups.status, recheck) { app, status, _ ->
            BackupUi(
                loaded = true,
                interval = app.backupInterval,
                folder = backups.folderLabel(status.folder),
                picked = status.folder != null,
                canUseDefault = backups.hasDefaultFolder,
                writable = backups.canWriteTo(status.folder),
                lastBackupAt = status.lastBackupAt,
                failure = status.failure,
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), BackupUi())

    val activity: StateFlow<BackupActivity?> = backups.activity

    private val restoreStep = MutableStateFlow<RestoreStep?>(null)
    val restore: StateFlow<RestoreStep?> = restoreStep.asStateFlow()

    private val notices = Channel<String>(Channel.BUFFERED)

    /** Short messages for a snackbar. */
    val messages: Flow<String> = notices.receiveAsFlow()

    fun recheck() = recheck.update { it + 1 }

    fun setInterval(interval: BackupInterval) {
        viewModelScope.launch { settings.updateAppSettings { it.copy(backupInterval = interval) } }
    }

    /** Takes [tree] as the backup folder and backs up into it straight away, which shows it works. */
    fun chooseFolder(tree: Uri) {
        viewModelScope.launch {
            val outcome =
                suspendRunCatching {
                    backups.chooseFolder(tree)
                    backups.backUpToFolder()
                    backups.folderLabel(tree.toString())
                }
            notices.send(outcome.fold({ "Backed up to $it" }, ::describe))
        }
    }

    /** Backs up to the default folder again, and does so straight away. */
    fun useDefaultFolder() {
        viewModelScope.launch {
            val outcome =
                suspendRunCatching {
                    backups.useDefaultFolder()
                    backups.backUpToFolder()
                }
            notices.send(outcome.fold({ "Backed up to ${BackupRepository.DEFAULT_FOLDER}" }, ::describe))
        }
    }

    fun backUpNow() {
        viewModelScope.launch { notices.send(suspendRunCatching { backups.backUpToFolder() }.fold({ "Backed up" }, ::describe)) }
    }

    fun fileName(): String = backups.fileName()

    fun saveTo(file: Uri) {
        viewModelScope.launch { notices.send(suspendRunCatching { backups.backUpTo(file) }.fold({ "Backup saved" }, ::describe)) }
    }

    fun inspect(file: Uri) {
        restoreStep.value = RestoreStep.Reading
        viewModelScope.launch {
            restoreStep.value = suspendRunCatching { backups.inspect(file) }.fold({ RestoreStep.Ready(it) }, { RestoreStep.Failed(describe(it)) })
        }
    }

    fun restore(preview: RestorePreview, withSettings: Boolean) {
        restoreStep.value = RestoreStep.Restoring
        viewModelScope.launch {
            restoreStep.value =
                suspendRunCatching { backups.restore(preview, withSettings) }.fold({ RestoreStep.Done(it) }, { RestoreStep.Failed(describe(it)) })
        }
    }

    private val importStep = MutableStateFlow<ImportStep?>(null)

    /** Where an import from NovelLibrary is. */
    val import: StateFlow<ImportStep?> = importStep.asStateFlow()

    /** Reads the NovelLibrary backup in [file] and says what importing it would do. */
    fun inspectNovelLibrary(file: Uri) {
        importStep.value = ImportStep.Reading
        viewModelScope.launch {
            importStep.value = suspendRunCatching { novelLibrary.inspect(file) }.fold({ ImportStep.Ready(it) }, { ImportStep.Failed(describe(it)) })
        }
    }

    fun importNovelLibrary(preview: NovelLibraryPreview) {
        importStep.value = ImportStep.Importing
        viewModelScope.launch {
            importStep.value = suspendRunCatching { novelLibrary.import(preview) }.fold({ ImportStep.Done(it) }, { ImportStep.Failed(describe(it)) })
        }
    }

    fun closeImport() {
        importStep.value = null
    }

    fun closeRestore() {
        (restoreStep.value as? RestoreStep.Ready)?.let { backups.discard(it.preview) }
        restoreStep.value = null
    }

    override fun onCleared() {
        (restoreStep.value as? RestoreStep.Ready)?.let { backups.discard(it.preview) }
    }

    // The backup code's own errors carry messages for the screen; the rest come from files.
    private fun describe(error: Throwable): String =
        when (error) {
            is IOException -> "Couldn't read or write the file: ${error.message ?: "Android refused"}."
            is SecurityException -> "Android didn't let Fanos use that file."
            else -> error.message ?: "Something went wrong (${error.javaClass.simpleName})."
        }
}

/** Where backups go and how often, backing up now, saving a backup file, and restoring one. */
@Composable
internal fun BackupSettings(snackbar: SnackbarHostState, viewModel: BackupViewModel = hiltViewModel()) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    val activity by viewModel.activity.collectAsStateWithLifecycle()
    val restore by viewModel.restore.collectAsStateWithLifecycle()
    val import by viewModel.import.collectAsStateWithLifecycle()
    LaunchedEffect(viewModel) { viewModel.messages.collect { snackbar.showSnackbar(it) } }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.recheck() }

    val pickFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { tree -> tree?.let(viewModel::chooseFolder) }
    val saveFile =
        rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(BackupRepository.FILE_TYPE)) { file -> file?.let(viewModel::saveTo) }
    val openFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { file -> file?.let(viewModel::inspect) }
    val openNovelLibrary = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { file -> file?.let(viewModel::inspectNovelLibrary) }
    val idle = activity == null

    val hasFolder = ui.folder != null
    SettingsGroup {
        item {
            SettingsItem(
                "Backup folder",
                Modifier.clickable(enabled = idle) { pickFolder.launch(null) },
                summary = { FolderSummary(ui) },
                trailing = { FolderButton(ui, enabled = idle, onPick = { pickFolder.launch(null) }, onDefault = viewModel::useDefaultFolder) },
            )
        }
        item {
            ChoiceItem("Back up automatically", null, BackupInterval.entries, ui.interval, label = { it.label }, enabled = hasFolder, onSelect = viewModel::setInterval)
        }
        item { ActionItem("Back up now", "Into the backup folder", enabled = hasFolder && idle, button = "Back up", onClick = viewModel::backUpNow) }
    }
    SettingsGroup {
        item {
            ActionItem("Save a backup file", "Anywhere Android can save one, such as a cloud drive", enabled = idle, button = "Save") {
                saveFile.launch(viewModel.fileName())
            }
        }
        item {
            ActionItem("Restore a backup", "Adds its novels and reading progress to the library; removes nothing", enabled = idle, button = "Restore") {
                openFile.launch(arrayOf("*/*"))
            }
        }
    }
    SettingsGroup {
        item {
            ActionItem("Import from NovelLibrary", "Its library, sections and reading progress, from a NovelLibrary backup file", enabled = idle, button = "Import") {
                openNovelLibrary.launch(arrayOf("application/zip", "application/octet-stream", "*/*"))
            }
        }
    }
    activity?.let { Box(Modifier.padding(horizontal = 16.dp)) { Working(it) } }

    restore?.let { step -> RestoreDialog(step, activity, onRestore = viewModel::restore, onClose = viewModel::closeRestore) }
    import?.let { step -> ImportDialog(step, activity, onImport = viewModel::importNovelLibrary, onClose = viewModel::closeImport) }
}

@Composable
private fun FolderSummary(ui: BackupUi) {
    val context = LocalContext.current
    val error = MaterialTheme.colorScheme.error
    when {
        !ui.loaded -> Text("")
        // Something to do: said in red, beside a button to do it.
        ui.folder == null -> Text("Choose where backups go, and Fanos backs up the library there by itself", color = error)
        !ui.writable -> Text("Fanos can't write to ${ui.folder} any more. Choose it again.", color = error)
        // One text, so the item goes back to its two-line height when the failure goes: with the
        // failure as a second Text in a Column, it kept the three-line height.
        else ->
            Text(
                buildAnnotatedString {
                    append("${ui.folder} · ")
                    append(ui.lastBackupAt?.let { "last backup ${ago(context, it)}" } ?: "no backups yet")
                    ui.failure?.let { failure -> withStyle(SpanStyle(color = error)) { append("\n$failure") } }
                },
            )
    }
}

/**
 * The way to the folder picker: "Choose" when there's no folder to back up to, "Change" otherwise,
 * which also offers the default folder back once another was picked.
 */
@Composable
private fun FolderButton(ui: BackupUi, enabled: Boolean, onPick: () -> Unit, onDefault: () -> Unit) {
    if (!ui.loaded) return
    var menu by remember { mutableStateOf(false) }
    Box {
        if (ui.folder == null || !ui.writable) {
            FilledTonalButton(onClick = onPick, enabled = enabled) { Text("Choose") }
        } else {
            OutlinedButton(onClick = { if (ui.picked && ui.canUseDefault) menu = true else onPick() }, enabled = enabled) { Text("Change") }
        }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem(
                text = { Text("Choose another folder") },
                onClick = {
                    menu = false
                    onPick()
                },
            )
            DropdownMenuItem(
                text = { Text("Back to ${BackupRepository.DEFAULT_FOLDER}") },
                onClick = {
                    menu = false
                    onDefault()
                },
            )
        }
    }
}

@Composable
private fun Working(activity: BackupActivity) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        when (activity) {
            BackupActivity.BackingUp ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    Text("Backing up…", style = MaterialTheme.typography.bodyMedium)
                }
            is BackupActivity.Restoring -> RestoreProgress(activity)
        }
    }
}

@Composable
private fun RestoreProgress(activity: BackupActivity.Restoring?) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        val total = activity?.total ?: 0
        Text(if (total > 0) "Restored ${activity?.done ?: 0} of ${novels(total)}" else "Restoring…", style = MaterialTheme.typography.bodyMedium)
        if (total > 0) {
            LinearProgressIndicator(progress = { (activity?.done ?: 0).toFloat() / total }, modifier = Modifier.fillMaxWidth())
        } else {
            LinearProgressIndicator(Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun RestoreDialog(
    step: RestoreStep,
    activity: BackupActivity?,
    onRestore: (RestorePreview, Boolean) -> Unit,
    onClose: () -> Unit,
) {
    when (step) {
        RestoreStep.Reading ->
            AlertDialog(
                onDismissRequest = {},
                confirmButton = {},
                title = { Text("Reading the backup") },
                text = { LinearProgressIndicator(Modifier.fillMaxWidth()) },
            )
        is RestoreStep.Ready -> {
            val preview = step.preview
            var withSettings by remember(preview) { mutableStateOf(preview.hasSettings) }
            AlertDialog(
                onDismissRequest = onClose,
                title = { Text("Restore this backup?") },
                text = { PreviewText(preview, withSettings) { withSettings = it } },
                confirmButton = { TextButton(onClick = { onRestore(preview, withSettings) }) { Text("Restore") } },
                dismissButton = { TextButton(onClick = onClose) { Text("Cancel") } },
            )
        }
        // Not to be waved away: the dialog is how the reader learns it finished.
        RestoreStep.Restoring ->
            AlertDialog(
                onDismissRequest = {},
                confirmButton = {},
                title = { Text("Restoring") },
                text = { RestoreProgress(activity as? BackupActivity.Restoring) },
            )
        is RestoreStep.Done ->
            AlertDialog(
                onDismissRequest = onClose,
                title = { Text(if (step.result.failed.isEmpty()) "Restored" else "Partly restored") },
                text = { Text(outcome(step.result)) },
                confirmButton = { TextButton(onClick = onClose) { Text("OK") } },
            )
        is RestoreStep.Failed ->
            AlertDialog(
                onDismissRequest = onClose,
                title = { Text("Can't restore that") },
                text = { Text(step.message) },
                confirmButton = { TextButton(onClick = onClose) { Text("OK") } },
            )
    }
}

@Composable
private fun PreviewText(preview: RestorePreview, withSettings: Boolean, onWithSettings: (Boolean) -> Unit) {
    val context = LocalContext.current
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        val made = DateUtils.formatDateTime(context, preview.createdAt, DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_YEAR or DateUtils.FORMAT_SHOW_TIME)
        Text("Made $made" + (preview.app?.let { " by Fanos $it" } ?: "") + ".")
        Text(
            when {
                preview.novels == 0 -> "It has no novels."
                preview.joining == 0 -> "${novels(preview.novels)}, all in the library already."
                preview.joining == preview.novels -> "${novels(preview.novels)}, none of them in the library yet."
                else -> "${novels(preview.novels)}, ${preview.joining} of them not in the library yet."
            },
        )
        Text("Nothing on this device is removed: a chapter read in either stays read, and the latest reading positions win.")
        if (preview.unavailable.isNotEmpty()) {
            val some = preview.unavailable.take(3).joinToString()
            val more = preview.unavailable.size - 3
            Text(
                "From sites Fanos no longer reads: $some" + (if (more > 0) " and $more more" else "") +
                    ". They come back to the library, but can't get new chapters.",
            )
        }
        if (preview.hasSettings) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().clickable { onWithSettings(!withSettings) },
            ) {
                Checkbox(checked = withSettings, onCheckedChange = onWithSettings)
                Text("Restore its settings too")
            }
        }
    }
}

@Composable
private fun ActionItem(title: String, summary: String, enabled: Boolean, button: String, onClick: () -> Unit) {
    SettingsItem(
        title,
        Modifier.clickable(enabled = enabled, onClick = onClick),
        enabled = enabled,
        summary = { Text(summary) },
        trailing = { OutlinedButton(onClick = onClick, enabled = enabled) { Text(button) } },
    )
}

@Composable
private fun ImportDialog(step: ImportStep, activity: BackupActivity?, onImport: (NovelLibraryPreview) -> Unit, onClose: () -> Unit) {
    when (step) {
        ImportStep.Reading ->
            AlertDialog(
                onDismissRequest = {},
                confirmButton = {},
                title = { Text("Reading the NovelLibrary backup") },
                text = { LinearProgressIndicator(Modifier.fillMaxWidth()) },
            )
        is ImportStep.Ready -> {
            val preview = step.preview
            AlertDialog(
                onDismissRequest = onClose,
                title = { Text("Import from NovelLibrary?") },
                text = { ImportSummary(preview) },
                confirmButton = { TextButton(onClick = { onImport(preview) }, enabled = preview.novels > 0) { Text("Import") } },
                dismissButton = { TextButton(onClick = onClose) { Text("Cancel") } },
            )
        }
        // Not to be waved away: the dialog is how the reader learns it finished.
        ImportStep.Importing ->
            AlertDialog(
                onDismissRequest = {},
                confirmButton = {},
                title = { Text("Importing") },
                text = { RestoreProgress(activity as? BackupActivity.Restoring) },
            )
        is ImportStep.Done ->
            AlertDialog(
                onDismissRequest = onClose,
                title = { Text(if (step.result.failed.isEmpty()) "Imported" else "Partly imported") },
                text = { Text(importOutcome(step.result)) },
                confirmButton = { TextButton(onClick = onClose) { Text("OK") } },
            )
        is ImportStep.Failed ->
            AlertDialog(
                onDismissRequest = onClose,
                title = { Text("Can't import that") },
                text = { Text(step.message) },
                confirmButton = { TextButton(onClick = onClose) { Text("OK") } },
            )
    }
}

/** What the NovelLibrary backup holds and what importing does with it, a line each. */
@Composable
private fun ImportSummary(preview: NovelLibraryPreview) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        val sections = if (preview.sections > 0) " in ${preview.sections + 1} sections" else ""
        Text("${novels(preview.novels)}$sections, ${countOf(preview.chaptersRead, "chapter")} read.")
        if (preview.readable > 0) {
            Text("${preview.readable} from sites Fanos reads: ${preview.readableSites.joinToString()}.")
        }
        val others = preview.novels - preview.readable
        if (others > 0) {
            Text("$others from sites it doesn't (${preview.unreadableSites.joinToString()}); they come in without a source, ready for one.")
        }
        if (preview.inLibrary > 0) Text("${preview.inLibrary} in your library already: their progress is merged.")
        Text(
            "Each novel opens at the start of the chapter you were on; NovelLibrary's spot inside a chapter can't carry over.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private fun importOutcome(result: RestoreResult): String {
    val imported =
        when {
            result.restored == 0 -> "No novels were imported."
            result.joined == 0 -> "${novels(result.restored)} imported, all of them in the library already."
            result.joined == result.restored -> "${novels(result.restored)} imported to the library."
            else -> "${novels(result.restored)} imported, ${result.joined} of them new to the library."
        }
    if (result.failed.isEmpty()) return imported
    return "$imported Couldn't import ${result.failed.joinToString()}. Importing the backup again tries them again."
}

private fun outcome(result: RestoreResult): String {
    val restored =
        when {
            result.restored == 0 -> "No novels were restored."
            result.joined == 0 -> "${novels(result.restored)} restored."
            result.joined == result.restored -> "${novels(result.restored)} restored to the library."
            else -> "${novels(result.restored)} restored, ${result.joined} of them new to the library."
        }
    if (result.failed.isEmpty()) return restored
    return "$restored Couldn't restore ${result.failed.joinToString()}. Restoring the backup again tries them again."
}

private fun novels(count: Int) = if (count == 1) "1 novel" else "$count novels"

/** How long ago [time] was, in words: "just now", "5 minutes ago", "on 3 Oct". */
internal fun ago(context: Context, time: Long): String {
    val minutes = (System.currentTimeMillis() - time) / DateUtils.MINUTE_IN_MILLIS
    val hours = minutes / 60
    return when {
        minutes < 1 -> "just now"
        minutes < 60 -> if (minutes == 1L) "a minute ago" else "$minutes minutes ago"
        hours < 24 -> if (hours == 1L) "an hour ago" else "$hours hours ago"
        else -> "on " + DateUtils.formatDateTime(context, time, DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_ABBREV_MONTH)
    }
}

internal val BackupInterval.label: String
    get() =
        when (this) {
            BackupInterval.Daily -> "Daily"
            BackupInterval.ThreeDays -> "Every 3 days"
            BackupInterval.Weekly -> "Weekly"
            BackupInterval.Off -> "Off"
        }
