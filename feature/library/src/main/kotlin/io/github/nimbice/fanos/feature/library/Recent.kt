package io.github.nimbice.fanos.feature.library

import android.text.format.DateUtils
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.nimbice.fanos.core.data.download.DownloadRepository
import io.github.nimbice.fanos.core.data.repository.NovelRepository
import io.github.nimbice.fanos.core.data.repository.RecentRepository
import io.github.nimbice.fanos.core.designsystem.component.EmptyState
import io.github.nimbice.fanos.core.designsystem.component.LocalTabBar
import io.github.nimbice.fanos.core.designsystem.component.LoadingState
import io.github.nimbice.fanos.core.designsystem.component.NovelCover
import io.github.nimbice.fanos.core.designsystem.component.SelectionAction
import io.github.nimbice.fanos.core.designsystem.component.SelectionActions
import io.github.nimbice.fanos.core.designsystem.component.countOf
import io.github.nimbice.fanos.core.designsystem.component.number
import io.github.nimbice.fanos.core.designsystem.icon.ReaderIcons
import io.github.nimbice.fanos.core.model.HistoryEntry
import io.github.nimbice.fanos.core.model.RecentChapter
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import java.text.DateFormat
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Date
import javax.inject.Inject
import io.github.nimbice.fanos.core.data.repository.StatsRepository
import io.github.nimbice.fanos.core.model.ReadingStats
import io.github.nimbice.fanos.core.data.repository.SettingsRepository
import kotlinx.coroutines.flow.map
import io.github.nimbice.fanos.core.data.release.releasePattern
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import androidx.compose.foundation.layout.BoxWithConstraints
import io.github.nimbice.fanos.core.designsystem.component.fittingStyle

/** The Updates tab: what came in lately and what was read lately. Set [SHOW_NEW_CHAPTERS] true in its saved state to open it on the new chapters. */
@Serializable
data object RecentRoute {
    const val SHOW_NEW_CHAPTERS = "show_new_chapters"
}

@HiltViewModel
class RecentViewModel @Inject constructor(
    private val recent: RecentRepository,
    statsRepository: StatsRepository,
    private val settings: SettingsRepository,
    private val novels: NovelRepository,
    private val downloads: DownloadRepository,
) : ViewModel() {

    /** Null until loaded. */
    val updates: StateFlow<List<RecentChapter>?> = recent.observeUpdates().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val history: StateFlow<List<HistoryEntry>?> = recent.observeHistory().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val stats: StateFlow<ReadingStats?> = statsRepository.observe().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val _calendar = MutableStateFlow<List<CalendarNovel>?>(null)

    /** When library novels' new chapters usually come out; null until worked out. */
    val calendar: StateFlow<List<CalendarNovel>?> = _calendar.asStateFlow()

    /** Works the calendar out afresh, from the chapters as they are now: each time it's looked at. */
    fun loadCalendar() {
        viewModelScope.launch {
            val today = LocalDate.now()
            _calendar.value =
                recent.releaseDays(ZoneId.systemDefault())
                    .mapNotNull { novel -> releasePattern(novel.days, today)?.let { CalendarNovel(novel.novelId, novel.title, novel.coverUrl, it, today in novel.days) } }
                    .sortedBy { it.title.lowercase() }
        }
    }

    /** Minutes a day to aim for; 0 for none. */
    val goal: StateFlow<Int> = settings.appSettings.map { it.dailyGoalMinutes }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    fun setGoal(minutes: Int) {
        viewModelScope.launch { settings.updateAppSettings { it.copy(dailyGoalMinutes = minutes) } }
    }

    fun markSeen() {
        viewModelScope.launch { recent.markUpdatesSeen() }
    }

    fun download(chapters: List<RecentChapter>) {
        viewModelScope.launch { chapters.groupBy { it.novelId }.forEach { (novelId, ofNovel) -> downloads.download(novelId, ofNovel.map { it.chapterId }) } }
    }

    fun setRead(chapterIds: List<Long>, read: Boolean) {
        viewModelScope.launch { novels.setRead(chapterIds, read) }
    }

    /** Deletes the chapters' downloads, and takes the ones waiting off the queue. */
    fun deleteDownloads(chapterIds: List<Long>) {
        viewModelScope.launch { downloads.delete(chapterIds) }
    }

    /** Takes the novel off the history; returns when it was last read, to put it back with. */
    suspend fun forget(novelId: Long): Long? = recent.forget(novelId)

    fun putBack(novelId: Long, lastReadAt: Long) {
        viewModelScope.launch { recent.putBack(novelId, lastReadAt) }
    }
}

