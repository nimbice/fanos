package io.github.nimbice.fanos.feature.library

import android.text.format.Formatter
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.nimbice.fanos.core.data.download.DownloadProgress
import io.github.nimbice.fanos.core.data.download.DownloadRepository
import io.github.nimbice.fanos.core.data.download.QueuedNovel
import io.github.nimbice.fanos.core.data.download.SavedNovel
import io.github.nimbice.fanos.core.designsystem.component.EmptyState
import io.github.nimbice.fanos.core.designsystem.component.LoadingState
import io.github.nimbice.fanos.core.designsystem.component.NovelCover
import io.github.nimbice.fanos.core.designsystem.component.countOf
import io.github.nimbice.fanos.core.designsystem.component.number
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import javax.inject.Inject
import androidx.compose.foundation.layout.FlowRow

/** The Downloads tab: what's downloading or waiting, and what each novel has saved. */
@Serializable
data object DownloadsRoute

@HiltViewModel
class DownloadsViewModel @Inject constructor(private val downloads: DownloadRepository) : ViewModel() {
    /** Null until known. */
    val saved: StateFlow<List<SavedNovel>?> = downloads.observeSavedNovels().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    val queued: StateFlow<List<QueuedNovel>> = downloads.observeQueuedNovels().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val progress: StateFlow<DownloadProgress?> = downloads.progress

    private val _messages = Channel<String>(Channel.BUFFERED)
    val messages: Flow<String> = _messages.receiveAsFlow()

    fun cancelAll() = viewModelScope.launch { downloads.cancelAll() }

    fun cancel(novel: QueuedNovel) = viewModelScope.launch { downloads.cancelNovel(novel.novelId) }

    fun retry(novel: QueuedNovel) = viewModelScope.launch { downloads.retryNovel(novel.novelId) }

    fun delete(novel: SavedNovel, readOnly: Boolean) =
        viewModelScope.launch {
            val deleted = downloads.deleteSaved(novel.novelId, readOnly)
            _messages.send("Deleted ${countOf(deleted, "chapter")} of ${novel.title}")
        }

    fun deleteAllRead() = viewModelScope.launch { _messages.send("Deleted ${countOf(downloads.deleteAllRead(), "read chapter")}") }

    fun deleteAll() = viewModelScope.launch { downloads.deleteAll() }
}

fun NavGraphBuilder.downloadsScreen(onOpenNovel: (Long) -> Unit) {
    composable<DownloadsRoute> { DownloadsScreen(onOpenNovel) }
}

/** What a question before deleting asks about. */
private enum class Deleting { AllRead, All }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DownloadsScreen(onOpenNovel: (Long) -> Unit, viewModel: DownloadsViewModel = hiltViewModel()) {
    val saved by viewModel.saved.collectAsStateWithLifecycle()
    val queued by viewModel.queued.collectAsStateWithLifecycle()
    val progress by viewModel.progress.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    var asking by remember { mutableStateOf<Deleting?>(null) }
    LaunchedEffect(viewModel) { viewModel.messages.collect { snackbar.showSnackbar(it) } }
    Scaffold(topBar = { TopAppBar(title = { Text("Downloads") }) }, snackbarHost = { SnackbarHost(snackbar) }) { padding ->
        val novels = saved
        when {
            novels == null -> LoadingState(Modifier.padding(padding))
            novels.isEmpty() && queued.isEmpty() ->
                EmptyState("Nothing downloaded", "Chapters you download to read offline show here, novel by novel.", Modifier.padding(padding))
            else ->
                LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(bottom = 16.dp)) {
                    if (novels.isNotEmpty()) {
                        item {
                            Text(
                                "${countOf(novels.sumOf { it.saved }, "chapter")} saved · ${Formatter.formatShortFileSize(context, novels.sumOf { it.bytes })}",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp),
                            )
                        }
                    }
                    if (queued.isNotEmpty()) {
                        item { Queue(progress, queued, viewModel::cancelAll, viewModel::cancel, viewModel::retry) }
                    }
                    if (novels.isNotEmpty()) {
                        item {
                            // Side by side when they fit; with large text, the second under the first rather than wrapped.
                            FlowRow(
                                Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                val read = novels.sumOf { it.read }
                                OutlinedButton(onClick = { asking = Deleting.AllRead }, enabled = read > 0) { Text("Delete read ones (${number(read)})", maxLines = 1) }
                                OutlinedButton(onClick = { asking = Deleting.All }) { Text("Delete all", maxLines = 1) }
                            }
                        }
                    }
                    items(novels, key = { it.novelId }) { novel ->
                        SavedRow(novel, onOpen = { onOpenNovel(novel.novelId) }, onDelete = { readOnly -> viewModel.delete(novel, readOnly) })
                    }
                }
        }
    }
    val novels = saved.orEmpty()
    asking?.let { question ->
        val count = if (question == Deleting.AllRead) novels.sumOf { it.read } else novels.sumOf { it.saved }
        AlertDialog(
            onDismissRequest = { asking = null },
            title = { Text(if (question == Deleting.AllRead) "Delete ${countOf(count, "read chapter")}?" else "Delete ${countOf(count, "downloaded chapter")}?") },
            text = {
                Text(
                    if (question == Deleting.AllRead) {
                        "Every novel's downloads you've read. They can be downloaded again."
                    } else {
                        "Chapters waiting to download are taken off the queue too. They can all be downloaded again."
                    },
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    asking = null
                    if (question == Deleting.AllRead) viewModel.deleteAllRead() else viewModel.deleteAll()
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { asking = null }) { Text("Cancel") } },
        )
    }
}

