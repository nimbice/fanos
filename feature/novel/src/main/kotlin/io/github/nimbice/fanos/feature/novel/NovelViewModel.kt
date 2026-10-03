package io.github.nimbice.fanos.feature.novel

import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.nimbice.fanos.core.common.suspendRunCatching
import io.github.nimbice.fanos.core.data.download.ChapterDownload
import io.github.nimbice.fanos.core.data.download.DownloadRepository
import io.github.nimbice.fanos.core.data.notification.NewChapterNotifier
import io.github.nimbice.fanos.core.data.repository.CatalogRepository
import io.github.nimbice.fanos.core.data.repository.CoverSearch
import io.github.nimbice.fanos.core.data.repository.NovelRepository
import io.github.nimbice.fanos.core.data.repository.ReadingRepository
import io.github.nimbice.fanos.core.data.repository.SectionRepository
import io.github.nimbice.fanos.core.data.repository.SettingsRepository
import io.github.nimbice.fanos.core.data.userMessage
import io.github.nimbice.fanos.core.model.Chapter
import io.github.nimbice.fanos.core.model.ChapterGroup
import io.github.nimbice.fanos.core.model.CoverResults
import io.github.nimbice.fanos.core.model.LibrarySection
import io.github.nimbice.fanos.core.model.Novel
import io.github.nimbice.fanos.core.model.SearchHistory
import io.github.nimbice.fanos.core.model.SourceInfo
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject
import io.github.nimbice.fanos.core.model.BOOK_SOURCE
import io.github.nimbice.fanos.core.data.book.BookImporter
import io.github.nimbice.fanos.core.data.repository.ContentRepository
import kotlinx.coroutines.flow.mapLatest

/** A search for the novel's cover: under way, found, or failed. */
data class CoverSearchState(
    val searching: Boolean = false,
    val results: CoverResults? = null,
    val error: String? = null,
)

data class NovelUiState(
    val novel: Novel? = null,
    /** The source's name, or the site's address while no extension reads from it. */
    val sourceName: String? = null,
    /** No installed extension reads from the novel's site: it can't be checked for chapters or read online. */
    val sourceMissing: Boolean = false,
    /** In display order: oldest first, or newest first when [newestFirst]; the chosen translation group's, when there's one. */
    val chapters: List<Chapter> = emptyList(),
    /** The novel's translation groups, most chapters first; none when its site doesn't say. */
    val groups: List<ChapterGroup> = emptyList(),
    /** How many chapters the novel lists, whichever group's. */
    val allChapters: Int = 0,
    /** The groups are versions of the same chapters (a site's uploads of them over again), not translators. */
    val groupsAreVersions: Boolean = false,
    val newestFirst: Boolean = false,
    val lastReadChapterId: Long? = null,
    /** Chapters that are saved, waiting, being fetched or failed, by id. */
    val downloads: Map<Long, ChapterDownload> = emptyMap(),
    val refreshing: Boolean = false,
    val error: Throwable? = null,
    val loaded: Boolean = false,
)

