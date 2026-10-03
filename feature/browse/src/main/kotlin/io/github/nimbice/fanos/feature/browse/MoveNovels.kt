package io.github.nimbice.fanos.feature.browse

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.nimbice.fanos.core.common.suspendRunCatching
import io.github.nimbice.fanos.core.data.browserLabel
import io.github.nimbice.fanos.core.data.browserUrl
import io.github.nimbice.fanos.core.data.move.MovePlan
import io.github.nimbice.fanos.core.data.move.NovelMover
import io.github.nimbice.fanos.core.data.repository.CatalogRepository
import io.github.nimbice.fanos.core.data.repository.LibraryRepository
import io.github.nimbice.fanos.core.data.userMessage
import io.github.nimbice.fanos.core.designsystem.component.EmptyState
import io.github.nimbice.fanos.core.designsystem.component.NovelCover
import io.github.nimbice.fanos.core.designsystem.component.countOf
import io.github.nimbice.fanos.core.designsystem.component.number
import io.github.nimbice.fanos.core.model.Novel
import io.github.nimbice.fanos.core.model.NovelSummary
import io.github.nimbice.fanos.core.model.SourceInfo
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.Serializable
import javax.inject.Inject

/** Moving the library's novels from one site to another, many at once. */
@Serializable
data object MoveNovelsRoute

/** A site the library has novels from: one to move them from. [name] is its source's, or its address with no extension for it. */
data class FromSite(val sourceId: String, val name: String, val novels: Int)

/** How finding one novel on the site to move to is going. */
sealed interface NovelMatch {
    /** Searching the site for its title. */
    data object Looking : NovelMatch

    /** Found by its title; reading its chapter list there. */
    data class Checking(val choice: NovelSummary) : NovelMatch

    data class Found(val plan: MovePlan) : NovelMatch

    /** Not among what the search found: by its title, or at a site whose search takes creators, among [author]'s. */
    data class NotFound(val author: String? = null) : NovelMatch

    /** The site's search takes creators, and the novel has no author to look for. */
    data object NoAuthor : NovelMatch

    data class Failed(val error: Throwable) : NovelMatch
}

/** A novel to move, how finding it went, and whether it's to move. */
data class MoveRow(val novel: Novel, val match: NovelMatch?, val selected: Boolean)

data class MoveNovelsUiState(
    val sites: List<FromSite> = emptyList(),
    val from: FromSite? = null,
    /** The sites there are to move to: every source but the one moved from. */
    val targets: List<SourceInfo> = emptyList(),
    val to: SourceInfo? = null,
    val rows: List<MoveRow> = emptyList(),
    /** How many have moved of how many, while they move. */
    val moving: Pair<Int, Int>? = null,
    val loaded: Boolean = false,
) {
    val toMove: List<MoveRow> get() = rows.filter { it.selected && it.match is NovelMatch.Found }
}

