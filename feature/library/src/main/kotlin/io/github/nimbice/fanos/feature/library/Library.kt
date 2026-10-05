package io.github.nimbice.fanos.feature.library

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.nimbice.fanos.core.data.download.DownloadRepository
import io.github.nimbice.fanos.core.data.repository.CatalogRepository
import io.github.nimbice.fanos.core.data.repository.LibraryRepository
import io.github.nimbice.fanos.core.data.repository.NovelRepository
import io.github.nimbice.fanos.core.data.repository.RemovedNovel
import io.github.nimbice.fanos.core.data.repository.SectionRepository
import io.github.nimbice.fanos.core.data.repository.SettingsRepository
import io.github.nimbice.fanos.core.data.repository.arrangeLibrary
import io.github.nimbice.fanos.core.data.work.LibraryUpdateScheduler
import io.github.nimbice.fanos.core.designsystem.component.EmptyState
import io.github.nimbice.fanos.core.designsystem.component.LoadingState
import io.github.nimbice.fanos.core.designsystem.component.ReorderableItem
import io.github.nimbice.fanos.core.designsystem.component.TopPullToRefreshBox
import io.github.nimbice.fanos.core.designsystem.component.countOf
import io.github.nimbice.fanos.core.designsystem.component.number
import io.github.nimbice.fanos.core.designsystem.component.rememberReorderState
import io.github.nimbice.fanos.core.designsystem.component.reorderable
import io.github.nimbice.fanos.core.designsystem.component.scaffoldContent
import io.github.nimbice.fanos.core.model.LibraryDisplay
import io.github.nimbice.fanos.core.model.LibraryFilter
import io.github.nimbice.fanos.core.model.LibraryNovel
import io.github.nimbice.fanos.core.model.LibrarySection
import io.github.nimbice.fanos.core.model.LibrarySort
import io.github.nimbice.fanos.core.model.LibraryUpdateProgress
import io.github.nimbice.fanos.core.model.SearchHistory
import io.github.nimbice.fanos.core.model.UpdateInterval
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import javax.inject.Inject
import io.github.nimbice.fanos.core.data.book.BookImporter
import io.github.nimbice.fanos.core.designsystem.component.RemoveBooksDialog

@Serializable
data object LibraryRoute

fun NavGraphBuilder.libraryScreen(
    onOpenNovel: (Long) -> Unit,
    onOpenSections: () -> Unit,
    onMoveNovels: () -> Unit,
    onBack: () -> Unit,
) {
    composable<LibraryRoute> { LibraryScreen(onOpenNovel, onOpenSections, onMoveNovels) }
    composable<SectionsRoute> { SectionsScreen(onBack) }
}

data class LibraryUiState(
    val novels: List<LibraryNovel> = emptyList(),
    /** The library's sections: tabs once there's more than one. */
    val sections: List<LibrarySection> = emptyList(),
    val display: LibraryDisplay = LibraryDisplay.Grid,
    /** The library shows only novels that pass every one of these. */
    val filters: Set<LibraryFilter> = emptySet(),
    val sort: LibrarySort = LibrarySort.Yours,
    /** The running update, or null. */
    val update: LibraryUpdateProgress? = null,
    /** Background checks are to post what they find. */
    val notifying: Boolean = false,
    val loaded: Boolean = false,
) {
    val updating: Boolean get() = update != null
}

