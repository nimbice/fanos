package io.github.nimbice.fanos.feature.novel

import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animate
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.MutatePriority
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.layout
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.requireLayoutCoordinates
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.relocation.BringIntoViewModifierNode
import androidx.compose.ui.relocation.bringIntoView
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.nimbice.fanos.core.data.browserLabel
import io.github.nimbice.fanos.core.data.browserUrl
import io.github.nimbice.fanos.core.data.download.ChapterDownload
import io.github.nimbice.fanos.core.data.errorDetail
import io.github.nimbice.fanos.core.data.source.UnknownSourceException
import io.github.nimbice.fanos.core.data.userMessage
import io.github.nimbice.fanos.core.designsystem.component.ErrorState
import io.github.nimbice.fanos.core.designsystem.component.LoadingState
import io.github.nimbice.fanos.core.designsystem.component.NovelCover
import io.github.nimbice.fanos.core.designsystem.component.SelectionAction
import io.github.nimbice.fanos.core.designsystem.component.SelectionActions
import io.github.nimbice.fanos.core.designsystem.component.TopPullToRefreshBox
import io.github.nimbice.fanos.core.designsystem.component.WithRecentSearches
import io.github.nimbice.fanos.core.designsystem.component.chapterSummary
import io.github.nimbice.fanos.core.designsystem.component.countOf
import io.github.nimbice.fanos.core.designsystem.component.number
import io.github.nimbice.fanos.core.designsystem.component.scaffoldContent
import io.github.nimbice.fanos.core.designsystem.icon.ReaderIcons
import io.github.nimbice.fanos.core.model.Chapter
import io.github.nimbice.fanos.core.model.ChapterGroup
import io.github.nimbice.fanos.core.model.Novel
import io.github.nimbice.fanos.core.model.NovelStatus
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date
import io.github.nimbice.fanos.core.designsystem.component.RemoveBooksDialog
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.runtime.derivedStateOf
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.ButtonDefaults
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import java.time.LocalDate
import java.time.ZoneId
import io.github.nimbice.fanos.core.data.release.releaseDays
import io.github.nimbice.fanos.core.data.release.releasePattern
import io.github.nimbice.fanos.core.data.release.releaseText
import io.github.nimbice.fanos.core.designsystem.component.FittingText
import androidx.compose.material3.HorizontalDivider

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun NovelScreen(
    onBack: () -> Unit,
    onOpenChapter: (novelId: Long, chapterId: Long) -> Unit,
    onOpenInBrowser: (String) -> Unit,
    onOpenExtensions: () -> Unit,
    onMove: (novelId: Long) -> Unit,
    movedTo: String? = null,
    viewModel: NovelViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val sections by viewModel.sections.collectAsStateWithLifecycle()
    val novelSections by viewModel.novelSections.collectAsStateWithLifecycle()
    val coverSearch by viewModel.coverSearch.collectAsStateWithLifecycle()
    var editing by rememberSaveable { mutableStateOf(false) }
    val toFinish by viewModel.toFinish.collectAsStateWithLifecycle()
    // A book asks before it leaves the library: its text goes with it.
    var removingBook by rememberSaveable { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    // The top bar shows the title only once the big one under it has scrolled away.
    val density = LocalDensity.current
    val titleScrolledAway by remember(listState, density) {
        derivedStateOf { listState.firstVisibleItemIndex > 0 || listState.firstVisibleItemScrollOffset > with(density) { TITLE_GONE.roundToPx() } }
    }
    val chapterSearches by viewModel.chapterSearches.items.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val novel = state.novel
    // When new chapters come out, going by when the last few weeks' did: for a novel still coming out.
    val release =
        remember(state.chapters, novel?.status) {
            val today = LocalDate.now()
            novel?.takeIf { it.status == NovelStatus.Ongoing || it.status == NovelStatus.Unknown }
                ?.let { releasePattern(releaseDays(state.chapters, ZoneId.systemDefault()), today) }
                ?.let { releaseText(it, today) }
        }

    // The chapter tools: a search (a number goes to that chapter, words find titles), filters, and chapters selected.
    var searching by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    var unreadOnly by rememberSaveable { mutableStateOf(false) }
    var downloadedOnly by rememberSaveable { mutableStateOf(false) }
    var unlockedOnly by rememberSaveable { mutableStateOf(false) }
    // Only a list with locked chapters and ones the reader can read has anything to narrow to.
    val mixedLocks = state.chapters.any { it.locked } && state.chapters.any { !it.locked }
    var selectedIds by rememberSaveable { mutableStateOf(LongArray(0)) }
    var anchor by rememberSaveable { mutableStateOf<Long?>(null) }
    val selected = remember(selectedIds) { selectedIds.toSet() }
    fun select(ids: Set<Long>) {
        selectedIds = ids.toLongArray()
        if (ids.isEmpty()) anchor = null
    }
    val readingOrder = if (state.newestFirst) state.chapters.asReversed() else state.chapters
    val found = remember(state.chapters, query) { findChapter(readingOrder, query) }
    val shown =
        remember(state.chapters, query, unreadOnly, downloadedOnly, unlockedOnly, mixedLocks, state.downloads) {
            val words = query.trim().takeUnless { it.isEmpty() || isChapterNumber(it) }
            state.chapters.filter { chapter ->
                (!unreadOnly || !chapter.isRead) &&
                    (!downloadedOnly || state.downloads[chapter.id] == ChapterDownload.Saved) &&
                    (!unlockedOnly || !mixedLocks || !chapter.locked) &&
                    (words == null || chapter.title.contains(words, ignoreCase = true))
            }
        }
    // Chapters that leave the list leave the selection.
    LaunchedEffect(state.chapters) {
        val listed = state.chapters.mapTo(HashSet()) { it.id }
        if (!listed.containsAll(selected)) select(selected intersect listed)
    }
    BackHandler(enabled = selected.isNotEmpty()) { select(emptySet()) }
    BackHandler(enabled = searching && selected.isEmpty()) {
        searching = false
        query = ""
    }
    /** A long press: selects the chapter, or, while some are selected, every chapter from the last one pressed to it. */
    fun hold(chapter: Chapter) {
        val from = anchor?.let { id -> shown.indexOfFirst { it.id == id } }?.takeIf { it >= 0 && selected.isNotEmpty() }
        val to = shown.indexOfFirst { it.id == chapter.id }
        select(if (from == null || to < 0) selected + chapter.id else selected + shown.subList(minOf(from, to), maxOf(from, to) + 1).map { it.id })
        anchor = chapter.id
    }

    /** A snackbar, with [actionLabel] doing [action] when there is one. */
    fun tell(message: String, actionLabel: String, action: (() -> Unit)?) {
        scope.launch {
            snackbar.currentSnackbarData?.dismiss()
            val result = snackbar.showSnackbar(message, actionLabel = action?.let { actionLabel }, duration = SnackbarDuration.Short)
            if (result == SnackbarResult.ActionPerformed) action?.invoke()
        }
    }

    // Says what a tap just did, with a way back: the library toggle is only an icon, and the
    // mass read/unread changes are easy to hit on the wrong chapter.
    fun confirm(message: String, undo: (() -> Unit)? = null) = tell(message, "Undo", undo)

    if (removingBook) {
        state.novel?.let { book ->
            RemoveBooksDialog(
                title = book.title,
                novels = 1,
                books = 1,
                size = viewModel::bookSize,
                onRemove = {
                    removingBook = false
                    viewModel.removeBook()
                    tell("Removed. Its text and pictures are deleted", "Undo", null)
                },
                onDismiss = { removingBook = false },
            )
        }
    }

    // Said once, when the novel has just been moved here from another site.
    var movedTold by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(movedTo) {
        if (movedTo != null && !movedTold) {
            movedTold = true
            tell("Moved to $movedTo", "", null)
        }
    }

    Scaffold(
        topBar = {
            if (selected.isNotEmpty()) {
                ChapterSelectionBar(
                    count = selected.size,
                    onClose = { select(emptySet()) },
                    onSelectAll = { select(selected + shown.map { it.id }) },
                    onMarkEarlier = { read ->
                        val first = state.chapters.filter { it.id in selected }.minByOrNull { it.index } ?: return@ChapterSelectionBar
                        select(emptySet())
                        val changed = viewModel.markEarlier(first, read)
                        val word = if (read) "read" else "unread"
                        if (changed.isEmpty()) confirm("Earlier chapters are already $word") else confirm("Marked ${countOf(changed.size, "chapter")} $word") { viewModel.setRead(changed, !read) }
                    },
                )
            } else {
            TopAppBar(
                title = {
                    AnimatedVisibility(visible = titleScrolledAway, enter = fadeIn(), exit = fadeOut()) {
                        Text(novel?.title.orEmpty(), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                },
                actions = {
                    if (novel != null) {
                        IconButton(onClick = { editing = true }) { Icon(Icons.Filled.Edit, contentDescription = "Edit title and cover") }
                    }
                    if (novel != null && state.chapters.isNotEmpty()) DownloadMenu(state.downloads, viewModel, ::tell)
                    // A book has no page to open, nor another site to move to.
                    if (novel != null && !novel.isBook) MoreMenu(onOpenInBrowser = { onOpenInBrowser(novel.url) }, onMove = { onMove(novel.id) })
                },
            )
            }
        },
        bottomBar = {
            if (selected.isNotEmpty()) {
                val chosen = state.chapters.filter { it.id in selected }
                ChapterActions(
                    onRead = { read ->
                        val changed = chosen.filter { it.isRead != read }.map { it.id }
                        select(emptySet())
                        viewModel.setRead(changed, read)
                        val word = if (read) "read" else "unread"
                        if (changed.isEmpty()) confirm("They're all $word already") else confirm("Marked ${countOf(changed.size, "chapter")} $word") { viewModel.setRead(changed, !read) }
                    },
                    onDownload = {
                        val ids = chosen.filter { state.downloads[it.id] != ChapterDownload.Saved && !it.locked }.map { it.id }
                        select(emptySet())
                        viewModel.download(ids)
                        tell(if (ids.isEmpty()) "They're all downloaded already" else "Downloading ${countOf(ids.size, "chapter")}", "", null)
                    },
                    onDeleteDownloads = {
                        val waiting = chosen.filter { state.downloads[it.id].let { d -> d == ChapterDownload.Waiting || d == ChapterDownload.Downloading } }.map { it.id }
                        val saved = chosen.filter { state.downloads[it.id] == ChapterDownload.Saved }.map { it.id }
                        select(emptySet())
                        viewModel.cancelDownloads(waiting)
                        viewModel.deleteDownloads(saved)
                        tell(if (saved.isEmpty() && waiting.isEmpty()) "None of them was downloaded" else "Deleted ${countOf(saved.size + waiting.size, "download")}", "", null)
                    },
                )
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        // A missing extension has a banner of its own, which says what to do about it.
        val error = state.error?.takeUnless { state.sourceMissing && it is UnknownSourceException }
        when {
            !state.loaded -> LoadingState(Modifier.padding(padding))
            novel == null -> ErrorState("This novel is no longer in the app.", Modifier.padding(padding))
            state.chapters.isEmpty() && state.sourceMissing ->
                Column(Modifier.fillMaxSize().padding(padding)) {
                    Header(novel, state.sourceName)
                    MissingExtension(novel.site, hasChapters = false, onOpenExtensions = onOpenExtensions, onFindElsewhere = { onMove(novel.id) })
                }
            state.chapters.isEmpty() && error != null ->
                ErrorState(
                    message = userMessage(error),
                    modifier = Modifier.padding(padding),
                    onRetry = viewModel::refresh,
                    onOpenInBrowser = browserUrl(error)?.let { url -> { onOpenInBrowser(url) } },
                    browserLabel = browserLabel(error),
                    detail = errorDetail(error),
                )
            else -> {
                // Where the chapter header sits in the list: after the header, notices, actions, description and error.
                val chaptersIndex = 2 + (if (state.sourceMissing) 1 else 0) + (if (novel.description != null) 1 else 0) + (if (error != null) 1 else 0)
                // Opening the chapter search brings the chapter header, and the search field under it, to the top, clear of
                // the keyboard as it comes up. However short the list, the room after the chapters lets it get there.
                LaunchedEffect(searching) {
                    if (searching) listState.bringToTop(chaptersIndex)
                }
                LaunchedEffect(found?.id) {
                    val target = found ?: return@LaunchedEffect
                    val at = shown.indexOfFirst { it.id == target.id }.takeIf { it >= 0 } ?: return@LaunchedEffect
                    // The chapter above it (the chips, above the first) just under the pinned chapter header, and it in full below.
                    val header = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.key == "chapters" }?.size ?: 0
                    listState.animateScrollToItem(chaptersIndex + 1 + at, -header)
                }
                TopPullToRefreshBox(
                    isRefreshing = state.refreshing,
                    onRefresh = viewModel::refresh,
                    scrolledFromTop = { listState.canScrollBackward },
                    modifier = Modifier.fillMaxSize().scaffoldContent(padding),
                ) {
                    LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                        item {
                            Header(novel, state.sourceName, release) {
                                if (novel.inLibrary) {
                                    SectionChip(sections, novelSections, onToggle = viewModel::setInSection, onCreate = viewModel::createSection)
                                }
                            }
                        }
                        if (state.sourceMissing) {
                            item { MissingExtension(novel.site, hasChapters = true, onOpenExtensions = onOpenExtensions, onFindElsewhere = { onMove(novel.id) }) }
                        }
                        item {
                            Actions(
                                inLibrary = novel.inLibrary,
                                hasProgress = state.lastReadChapterId != null || state.chapters.any { it.isRead },
                                canRead = state.chapters.isNotEmpty(),
                                onContinue = { scope.launch { viewModel.resumeChapterId()?.let { onOpenChapter(novel.id, it) } } },
                                onToggleLibrary = {
                                    val adding = !novel.inLibrary
                                    if (novel.isBook && !adding) {
                                        removingBook = true
                                    } else {
                                        viewModel.setInLibrary(adding)
                                        confirm(if (adding) "Added to library" else "Removed from library") { viewModel.setInLibrary(!adding) }
                                    }
                                },
                            )
                        }
                        novel.description?.let { item { Description(it) } }
                        if (error != null) {
                            item {
                                ErrorBanner(userMessage(error), browserLabel(error), browserUrl(error)?.let { url -> { onOpenInBrowser(url) } })
                            }
                        }
                        stickyHeader(key = "chapters") {
                            ChaptersHeader(
                                // Pinned over the list once it has scrolled past its own place: an edge then shows what slides under it.
                                pinned = listState.firstVisibleItemIndex >= chaptersIndex,
                                toFinish = toFinish?.let(::finishLabel),
                                text = when {
                                    state.chapters.isNotEmpty() -> chapterSummary(state.chapters.size, state.chapters.count { !it.isRead })
                                    state.refreshing -> "Loading chapters…"
                                    else -> "No chapters"
                                },
                                newestFirst = state.newestFirst,
                                canReorder = state.chapters.size > 1,
                                onToggleOrder = {
                                    viewModel.toggleChapterOrder()
                                    // From deep in the list, go back to the top of the chapters so the new first ones show.
                                    if (listState.firstVisibleItemIndex > chaptersIndex) {
                                        scope.launch { listState.scrollToItem(chaptersIndex) }
                                    }
                                },
                                searching = searching,
                                query = query,
                                onQueryChange = { query = it },
                                onCloseSearch = {
                                    searching = false
                                    query = ""
                                },
                                recent = chapterSearches,
                                onSubmit = { viewModel.chapterSearches.remember(query) },
                                onPickRecent = { words ->
                                    query = words
                                    viewModel.chapterSearches.remember(words)
                                },
                                onForgetRecent = viewModel.chapterSearches::forget,
                            )
                        }
                        // Several groups translate it: the reader can follow one; until they do, each chapter says whose it is.
                        val showGroups = state.groups.size > 1
                        if (state.chapters.isNotEmpty()) {
                            item(key = "tools") {
                                ChapterFilters(
                                    searching = searching,
                                    onSearch = {
                                        searching = !searching
                                        if (!searching) query = ""
                                    },
                                    group =
                                        if (showGroups) {
                                            {
                                                GroupChip(
                                                    followed = novel.chapterGroup,
                                                    chosen = novel.chapterGroupChosen,
                                                    versions = state.groupsAreVersions,
                                                    groups = state.groups,
                                                    allChapters = state.allChapters,
                                                    onChoose = viewModel::setChapterGroup,
                                                    onDefault = viewModel::followDefaultVersion,
                                                )
                                            }
                                        } else {
                                            null
                                        },
                                    unreadOnly = unreadOnly,
                                    downloadedOnly = downloadedOnly,
                                    unlockedOnly = if (mixedLocks) unlockedOnly else null,
                                    onUnreadOnly = { unreadOnly = it },
                                    onDownloadedOnly = { downloadedOnly = it },
                                    onUnlockedOnly = { unlockedOnly = it },
                                )
                            }
                        }
                        if (state.chapters.isNotEmpty() && shown.isEmpty()) {
                            item(key = "none") {
                                Text(
                                    if (query.isNotBlank() && !isChapterNumber(query)) "No chapter title has “${query.trim()}” in it." else "No chapter here passes the filters.",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                                )
                            }
                        }
                        items(shown, key = { it.id }) { chapter ->
                            val isFound = chapter.id == found?.id
                            ChapterRow(
                                chapter = chapter,
                                group = chapter.group.takeIf { showGroups && novel.chapterGroup == null },
                                isLastRead = chapter.id == state.lastReadChapterId,
                                download = state.downloads[chapter.id],
                                selecting = selected.isNotEmpty(),
                                selected = chapter.id in selected,
                                // Its place in the list, which a prologue or a note can put apart from the number in its title.
                                found = if (isFound) "Found · ${number(readingOrder.indexOf(chapter) + 1)} of ${number(readingOrder.size)}" else null,
                                onOpen = {
                                    if (selected.isNotEmpty()) {
                                        select(if (chapter.id in selected) selected - chapter.id else selected + chapter.id)
                                    } else {
                                        // A chapter found by searching: the search is worth keeping.
                                        if (searching) viewModel.chapterSearches.remember(query)
                                        onOpenChapter(novel.id, chapter.id)
                                    }
                                },
                                onHold = { hold(chapter) },
                                onSetRead = { viewModel.setRead(listOf(chapter.id), it) },
                                onDownloadFailed = { reason -> tell(reason, "Try again") { viewModel.download(listOf(chapter.id)) } },
                            )
                        }
                        // The header stays at the top however the filters and the search narrow the list.
                        if (searching) item(key = "room") { Room(rows = shown.size) }
                    }
                }
            }
        }
    }

    if (editing && novel != null) {
        EditNovelSheet(
            novel = novel,
            coverSearch = coverSearch,
            onSearchCovers = viewModel::searchCovers,
            onCloseSearch = viewModel::clearCoverSearch,
            onKeepPicture = { picture, onKept -> viewModel.keepPicture(picture, onKept) { problem -> tell(problem, "", null) } },
            onSave = { title, cover ->
                viewModel.saveEdits(title, cover)
                editing = false
            },
            onDismiss = {
                viewModel.discardEdits()
                editing = false
            },
        )
    }
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun Header(novel: Novel, sourceName: String?, release: String? = null, sections: @Composable () -> Unit = {}) {
    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            NovelCover(novel.coverUrl, novel.title, Modifier.width(112.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(novel.title, style = MaterialTheme.typography.titleLarge)
                novel.author?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                val status = novel.status.label()
                Text(
                    listOfNotNull(status, sourceName).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                release?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                sections()
            }
        }
        // The genres wrap across the page under the cover: beside it, a long list ran on far below it.
        if (novel.genres.isNotEmpty()) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                novel.genres.take(8).forEach { SuggestionChip(onClick = {}, label = { Text(it, style = MaterialTheme.typography.labelSmall) }) }
            }
        }
    }
}

@Composable
private fun Actions(
    inLibrary: Boolean,
    hasProgress: Boolean,
    canRead: Boolean,
    onContinue: () -> Unit,
    onToggleLibrary: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Button(onClick = onContinue, enabled = canRead, modifier = Modifier.weight(1f)) {
            Text(if (hasProgress) "Continue" else "Start reading", maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        // Says what it is: "In library" (a tap takes it out), or "Add to library".
        if (inLibrary) {
            FilledTonalButton(
                onClick = onToggleLibrary,
                contentPadding = ButtonDefaults.ButtonWithIconContentPadding,
                modifier = Modifier.semantics { contentDescription = "In library, tap to remove" },
            ) {
                Icon(ReaderIcons.LibraryAdded, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
                Spacer(Modifier.width(ButtonDefaults.IconSpacing))
                Text("In library")
            }
        } else {
            OutlinedButton(onClick = onToggleLibrary, contentPadding = ButtonDefaults.ButtonWithIconContentPadding) {
                Icon(ReaderIcons.LibraryAdd, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
                Spacer(Modifier.width(ButtonDefaults.IconSpacing))
                Text("Add to library")
            }
        }
    }
}

@Composable
private fun Description(text: String) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    Column(
        Modifier
            .fillMaxWidth()
            .clickable { expanded = !expanded }
            .padding(horizontal = 16.dp, vertical = 12.dp)
            .animateContentSize(),
    ) {
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = if (expanded) Int.MAX_VALUE else 4,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            if (expanded) "Less" else "More",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

/** The novel's site has no extension installed: what that means for the novel, and the ways to fix it. */
@Composable
private fun MissingExtension(site: String, hasChapters: Boolean, onOpenExtensions: () -> Unit, onFindElsewhere: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 12.dp),
    ) {
        Column(Modifier.padding(start = 16.dp, top = 12.dp, end = 8.dp, bottom = 4.dp)) {
            Text(
                if (hasChapters) {
                    "The extension for $site isn't installed, so new chapters can't be checked. Chapters you downloaded can still be read."
                } else {
                    "The extension for $site isn't installed, so this novel's chapters can't be listed."
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
            Row(Modifier.align(Alignment.End)) {
                TextButton(onClick = onOpenExtensions) { Text("Extensions") }
                TextButton(onClick = onFindElsewhere) { Text("Find it on another site") }
            }
        }
    }
}

/** The novel's site in the browser, and moving the novel to another site. */
@Composable
private fun MoreMenu(onOpenInBrowser: () -> Unit, onMove: () -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) { Icon(Icons.Filled.MoreVert, contentDescription = "More") }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(
                text = { Text("Open in browser") },
                leadingIcon = { Icon(ReaderIcons.Globe, contentDescription = null) },
                onClick = {
                    open = false
                    onOpenInBrowser()
                },
            )
            DropdownMenuItem(
                text = { Text("Move to another site") },
                leadingIcon = { Icon(ReaderIcons.Move, contentDescription = null) },
                onClick = {
                    open = false
                    onMove()
                },
            )
        }
    }
}

@Composable
private fun ErrorBanner(message: String, browserLabel: String, onOpenInBrowser: (() -> Unit)?) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error, modifier = Modifier.weight(1f))
        if (onOpenInBrowser != null) TextButton(onClick = onOpenInBrowser) { Text(browserLabel) }
    }
}

