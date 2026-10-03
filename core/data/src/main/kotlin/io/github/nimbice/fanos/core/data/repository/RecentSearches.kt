package io.github.nimbice.fanos.core.data.repository

import io.github.nimbice.fanos.core.model.SearchHistory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** A screen's recent searches in [history]: what its search box offers, newest first, and what it keeps and forgets. */
class RecentSearches internal constructor(
    private val settings: SettingsRepository,
    private val history: SearchHistory,
    private val scope: CoroutineScope,
) {
    val items: StateFlow<List<String>> = settings.searchHistory(history).stateIn(scope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Keeps [words] as the newest search; blank words aren't kept. */
    fun remember(words: String) {
        scope.launch { settings.rememberSearch(history, words) }
    }

    fun forget(words: String) {
        scope.launch { settings.forgetSearch(history, words) }
    }
}
