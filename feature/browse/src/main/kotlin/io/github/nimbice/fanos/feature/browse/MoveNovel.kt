package io.github.nimbice.fanos.feature.browse

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import androidx.navigation.toRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.nimbice.fanos.core.common.suspendRunCatching
import io.github.nimbice.fanos.core.data.move.MovePlan
import io.github.nimbice.fanos.core.data.move.NovelMover
import io.github.nimbice.fanos.core.data.repository.CatalogRepository
import io.github.nimbice.fanos.core.data.repository.NovelRepository
import io.github.nimbice.fanos.core.data.repository.SettingsRepository
import io.github.nimbice.fanos.core.data.userMessage
import io.github.nimbice.fanos.core.designsystem.component.EmptyState
import io.github.nimbice.fanos.core.designsystem.component.NovelCover
import io.github.nimbice.fanos.core.designsystem.component.SearchField
import io.github.nimbice.fanos.core.designsystem.component.countOf
import io.github.nimbice.fanos.core.designsystem.component.scaffoldContent
import io.github.nimbice.fanos.core.model.Novel
import io.github.nimbice.fanos.core.model.NovelSummary
import io.github.nimbice.fanos.core.model.SearchHistory
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import javax.inject.Inject

/**
 * Finding a novel on another site and moving it there: on the one site [sourceId] when there's one, as when Move novels
 * didn't find the novel there by its title, else on every site but its own.
 */
@Serializable
data class MoveNovelRoute(val novelId: Long, val sourceId: String? = null)

/** The novel [novelId] on its new site, [site]; [onlySite] when that was the one site searched. */
data class Moved(val novelId: Long, val site: String, val onlySite: Boolean = false)

/** Where a move is, once the reader has picked the novel on another site. */
sealed interface MoveStep {
    val choice: NovelSummary

    /** Reading the other site's chapter list, to match the chapters read. */
    data class Checking(override val choice: NovelSummary) : MoveStep

    data class Ready(override val choice: NovelSummary, val plan: MovePlan) : MoveStep

    data class Moving(override val choice: NovelSummary, val plan: MovePlan) : MoveStep

    data class Failed(override val choice: NovelSummary, val error: Throwable) : MoveStep
}

data class MoveNovelUiState(
    val novel: Novel? = null,
    /** The name of the novel's site now: its source's, or its address's for a site with no extension. */
    val siteName: String? = null,
    val step: MoveStep? = null,
)

@HiltViewModel
class MoveNovelViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val catalog: CatalogRepository,
    private val novels: NovelRepository,
    private val mover: NovelMover,
    settings: SettingsRepository,
) : ViewModel() {

    private val route = savedStateHandle.toRoute<MoveNovelRoute>()
    private val novelId = route.novelId
    private val searching = SourcesSearch(viewModelScope, catalog, query = "")
    val search: StateFlow<GlobalSearchUiState> = searching.state
    val recent = settings.recentSearches(SearchHistory.Novels, viewModelScope)

    private val _state = MutableStateFlow(MoveNovelUiState())
    val state: StateFlow<MoveNovelUiState> = _state.asStateFlow()

    private val _moved = Channel<Moved>(Channel.BUFFERED)

    /** The novel on its new site, once it has moved there. */
    val moved: Flow<Moved> = _moved.receiveAsFlow()

    init {
        viewModelScope.launch {
            val novel = novels.observeNovel(novelId).first() ?: return@launch
            _state.update { it.copy(novel = novel, siteName = catalog.sourceInfo(novel.sourceId)?.name ?: novel.site) }
            // The site's own title: one the reader gave it may be theirs alone.
            searching.onQueryChange(novel.siteTitle)
            search()
        }
    }

    fun onQueryChange(query: String) = searching.onQueryChange(query)

    /** The reader's own search, from the box: kept among the recent searches (the first, by the novel's title, isn't). */
    fun submit() {
        recent.remember(search.value.query)
        search()
    }

    /**
     * Searches every site but the novel's own, or only the one the route names. A site whose search takes creators is
     * asked for the novel's author while the box still holds the title; words typed there go to every site as they are.
     */
    fun search() {
        val novel = _state.value.novel ?: return
        val author = authorToSearch(novel, typed = searching.state.value.query)
        searching.search(
            sources = { catalog.allSources.first().filter { if (route.sourceId != null) it.id == route.sourceId else it.id != novel.sourceId } },
            authorAt = { source -> author.takeIf { source.searchesCreators } },
        )
    }

    fun retry(sourceId: String) = searching.retry(sourceId)

    /** Reads [choice]'s chapter list and works out what moving there would do. */
    fun choose(choice: NovelSummary) {
        _state.update { it.copy(step = MoveStep.Checking(choice)) }
        viewModelScope.launch {
            val step = suspendRunCatching { mover.plan(novelId, choice) }.fold({ MoveStep.Ready(choice, it) }, { MoveStep.Failed(choice, it) })
            _state.update { state -> if (state.step?.choice == choice) state.copy(step = step) else state }
        }
    }

    fun dismiss() = _state.update { state -> if (state.step is MoveStep.Moving) state else state.copy(step = null) }

    fun move(keep: Boolean) {
        val ready = _state.value.step as? MoveStep.Ready ?: return
        _state.update { it.copy(step = MoveStep.Moving(ready.choice, ready.plan)) }
        viewModelScope.launch {
            suspendRunCatching { mover.move(ready.plan, keep) }.fold(
                onSuccess = { _moved.send(Moved(it, ready.plan.site, onlySite = route.sourceId != null)) },
                onFailure = { error -> _state.update { it.copy(step = MoveStep.Failed(ready.choice, error)) } },
            )
        }
    }
}