@HiltViewModel
class LibraryViewModel @Inject constructor(
    private val library: LibraryRepository,
    private val sectionRepository: SectionRepository,
    private val novels: NovelRepository,
    private val downloads: DownloadRepository,
    private val catalog: CatalogRepository,
    private val settings: SettingsRepository,
    private val scheduler: LibraryUpdateScheduler,
    private val books: BookImporter,
) : ViewModel() {

    val recent = settings.recentSearches(SearchHistory.Novels, viewModelScope)

    val state: StateFlow<LibraryUiState> =
        combine(library.observeLibrary(), sectionRepository.observeSections(), settings.appSettings, library.progress) { novels, sections, app, update ->
            val notifying = app.newChapterNotifications && app.libraryUpdateInterval != UpdateInterval.Off
            LibraryUiState(novels, sections, app.libraryDisplay, app.libraryFilters, app.librarySort, update, notifying, loaded = true)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LibraryUiState())

    fun update() = scheduler.updateNow()

    /** The reader said no to notifications: stop asking (Settings can turn them back on). */
    fun stopNotifying() {
        viewModelScope.launch { settings.updateAppSettings { it.copy(newChapterNotifications = false) } }
    }

    fun setDisplay(display: LibraryDisplay) {
        viewModelScope.launch { settings.updateAppSettings { it.copy(libraryDisplay = display) } }
    }

    fun setFilter(filter: LibraryFilter, on: Boolean) {
        viewModelScope.launch { settings.updateAppSettings { it.copy(libraryFilters = if (on) it.libraryFilters + filter else it.libraryFilters - filter) } }
    }

    fun setSort(sort: LibrarySort) {
        viewModelScope.launch { settings.updateAppSettings { it.copy(librarySort = sort) } }
    }

    /** The sources' names by id, as extensions come and go. */
    val sourceNames: StateFlow<Map<String, String>> =
        catalog.allSources.map { sources -> sources.associate { it.id to it.name } }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    /** Keeps the order the reader dragged a section of the library (or all of it) into. */
    fun reorder(novelIds: List<Long>) {
        viewModelScope.launch { library.reorder(novelIds) }
    }

    /** Puts the novels in the sections [changes] ticks and takes them out of those it unticks; none loses its last one. */
    fun setSections(novelIds: Set<Long>, changes: Map<Long, Boolean>) {
        viewModelScope.launch {
            for ((sectionId, inSection) in changes) novelIds.forEach { sectionRepository.setInSection(it, sectionId, inSection) }
        }
    }

    /** Marks every chapter of the novels read; returns the chapters that changed, to undo it with. */
    suspend fun markRead(novelIds: Set<Long>): List<Long> = library.markAllRead(novelIds.toList())

    fun setRead(chapterIds: List<Long>, read: Boolean) {
        viewModelScope.launch { novels.setRead(chapterIds, read) }
    }

    /** How many of the novels' unread chapters aren't downloaded or waiting yet. */
    suspend fun unreadToDownload(novelIds: Set<Long>): Int = novelIds.sumOf { downloads.unreadToDownload(it).size }

    fun downloadUnread(novelIds: Set<Long>) {
        viewModelScope.launch { novelIds.forEach { downloads.downloadUnread(it) } }
    }

    suspend fun downloadsWaitForWifi(): Boolean = settings.appSettings.first().downloadOnlyOnWifi

    suspend fun remove(novelIds: Set<Long>): List<RemovedNovel> = library.remove(novelIds.toList())

    /** Lets the books' text and pictures go, as they leave the library. */
    fun freeBooks(novelIds: Collection<Long>) {
        viewModelScope.launch { novelIds.forEach { books.free(it) } }
    }

    suspend fun bookSize(novelIds: Collection<Long>): Long = novelIds.sumOf { books.size(it) }

    fun putBack(removed: List<RemovedNovel>) {
        viewModelScope.launch { library.putBack(removed) }
    }
}

/**
 * The library: a slim header (search, filters and order, sections, recent chapters), tabs for its sections once there's
 * more than one, and the novels of the section shown. In the reader's own order a long press and drag rearranges them;
 * a long press let go selects one, and the header turns to what can be done with those selected.
 */
