package io.github.nimbice.fanos.core.data.readinglist

import io.github.nimbice.fanos.core.common.ApplicationScope
import io.github.nimbice.fanos.core.common.Clock
import io.github.nimbice.fanos.core.data.repository.SettingsRepository
import io.github.nimbice.fanos.core.data.source.SourceRegistry
import io.github.nimbice.fanos.core.data.userMessage
import io.github.nimbice.fanos.core.database.dao.NovelDao
import io.github.nimbice.fanos.core.database.dao.ReadingListDao
import io.github.nimbice.fanos.core.database.dao.SectionDao
import io.github.nimbice.fanos.core.database.entity.ReadingListEntryEntity
import io.github.nimbice.fanos.core.database.entity.ReadingListNovelRow
import io.github.nimbice.fanos.core.database.entity.SectionEntity
import io.github.nimbice.fanos.core.datastore.SettingsDataSource
import io.github.nimbice.fanos.core.model.SourceInfo
import io.github.nimbice.fanos.source.api.ReadingList
import io.github.nimbice.fanos.source.api.ReadingListSource
import io.github.nimbice.fanos.source.api.SignInRequiredException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/** A source whose site keeps reading lists, and how keeping them in step with the library is going. */
data class ReadingListStatus(
    val source: SourceInfo,
    /** The reader asked for its lists to be kept in step. */
    val enabled: Boolean,
    /** Telling the site right now. */
    val syncing: Boolean,
    /** When the lists were last in step with the library, and how many novels were on them then. */
    val syncedAt: Long?,
    val novels: Int,
    /** What stopped the last try, if anything. */
    val problem: String?,
    /** Where to sign in to the site, when that's what it waits for. */
    val signInUrl: String?,
)

/**
 * Keeps the reader's reading lists on the sites that have them (see ReadingListSource) in step with the library, for the
 * sources the reader asks it of. Each library novel from such a source goes on the list named like its section (the
 * first of its sections that has one), else the reader's first list; its bookmark follows the furthest chapter read;
 * and a novel taken out of the library comes off. The app only tells the site, never the other way: it remembers what it
 * told it (ReadingListEntryEntity), sends only what changed, and never takes off a novel it didn't put there.
 */