fun NavGraphBuilder.recentScreen(onOpenNovel: (Long) -> Unit, onOpenChapter: (novelId: Long, chapterId: Long) -> Unit) {
    composable<RecentRoute> { entry ->
        val showNewChapters by entry.savedStateHandle.getStateFlow(RecentRoute.SHOW_NEW_CHAPTERS, false).collectAsStateWithLifecycle()
        RecentScreen(
            onOpenNovel = onOpenNovel,
            onOpenChapter = onOpenChapter,
            showNewChapters = showNewChapters,
            onNewChaptersShown = { entry.savedStateHandle[RecentRoute.SHOW_NEW_CHAPTERS] = false },
        )
    }
}

/**
 * The Updates tab. New chapters: the chapters that arrived in library novels, newest first by day; a tap opens one.
 * History: the novels read, last read first, each with the chapter the reader was on; a tap goes on reading there, and
 * × takes a novel off. [showNewChapters] turns it to the new chapters, as a tapped new-chapters notice asks.
 *
 * New chapters are selected as on a novel's page: a long press selects one, or, while some are, every one from the last
 * pressed to it; a tap then adds or drops one, and a day's heading takes its day. What can be done with them takes the
 * tab bar's place meanwhile.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecentScreen(
    onOpenNovel: (Long) -> Unit,
    onOpenChapter: (novelId: Long, chapterId: Long) -> Unit,
    showNewChapters: Boolean = false,
    onNewChaptersShown: () -> Unit = {},
    viewModel: RecentViewModel = hiltViewModel(),
) {
    val updates by viewModel.updates.collectAsStateWithLifecycle()
    val history by viewModel.history.collectAsStateWithLifecycle()
    val stats by viewModel.stats.collectAsStateWithLifecycle()
    val goal by viewModel.goal.collectAsStateWithLifecycle()
    val calendar by viewModel.calendar.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableIntStateOf(0) }
    LaunchedEffect(tab) { if (tab == CALENDAR_TAB) viewModel.loadCalendar() }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var selectedIds by rememberSaveable { mutableStateOf(LongArray(0)) }
    var anchor by rememberSaveable { mutableStateOf<Long?>(null) }
    val selected = remember(selectedIds) { selectedIds.toSet() }
    val selecting = selected.isNotEmpty()
    fun select(ids: Set<Long>) {
        selectedIds = ids.toLongArray()
        if (ids.isEmpty()) anchor = null
    }
    val listed = updates.orEmpty()
    // Chapters that leave the list leave the selection.
    LaunchedEffect(updates) {
        val ids = listed.mapTo(HashSet()) { it.chapterId }
        if (!ids.containsAll(selected)) select(selected intersect ids)
    }
    BackHandler(enabled = selecting) { select(emptySet()) }
    // The tab bar makes way for what can be done with the chapters selected.
    val tabBar = LocalTabBar.current
    DisposableEffect(selecting) {
        tabBar.hidden = selecting
        onDispose { tabBar.hidden = false }
    }

    /** A long press: selects the chapter, or, while some are selected, every chapter from the last one pressed to it. */
    fun hold(chapter: RecentChapter) {
        val from = anchor?.let { id -> listed.indexOfFirst { it.chapterId == id } }?.takeIf { it >= 0 && selecting }
        val to = listed.indexOfFirst { it.chapterId == chapter.chapterId }
        select(if (from == null || to < 0) selected + chapter.chapterId else selected + listed.subList(minOf(from, to), maxOf(from, to) + 1).map { it.chapterId })
        anchor = chapter.chapterId
    }

    /** A snackbar, with Undo doing [undo] when there is one. */
    fun tell(message: String, undo: (() -> Unit)? = null) {
        scope.launch {
            snackbar.currentSnackbarData?.dismiss()
            val result = snackbar.showSnackbar(message, actionLabel = undo?.let { "Undo" }, duration = SnackbarDuration.Short)
            if (result == SnackbarResult.ActionPerformed) undo?.invoke()
        }
    }
    LaunchedEffect(showNewChapters) {
        if (showNewChapters) {
            tab = 0
            onNewChaptersShown()
        }
    }
    // Looking at the tab clears the number on it; so does leaving, for what came in while it was open.
    DisposableEffect(viewModel) {
        viewModel.markSeen()
        onDispose { viewModel.markSeen() }
    }
    Scaffold(
        topBar = {
            if (selecting) {
                TopAppBar(
                    title = { Text("${number(selected.size)} selected") },
                    navigationIcon = { IconButton(onClick = { select(emptySet()) }) { Icon(Icons.Filled.Close, contentDescription = "Stop selecting") } },
                    actions = { IconButton(onClick = { select(listed.mapTo(HashSet()) { it.chapterId }) }) { Icon(ReaderIcons.SelectAll, contentDescription = "Select all") } },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
                )
            } else {
                TopAppBar(title = { Text("Updates") })
            }
        },
        bottomBar = {
            if (selecting) {
                val chosen = listed.filter { it.chapterId in selected }
                SelectionActions {
                    for (read in listOf(true, false)) {
                        SelectionAction(if (read) ReaderIcons.DoneAll else ReaderIcons.RemoveDone, if (read) "Read" else "Unread") {
                            val changed = chosen.filter { it.read != read }.map { it.chapterId }
                            select(emptySet())
                            viewModel.setRead(changed, read)
                            val word = if (read) "read" else "unread"
                            if (changed.isEmpty()) tell("They're all $word already") else tell("Marked ${countOf(changed.size, "chapter")} $word") { viewModel.setRead(changed, !read) }
                        }
                    }
                    SelectionAction(ReaderIcons.Download, "Download") {
                        val wanted = chosen.filter { !it.saved && !it.waiting }
                        select(emptySet())
                        viewModel.download(wanted)
                        tell(if (wanted.isEmpty()) "They're all downloaded already" else "Downloading ${countOf(wanted.size, "chapter")}")
                    }
                    SelectionAction(Icons.Filled.Delete, "Delete download") {
                        val saved = chosen.filter { it.saved || it.waiting }.map { it.chapterId }
                        select(emptySet())
                        viewModel.deleteDownloads(saved)
                        tell(if (saved.isEmpty()) "None of them was downloaded" else "Deleted ${countOf(saved.size, "download")}")
                    }
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            // Four to a row ("New", not "New chapters"): their labels a size smaller when one wouldn't fit (a large text size).
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                val tabLabel = fittingStyle(listOf("New", "This week", "History", "Stats"), maxWidth / 4 - TAB_PADDING * 2, MaterialTheme.typography.titleSmall)
                PrimaryTabRow(selectedTabIndex = tab) {
                    Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("New", style = tabLabel, maxLines = 1) })
                    Tab(
                        selected = tab == CALENDAR_TAB,
                        onClick = {
                            tab = CALENDAR_TAB
                            select(emptySet())
                        },
                        text = { Text("This week", style = tabLabel, maxLines = 1) },
                    )
                    Tab(
                        selected = tab == HISTORY_TAB,
                        onClick = {
                            tab = HISTORY_TAB
                            select(emptySet())
                        },
                        text = { Text("History", style = tabLabel, maxLines = 1) },
                    )
                    Tab(
                        selected = tab == STATS_TAB,
                        onClick = {
                            tab = STATS_TAB
                            select(emptySet())
                        },
                        text = { Text("Stats", style = tabLabel, maxLines = 1) },
                    )
                }
            }
            if (tab == 0) {
                val chapters = updates
                when {
                    chapters == null -> LoadingState()
                    chapters.isEmpty() -> EmptyState("No new chapters yet", "Chapters that arrive in library novels show here, newest first.")
                    else ->
                        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 16.dp)) {
                            byDay(
                                chapters,
                                { it.arrivedAt },
                                { it.chapterId },
                                // While selecting, a day's heading takes the day, or lets it go when all of it is taken.
                                dayAction =
                                    if (selecting) {
                                        { onDay ->
                                            val ids = onDay.map { it.chapterId }
                                            val all = selected.containsAll(ids)
                                            DaySelection(all) { select(if (all) selected - ids.toSet() else selected + ids) }
                                        }
                                    } else {
                                        null
                                    },
                            ) { chapter ->
                                UpdateRow(
                                    chapter = chapter,
                                    selecting = selecting,
                                    selected = chapter.chapterId in selected,
                                    onOpen = {
                                        when {
                                            !selecting -> onOpenChapter(chapter.novelId, chapter.chapterId)
                                            chapter.chapterId in selected -> select(selected - chapter.chapterId)
                                            else -> {
                                                select(selected + chapter.chapterId)
                                                anchor = chapter.chapterId
                                            }
                                        }
                                    },
                                    onHold = { hold(chapter) },
                                    onDownload = { viewModel.download(listOf(chapter)) },
                                )
                            }
                        }
                }
            } else if (tab == CALENDAR_TAB) {
                ReleaseCalendarPane(calendar, onOpenNovel)
            } else if (tab == STATS_TAB) {
                StatsPane(stats, goal, viewModel::setGoal)
            } else {
                val entries = history
                when {
                    entries == null -> LoadingState()
                    entries.isEmpty() -> EmptyState("Nothing read yet", "Novels you read show here, the last one first.")
                    else ->
                        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 16.dp)) {
                            byDay(entries, { it.lastReadAt }, { it.novelId }) { entry ->
                                HistoryRow(
                                    entry = entry,
                                    onOpen = { entry.chapterId?.let { onOpenChapter(entry.novelId, it) } ?: onOpenNovel(entry.novelId) },
                                    onForget = {
                                        scope.launch {
                                            val at = viewModel.forget(entry.novelId) ?: return@launch
                                            snackbar.currentSnackbarData?.dismiss()
                                            val result = snackbar.showSnackbar("Took ${entry.novelTitle} off the history", actionLabel = "Undo", duration = SnackbarDuration.Short)
                                            if (result == SnackbarResult.ActionPerformed) viewModel.putBack(entry.novelId, at)
                                        }
                                    },
                                )
                            }
                        }
                }
            }
        }
    }
}

