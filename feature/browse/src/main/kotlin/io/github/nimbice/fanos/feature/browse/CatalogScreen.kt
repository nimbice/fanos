package io.github.nimbice.fanos.feature.browse

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.nimbice.fanos.core.data.browserLabel
import io.github.nimbice.fanos.core.data.browserUrl
import io.github.nimbice.fanos.core.data.errorDetail
import io.github.nimbice.fanos.core.data.userMessage
import io.github.nimbice.fanos.core.designsystem.component.EmptyState
import io.github.nimbice.fanos.core.designsystem.component.ErrorState
import io.github.nimbice.fanos.core.designsystem.component.NovelCover
import io.github.nimbice.fanos.core.designsystem.component.SearchField
import io.github.nimbice.fanos.core.designsystem.component.scaffoldContent
import io.github.nimbice.fanos.core.model.NovelSummary
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CatalogScreen(
    onBack: () -> Unit,
    onOpenNovel: (Long) -> Unit,
    onOpenInBrowser: (String) -> Unit,
    viewModel: CatalogViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val recent by viewModel.recent.items.collectAsStateWithLifecycle()
    val source by viewModel.source.collectAsStateWithLifecycle()
    val modes by viewModel.modes.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()

    // Load the next page as the end of the list comes into view.
    LaunchedEffect(listState, state.items.size) {
        snapshotFlow { listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0 }
            .distinctUntilChanged()
            .collect { last -> if (state.items.isNotEmpty() && last >= state.items.size - 5) viewModel.loadMore() }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(source?.name ?: "Source") },
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
                placeholder = "Search ${source?.name ?: ""}",
                recent = recent,
                onForget = viewModel.recent::forget,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            )
            if (modes.isNotEmpty()) {
                Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    modes.forEach { mode ->
                        FilterChip(
                            selected = state.mode == mode,
                            onClick = { viewModel.show(mode) },
                            label = { Text(if (mode == CatalogMode.Popular) "Popular" else "Latest") },
                        )
                    }
                }
            }
            val error = state.error
            when {
                state.items.isEmpty() && error != null ->
                    ErrorState(
                        message = userMessage(error),
                        onRetry = viewModel::retry,
                        onOpenInBrowser = browserUrl(error)?.let { url -> { onOpenInBrowser(url) } },
                        browserLabel = browserLabel(error),
                        detail = errorDetail(error),
                    )
                state.items.isEmpty() && state.loading ->
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                state.items.isEmpty() && state.mode == CatalogMode.Search ->
                    EmptyState(
                        title = if (state.submittedQuery.isEmpty()) "Search ${source?.name ?: "this source"}" else "Nothing found",
                        message = source?.searchHint ?: if (state.submittedQuery.isEmpty()) "Type a title and press search." else "Try other words.",
                    )
                else ->
                    LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                        items(state.items, key = { it.url }) { novel ->
                            NovelRow(novel) { scope.launch { onOpenNovel(viewModel.open(novel)) } }
                        }
                        item {
                            Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                                when {
                                    state.loading -> CircularProgressIndicator()
                                    error != null ->
                                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                            Text(userMessage(error), style = MaterialTheme.typography.bodySmall)
                                            TextButton(onClick = viewModel::retry) { Text("Try again") }
                                        }
                                }
                            }
                        }
                    }
            }
        }
    }
}

@Composable
private fun NovelRow(novel: NovelSummary, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        NovelCover(novel.coverUrl, novel.title, Modifier.width(56.dp))
        Column(Modifier.weight(1f)) {
            Text(novel.title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            novel.author?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            }
            novel.description?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
    }
}