/** The novel's author, for sites whose search takes creators, while the words [typed] are still its title. */
internal fun authorToSearch(novel: Novel, typed: String): String? =
    novel.author?.trim()?.takeIf { it.isNotEmpty() && typed.trim() == novel.siteTitle.trim() }

fun NavGraphBuilder.moveNovelScreen(onBack: () -> Unit, onMoved: (Moved) -> Unit, onOpenInBrowser: (url: String) -> Unit) {
    composable<MoveNovelRoute> {
        MoveNovelScreen(onBack = onBack, onMoved = onMoved, onOpenInBrowser = onOpenInBrowser)
    }
}

/**
 * Every other site searched for the novel's title, laid out as Browse's search of all sources; tapping the same novel
 * there says what moving it would do, and moves it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MoveNovelScreen(
    onBack: () -> Unit,
    onMoved: (Moved) -> Unit,
    onOpenInBrowser: (String) -> Unit,
    viewModel: MoveNovelViewModel = hiltViewModel(),
) {
    val search by viewModel.search.collectAsStateWithLifecycle()
    val state by viewModel.state.collectAsStateWithLifecycle()
    val recent by viewModel.recent.items.collectAsStateWithLifecycle()
    LaunchedEffect(viewModel) { viewModel.moved.collect { onMoved(it) } }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Move to another site") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().scaffoldContent(padding)) {
            SearchField(
                value = search.query,
                onValueChange = viewModel::onQueryChange,
                onSearch = viewModel::submit,
                placeholder = "Title",
                recent = recent,
                onForget = viewModel.recent::forget,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            )
            if (search.submitted.isNotEmpty() && search.sources.isEmpty()) {
                EmptyState("No other sites to search", "Install the extensions of other sites in Browse › Extensions.")
            }
            val answered = search.sources.count { it.search !is SourceSearch.Searching }
            if (answered < search.sources.size) {
                LinearProgressIndicator(progress = { answered.toFloat() / search.sources.size }, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp))
            }
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 16.dp)) {
                items(search.sources.sortedBy { rank(it.search) }, key = { it.source.id }) { result ->
                    SourceSection(
                        result = result,
                        onOpen = viewModel::choose,
                        onMore = null,
                        onRetry = { viewModel.retry(result.source.id) },
                        onOpenInBrowser = onOpenInBrowser,
                        modifier = Modifier.animateItem(),
                    )
                }
            }
        }
    }
    val novel = state.novel
    val step = state.step
    if (novel != null && step != null) {
        val site = search.sources.firstOrNull { it.source.id == step.choice.sourceId }?.source?.name ?: step.choice.sourceId
        MoveDialog(
            novel = novel,
            siteName = state.siteName ?: novel.site,
            targetSite = site,
            step = step,
            onMove = viewModel::move,
            onRetry = { viewModel.choose(step.choice) },
            onDismiss = viewModel::dismiss,
        )
    }
}

/** What moving to [targetSite] would do, once its chapter list has been read, and the go-ahead. */
@Composable
private fun MoveDialog(
    novel: Novel,
    siteName: String,
    targetSite: String,
    step: MoveStep,
    onMove: (keep: Boolean) -> Unit,
    onRetry: () -> Unit,
    onDismiss: () -> Unit,
) {
    var keep by rememberSaveable { mutableStateOf(false) }
    val plan = (step as? MoveStep.Ready)?.plan ?: (step as? MoveStep.Moving)?.plan
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Move to $targetSite?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SiteCover(novel.coverUrl, siteName, plan?.let { countOf(it.chapters, "chapter") } ?: "…", Modifier.weight(1f))
                    Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    SiteCover(step.choice.coverUrl, targetSite, plan?.let { countOf(it.targetChapters, "chapter") } ?: "…", Modifier.weight(1f))
                }
                when (step) {
                    is MoveStep.Checking -> {
                        Text("Reading $targetSite's chapter list…", style = MaterialTheme.typography.bodyMedium)
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                    }
                    is MoveStep.Failed -> Text(userMessage(step.error), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                    is MoveStep.Ready, is MoveStep.Moving -> {
                        val ready = checkNotNull(plan)
                        Text(matchSummary(ready), style = MaterialTheme.typography.bodyMedium)
                        Text(
                            if (novel.inLibrary) {
                                "It keeps its place in the library, its sections, your reading settings and any title or cover you gave it."
                            } else {
                                "It keeps your reading settings and any title or cover you gave it."
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Column {
                            Row(
                                Modifier.fillMaxWidth().clickable(enabled = step is MoveStep.Ready) { keep = !keep },
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Checkbox(checked = keep, onCheckedChange = { keep = it }, enabled = step is MoveStep.Ready)
                                Text("Keep the $siteName copy too", style = MaterialTheme.typography.bodyMedium)
                            }
                            if (!keep) {
                                Text(
                                    if (novel.inLibrary) {
                                        "Otherwise that copy leaves the library, and its saved chapters are deleted."
                                    } else {
                                        "Otherwise that copy's saved chapters are deleted."
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(start = 12.dp),
                                )
                            }
                        }
                        if (step is MoveStep.Moving) LinearProgressIndicator(Modifier.fillMaxWidth())
                    }
                }
            }
        },
        confirmButton = {
            if (step is MoveStep.Failed) {
                TextButton(onClick = onRetry) { Text("Try again") }
            } else {
                TextButton(onClick = { onMove(keep) }, enabled = step is MoveStep.Ready) { Text("Move") }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = step !is MoveStep.Moving) { Text("Cancel") } },
    )
}