@HiltViewModel
class NovelViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val novels: NovelRepository,
    private val reading: ReadingRepository,
    private val catalog: CatalogRepository,
    private val settings: SettingsRepository,
    private val downloads: DownloadRepository,
    private val notifier: NewChapterNotifier,
    private val covers: CoverSearch,
    private val sectionRepository: SectionRepository,
    private val books: BookImporter,
    private val contents: ContentRepository,
) : ViewModel() {

    val novelId: Long = savedStateHandle.toRoute<NovelRoute>().novelId

    /** The chapter search's recent searches, shared by every novel's. */
    val chapterSearches = settings.recentSearches(SearchHistory.Chapters, viewModelScope)

    /** The library's sections, and the ones this novel is in (while it's in the library). */
    val sections: StateFlow<List<LibrarySection>> =
        sectionRepository.observeSections().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val novelSections: StateFlow<Set<Long>> =
        sectionRepository.observeSectionsOf(novelId).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), setOf(LibrarySection.DEFAULT_ID))

    fun setInSection(sectionId: Long, inSection: Boolean) {
        viewModelScope.launch { sectionRepository.setInSection(novelId, sectionId, inSection) }
    }

    /** Starts a section with this novel in it. */
    fun createSection(name: String) {
        viewModelScope.launch { sectionRepository.setInSection(novelId, sectionRepository.create(name), true) }
    }

    private val refreshing = MutableStateFlow(false)
    private val error = MutableStateFlow<Throwable?>(null)

    // The novel's source, looked up again only when the novel moves to another: the novel shows at
    // once, its source once the extensions have loaded.
    @OptIn(ExperimentalCoroutinesApi::class)
    private val source: Flow<SourceState> =
        novels.observeNovel(novelId).map { it?.sourceId }.distinctUntilChanged().flatMapLatest { id ->
            // A book's novel has no source, and misses none.
            if (id == null || id == BOOK_SOURCE) {
                flowOf(SourceState(null, missing = false))
            } else {
                catalog.source(id).map { SourceState(it, missing = it == null) }.onStart { emit(SourceState(null, missing = false)) }
            }
        }

    val state: StateFlow<NovelUiState> =
        combine(
            combine(novels.observeNovel(novelId), source) { novel, source -> NovelAndSource(novel, source.info, source.missing) },
            combine(novels.observeChapters(novelId), novels.observeGroups(novelId), novels.observeListedCount(novelId), ::Triple),
            reading.observeLastRead(novelId),
            settings.appSettings.map { it.chaptersNewestFirst }.distinctUntilChanged(),
            combine(refreshing, error, downloads.observe(novelId), ::Triple),
        ) { (novel, source, sourceMissing), (chapters, groups, allChapters), lastRead, newestFirst, (refreshing, error, downloads) ->
            NovelUiState(
                novel = novel,
                sourceName = source?.name ?: novel?.site,
                sourceMissing = sourceMissing,
                chapters = if (novel?.chaptersNewestFirst ?: newestFirst) chapters.asReversed() else chapters,
                groups = groups,
                allChapters = allChapters,
                groupsAreVersions = source?.groupsAreVersions == true,
                newestFirst = novel?.chaptersNewestFirst ?: newestFirst,
                lastReadChapterId = lastRead,
                downloads = downloads,
                refreshing = refreshing,
                error = error,
                loaded = true,
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), NovelUiState())

    /** Minutes to finish the novel's unread chapters at the reader's speed; null while it can't be told. */
    @OptIn(ExperimentalCoroutinesApi::class)
    val toFinish: StateFlow<Int?> =
        combine(
            state.map { it.chapters.filterNot { chapter -> chapter.isRead }.map { chapter -> chapter.id } }.distinctUntilChanged(),
            settings.appSettings.map { it.readingSpeed }.distinctUntilChanged(),
        ) { unread, speed -> unread to speed }
            .mapLatest { (unread, speed) -> contents.wordsToRead(novelId, unread)?.let { words -> (words + speed - 1) / speed.coerceAtLeast(1) } }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    init {
        // The novel's new chapters are in front of the reader now.
        viewModelScope.launch { notifier.dismiss(novelId) }
        viewModelScope.launch {
            val novel = novels.observeNovel(novelId).first()
            if (novel != null && (novel.detailsFetchedAt == null || novel.chaptersFetchedAt == null)) refresh()
        }
    }

    /** Fetches details and the chapter list side by side; either failing does not stop the other. */
    fun refresh() {
        // A book has nothing to fetch: what it has came with it.
        if (refreshing.value || state.value.novel?.isBook == true) return
        viewModelScope.launch {
            refreshing.value = true
            error.value = null
            val details = async { suspendRunCatching { novels.refreshDetails(novelId) } }
            val chapters = async { suspendRunCatching { novels.refreshChapters(novelId) } }
            error.value = chapters.await().exceptionOrNull() ?: details.await().exceptionOrNull()
            refreshing.value = false
        }
    }

    private val coverSearchState = MutableStateFlow(CoverSearchState())
    val coverSearch: StateFlow<CoverSearchState> = coverSearchState.asStateFlow()
    private var coverSearchJob: Job? = null

    /** Looks for covers of the book called [title] by [author], or failing that, of its series. */
    fun searchCovers(title: String, author: String) {
        coverSearchJob?.cancel()
        coverSearchJob =
            viewModelScope.launch {
                coverSearchState.value = CoverSearchState(searching = true)
                coverSearchState.value =
                    suspendRunCatching { covers.search(title, author) }.fold(
                        onSuccess = { CoverSearchState(results = it) },
                        onFailure = { CoverSearchState(error = userMessage(it)) },
                    )
            }
    }

    fun clearCoverSearch() {
        coverSearchJob?.cancel()
        coverSearchState.value = CoverSearchState()
    }

    /** Keeps a copy of [picture] from the phone for the cover; [onKept] gets its address, [onFailed] why not. */
    fun keepPicture(picture: Uri, onKept: (String) -> Unit, onFailed: (String) -> Unit) {
        viewModelScope.launch {
            suspendRunCatching { novels.keepPickedCover(novelId, picture) }.fold(onKept) { onFailed(userMessage(it)) }
        }
    }

    /** Shows [title] and [coverUrl] instead of the site's; the site's own ones where they're the same. */
    fun saveEdits(title: String, coverUrl: String?) {
        viewModelScope.launch { novels.setEdits(novelId, title, coverUrl) }
    }

    /** Lets go of pictures picked while editing and not saved. */
    fun discardEdits() {
        viewModelScope.launch { novels.discardPickedCovers(novelId) }
    }

    /** Takes the book out of the library, its text and pictures with it; its reading stays for when it's opened again. */
    fun removeBook() {
        viewModelScope.launch { books.free(novelId) }
    }

    suspend fun bookSize(): Long = books.size(novelId)

    fun setInLibrary(inLibrary: Boolean) {
        viewModelScope.launch { novels.setInLibrary(novelId, inLibrary) }
    }

    /** Lists only [group]'s chapters, and reads on through them; all of them for null. */
    fun setChapterGroup(group: String?) {
        viewModelScope.launch { novels.setChapterGroup(novelId, group) }
    }

    fun followDefaultVersion() {
        viewModelScope.launch { novels.followDefaultVersion(novelId) }
    }

    /** Turns this novel's chapter order around; other novels keep theirs. */
    fun toggleChapterOrder() {
        viewModelScope.launch { novels.setChaptersNewestFirst(novelId, !state.value.newestFirst) }
    }

    fun setRead(chapterIds: List<Long>, read: Boolean) {
        viewModelScope.launch { novels.setRead(chapterIds, read) }
    }

    /**
     * Marks every chapter before [chapter] read (catching up with where the reader got to elsewhere) or
     * unread (starting over). Returns the chapters that changed, so the change can be undone.
     */
    fun markEarlier(chapter: Chapter, read: Boolean): List<Long> {
        val ids = state.value.chapters.filter { it.index < chapter.index && it.isRead != read }.map { it.id }
        if (ids.isNotEmpty()) setRead(ids, read)
        return ids
    }

    suspend fun resumeChapterId(): Long? = reading.resumeChapter(novelId)?.id

    /** Queues chapters for reading offline; failed ones are tried again. */
    fun download(chapterIds: List<Long>) {
        viewModelScope.launch { downloads.download(novelId, chapterIds) }
    }

    /** How many unread chapters aren't saved or waiting yet. */
    suspend fun unreadToDownload(): Int = downloads.unreadToDownload(novelId).size

    /** Queues the next [limit] unread chapters, or all of them; returns how many. */
    suspend fun downloadUnread(limit: Int?): Int = downloads.downloadUnread(novelId, limit)

    fun cancelDownloads(chapterIds: List<Long>) {
        viewModelScope.launch { downloads.cancel(chapterIds) }
    }

    fun deleteDownloads(chapterIds: List<Long>) {
        viewModelScope.launch { downloads.delete(chapterIds) }
    }

    suspend fun downloadsWaitForWifi(): Boolean = settings.appSettings.first().downloadOnlyOnWifi
}

private data class NovelAndSource(val novel: Novel?, val source: SourceInfo?, val missing: Boolean)

private data class SourceState(val info: SourceInfo?, val missing: Boolean)