/** How long the unread chapters take, roughly: minutes under an hour, then hours (and tens of minutes, under ten). */
private fun finishLabel(minutes: Int): String? =
    when {
        minutes <= 0 -> null
        minutes < 60 -> "About $minutes min to finish"
        minutes < 600 -> {
            val tens = ((minutes % 60) + 5) / 10 * 10
            val hours = minutes / 60 + if (tens == 60) 1 else 0
            if (tens == 0 || tens == 60) "About $hours h to finish" else "About $hours h $tens min to finish"
        }
        else -> "About ${(minutes + 30) / 60} h to finish"
    }

/**
 * Chapter count and order. It sticks to the top while the chapters scroll under it, with the chapter search's field
 * under it while searching (the chips start the search): a number goes to that chapter, words find titles.
 */
@Composable
private fun ChaptersHeader(
    pinned: Boolean,
    toFinish: String?,
    text: String,
    newestFirst: Boolean,
    canReorder: Boolean,
    onToggleOrder: () -> Unit,
    searching: Boolean,
    query: String,
    onQueryChange: (String) -> Unit,
    onCloseSearch: () -> Unit,
    recent: List<String>,
    onSubmit: () -> Unit,
    onPickRecent: (String) -> Unit,
    onForgetRecent: (String) -> Unit,
) {
    Surface(color = MaterialTheme.colorScheme.background) {
        Column {
            Row(
                Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(start = 16.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f).padding(vertical = 4.dp)) {
                    Text(text, style = MaterialTheme.typography.titleSmall)
                    toFinish?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
                if (canReorder) {
                    TextButton(onClick = onToggleOrder) {
                        Icon(ReaderIcons.SwapVert, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(if (newestFirst) "Newest first" else "Oldest first")
                    }
                }
            }
            if (searching) {
                val focus = remember { FocusRequester() }
                val focusManager = LocalFocusManager.current
                LaunchedEffect(Unit) { focus.requestFocus() }
                WithRecentSearches(query, recent, onPickRecent, onForgetRecent, Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) { anchor ->
                    TextField(
                        value = query,
                        onValueChange = onQueryChange,
                        placeholder = { FittingText("Chapter number or title") },
                        leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                        trailingIcon = { IconButton(onClick = onCloseSearch) { Icon(Icons.Filled.Close, contentDescription = "Stop searching") } },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        // The list narrows, or goes to a chapter, as it's typed in: the search key puts the keyboard away, and keeps the words.
                        keyboardActions =
                            KeyboardActions(onSearch = {
                                focusManager.clearFocus()
                                onSubmit()
                            }),
                        shape = RoundedCornerShape(28.dp),
                        colors =
                            TextFieldDefaults.colors(
                                focusedIndicatorColor = Color.Transparent,
                                unfocusedIndicatorColor = Color.Transparent,
                                focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                                unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                            ),
                        modifier = anchor.fillMaxWidth().focusRequester(focus).then(StayWhileShown),
                    )
                }
            }
            if (pinned) HorizontalDivider()
        }
    }
}

/**
 * Keeps the chapter list still when the search field, already in full view, asks to be scrolled into view, as a text
 * field does for its cursor at each change: the list takes the pinned header the field sits in for something covering
 * it, and nudges itself down. A field partly out of view is still scrolled to.
 */
private class StayWhileShownNode : Modifier.Node(), BringIntoViewModifierNode {
    override suspend fun bringIntoView(childCoordinates: LayoutCoordinates, boundsProvider: () -> Rect?) {
        val field = requireLayoutCoordinates()
        val shown = field.boundsInWindow()
        if (shown.width >= field.size.width - 0.5f && shown.height >= field.size.height - 0.5f) return
        val child = field.localBoundingBoxOf(childCoordinates, clipBounds = false)
        bringIntoView { boundsProvider()?.translate(child.topLeft) ?: child }
    }
}

private data object StayWhileShown : ModifierNodeElement<StayWhileShownNode>() {
    override fun create() = StayWhileShownNode()

    override fun update(node: StayWhileShownNode) = Unit
}

/**
 * The chips over the chapters: the chapter search, the group followed where several translate the novel, and unread
 * ones only, downloaded ones only, and (where some are locked and some not) unlocked ones only. [unlockedOnly] is null
 * when there's no such mix.
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun ChapterFilters(
    searching: Boolean,
    onSearch: () -> Unit,
    group: (@Composable () -> Unit)?,
    unreadOnly: Boolean,
    downloadedOnly: Boolean,
    unlockedOnly: Boolean?,
    onUnreadOnly: (Boolean) -> Unit,
    onDownloadedOnly: (Boolean) -> Unit,
    onUnlockedOnly: (Boolean) -> Unit,
) {
    FlowRow(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(
            selected = searching,
            onClick = onSearch,
            label = { Icon(Icons.Filled.Search, contentDescription = if (searching) "Stop finding a chapter" else "Find a chapter", modifier = Modifier.size(FilterChipDefaults.IconSize)) },
        )
        group?.invoke()
        FilterChip(selected = unreadOnly, onClick = { onUnreadOnly(!unreadOnly) }, label = { Text("Unread") })
        FilterChip(selected = downloadedOnly, onClick = { onDownloadedOnly(!downloadedOnly) }, label = { Text("Downloaded") })
        if (unlockedOnly != null) {
            FilterChip(
                selected = unlockedOnly,
                onClick = { onUnlockedOnly(!unlockedOnly) },
                label = { Text("Unlocked") },
                leadingIcon = if (unlockedOnly) ({ Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.size(FilterChipDefaults.IconSize)) }) else null,
            )
        }
    }
}

/** The top bar while chapters are selected: how many, select all, and marking the chapters before them. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChapterSelectionBar(count: Int, onClose: () -> Unit, onSelectAll: () -> Unit, onMarkEarlier: (read: Boolean) -> Unit) {
    var menu by remember { mutableStateOf(false) }
    TopAppBar(
        title = { Text("${number(count)} selected") },
        navigationIcon = { IconButton(onClick = onClose) { Icon(Icons.Filled.Close, contentDescription = "Stop selecting") } },
        actions = {
            IconButton(onClick = onSelectAll) { Icon(ReaderIcons.SelectAll, contentDescription = "Select all") }
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, contentDescription = "More") }
                // "Earlier" rather than "above" or "below": it means the same thing in either order.
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(
                        text = { Text("Mark earlier chapters read") },
                        leadingIcon = { Icon(ReaderIcons.DoneAll, contentDescription = null) },
                        onClick = {
                            menu = false
                            onMarkEarlier(true)
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("Mark earlier chapters unread") },
                        leadingIcon = { Icon(ReaderIcons.RemoveDone, contentDescription = null) },
                        onClick = {
                            menu = false
                            onMarkEarlier(false)
                        },
                    )
                }
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
    )
}

/** What can be done with the chapters selected, along the bottom. */
@Composable
private fun ChapterActions(onRead: (Boolean) -> Unit, onDownload: () -> Unit, onDeleteDownloads: () -> Unit) {
    SelectionActions {
        SelectionAction(ReaderIcons.DoneAll, "Read") { onRead(true) }
        SelectionAction(ReaderIcons.RemoveDone, "Unread") { onRead(false) }
        SelectionAction(ReaderIcons.Download, "Download", onDownload)
        SelectionAction(Icons.Filled.Delete, "Delete download", onDeleteDownloads)
    }
}

/** Whose chapters the list shows, for a novel several translation groups translate; a tap offers the others. */
@Composable
private fun GroupChip(
    followed: String?,
    chosen: Boolean,
    versions: Boolean,
    groups: List<ChapterGroup>,
    allChapters: Int,
    onChoose: (String?) -> Unit,
    onDefault: () -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    // Versions of the same chapters: one is always followed, the default one until the reader picks another.
    val default = versions && !chosen
    Box(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 4.dp)) {
        FilterChip(
            selected = if (versions) chosen else followed != null,
            onClick = { open = true },
            label = { Text(if (default && followed != null) "$followed · default" else followed ?: "All groups", maxLines = 1, overflow = TextOverflow.Ellipsis) },
            leadingIcon = { Icon(ReaderIcons.Group, contentDescription = null, modifier = Modifier.size(FilterChipDefaults.IconSize)) },
            trailingIcon = { Icon(Icons.Filled.ArrowDropDown, contentDescription = null, modifier = Modifier.size(FilterChipDefaults.IconSize)) },
        )
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            if (versions) {
                GroupItem("Default", chapters = null, selected = default) {
                    open = false
                    onDefault()
                }
            } else {
                GroupItem("All groups", allChapters, selected = followed == null) {
                    open = false
                    onChoose(null)
                }
            }
            groups.forEach { group ->
                GroupItem(group.name, group.chapters, selected = !default && followed == group.name) {
                    open = false
                    onChoose(group.name)
                }
            }
        }
    }
}