@HiltViewModel
class MoveNovelsViewModel @Inject constructor(
    private val library: LibraryRepository,
    private val catalog: CatalogRepository,
    private val mover: NovelMover,
) : ViewModel() {

    private val from = MutableStateFlow<String?>(null)
    private val to = MutableStateFlow<String?>(null)

    /** How finding each novel went, by its id, for the sites picked now. */
    private val matches = MutableStateFlow<Map<Long, NovelMatch>>(emptyMap())

    /** Rows the reader ticked or unticked themselves, by novel id. */
    private val ticks = MutableStateFlow<Map<Long, Boolean>>(emptyMap())
    private val moving = MutableStateFlow<Pair<Int, Int>?>(null)
    private val _messages = Channel<String>(Channel.BUFFERED)
    val messages: Flow<String> = _messages.receiveAsFlow()

    private val sites: Flow<List<FromSite>> =
        combine(library.observeLibrary(), catalog.allSources) { novels, sources ->
            val names = sources.associate { it.id to it.name }
            // Sites without an extension first: nothing reads them, so they're the ones most in need of a move.
            novels.map { it.novel }.filterNot { it.isBook }.groupBy { it.sourceId }.map { (id, inSite) -> FromSite(id, names[id] ?: inSite.first().site, inSite.size) }
                .sortedWith(compareBy<FromSite> { it.sourceId in names }.thenBy { it.name.lowercase() })
        }

    val state: StateFlow<MoveNovelsUiState> =
        combine(
            combine(library.observeLibrary(), sites, catalog.allSources, ::Triple),
            from,
            to,
            combine(matches, ticks, ::Pair),
            moving,
        ) { (novels, sites, sources), fromId, toId, (matches, ticks), moving ->
            val chosen = sites.firstOrNull { it.sourceId == fromId }
            MoveNovelsUiState(
                sites = sites,
                from = chosen,
                targets = sources.filter { it.id != fromId },
                to = sources.firstOrNull { it.id == toId && it.id != fromId },
                rows =
                    novels.map { it.novel }.filter { it.sourceId == fromId }.map { novel ->
                        val match = matches[novel.id]
                        MoveRow(novel, match, selected = ticks[novel.id] ?: (match is NovelMatch.Found && isSure(novel, match.plan)))
                    },
                moving = moving,
                loaded = true,
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), MoveNovelsUiState())

    private var matching: Job? = null
    private val novelJobs = mutableMapOf<Long, Job>()

    // A few novels at a time: each is a search and a chapter list on the same site.
    private val permits = Semaphore(PARALLEL_NOVELS)

    init {
        viewModelScope.launch { if (from.value == null) from.value = sites.first().firstOrNull()?.sourceId }
    }

    fun setFrom(sourceId: String) {
        if (moving.value != null || from.value == sourceId) return
        from.value = sourceId
        if (to.value == sourceId) to.value = null
        matchAll()
    }

    fun setTo(sourceId: String) {
        if (moving.value != null || to.value == sourceId) return
        to.value = sourceId
        matchAll()
    }

    fun setSelected(novelId: Long, selected: Boolean) = ticks.update { it + (novelId to selected) }

    /** Looks for the novel again, after a search or chapter list failed. */
    fun retry(novelId: Long) {
        val toId = to.value ?: return
        viewModelScope.launch {
            val novel = library.observeLibrary().first().map { it.novel }.firstOrNull { it.id == novelId } ?: return@launch
            find(novel, toId)
        }
    }

    /** Looks for every novel of the site moved from on the site moved to, anew. */
    private fun matchAll() {
        matching?.cancel()
        novelJobs.values.forEach { it.cancel() }
        novelJobs.clear()
        matches.value = emptyMap()
        ticks.value = emptyMap()
        val fromId = from.value ?: return
        val toId = to.value ?: return
        matching =
            viewModelScope.launch {
                library.observeLibrary().first().map { it.novel }.filter { it.sourceId == fromId }.forEach { find(it, toId) }
            }
    }

    private fun find(novel: Novel, toId: String) {
        novelJobs.remove(novel.id)?.cancel()
        set(novel.id, NovelMatch.Looking)
        novelJobs[novel.id] =
            viewModelScope.launch {
                permits.withPermit {
                    // A site whose search takes creators is asked for the novel's author, and the novel looked for among theirs.
                    val creators = catalog.sourceInfo(toId)?.searchesCreators == true
                    val words = searchWords(novel, creators)
                    if (words == null) {
                        set(novel.id, NovelMatch.NoAuthor)
                        return@withPermit
                    }
                    val found = suspendRunCatching { catalog.search(toId, words, 1).novels }.getOrElse { error ->
                        set(novel.id, NovelMatch.Failed(error))
                        return@withPermit
                    }
                    val choice = found.firstOrNull { sameTitle(it.title, novel.siteTitle) || sameTitle(it.title, novel.title) }
                    if (choice == null) {
                        set(novel.id, NovelMatch.NotFound(author = words.takeIf { creators }))
                        return@withPermit
                    }
                    set(novel.id, NovelMatch.Checking(choice))
                    set(novel.id, suspendRunCatching { mover.plan(novel.id, choice) }.fold({ NovelMatch.Found(it) }, { NovelMatch.Failed(it) }))
                }
            }
    }

    private fun set(novelId: Long, match: NovelMatch) {
        matches.update { it + (novelId to match) }
        ticks.update { it - novelId }
    }

    /** Moves the ticked novels, one after another, each as a move of one would. */
    fun moveSelected() {
        val rows = state.value.toMove
        val site = state.value.to ?: return
        if (rows.isEmpty() || moving.value != null) return
        viewModelScope.launch {
            moving.value = 0 to rows.size
            val failed = ArrayList<String>()
            rows.forEachIndexed { done, row ->
                val plan = (row.match as NovelMatch.Found).plan
                suspendRunCatching { mover.move(plan, keep = false) }.onFailure { failed += row.novel.title }
                moving.value = done + 1 to rows.size
            }
            moving.value = null
            val moved = rows.size - failed.size
            _messages.send(
                when {
                    failed.isEmpty() -> "Moved ${countOf(moved, "novel")} to ${site.name}"
                    else -> "Moved ${countOf(moved, "novel")} to ${site.name}; ${failed.size} couldn't be: ${failed.joinToString(", ")}"
                },
            )
        }
    }

    private companion object {
        const val PARALLEL_NOVELS = 2
    }
}