@Composable
fun LibraryScreen(
    onOpenNovel: (Long) -> Unit,
    onOpenSections: () -> Unit,
    onMoveNovels: () -> Unit,
    viewModel: LibraryViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val sourceNames by viewModel.sourceNames.collectAsStateWithLifecycle()
    val gridState = rememberLazyGridState()
    val listState = rememberLazyListState()
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val asList = state.display == LibraryDisplay.List

    var searching by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    val recent by viewModel.recent.items.collectAsStateWithLifecycle()
    var filtering by rememberSaveable { mutableStateOf(false) }
    var selectedIds by rememberSaveable { mutableStateOf(LongArray(0)) }
    val selected = remember(selectedIds) { selectedIds.toSet() }
    var dialog by rememberSaveable { mutableStateOf<LibraryDialog?>(null) }
    fun select(ids: Set<Long>) {
        selectedIds = ids.toLongArray()
    }
    fun toggle(id: Long) = select(if (id in selected) selected - id else selected + id)

    // The section shown: the default one to start with, and again should the one shown be deleted.
    var sectionId by rememberSaveable { mutableLongStateOf(LibrarySection.DEFAULT_ID) }
    val section = state.sections.firstOrNull { it.id == sectionId } ?: state.sections.firstOrNull()
    val shownId = section?.id ?: LibrarySection.DEFAULT_ID
    val shownAll = remember(state.novels, query, state.filters, state.sort) { arrangeLibrary(state.novels, query, state.filters, state.sort) }
    val inSection = remember(shownAll, shownId) { shownAll.filter { shownId in it.sectionIds } }
    val narrowed = query.isNotBlank() || state.filters.isNotEmpty()
    // Only the reader's own order can be dragged into shape; a search shows too little of it to.
    val canDrag = state.sort == LibrarySort.Yours && query.isBlank()

    // Novels that leave the library leave the selection.
    LaunchedEffect(state.novels) {
        val present = state.novels.mapTo(HashSet()) { it.novel.id }
        if (!present.containsAll(selected)) select(selected intersect present)
    }
    BackHandler(enabled = selected.isNotEmpty()) { select(emptySet()) }
    BackHandler(enabled = searching && selected.isEmpty()) {
        searching = false
        query = ""
    }

    // While a novel is dragged, and until the library comes back from the database in the new order,
    // the screen shows its own copy of the section's order.
    var arranged by remember(shownId) { mutableStateOf<List<LibraryNovel>?>(null) }
    val novels = arranged ?: inSection
    LaunchedEffect(inSection) {
        val local = arranged ?: return@LaunchedEffect
        val saved = inSection.map { it.novel.id }
        val mine = local.map { it.novel.id }
        // Caught up, or the section changed underneath (a novel added or removed): show the saved order.
        if (saved == mine || saved.toSet() != mine.toSet()) arranged = null
    }
    val move: (Int, Int) -> Unit = { from, to -> arranged = novels.toMutableList().apply { add(to, removeAt(from)) } }
    val drop: () -> Unit = {
        arranged?.let { local ->
            val ids = local.map { it.novel.id }
            if (ids == inSection.map { it.novel.id }) arranged = null else viewModel.reorder(ids)
        }
    }
    val press: (Any) -> Unit = { key -> (key as? Long)?.let(::toggle) }
    val gridPadding = PaddingValues(start = 12.dp, end = 12.dp, bottom = 12.dp)
    val listPadding = PaddingValues(bottom = 4.dp)
    val gridReorder = rememberReorderState(gridState, gridPadding, move, drop, press)
    val listReorder = rememberReorderState(listState, listPadding, move, drop, press)

    AskToNotify(ask = state.notifying && state.novels.isNotEmpty(), onDenied = viewModel::stopNotifying)

    fun tell(message: String, undo: (() -> Unit)? = null) {
        scope.launch {
            snackbar.currentSnackbarData?.dismiss()
            val result = snackbar.showSnackbar(message, actionLabel = if (undo != null) "Undo" else null, duration = SnackbarDuration.Short)
            if (result == SnackbarResult.ActionPerformed) undo?.invoke()
        }
    }

    // No title bar: the tab bar already says this is the library. A slim header carries the unread
    // count and the library's buttons instead.
    Scaffold(snackbarHost = { SnackbarHost(snackbar) }) { padding ->
        Column(Modifier.fillMaxSize().scaffoldContent(padding)) {
            if (state.novels.isNotEmpty()) {
                when {
                    selected.isNotEmpty() ->
                        SelectionBar(
                            count = selected.size,
                            onClose = { select(emptySet()) },
                            onSelectAll = { select(selected + novels.map { it.novel.id }) },
                            onSections = { dialog = LibraryDialog.Sections },
                            onMarkRead = {
                                val ids = selected
                                select(emptySet())
                                scope.launch {
                                    val changed = viewModel.markRead(ids)
                                    if (changed.isEmpty()) {
                                        tell("Every chapter was read already")
                                    } else {
                                        tell("Marked ${countOf(changed.size, "chapter")} read") { viewModel.setRead(changed, read = false) }
                                    }
                                }
                            },
                            onDownload = { dialog = LibraryDialog.Download },
                            onRemove = {
                                // Books ask first: their text goes with them.
                                if (state.novels.any { it.novel.id in selected && it.novel.isBook }) {
                                    dialog = LibraryDialog.RemoveBooks
                                } else {
                                    val ids = selected
                                    select(emptySet())
                                    scope.launch {
                                        val removed = viewModel.remove(ids)
                                        tell("Took ${countOf(removed.size, "novel")} out of the library") { viewModel.putBack(removed) }
                                    }
                                }
                            },
                        )
                    searching ->
                        SearchBar(
                            query = query,
                            onQueryChange = { query = it },
                            onClose = {
                                searching = false
                                query = ""
                            },
                            recent = recent,
                            onSearch = { viewModel.recent.remember(query) },
                            onPick = { words ->
                                query = words
                                viewModel.recent.remember(words)
                            },
                            onForget = viewModel.recent::forget,
                        )
                    else ->
                        LibraryHeader(
                            text = novels.sumOf { it.unreadCount }.takeIf { it > 0 }?.let { "${number(it)} unread" }.orEmpty(),
                            filtered = state.filters.isNotEmpty(),
                            onSearch = { searching = true },
                            onFilter = { filtering = true },
                            onOpenSections = onOpenSections,
                            onMoveNovels = onMoveNovels,
                        )
                }
                if (state.sections.size > 1) {
                    PrimaryScrollableTabRow(
                        selectedTabIndex = state.sections.indexOfFirst { it.id == shownId }.coerceAtLeast(0),
                        edgePadding = 8.dp,
                        divider = {},
                    ) {
                        state.sections.forEach { tab ->
                            // Narrowed down, each tab says how many of its novels are left.
                            val label = if (narrowed) "${tab.name} · ${number(shownAll.count { tab.id in it.sectionIds })}" else tab.name
                            Tab(selected = tab.id == shownId, onClick = { sectionId = tab.id }, text = { Text(label) })
                        }
                    }
                }
            }
            TopPullToRefreshBox(
                isRefreshing = state.updating,
                onRefresh = viewModel::update,
                scrolledFromTop = { if (asList) listState.canScrollBackward else gridState.canScrollBackward },
                modifier = Modifier.fillMaxSize(),
            ) {
                val open: (LibraryNovel) -> Unit = { item ->
                    if (selected.isNotEmpty()) {
                        toggle(item.novel.id)
                    } else {
                        // A novel found by searching: the search is worth keeping.
                        if (searching) viewModel.recent.remember(query)
                        onOpenNovel(item.novel.id)
                    }
                }
                // An update greys out the novels it has yet to check, and says which are holding it up.
                val waiting = state.update?.waiting.orEmpty()
                val checking = state.update?.checking.orEmpty()
                // Where novels can't be dragged, a long press selects at once.
                val hold: ((LibraryNovel) -> Unit)? = if (canDrag) null else { item -> toggle(item.novel.id) }
                when {
                    !state.loaded -> LoadingState()
                    state.novels.isEmpty() ->
                        EmptyState("Your library is empty", "Find a novel in Browse and add it to your library.")
                    novels.isEmpty() && narrowed ->
                        EmptyState(
                            "Nothing matches",
                            if (query.isNotBlank()) "No novel in ${section?.name ?: "this section"} matches “${query.trim()}”." else "No novel here passes the filters.",
                        )
                    novels.isEmpty() ->
                        EmptyState("Nothing in ${section?.name ?: "this section"} yet", "Put a novel here from its page, under its title.")
                    asList ->
                        LazyColumn(
                            state = listState,
                            contentPadding = listPadding,
                            modifier = Modifier.fillMaxSize().then(if (canDrag) Modifier.reorderable(listReorder) else Modifier),
                        ) {
                            items(novels.size, key = { novels[it].novel.id }) { index ->
                                val item = novels[index]
                                ReorderableItem(listReorder, key = item.novel.id) { dragging ->
                                    LibraryRow(
                                        item = item,
                                        sourceName = sourceNames[item.novel.sourceId] ?: item.novel.site,
                                        waiting = item.novel.id in waiting,
                                        checkingSince = checking[item.novel.id],
                                        dragging = dragging,
                                        selected = item.novel.id in selected,
                                        onClick = { open(item) },
                                        onLongClick = hold?.let { { it(item) } },
                                    )
                                }
                            }
                        }
                    else ->
                        LazyVerticalGrid(
                            columns = GridCells.Adaptive(minSize = 108.dp),
                            state = gridState,
                            contentPadding = gridPadding,
                            modifier = Modifier.fillMaxSize().then(if (canDrag) Modifier.reorderable(gridReorder) else Modifier),
                        ) {
                            items(novels.size, key = { novels[it].novel.id }) { index ->
                                val item = novels[index]
                                ReorderableItem(gridReorder, key = item.novel.id) { dragging ->
                                    LibraryItem(
                                        item = item,
                                        waiting = item.novel.id in waiting,
                                        checkingSince = checking[item.novel.id],
                                        dragging = dragging,
                                        selected = item.novel.id in selected,
                                        onClick = { open(item) },
                                        onLongClick = hold?.let { { it(item) } },
                                    )
                                }
                            }
                        }
                }
            }
        }
    }

    if (filtering) {
        FilterSheet(
            filters = state.filters,
            sort = state.sort,
            display = state.display,
            onFilter = viewModel::setFilter,
            onSort = viewModel::setSort,
            onDisplay = viewModel::setDisplay,
            onDismiss = { filtering = false },
        )
    }
    when (dialog) {
        LibraryDialog.Sections ->
            SectionsDialog(
                sections = state.sections,
                novels = state.novels.filter { it.novel.id in selected },
                onApply = { changes ->
                    dialog = null
                    viewModel.setSections(selected, changes)
                    select(emptySet())
                },
                onDismiss = { dialog = null },
            )
        LibraryDialog.RemoveBooks -> {
            val chosen = state.novels.filter { it.novel.id in selected }.map { it.novel }
            val bookIds = chosen.filter { it.isBook }.map { it.id }
            RemoveBooksDialog(
                title = chosen.singleOrNull()?.title,
                novels = chosen.size,
                books = bookIds.size,
                size = { viewModel.bookSize(bookIds) },
                onRemove = {
                    dialog = null
                    val ids = selected
                    select(emptySet())
                    scope.launch {
                        val removed = viewModel.remove(ids)
                        viewModel.freeBooks(bookIds)
                        // Only the others can be put back: the books' text is gone.
                        val others = removed.filter { it.id !in bookIds }
                        tell("Took ${countOf(removed.size, "novel")} out of the library", if (others.isEmpty()) null else ({ viewModel.putBack(others) }))
                    }
                },
                onDismiss = { dialog = null },
            )
        }
        LibraryDialog.Download ->
            DownloadDialog(
                novels = selected.size,
                count = { viewModel.unreadToDownload(selected) },
                waitsForWifi = { viewModel.downloadsWaitForWifi() },
                onDownload = { count ->
                    dialog = null
                    viewModel.downloadUnread(selected)
                    select(emptySet())
                    tell(if (count == 0) "The unread chapters are all downloaded" else "Downloading ${countOf(count, "chapter")}")
                },
                onDismiss = { dialog = null },
            )
        null -> Unit
    }
}

/** The dialogs the library's selection opens. */
internal enum class LibraryDialog { Sections, Download, RemoveBooks }

/**
 * New-chapter notifications are on from the start, but from Android 13 the app must ask before
 * posting any. It asks here, once the library has novels for the background check to look at.
 */
@Composable
private fun AskToNotify(ask: Boolean, onDenied: () -> Unit) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
    val context = LocalContext.current
    val request = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted -> if (!granted) onDenied() }
    LaunchedEffect(ask) {
        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        if (ask && !granted) request.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
}
