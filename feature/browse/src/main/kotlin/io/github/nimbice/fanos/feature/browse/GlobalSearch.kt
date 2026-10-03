package io.github.nimbice.fanos.feature.browse

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.nimbice.fanos.core.data.browserLabel
import io.github.nimbice.fanos.core.data.browserUrl
import io.github.nimbice.fanos.core.data.repository.CatalogRepository
import io.github.nimbice.fanos.core.data.repository.NovelRepository
import io.github.nimbice.fanos.core.data.repository.SettingsRepository
import io.github.nimbice.fanos.core.data.userMessage
import io.github.nimbice.fanos.core.designsystem.component.EmptyState
import io.github.nimbice.fanos.core.designsystem.component.NovelCover
import io.github.nimbice.fanos.core.designsystem.component.SearchField
import io.github.nimbice.fanos.core.designsystem.component.countOf
import io.github.nimbice.fanos.core.designsystem.component.scaffoldContent
import io.github.nimbice.fanos.core.model.NovelSummary
import io.github.nimbice.fanos.core.model.SearchHistory
import io.github.nimbice.fanos.core.model.SourceInfo
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

/** One source's part of a search across all sources. */
sealed interface SourceSearch {
    data object Searching : SourceSearch

    data class Found(val novels: List<NovelSummary>) : SourceSearch

    data object NoResults : SourceSearch

    data class Failed(val error: Throwable) : SourceSearch
}

data class SourceResults(
    val source: SourceInfo,
    val search: SourceSearch,
    /** The novel's author, searched for at this site in place of the words in the box: its search takes creators' names. */
    val author: String? = null,
)

data class GlobalSearchUiState(
    val query: String,
    val submitted: String = "",
    /** In source-name order. */
    val sources: List<SourceResults> = emptyList(),
)

@HiltViewModel
class GlobalSearchViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val catalog: CatalogRepository,
    private val novels: NovelRepository,
    private val settings: SettingsRepository,
) : ViewModel() {

    private val searching = SourcesSearch(viewModelScope, catalog, savedStateHandle.toRoute<GlobalSearchRoute>().query)
    val state: StateFlow<GlobalSearchUiState> = searching.state
    val recent = settings.recentSearches(SearchHistory.Novels, viewModelScope)

    init {
        search()
    }

    fun onQueryChange(query: String) = searching.onQueryChange(query)

    /** The reader's own search, from the box: kept among the recent searches. */
    fun submit() {
        recent.remember(state.value.query)
        search()
    }

    /** Searches every ticked source for the query, each showing its results as they come. */
    fun search() =
        searching.search(
            sources = {
                val excluded = settings.appSettings.first().searchExcludedSources
                catalog.allSources.first().filter { it.id !in excluded }
            },
        )

    /** Searches one source again: after an error, or after its bot check was passed in the browser. */
    fun retry(sourceId: String) = searching.retry(sourceId)

    /** Stores the novel (if new) and returns its id for navigation. */
    suspend fun open(summary: NovelSummary): Long = novels.open(summary)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GlobalSearchScreen(
    onBack: () -> Unit,
    onOpenNovel: (Long) -> Unit,
    onOpenInBrowser: (String) -> Unit,
    onOpenSource: (sourceId: String, query: String) -> Unit,
    viewModel: GlobalSearchViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val recent by viewModel.recent.items.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (state.sources.isEmpty()) "Search" else "Search ${countOf(state.sources.size, "source")}") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().scaffoldContent(padding)) {
            SearchField(
                value = state.query,
                onValueChange = viewModel::onQueryChange,
                onSearch = viewModel::submit,
                placeholder = "Title or author",
                recent = recent,
                onForget = viewModel.recent::forget,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            )
            if (state.submitted.isNotEmpty() && state.sources.isEmpty()) {
                EmptyState("No sources to search", "Tick the sources to search in Browse.")
            }
            val answered = state.sources.count { it.search !is SourceSearch.Searching }
            if (answered < state.sources.size) {
                LinearProgressIndicator(
                    progress = { answered.toFloat() / state.sources.size },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                )
            }
            // Sources with results first, then those still searching, then the rest; each group
            // in source-name order.
            val ordered = state.sources.sortedBy { rank(it.search) }
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 16.dp)) {
                items(ordered, key = { it.source.id }) { result ->
                    SourceSection(
                        result = result,
                        onOpen = { novel -> scope.launch { onOpenNovel(viewModel.open(novel)) } },
                        onMore = { onOpenSource(result.source.id, state.submitted) },
                        onRetry = { viewModel.retry(result.source.id) },
                        onOpenInBrowser = onOpenInBrowser,
                        modifier = Modifier.animateItem(),
                    )
                }
            }
        }
    }
}

internal fun rank(search: SourceSearch): Int =
    when (search) {
        is SourceSearch.Found -> 0
        SourceSearch.Searching -> 1
        is SourceSearch.Failed -> 2
        SourceSearch.NoResults -> 3
    }

@Composable
internal fun SourceSection(
    result: SourceResults,
    onOpen: (NovelSummary) -> Unit,
    onMore: (() -> Unit)?,
    onRetry: () -> Unit,
    onOpenInBrowser: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxWidth().padding(top = 8.dp)) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 40.dp).padding(start = 16.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(result.source.name, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            when (result.search) {
                SourceSearch.Searching -> CircularProgressIndicator(Modifier.padding(end = 12.dp).size(18.dp), strokeWidth = 2.dp)
                // The source's own search, with its further pages.
                is SourceSearch.Found -> if (onMore != null) TextButton(onClick = onMore) { Text("More") }
                else -> Unit
            }
        }
        result.author?.let { author ->
            Text(
                "Searched for its author, $author",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 4.dp),
            )
        }
        when (val search = result.search) {
            SourceSearch.Searching -> Unit
            is SourceSearch.Found ->
                LazyRow(contentPadding = PaddingValues(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    items(search.novels, key = { it.url }) { novel -> ResultCard(novel) { onOpen(novel) } }
                }
            SourceSearch.NoResults ->
                Text(
                    // Words that were a title, most likely, sent to a site that only knows creators.
                    if (result.source.searchesCreators && result.author == null) {
                        "No results: ${result.source.name}'s search finds creators by name, not titles."
                    } else {
                        "No results"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            is SourceSearch.Failed ->
                Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        userMessage(search.error),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.weight(1f),
                    )
                    browserUrl(search.error)?.let { url -> TextButton(onClick = { onOpenInBrowser(url) }) { Text(browserLabel(search.error)) } }
                    TextButton(onClick = onRetry) { Text("Try again") }
                }
        }
    }
}

@Composable
internal fun ResultCard(novel: NovelSummary, onClick: () -> Unit) {
    Column(Modifier.width(112.dp).clickable(onClick = onClick).padding(4.dp)) {
        NovelCover(novel.coverUrl, novel.title, Modifier.fillMaxWidth())
        Text(
            novel.title,
            style = MaterialTheme.typography.labelMedium,
            minLines = 2,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}
