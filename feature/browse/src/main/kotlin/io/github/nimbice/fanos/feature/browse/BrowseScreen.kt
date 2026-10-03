package io.github.nimbice.fanos.feature.browse

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.nimbice.fanos.core.data.repository.CatalogRepository
import io.github.nimbice.fanos.core.data.repository.SettingsRepository
import io.github.nimbice.fanos.core.designsystem.component.SearchField
import io.github.nimbice.fanos.core.designsystem.component.countOf
import io.github.nimbice.fanos.core.designsystem.component.scaffoldContent
import io.github.nimbice.fanos.core.designsystem.icon.ReaderIcons
import io.github.nimbice.fanos.core.model.SearchHistory
import io.github.nimbice.fanos.core.model.SourceInfo
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.net.URI
import javax.inject.Inject
import android.widget.Toast
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.ui.platform.LocalContext
import androidx.compose.material3.SnackbarResult
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.material3.Switch
import androidx.compose.material3.OutlinedButton
import androidx.compose.foundation.layout.Row

@HiltViewModel
class SourcesViewModel @Inject constructor(catalog: CatalogRepository, private val settings: SettingsRepository) : ViewModel() {
    /** Null until the extensions have loaded. */
    val sources: StateFlow<List<SourceInfo>?> = catalog.allSources.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val recent = settings.recentSearches(SearchHistory.Novels, viewModelScope)

    /** Sources left out of the search across all sources. */
    val excluded: StateFlow<Set<String>> =
        settings.appSettings.map { it.searchExcludedSources }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptySet())

    fun setSearched(sourceId: String, searched: Boolean) {
        viewModelScope.launch {
            settings.updateAppSettings {
                it.copy(searchExcludedSources = if (searched) it.searchExcludedSources - sourceId else it.searchExcludedSources + sourceId)
            }
        }
    }

    /** Every source searched, or none. */
    fun setAllSearched(searched: Boolean) {
        val ids = sources.value.orEmpty().map { it.id }.toSet()
        viewModelScope.launch {
            settings.updateAppSettings { it.copy(searchExcludedSources = if (searched) it.searchExcludedSources - ids else it.searchExcludedSources + ids) }
        }
    }
}

/**
 * Browse: the sources to read from, and the extensions they come from. [showExtensions] opens on the
 * extensions, as when a novel's extension is missing or extension updates were announced;
 * [onExtensionsShown] says that's been done.
 */
@Composable
fun BrowseScreen(
    showExtensions: Boolean,
    onExtensionsShown: () -> Unit,
    onOpenSource: (String) -> Unit,
    onSearchAll: (String) -> Unit,
    onOpenNovel: (Long) -> Unit = {},
    onOpenLibrary: () -> Unit = {},
    sourcesViewModel: SourcesViewModel = hiltViewModel(),
    extensionsViewModel: ExtensionsViewModel = hiltViewModel(),
    booksViewModel: BooksViewModel = hiltViewModel(),
) {
    var tab by rememberSaveable { mutableIntStateOf(SOURCES) }
    val extensions by extensionsViewModel.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { files -> extensionsViewModel.install(files) }
    val installFromFile = {
        tab = EXTENSIONS
        picker.launch(EXTENSION_FILE_TYPES)
    }
    // A book file: into the library, then its page.
    val context = LocalContext.current
    val openingBook by booksViewModel.progress.collectAsStateWithLifecycle()
    val bookPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris -> booksViewModel.open(uris) }
    val openBook = { bookPicker.launch(BOOK_FILE_TYPES) }
    LaunchedEffect(booksViewModel) {
        booksViewModel.results.collect { result ->
            when (result) {
                is BookResult.Opened -> {
                    val book = result.book
                    val said =
                        if (book.already) "\u201c${book.title}\u201d is in the library already" else "Added \u201c${book.title}\u201d to the library \u00b7 ${countOf(book.chapters, "chapter")}"
                    Toast.makeText(context, said, Toast.LENGTH_LONG).show()
                    onOpenNovel(book.novelId)
                }
                is BookResult.Failed -> snackbar.showSnackbar(result.message, withDismissAction = true, duration = SnackbarDuration.Long)
                is BookResult.Several -> {
                    val said =
                        listOfNotNull(
                            if (result.added > 0) "Added ${countOf(result.added, "book")} to the library" else "No books added",
                            if (result.already > 0) "${result.already} there already" else null,
                            when (result.failed) {
                                0 -> null
                                1 -> "1 wasn\u2019t an EPUB"
                                else -> "${result.failed} weren\u2019t EPUBs"
                            },
                        ).joinToString(" \u00b7 ")
                    val shown = snackbar.showSnackbar(said, actionLabel = "Library", withDismissAction = true, duration = SnackbarDuration.Long)
                    if (shown == SnackbarResult.ActionPerformed) onOpenLibrary()
                }
            }
        }
    }

    LaunchedEffect(showExtensions) {
        if (showExtensions) {
            tab = EXTENSIONS
            extensionsViewModel.lookForPublished()
            onExtensionsShown()
        }
    }
    LaunchedEffect(extensionsViewModel) {
        extensionsViewModel.messages.collect { snackbar.showSnackbar(it, withDismissAction = true, duration = SnackbarDuration.Long) }
    }

    // No title bar: the tab bar already says this is Browse.
    Scaffold(snackbarHost = { SnackbarHost(snackbar) }) { padding ->
        Column(Modifier.fillMaxSize().scaffoldContent(padding)) {
            PrimaryTabRow(selectedTabIndex = tab) {
                Tab(selected = tab == SOURCES, onClick = { tab = SOURCES }, text = { Text("Sources") })
                Tab(selected = tab == EXTENSIONS, onClick = { tab = EXTENSIONS }, text = { Text("Extensions") })
            }
            when (tab) {
                SOURCES -> SourcesTab(sourcesViewModel, onOpenSource, onSearchAll, onInstall = installFromFile, openingBook = openingBook, onOpenBook = openBook)
                else ->
                    ExtensionsTab(
                        extensions,
                        onShown = { extensionsViewModel.lookForPublished() },
                        onInstall = installFromFile,
                        onInstallPublished = extensionsViewModel::installPublished,
                        onLookAgain = { extensionsViewModel.lookForPublished(again = true) },
                        onRemove = extensionsViewModel::remove,
                        onForgetKey = extensionsViewModel::forgetKey,
                    )
            }
        }
    }

    extensions.trust?.let { request -> TrustDialog(request, onTrust = extensionsViewModel::trustKey, onDecline = extensionsViewModel::declineKey) }
}