@Singleton
class ReadingListSync @Inject constructor(
    private val sources: SourceRegistry,
    private val novels: NovelDao,
    private val sections: SectionDao,
    private val entries: ReadingListDao,
    private val settings: SettingsRepository,
    private val records: SettingsDataSource,
    private val scheduler: ReadingListScheduler,
    private val clock: Clock,
    @ApplicationScope private val scope: CoroutineScope,
) {
    private val running = MutableStateFlow<Set<String>>(emptySet())

    // One pass at a time: a pass asked for while another runs waits for it, then works from what it left.
    private val passes = Mutex()

    /** Each source that keeps reading lists, and how keeping them in step is going. */
    val status: Flow<List<ReadingListStatus>> =
        combine(sources.withReadingLists, settings.appSettings.map { it.readingListSources }, records.readingListRecords, running) { lists, enabled, records, running ->
            lists.map { source ->
                val record = records[source.id]
                ReadingListStatus(
                    source = source,
                    enabled = source.id in enabled,
                    syncing = source.id in running,
                    syncedAt = record?.syncedAt,
                    novels = record?.novels ?: 0,
                    problem = record?.problem,
                    signInUrl = record?.signInUrl,
                )
            }
        }

    /** Keeps the source's lists in step from now on, starting with every novel at once; or stops. */
    suspend fun setEnabled(sourceId: String, on: Boolean) {
        settings.updateAppSettings { it.copy(readingListSources = if (on) it.readingListSources + sourceId else it.readingListSources - sourceId) }
        if (on) scheduler.syncNow(sourceId) else records.clearReadingListRecord(sourceId)
    }

    /** Puts every library novel of the source on its lists now, whatever the site was told before. */
    fun syncNow(sourceId: String) = scheduler.syncNow(sourceId)

    /** While any source's lists are kept in step, watches the library and has the sites told soon after it changes. */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun start() {
        scope.launch {
            settings.appSettings.map { it.readingListSources }.distinctUntilChanged().flatMapLatest { enabled ->
                if (enabled.isEmpty()) emptyFlow() else combine(novels.observeReadingListNovels(enabled), sections.observeAll(), ::wanted)
            }
                .distinctUntilChanged()
                // As things stand when the app starts, the sites were told already (or a retry waits to tell them).
                .drop(1)
                .collect { scheduler.syncSoon() }
        }
    }

    /**
     * Tells the sites of the sources kept in step what changed since they were last told, and everything for the sources
     * in [everything]. Returns false when a site couldn't be reached, to try again later.
     */
    suspend fun run(everything: Set<String>): Boolean =
        passes.withLock {
            val enabled = settings.appSettings.first().readingListSources
            var reached = true
            for (sourceId in enabled) {
                val site = sources.readingLists(sourceId) ?: continue
                if (!sync(sourceId, site, sourceId in everything)) reached = false
            }
            reached
        }

    private suspend fun sync(sourceId: String, site: ReadingListSource, everything: Boolean): Boolean {
        running.update { it + sourceId }
        try {
            val lists = site.readingLists()
            val wanted = wanted(novels.observeReadingListNovels(setOf(sourceId)).first(), sections.all())[sourceId].orEmpty()
            val told = entries.entries(sourceId).associateByTo(HashMap()) { it.novelUrl }
            val steps = ReadingListPlan.steps(wanted, told.values.map { ToldNovel(it.novelUrl, it.listId, it.bookmarkUrl) }, lists, everything)
            for (step in steps) {
                when (step) {
                    is ListStep.Put -> {
                        site.putOnList(step.novelUrl, step.list)
                        remember(told, sourceId, step.novelUrl) { it.copy(listId = step.list.id) }
                    }
                    is ListStep.Bookmark -> {
                        site.setBookmark(step.novelUrl, step.chapterUrl)
                        remember(told, sourceId, step.novelUrl) { it.copy(bookmarkUrl = step.chapterUrl) }
                    }
                    is ListStep.TakeOff -> {
                        site.takeOffLists(step.novelUrl)
                        entries.delete(sourceId, step.novelUrl)
                        told.remove(step.novelUrl)
                    }
                }
            }
            if (everything && wanted.isNotEmpty() && noneThere(site, wanted, lists)) {
                val name = sources.info(sourceId)?.name ?: sourceId
                records.recordReadingListProblem(sourceId, "Fanos couldn't find the novels on your $name lists afterwards.", null)
                return true
            }
            records.recordReadingListSync(sourceId, clock.now(), wanted.size)
            return true
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (signedOut: SignInRequiredException) {
            records.recordReadingListProblem(sourceId, "Waiting for you to sign in", signedOut.url)
            return true
        } catch (failed: IOException) {
            records.recordReadingListProblem(sourceId, userMessage(failed), null)
            return false
        } catch (failed: Exception) {
            records.recordReadingListProblem(sourceId, userMessage(failed), null)
            return true
        } finally {
            running.update { it - sourceId }
        }
    }

    /** Keeps what the site was just told of a novel, starting its record when it's the first time. */
    private suspend fun remember(
        told: MutableMap<String, ReadingListEntryEntity>,
        sourceId: String,
        novelUrl: String,
        change: (ReadingListEntryEntity) -> ReadingListEntryEntity,
    ) {
        val entry = change(told[novelUrl] ?: ReadingListEntryEntity(sourceId, novelUrl, listId = "", syncedAt = 0)).copy(syncedAt = clock.now())
        entries.upsert(entry)
        told[novelUrl] = entry
    }

    /** Whether the lists, read back, show none of the novels just put there: the site didn't take the requests. */
    private suspend fun noneThere(site: ReadingListSource, wanted: List<WantedNovel>, lists: List<ReadingList>): Boolean {
        val used = wanted.mapNotNull { ReadingListPlan.listFor(it.sections, lists) }.distinct()
        val there = used.flatMap { list -> site.listedNovels(list).map { ReadingListPlan.key(it.novelUrl) } }.toSet()
        return wanted.none { ReadingListPlan.key(it.url) in there }
    }

    /** Each source's library novels as its lists should have them, by source id: in their sections' order, the default one for none. */
    private fun wanted(rows: List<ReadingListNovelRow>, all: List<SectionEntity>): Map<String, List<WantedNovel>> {
        val ordered = all.sortedWith(compareBy<SectionEntity> { it.position }.thenBy { it.id })
        val default = all.firstOrNull { it.id == SectionEntity.DEFAULT_ID }?.name ?: SectionEntity.DEFAULT_NAME
        return rows.groupBy { it.sourceId }.mapValues { (_, rows) ->
            rows.map { row ->
                val ids = row.sectionIds?.split(',')?.mapNotNull { it.trim().toLongOrNull() }?.toSet().orEmpty()
                WantedNovel(row.url, ordered.filter { it.id in ids }.map { it.name }.ifEmpty { listOf(default) }, row.bookmarkUrl)
            }.sortedBy { it.url }
        }
    }
}
