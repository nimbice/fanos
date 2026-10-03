package io.github.nimbice.fanos.core.data.repository

import io.github.nimbice.fanos.core.datastore.SettingsDataSource
import io.github.nimbice.fanos.core.model.AppSettings
import io.github.nimbice.fanos.core.model.ListeningSettings
import io.github.nimbice.fanos.core.model.ReaderSettings
import io.github.nimbice.fanos.core.model.SearchHistory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SettingsRepository @Inject constructor(private val dataSource: SettingsDataSource) {

    val appSettings: Flow<AppSettings> = dataSource.appSettings

    val readerSettings: Flow<ReaderSettings> = dataSource.readerSettings

    suspend fun updateAppSettings(transform: (AppSettings) -> AppSettings) = dataSource.updateAppSettings(transform)

    suspend fun updateReaderSettings(transform: (ReaderSettings) -> ReaderSettings) = dataSource.updateReaderSettings(transform)

    fun searchHistory(history: SearchHistory): Flow<List<String>> = dataSource.searchHistory(history)

    val listeningSettings: Flow<ListeningSettings> = dataSource.listeningSettings

    suspend fun updateListeningSettings(transform: (ListeningSettings) -> ListeningSettings) = dataSource.updateListeningSettings(transform)

    /** When the Updates tab was last looked at; null until it first is. */
    val newChaptersSeenAt: Flow<Long?> = dataSource.newChaptersSeenAt

    suspend fun setNewChaptersSeenAt(time: Long) = dataSource.setNewChaptersSeenAt(time)

    suspend fun rememberSearch(history: SearchHistory, words: String) = dataSource.rememberSearch(history, words)

    suspend fun forgetSearch(history: SearchHistory, words: String) = dataSource.forgetSearch(history, words)

    /** [history]'s recent searches, for a view model to offer in its search box while it lives in [scope]. */
    fun recentSearches(history: SearchHistory, scope: CoroutineScope) = RecentSearches(this, history, scope)
}