/**
 * What to search the site moved to for: the novel's title, or at a site whose search takes creators, its author (null
 * when it has none).
 */
internal fun searchWords(novel: Novel, searchesCreators: Boolean): String? =
    if (searchesCreators) novel.author?.trim()?.takeIf { it.isNotEmpty() } else novel.siteTitle

/** Two titles are the same when they differ only in capitals, spacing and punctuation. */
internal fun sameTitle(a: String, b: String): Boolean = normalTitle(a).let { it.isNotEmpty() && it == normalTitle(b) }

private fun normalTitle(title: String): String =
    title.lowercase().map { if (it.isLetterOrDigit()) it else ' ' }.joinToString("").split(' ').filter { it.isNotEmpty() }.joinToString(" ")

/** A match to tick without asking: the same title, and every chapter read there too. */
private fun isSure(novel: Novel, plan: MovePlan) =
    (sameTitle(plan.target.title, novel.siteTitle) || sameTitle(plan.target.title, novel.title)) && plan.matched == plan.read

fun NavGraphBuilder.moveNovelsScreen(onBack: () -> Unit, onSearch: (novelId: Long, sourceId: String) -> Unit, onOpenInBrowser: (url: String) -> Unit) {
    composable<MoveNovelsRoute> { MoveNovelsScreen(onBack = onBack, onSearch = onSearch, onOpenInBrowser = onOpenInBrowser) }
}

