package io.github.nimbice.fanos.feature.novel

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import io.github.nimbice.fanos.core.designsystem.component.NovelCover
import io.github.nimbice.fanos.core.designsystem.icon.ReaderIcons
import io.github.nimbice.fanos.core.model.FoundCover
import io.github.nimbice.fanos.core.model.Novel
import io.github.nimbice.fanos.core.designsystem.component.FittingText

/**
 * The novel's title and cover, the reader's own instead of the site's: typed, found online, or chosen
 * from the phone. Nothing changes until Save; the site's own stay a tap away.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun EditNovelSheet(
    novel: Novel,
    coverSearch: CoverSearchState,
    onSearchCovers: (title: String, author: String) -> Unit,
    onCloseSearch: () -> Unit,
    onKeepPicture: (picture: Uri, onKept: (String) -> Unit) -> Unit,
    onSave: (title: String, coverUrl: String?) -> Unit,
    onDismiss: () -> Unit,
) {
    var title by rememberSaveable(novel.id) { mutableStateOf(novel.title) }
    var cover by rememberSaveable(novel.id) { mutableStateOf(novel.coverUrl) }
    var findingCover by rememberSaveable { mutableStateOf(false) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { picture -> if (picture != null) onKeepPicture(picture) { cover = it } }
    val choosePicture = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 24.dp).padding(bottom = 24.dp)) {
            Text("Title and cover", style = MaterialTheme.typography.titleLarge)
            Row(Modifier.padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                NovelCover(cover, title, Modifier.width(96.dp))
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    OutlinedButton(onClick = { findingCover = true }) {
                        Icon(Icons.Filled.Search, contentDescription = null, modifier = Modifier.size(18.dp))
                        Text("Find cover", modifier = Modifier.padding(start = 8.dp))
                    }
                    OutlinedButton(onClick = choosePicture) {
                        Icon(ReaderIcons.Image, contentDescription = null, modifier = Modifier.size(18.dp))
                        Text("Choose image", modifier = Modifier.padding(start = 8.dp))
                    }
                    if (cover != novel.siteCoverUrl) TextButton(onClick = { cover = novel.siteCoverUrl }) { Text("Use the site's cover") }
                }
            }
            OutlinedTextField(
                value = title,
                onValueChange = { title = it },
                label = { Text("Title") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
            )
            if (title.trim() != novel.siteTitle) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "The site calls it ${novel.siteTitle}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = { title = novel.siteTitle }) { Text("Use that") }
                }
            }
            Text(
                "Your title and cover stay when the site updates the novel.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp),
            )
            Row(Modifier.fillMaxWidth().padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                TextButton(onClick = onDismiss) { Text("Cancel") }
                Button(onClick = { onSave(title, cover) }) { Text("Save") }
            }
        }
    }

    if (findingCover) {
        val close = {
            findingCover = false
            onCloseSearch()
        }
        CoverSearchDialog(
            initialTitle = title.trim(),
            initialAuthor = novel.author?.let(::searchableAuthor).orEmpty(),
            state = coverSearch,
            onSearch = onSearchCovers,
            onPick = { found ->
                cover = found.coverUrl
                close()
            },
            onChoosePicture = {
                close()
                choosePicture()
            },
            onDismiss = close,
        )
    }
}

/**
 * Asks what to search for, the novel's title and author to start with, since a site's often don't match
 * the book's ("Book 14" by "Shirtaloon (Travis Deverell)"); then shows the covers found to pick one.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CoverSearchDialog(
    initialTitle: String,
    initialAuthor: String,
    state: CoverSearchState,
    onSearch: (title: String, author: String) -> Unit,
    onPick: (FoundCover) -> Unit,
    onChoosePicture: () -> Unit,
    onDismiss: () -> Unit,
) {
    var title by rememberSaveable { mutableStateOf(initialTitle) }
    var author by rememberSaveable { mutableStateOf(initialAuthor) }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        // The dialog's own window has the keyboard, so it's this window's controller that hides it.
        val keyboard = LocalSoftwareKeyboardController.current
        val search = {
            if (title.isNotBlank()) {
                // Out of the way of the covers found.
                keyboard?.hide()
                onSearch(title, author)
            }
        }
        // Drawn under the system bars, whose icons then follow the theme as the app's do.
        val view = LocalView.current
        val dark = MaterialTheme.colorScheme.surface.luminance() < 0.5f
        DisposableEffect(view, dark) {
            (view.parent as? DialogWindowProvider)?.window?.let { window ->
                WindowCompat.getInsetsController(window, view).apply {
                    isAppearanceLightStatusBars = !dark
                    isAppearanceLightNavigationBars = !dark
                }
            }
            onDispose {}
        }
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().systemBarsPadding().imePadding()) {
                Row(Modifier.padding(horizontal = 4.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onDismiss) { Icon(Icons.Filled.Close, contentDescription = "Close") }
                    Text("Find a cover", style = MaterialTheme.typography.titleLarge)
                }
                Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "Search for the book as it's published: its title, with its number in the series, and its author.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedTextField(
                        value = title,
                        onValueChange = { title = it },
                        label = { Text("Title") },
                        placeholder = { FittingText("He Who Fights With Monsters 14") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = { search() }),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = author,
                        onValueChange = { author = it },
                        label = { Text("Author") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = { search() }),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Button(onClick = search, enabled = !state.searching, modifier = Modifier.align(Alignment.End)) { Text("Search") }
                }
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    val results = state.results
                    when {
                        state.searching -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                        state.error != null -> Message(state.error, onChoosePicture)
                        results == null -> Unit
                        results.covers.isEmpty() -> Message("No covers found. Try fewer words, or use a picture from this device.", onChoosePicture)
                        else ->
                            LazyVerticalGrid(
                                columns = GridCells.Adaptive(104.dp),
                                contentPadding = PaddingValues(16.dp),
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                                verticalArrangement = Arrangement.spacedBy(16.dp),
                            ) {
                                if (results.series != null) {
                                    item(span = { GridItemSpan(maxLineSpan) }) {
                                        Text(
                                            "No covers for this book yet. Other books in ${results.series}:",
                                            style = MaterialTheme.typography.bodyMedium,
                                        )
                                    }
                                }
                                items(results.covers, key = { it.coverUrl }) { found -> FoundCoverItem(found) { onPick(found) } }
                                item(span = { GridItemSpan(maxLineSpan) }) {
                                    TextButton(onClick = onChoosePicture) { Text("Not here? Choose an image from this device") }
                                }
                            }
                    }
                }
                Text(
                    "Covers from Open Library",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.align(Alignment.CenterHorizontally).padding(8.dp),
                )
            }
        }
    }
}

@Composable
private fun FoundCoverItem(found: FoundCover, onClick: () -> Unit) {
    Column(Modifier.clickable(onClick = onClick)) {
        NovelCover(found.thumbnailUrl, found.title, Modifier.fillMaxWidth())
        Text(found.title, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
        found.year?.let { Text("$it", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

@Composable
private fun Message(text: String, onChoosePicture: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(text, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
        OutlinedButton(onClick = onChoosePicture) {
            Icon(ReaderIcons.Image, contentDescription = null, modifier = Modifier.size(18.dp))
            Text("Choose image", modifier = Modifier.padding(start = 8.dp))
        }
    }
}

/** An author as book catalogues know them: without the real name some sites add, "Shirtaloon (Travis Deverell)". */
internal fun searchableAuthor(author: String): String = author.replace(TRAILING_BRACKETS, "").trim()

private val TRAILING_BRACKETS = Regex("""\s*\([^)]*\)\s*$""")