/** The sources, each ticked to be searched by the box above; a row opens its source. */
@Composable
private fun SourcesTab(
    viewModel: SourcesViewModel,
    onOpenSource: (String) -> Unit,
    onSearchAll: (String) -> Unit,
    onInstall: () -> Unit,
    openingBook: BookProgress?,
    onOpenBook: () -> Unit,
) {
    val excluded by viewModel.excluded.collectAsStateWithLifecycle()
    val sources = viewModel.sources.collectAsStateWithLifecycle().value ?: return
    var query by rememberSaveable { mutableStateOf("") }
    val recent by viewModel.recent.items.collectAsStateWithLifecycle()
    if (sources.isEmpty()) {
        Column(Modifier.fillMaxSize()) {
            OnThisDevice(openingBook, onOpenBook)
            Box(Modifier.weight(1f)) { NoSources(onInstall) }
        }
        return
    }
    val searched = sources.count { it.id !in excluded }
    Column(Modifier.fillMaxSize()) {
        SearchField(
            value = query,
            onValueChange = { query = it },
            onSearch = {
                if (query.isNotBlank() && searched > 0) {
                    viewModel.recent.remember(query)
                    onSearchAll(query.trim())
                }
            },
            recent = recent,
            onForget = viewModel.recent::forget,
            placeholder =
                when (searched) {
                    sources.size -> "Search all sources"
                    0 -> "Turn on a source to search"
                    1 -> "Search ${sources.first { it.id !in excluded }.name}"
                    else -> "Search ${countOf(searched, "source")}"
                },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        )
        Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { viewModel.setAllSearched(true) }, enabled = searched < sources.size) { Text("All on") }
            OutlinedButton(onClick = { viewModel.setAllSearched(false) }, enabled = searched > 0) { Text("All off") }
        }
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 8.dp)) {
            item(key = "on this device") { OnThisDevice(openingBook, onOpenBook) }
            items(sources, key = { it.id }) { source ->
                ListItem(
                    leadingContent = {
                        Switch(
                            checked = source.id !in excluded,
                            onCheckedChange = { viewModel.setSearched(source.id, it) },
                            modifier = Modifier.semantics { contentDescription = "Include ${source.name} in searches" },
                        )
                    },
                    headlineContent = { Text(source.name) },
                    supportingContent = { Text(host(source.baseUrl)) },
                    trailingContent = { Text(source.lang.uppercase()) },
                    modifier = Modifier.clickable { onOpenSource(source.id) },
                )
            }
        }
    }
}

/** Books on this device, first among the sources: a tap opens an EPUB file into the library. */
@Composable
private fun OnThisDevice(opening: BookProgress?, onOpen: () -> Unit) {
    ListItem(
        leadingContent = {
            // As wide as the sources' switches below it, so the names line up.
            Box(Modifier.size(width = 52.dp, height = 48.dp), contentAlignment = Alignment.Center) {
                Icon(ReaderIcons.Book, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            }
        },
        headlineContent = { Text("On this device") },
        supportingContent = {
            Text(
                when {
                    opening == null -> "Open EPUB files"
                    opening.count == 1 -> "Opening the book\u2026"
                    else -> "Opening ${opening.index} of ${opening.count}" + (opening.name?.let { " \u00b7 $it\u2026" } ?: "\u2026")
                },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        trailingContent = { if (opening != null) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp) },
        modifier = Modifier.clickable(enabled = opening == null, onClick = onOpen),
    )
}

/** No extension installed yet: a fresh install, or the first start after the sources moved out of the app. */
@Composable
private fun NoSources(onInstall: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(ReaderIcons.Extension, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(40.dp))
        Text("No sources yet", style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        Text(
            "Sources come from extensions. Install one from a file to start reading from a site.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Button(onClick = onInstall, modifier = Modifier.padding(top = 8.dp)) {
            Icon(ReaderIcons.FileAdd, contentDescription = null, modifier = Modifier.size(18.dp))
            Text("Install from file", modifier = Modifier.padding(start = 8.dp))
        }
    }
}

private fun host(url: String): String = runCatching { URI(url).host?.removePrefix("www.") }.getOrNull() ?: url

/** What a book file can say it is: EPUB, or what a file manager calls one it doesn't know. */
private val BOOK_FILE_TYPES = arrayOf("application/epub+zip", "application/octet-stream", "application/zip")

private const val SOURCES = 0
private const val EXTENSIONS = 1
