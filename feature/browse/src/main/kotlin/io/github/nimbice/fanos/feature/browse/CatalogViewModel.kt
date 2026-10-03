package io.github.nimbice.fanos.feature.browse

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.nimbice.fanos.core.common.suspendRunCatching
import io.github.nimbice.fanos.core.data.repository.CatalogRepository
import io.github.nimbice.fanos.core.data.repository.NovelRepository
import io.github.nimbice.fanos.core.data.repository.SettingsRepository
import io.github.nimbice.fanos.core.model.NovelSummary
import io.github.nimbice.fanos.core.model.SearchHistory
import io.github.nimbice.fanos.core.model.SourceInfo
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

enum class CatalogMode { Popular, Latest, Search }

data class CatalogUiState(
    val mode: CatalogMode,
    val query: String = "",
    val submittedQuery: String = "",
    val items: List<NovelSummary> = emptyList(),
    val nextPage: Int = 1,
    val loading: Boolean = false,
    val endReached: Boolean = false,
    val error: Throwable? = null,
)

@HiltViewModel
class CatalogViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val catalog: CatalogRepository,
    private val novels: NovelRepository,
    settings: SettingsRepository,
) : ViewModel() {

    val recent = settings.recentSearches(SearchHistory.Novels, viewModelScope)

    private val route = savedStateHandle.toRoute<CatalogRoute>()
    private val sourceId = route.sourceId

    /** The source, known once its extension has loaded; null while no extension reads from it. */
    val source: StateFlow<SourceInfo?> = catalog.source(sourceId).stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /** The source's lists, besides search. */
    val modes: StateFlow<List<CatalogMode>> = source.map { modesOf(it) }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    // Opened from a search across all sources, it shows that search; otherwise its first list.
    private val _state =
        MutableStateFlow(
            route.query?.let { CatalogUiState(mode = CatalogMode.Search, query = it, submittedQuery = it) }
                ?: CatalogUiState(mode = CatalogMode.Search),
        )
    val state: StateFlow<CatalogUiState> = _state.asStateFlow()

    private var loading: Job? = null

    init {
        viewModelScope.launch {
            // Its first list is known once its extension has loaded.
            if (route.query == null) {
                modesOf(catalog.sourceInfo(sourceId)).firstOrNull()?.let { mode -> _state.update { it.copy(mode = mode) } }
            }
            loadMore()
        }
    }

    private fun modesOf(source: SourceInfo?): List<CatalogMode> =
        buildList {
            if (source?.supportsPopular == true) add(CatalogMode.Popular)
            if (source?.supportsLatest == true) add(CatalogMode.Latest)
        }

    fun onQueryChange(query: String) = _state.update { it.copy(query = query) }

    /** The reader's own search, from the box: kept among the recent searches. */
    fun submit() {
        recent.remember(_state.value.query)
        search()
    }

    fun search() {
        val query = _state.value.query.trim()
        if (query.isEmpty()) return
        reset(CatalogMode.Search, query)
        loadMore()
    }

    fun show(mode: CatalogMode) {
        reset(mode, "")
        loadMore()
    }

    /** Loads the next page, unless one is loading, the list has ended, or there is nothing to search for. */
    fun loadMore() {
        val current = _state.value
        if (current.loading || current.endReached || current.error != null) return
        if (current.mode == CatalogMode.Search && current.submittedQuery.isEmpty()) return
        loading =
            viewModelScope.launch {
                _state.update { it.copy(loading = true) }
                val page = current.nextPage
                suspendRunCatching {
                    when (current.mode) {
                        CatalogMode.Popular -> catalog.popular(sourceId, page)
                        CatalogMode.Latest -> catalog.latest(sourceId, page)
                        CatalogMode.Search -> catalog.search(sourceId, current.submittedQuery, page)
                    }
                }.onSuccess { result ->
                    _state.update { state ->
                        val known = state.items.mapTo(HashSet()) { it.url }
                        val fresh = result.novels.filter { known.add(it.url) }
                        state.copy(
                            items = state.items + fresh,
                            nextPage = page + 1,
                            endReached = !result.hasNextPage || fresh.isEmpty(),
                            loading = false,
                        )
                    }
                }.onFailure { error -> _state.update { it.copy(loading = false, error = error) } }
            }
    }

    fun retry() {
        _state.update { it.copy(error = null) }
        loadMore()
    }

    /** Stores the novel (if new) and returns its id for navigation. */
    suspend fun open(summary: NovelSummary): Long = novels.open(summary)

    private fun reset(mode: CatalogMode, query: String) {
        loading?.cancel()
        _state.update {
            it.copy(mode = mode, submittedQuery = query, items = emptyList(), nextPage = 1, loading = false, endReached = false, error = null)
        }
    }
}