@Composable
private fun GroupItem(name: String, chapters: Int?, selected: Boolean, onClick: () -> Unit) {
    DropdownMenuItem(
        text = { Text(name) },
        leadingIcon = {
            if (selected) Icon(Icons.Filled.Check, contentDescription = "Shown", tint = MaterialTheme.colorScheme.primary) else Spacer(Modifier.size(24.dp))
        },
        trailingIcon = chapters?.let { { Text(number(it), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) } },
        onClick = onClick,
    )
}

/**
 * Blank space after the chapters while searching, so the chapter header can scroll to the top however few chapters
 * pass: the list's height, less the least the [rows] above take. A list taller than the space gets none, and so no
 * blank to scroll into at its end.
 */
@Composable
private fun LazyItemScope.Room(rows: Int) {
    Spacer(
        Modifier
            .layout { measurable, constraints ->
                val whole = measurable.measure(constraints)
                layout(whole.width, (whole.height - (ChapterRowLeast * rows).roundToPx()).coerceAtLeast(0)) {}
            }
            .fillParentMaxHeight(),
    )
}

/** The least height of a chapter row, at any text size: its read button's, or its checkbox's while selecting. */
private val ChapterRowLeast = 48.dp

/**
 * Scrolls the item at [index] to the top of the list as a drag would, so the list keeping a focused field just in view
 * while the keyboard comes up can't stop it partway. A drag still can.
 */