/** [items] under a heading for each day ("Today", "Yesterday", "Sep 27"), in the order they come; [dayAction] ends a heading. */
private fun <T> LazyListScope.byDay(
    items: List<T>,
    time: (T) -> Long,
    key: (T) -> Any,
    dayAction: (@Composable (onDay: List<T>) -> Unit)? = null,
    row: @Composable (T) -> Unit,
) {
    val today = LocalDate.now()
    items.groupBy { Instant.ofEpochMilli(time(it)).atZone(ZoneId.systemDefault()).toLocalDate() }.forEach { (day, onDay) ->
        item(key = "day $day") {
            Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    dayLabel(day, today),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f).padding(top = 4.dp, bottom = 4.dp),
                )
                dayAction?.invoke(onDay)
            }
        }
        items(onDay, key = key) { row(it) }
    }
}

/** A day heading's way to take the day into the selection, or let it go when it's all in. */
@Composable
private fun DaySelection(all: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.clip(CircleShape).clickable(onClick = onClick).padding(horizontal = 8.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(ReaderIcons.SelectAll, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
        Text(if (all) "Leave the day" else "Select the day", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
    }
}

private fun dayLabel(day: LocalDate, today: LocalDate): String =
    when (day) {
        today -> "Today"
        today.minusDays(1) -> "Yesterday"
        else -> day.format(DateTimeFormatter.ofPattern(if (day.year == today.year) "MMM d" else "MMM d, yyyy"))
    }

/** "2 hours ago" today, the time of day before then. */
private fun arrived(time: Long): String {
    val now = System.currentTimeMillis()
    val sameDay = Instant.ofEpochMilli(time).atZone(ZoneId.systemDefault()).toLocalDate() == LocalDate.now()
    return if (sameDay) DateUtils.getRelativeTimeSpanString(time, now, DateUtils.MINUTE_IN_MILLIS).toString() else clock(time)
}

private fun clock(time: Long): String = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(time))

@Composable
private fun UpdateRow(chapter: RecentChapter, selecting: Boolean, selected: Boolean, onOpen: () -> Unit, onHold: () -> Unit, onDownload: () -> Unit) {
    val faded = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
    RecentRow(
        coverUrl = chapter.coverUrl,
        title = chapter.novelTitle,
        line = "${chapter.chapterTitle} · ${arrived(chapter.arrivedAt)}",
        faded = chapter.read,
        onClick = onOpen,
        onHold = onHold,
        selected = selected,
    ) {
        when {
            chapter.saved -> Icon(ReaderIcons.DownloadDone, contentDescription = "Downloaded", tint = if (chapter.read) faded else MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(12.dp))
            chapter.waiting -> CircularProgressIndicator(Modifier.padding(14.dp).size(20.dp), strokeWidth = 2.dp)
            // While selecting, a tap anywhere on the row is for the selection.
            !selecting -> IconButton(onClick = onDownload) { Icon(ReaderIcons.Download, contentDescription = "Download") }
        }
    }
}

@Composable
private fun HistoryRow(entry: HistoryEntry, onOpen: () -> Unit, onForget: () -> Unit) {
    val line = listOfNotNull(entry.chapterTitle, clock(entry.lastReadAt)).joinToString(" · ") + if (entry.inLibrary) "" else " (not in the library)"
    RecentRow(coverUrl = entry.coverUrl, title = entry.novelTitle, line = line, faded = false, onClick = onOpen) {
        IconButton(onClick = onForget) { Icon(Icons.Filled.Close, contentDescription = "Take off the history") }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun RecentRow(
    coverUrl: String?,
    title: String,
    line: String,
    faded: Boolean,
    onClick: () -> Unit,
    onHold: (() -> Unit)? = null,
    selected: Boolean = false,
    trailing: @Composable () -> Unit,
) {
    val color = if (faded) MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f) else MaterialTheme.colorScheme.onSurface
    Row(
        Modifier
            .fillMaxWidth()
            .background(if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent)
            .combinedClickable(onClick = onClick, onLongClick = onHold)
            .padding(start = 16.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(40.dp), contentAlignment = Alignment.Center) {
            NovelCover(coverUrl, title, Modifier.width(40.dp))
            if (selected) {
                Box(Modifier.matchParentSize().clip(MaterialTheme.shapes.extraSmall).background(Color.Black.copy(alpha = 0.45f)))
                Box(Modifier.size(26.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary), contentAlignment = Alignment.Center) {
                    Icon(Icons.Filled.Check, contentDescription = "Selected", tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(18.dp))
                }
            }
        }
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = if (faded) FontWeight.Normal else FontWeight.Medium, color = color, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (line.isNotEmpty()) {
                Text(
                    line,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (faded) color else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        trailing()
    }
}

/** The Updates tab's tabs, after New chapters. */
private const val CALENDAR_TAB = 1
private const val HISTORY_TAB = 2
private const val STATS_TAB = 3

/** A tab's own room either side of its label. */
private val TAB_PADDING = 16.dp