/** What's downloading and waiting, novel by novel, and what failed, with a way to try it again. */
@Composable
private fun Queue(
    progress: DownloadProgress?,
    queued: List<QueuedNovel>,
    onCancelAll: () -> Unit,
    onCancel: (QueuedNovel) -> Unit,
    onRetry: (QueuedNovel) -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    Surface(color = colors.surfaceContainer, shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
        Column(Modifier.padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                val waiting = queued.sumOf { it.waiting }
                Text(
                    when {
                        progress != null -> "Downloading · ${number(progress.done)} of ${number(progress.done + progress.left)}"
                        waiting > 0 -> "Waiting to download · ${number(waiting)}"
                        else -> "Downloads that failed"
                    },
                    style = MaterialTheme.typography.titleSmall,
                    color = colors.primary,
                    modifier = Modifier.weight(1f),
                )
                if (waiting > 0) TextButton(onClick = onCancelAll) { Text("Cancel all") }
            }
            progress?.let { run ->
                run.latest?.let { Text("${it.novelTitle} — ${it.chapterTitle}", style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                LinearProgressIndicator(
                    progress = { run.done.toFloat() / (run.done + run.left).coerceAtLeast(1) },
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 4.dp, end = 12.dp),
                )
            }
            queued.forEach { novel ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f).padding(vertical = 6.dp)) {
                        Text(novel.title, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (novel.waiting > 0) Text("${number(novel.waiting)} waiting", style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                        if (novel.failed > 0) {
                            Text(
                                "${number(novel.failed)} failed" + novel.failure?.let { ": $it" }.orEmpty(),
                                style = MaterialTheme.typography.bodySmall,
                                color = colors.error,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                    if (novel.failed > 0) TextButton(onClick = { onRetry(novel) }) { Text("Try again") }
                    IconButton(onClick = { onCancel(novel) }) { Icon(Icons.Filled.Close, contentDescription = "Take ${novel.title} off the queue") }
                }
            }
        }
    }
}

/** A novel's saved chapters, and deleting its read ones or all of them. */
@Composable
private fun SavedRow(novel: SavedNovel, onOpen: () -> Unit, onDelete: (readOnly: Boolean) -> Unit) {
    val context = LocalContext.current
    var menu by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().clickable(onClick = onOpen).padding(start = 16.dp, top = 8.dp, bottom = 8.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        NovelCover(novel.coverUrl, title = "", modifier = Modifier.width(40.dp))
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(novel.title, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(
                "${number(novel.saved)} saved · ${number(novel.read)} read · ${Formatter.formatShortFileSize(context, novel.bytes)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Box {
            IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, contentDescription = "Delete downloads of ${novel.title}") }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(
                    text = { Text("Delete read ones (${number(novel.read)})") },
                    enabled = novel.read > 0,
                    onClick = {
                        menu = false
                        onDelete(true)
                    },
                )
                DropdownMenuItem(
                    text = { Text("Delete all (${number(novel.saved)})") },
                    onClick = {
                        menu = false
                        onDelete(false)
                    },
                )
            }
        }
    }
}