/**
 * Picks the site to move novels from (sites without an extension included) and the one to move them to; each novel is
 * looked for there by its title (among its author's, at a site whose search takes creators) and its chapters checked
 * before anything moves. Each moves as it does on its own.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MoveNovelsScreen(
    onBack: () -> Unit,
    onSearch: (novelId: Long, sourceId: String) -> Unit,
    onOpenInBrowser: (String) -> Unit,
    viewModel: MoveNovelsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(viewModel) { viewModel.messages.collect { snackbar.showSnackbar(it) } }
    val to = state.to
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Move novels") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            if (to != null && state.rows.isNotEmpty()) MoveBar(state.toMove.size, state.moving, onMove = viewModel::moveSelected)
        },
    ) { padding ->
        if (state.loaded && state.sites.isEmpty()) {
            EmptyState("Nothing to move", "Novels added to the library can be moved to another site from here.", Modifier.padding(padding))
            return@Scaffold
        }
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(bottom = 16.dp)) {
            item { Label("From") }
            item {
                FlowRow(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    state.sites.forEach { site ->
                        FilterChip(
                            selected = site.sourceId == state.from?.sourceId,
                            onClick = { viewModel.setFrom(site.sourceId) },
                            label = { Text("${site.name} · ${number(site.novels)}") },
                            enabled = state.moving == null,
                        )
                    }
                }
            }
            item { Label("To") }
            item { TargetPicker(state.targets, to, enabled = state.moving == null, onPick = viewModel::setTo) }
            item {
                Text(
                    when {
                        to == null -> "Pick the site to move them to."
                        state.rows.isEmpty() -> "There are none left to move from there."
                        to.searchesCreators -> "Fanos looked for each among its author's novels on ${to.name} and read its chapter list there:"
                        else -> "Fanos looked for each by its title on ${to.name} and read its chapter list there:"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 8.dp),
                )
            }
            items(state.rows, key = { it.novel.id }) { row ->
                MoveRowItem(
                    row = row,
                    siteName = to?.name,
                    enabled = state.moving == null,
                    onSelect = { viewModel.setSelected(row.novel.id, it) },
                    onSearch = { to?.let { onSearch(row.novel.id, it.id) } },
                    onRetry = { viewModel.retry(row.novel.id) },
                    onOpenInBrowser = onOpenInBrowser,
                    modifier = Modifier.animateItem(),
                )
            }
            item {
                Text(
                    "Each moves as it does on its own: its place in the library, sections, settings and read chapters go with it, and the " +
                        "old copy leaves the library. A match you doubt can be unticked, or searched for yourself.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                )
            }
        }
    }
}

@Composable
private fun Label(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 6.dp),
    )
}

/** The site to move to, from those there are. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TargetPicker(targets: List<SourceInfo>, chosen: SourceInfo?, enabled: Boolean, onPick: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = open, onExpandedChange = { if (enabled) open = it }, modifier = Modifier.padding(horizontal = 16.dp)) {
        OutlinedTextField(
            value = chosen?.name.orEmpty(),
            onValueChange = {},
            readOnly = true,
            enabled = enabled,
            placeholder = { Text(if (targets.isEmpty()) "No other sites: install their extensions in Browse" else "Choose a site") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = open) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable, enabled),
        )
        ExposedDropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            targets.forEach { source ->
                DropdownMenuItem(
                    text = { Text(source.name) },
                    onClick = {
                        open = false
                        onPick(source.id)
                    },
                )
            }
        }
    }
}

/** A novel, and the same novel on the site moved to with how its chapters compare; or why there isn't one. */
@Composable
private fun MoveRowItem(
    row: MoveRow,
    siteName: String?,
    enabled: Boolean,
    onSelect: (Boolean) -> Unit,
    onSearch: () -> Unit,
    onRetry: () -> Unit,
    onOpenInBrowser: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    Surface(color = colors.surfaceContainer, shape = RoundedCornerShape(12.dp), modifier = modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 2.dp)) {
        Row(Modifier.padding(start = 12.dp, top = 10.dp, bottom = 10.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            NovelCover(row.novel.coverUrl, title = "", modifier = Modifier.width(COVER_WIDTH))
            Spacer(Modifier.width(10.dp))
            val match = row.match
            Column(Modifier.weight(1f)) {
                Text(row.novel.title, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                val (line, problem) =
                    when (match) {
                        null -> "" to false
                        NovelMatch.Looking -> "Looking on $siteName…" to false
                        is NovelMatch.Checking -> "Reading the chapter list there…" to false
                        is NovelMatch.Found -> readLine(match.plan)
                        is NovelMatch.NotFound -> (match.author?.let { "None of $it's on $siteName has this title" } ?: "Not found on $siteName") to true
                        NovelMatch.NoAuthor -> "No author to look for on $siteName" to true
                        is NovelMatch.Failed -> userMessage(match.error) to true
                    }
                if (line.isNotEmpty()) {
                    Text(line, style = MaterialTheme.typography.bodySmall, color = if (problem) colors.error else colors.onSurfaceVariant, maxLines = 3, overflow = TextOverflow.Ellipsis)
                }
            }
            when (match) {
                is NovelMatch.Found -> {
                    Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = "to", tint = colors.primary, modifier = Modifier.padding(horizontal = 6.dp).size(20.dp))
                    NovelCover(match.plan.target.coverUrl, title = "", modifier = Modifier.width(COVER_WIDTH))
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(match.plan.target.title, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(countOf(match.plan.targetChapters, "chapter"), style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                    }
                    Checkbox(checked = row.selected, onCheckedChange = onSelect, enabled = enabled)
                }
                NovelMatch.Looking, is NovelMatch.Checking -> CircularProgressIndicator(Modifier.padding(horizontal = 14.dp).size(20.dp), strokeWidth = 2.dp)
                is NovelMatch.NotFound, NovelMatch.NoAuthor -> {
                    TextButton(onClick = onSearch, enabled = enabled) { Text("Search") }
                    Checkbox(checked = false, onCheckedChange = null, enabled = false)
                }
                is NovelMatch.Failed -> {
                    val url = browserUrl(match.error)
                    if (url != null) TextButton(onClick = { onOpenInBrowser(url) }) { Text(browserLabel(match.error)) }
                    TextButton(onClick = onRetry, enabled = enabled) { Text("Try again") }
                }
                null -> Unit
            }
        }
    }
}

/** How the chapters read compare with the other site's: "7 chapters read · all 7 there". */
private fun readLine(plan: MovePlan): Pair<String, Boolean> =
    when {
        plan.read == 0 -> "No chapters read yet" to false
        plan.matched == plan.read -> (if (plan.read == 1) "1 chapter read · there too" else "${countOf(plan.read, "chapter")} read · all ${number(plan.read)} there") to false
        else -> "${countOf(plan.read, "chapter")} read · only ${number(plan.matched)} there" to true
    }

/** The go-ahead, with how many are ticked, and how the move is going once it's under way. */
@Composable
private fun MoveBar(count: Int, moving: Pair<Int, Int>?, onMove: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surface, tonalElevation = 3.dp) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (moving != null) {
                LinearProgressIndicator(progress = { moving.first.toFloat() / moving.second }, modifier = Modifier.fillMaxWidth())
            }
            Button(onClick = onMove, enabled = count > 0 && moving == null, modifier = Modifier.fillMaxWidth()) {
                Text(
                    when {
                        moving != null -> "Moving ${minOf(moving.first + 1, moving.second)} of ${moving.second}…"
                        count == 0 -> "Tick the novels to move"
                        else -> "Move ${countOf(count, "novel")}"
                    },
                )
            }
        }
    }
}

private val COVER_WIDTH = 36.dp