/** How many of the chapters read the other site has, and where reading goes on there. */
@Composable
private fun matchSummary(plan: MovePlan) =
    buildAnnotatedString {
        val goOn = plan.continueFrom?.let { " You go on from $it." }.orEmpty()
        when {
            plan.read == 0 -> append("You haven't read any of it yet.$goOn")
            plan.matched == plan.read -> {
                withStyle(SpanStyle(color = MaterialTheme.colorScheme.onSurface)) {
                    append(
                        when (plan.read) {
                            1 -> "The chapter you've read is there too"
                            2 -> "Both chapters you've read are there too"
                            else -> "All ${plan.read} chapters you've read are there too"
                        },
                    )
                }
                append(", matched by number.$goOn")
            }
            else -> {
                withStyle(SpanStyle(color = MaterialTheme.colorScheme.error)) {
                    append("Only ${plan.matched} of the ${plan.read} chapters you've read have a match there")
                }
                append(", as ${plan.site} numbers some differently; the others will show as unread.$goOn")
            }
        }
    }

/** A novel's cover, small, above its site's name and chapter count. */
@Composable
private fun SiteCover(coverUrl: String?, site: String, chapters: String, modifier: Modifier = Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        NovelCover(coverUrl, title = "", modifier = Modifier.width(48.dp))
        Text(site, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(chapters, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