private suspend fun LazyListState.bringToTop(index: Int) {
    val item = layoutInfo.visibleItemsInfo.firstOrNull { it.index == index } ?: return scrollToItem(index)
    scroll(MutatePriority.UserInput) {
        var moved = 0f
        animate(0f, item.offset.toFloat()) { value, _ -> moved += scrollBy(value - moved) }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ChapterRow(
    chapter: Chapter,
    /** The translation group that put it out, when the list shows several groups'. */
    group: String?,
    isLastRead: Boolean,
    download: ChapterDownload?,
    selecting: Boolean,
    selected: Boolean,
    /** Said under the chapter the chapter search went to. */
    found: String?,
    onOpen: () -> Unit,
    onHold: () -> Unit,
    onSetRead: (Boolean) -> Unit,
    onDownloadFailed: (reason: String) -> Unit,
) {
    val color =
        when {
            chapter.isRead -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
            chapter.locked -> MaterialTheme.colorScheme.onSurfaceVariant
            else -> MaterialTheme.colorScheme.onSurface
        }
    Row(
        Modifier
            .fillMaxWidth()
            .background(if (selected || found != null) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent)
            .combinedClickable(onClick = onOpen, onLongClick = onHold)
            .padding(start = 16.dp, end = 4.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(chapter.title, style = MaterialTheme.typography.bodyMedium, color = color, maxLines = 2, overflow = TextOverflow.Ellipsis)
            val published = chapter.publishedAt?.let { DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(it)) }
            val line = listOfNotNull(found, group, published, if (chapter.locked) "Members only" else null).joinToString(" · ")
            if (line.isNotEmpty()) Text(line, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (chapter.locked) {
            Icon(Icons.Filled.Lock, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
        }
        if (isLastRead) {
            Icon(ReaderIcons.Bookmark, contentDescription = "Last read", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
        }
        DownloadMark(download, onDownloadFailed)
        if (selecting) {
            Checkbox(checked = selected, onCheckedChange = { onOpen() })
        } else {
            IconButton(onClick = { onSetRead(!chapter.isRead) }) {
                Icon(
                    Icons.Filled.Check,
                    contentDescription = if (chapter.isRead) "Mark unread" else "Mark read",
                    tint = if (chapter.isRead) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                )
            }
        }
    }
}

/** Whether the chapter is saved, waiting or being fetched; nothing at all when it's none of these. */
@Composable
private fun DownloadMark(download: ChapterDownload?, onFailed: (reason: String) -> Unit) {
    val mark = Modifier.padding(horizontal = 4.dp).size(18.dp)
    when (download) {
        null -> Unit
        ChapterDownload.Saved ->
            Icon(ReaderIcons.DownloadDone, contentDescription = "Downloaded", tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = mark)
        ChapterDownload.Waiting ->
            Icon(ReaderIcons.Schedule, contentDescription = "Waiting to download", tint = MaterialTheme.colorScheme.outline, modifier = mark)
        ChapterDownload.Downloading -> CircularProgressIndicator(modifier = mark.padding(1.dp), strokeWidth = 2.dp)
        // Tapping says why, with a way to try again.
        is ChapterDownload.Failed ->
            Icon(
                ReaderIcons.ErrorOutline,
                contentDescription = "Download failed",
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.clickable { onFailed(download.reason) }.padding(horizontal = 4.dp).size(18.dp),
            )
    }
}

/**
 * The novel's downloads: the next ten unread chapters, or all of them (asking first when that's a
 * lot, as it keeps the site busy for a while), and cancelling or deleting what's there.
 */
@Composable
private fun DownloadMenu(
    downloads: Map<Long, ChapterDownload>,
    viewModel: NovelViewModel,
    tell: (message: String, actionLabel: String, action: (() -> Unit)?) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var open by remember { mutableStateOf(false) }
    var unread by remember { mutableStateOf<Int?>(null) }
    var asking by remember { mutableStateOf<Int?>(null) }
    var deleting by remember { mutableStateOf(false) }
    var waitsForWifi by remember { mutableStateOf(true) }
    LaunchedEffect(open) {
        if (open) {
            unread = viewModel.unreadToDownload()
            waitsForWifi = viewModel.downloadsWaitForWifi()
        }
    }
    val queued = downloads.filterValues { it != ChapterDownload.Saved }.keys.toList()
    val saved = downloads.filterValues { it == ChapterDownload.Saved }.keys.toList()

    fun queue(limit: Int?) {
        scope.launch {
            val count = viewModel.downloadUnread(limit)
            tell(if (count == 0) "The unread chapters are all downloaded" else "Downloading ${countOf(count, "chapter")}", "", null)
        }
    }

    Box {
        IconButton(onClick = { open = true }) { Icon(ReaderIcons.Download, contentDescription = "Download") }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            val count = unread
            DropdownMenuItem(
                text = { Text("Next $NEXT unread") },
                enabled = count != 0,
                onClick = {
                    open = false
                    queue(NEXT)
                },
            )
            DropdownMenuItem(
                text = { Text(if (count == null || count == 0) "All unread" else "All unread ($count)") },
                enabled = count != 0,
                onClick = {
                    open = false
                    if (count != null && count > ASK_ABOVE) asking = count else queue(null)
                },
            )
            if (queued.isNotEmpty()) {
                DropdownMenuItem(
                    text = { Text("Cancel downloads") },
                    onClick = {
                        open = false
                        viewModel.cancelDownloads(queued)
                    },
                )
            }
            if (saved.isNotEmpty()) {
                DropdownMenuItem(
                    text = { Text("Delete downloads") },
                    onClick = {
                        open = false
                        deleting = true
                    },
                )
            }
        }
    }

    asking?.let { count ->
        // A few seconds a chapter, one at a time: see the downloader.
        val minutes = (count * SECONDS_PER_CHAPTER / 60).toInt().coerceAtLeast(1)
        AlertDialog(
            onDismissRequest = { asking = null },
            title = { Text("Download ${countOf(count, "chapter")}?") },
            text = {
                Text(
                    "They're fetched one at a time, a few seconds apart, so the site isn't flooded: about " +
                        "${countOf(minutes, "minute")}." + if (waitsForWifi) " Downloads wait for Wi-Fi." else "",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    asking = null
                    queue(null)
                }) { Text("Download") }
            },
            dismissButton = { TextButton(onClick = { asking = null }) { Text("Cancel") } },
        )
    }
    if (deleting) {
        AlertDialog(
            onDismissRequest = { deleting = false },
            title = { Text("Delete ${countOf(saved.size, "downloaded chapter")}?") },
            text = { Text("They stay in the list and can be downloaded again.") },
            confirmButton = {
                TextButton(onClick = {
                    deleting = false
                    viewModel.deleteDownloads(saved)
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { deleting = false }) { Text("Cancel") } },
        )
    }
}

private const val NEXT = 10
private const val ASK_ABOVE = 50
private const val SECONDS_PER_CHAPTER = 2.75

private fun NovelStatus.label(): String? =
    when (this) {
        NovelStatus.Unknown -> null
        NovelStatus.Ongoing -> "Ongoing"
        NovelStatus.Completed -> "Completed"
        NovelStatus.Hiatus -> "On hiatus"
        NovelStatus.Cancelled -> "Cancelled"
    }

/** How far the page scrolls before the big title under the top bar is gone, and the top bar shows it instead. */
private val TITLE_GONE = 96.dp
