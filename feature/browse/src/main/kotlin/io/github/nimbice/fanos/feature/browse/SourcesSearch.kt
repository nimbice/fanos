package io.github.nimbice.fanos.feature.browse

import io.github.nimbice.fanos.core.common.suspendRunCatching
import io.github.nimbice.fanos.core.data.repository.CatalogRepository
import io.github.nimbice.fanos.core.model.SourceInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import java.net.SocketTimeoutException

/**
 * A search of several sources at once, each showing its results as they come: Browse's search of all sources, and
 * finding a novel on another site to move it to.
 */
internal class SourcesSearch(private val scope: CoroutineScope, private val catalog: CatalogRepository, query: String) {

    private val _state = MutableStateFlow(GlobalSearchUiState(query = query))
    val state: StateFlow<GlobalSearchUiState> = _state.asStateFlow()

    // A few sources at a time: each site keeps to its own request limits anyway, but a phone on
    // mobile data does not need every site answering at once.
    private val permits = Semaphore(PARALLEL_SOURCES)
    private var run: Job? = null

    fun onQueryChange(query: String) = _state.update { it.copy(query = query) }

    /**
     * Searches the sources [sources] gives for the query, each showing its results as they come; a source [authorAt]
     * names an author for is searched for that author instead.
     */
    fun search(sources: suspend () -> List<SourceInfo>, authorAt: (SourceInfo) -> String? = { null }) {
        val query = _state.value.query.trim()
        if (query.isEmpty()) return
        run?.cancel()
        run =
            scope.launch {
                val chosen = sources().map { source -> SourceResults(source, SourceSearch.Searching, authorAt(source)) }
                _state.update { it.copy(submitted = query, sources = chosen) }
                chosen.forEach { result -> launch { searchIn(result.source, query, words = result.author ?: query) } }
            }
    }

    /** Searches one source again: after an error, or after its bot check was passed in the browser. */
    fun retry(sourceId: String) {
        val current = _state.value
        val result = current.sources.firstOrNull { it.source.id == sourceId } ?: return
        set(sourceId, current.submitted, SourceSearch.Searching)
        scope.launch { searchIn(result.source, current.submitted, words = result.author ?: current.submitted) }
    }

    /** Searches [source] for [words], as its part of the search for [query]. */
    private suspend fun searchIn(source: SourceInfo, query: String, words: String) {
        val result = permits.withPermit { suspendRunCatching { withTimeoutOrNull(TIMEOUT_MS) { catalog.search(source.id, words, 1) } } }
        val search =
            result.fold(
                onSuccess = { page ->
                    when {
                        page == null -> SourceSearch.Failed(SocketTimeoutException())
                        page.novels.isEmpty() -> SourceSearch.NoResults
                        else -> SourceSearch.Found(page.novels)
                    }
                },
                onFailure = { SourceSearch.Failed(it) },
            )
        set(source.id, query, search)
    }

    // Results for a query that has since been replaced are dropped.
    private fun set(sourceId: String, query: String, search: SourceSearch) =
        _state.update { state ->
            if (state.submitted != query) {
                state
            } else {
                state.copy(sources = state.sources.map { if (it.source.id == sourceId) it.copy(search = search) else it })
            }
        }

    private companion object {
        const val PARALLEL_SOURCES = 4
        const val TIMEOUT_MS = 45_000L
    }
}
