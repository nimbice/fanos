package io.github.nimbice.fanos.core.data.source

import io.github.nimbice.fanos.core.common.Dispatcher
import io.github.nimbice.fanos.core.common.ReaderDispatchers
import io.github.nimbice.fanos.core.data.extension.ExtensionManager
import io.github.nimbice.fanos.core.model.SourceInfo
import io.github.nimbice.fanos.source.api.ListedNovel
import io.github.nimbice.fanos.source.api.NovelSource
import io.github.nimbice.fanos.source.api.ReadingList
import io.github.nimbice.fanos.source.api.ReadingListSource
import io.github.nimbice.fanos.source.api.SearchBy
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.net.URI
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The sources the app can read from, by id: those of the working extensions. Asking for one waits until the
 * installed extensions have loaded, so nothing mistakes an extension that hasn't loaded yet for a missing one.
 */
@Singleton
class SourceRegistry @Inject constructor(private val extensions: ExtensionManager, @Dispatcher(ReaderDispatchers.IO) private val io: CoroutineDispatcher) {

    /** Every source there is to read from, by name, as extensions come and go. */
    val all: Flow<List<SourceInfo>> = extensions.sources.map { sources -> sources.values.map { it.sourceInfo() }.sortedBy { it.name.lowercase() } }

    /** The source [id], waiting for the extensions to load; [UnknownSourceException] when no extension has it. */
    suspend operator fun get(id: String): NovelSource {
        val source = extensions.loadedSources()[id] ?: throw UnknownSourceException(id)
        return BackgroundSource(source, io)
    }

    /** What's known of the source [id], once the extensions have loaded; null when no extension has it. */
    suspend fun info(id: String): SourceInfo? = extensions.loadedSources()[id]?.sourceInfo()

    /** The ids of every source there is to read from, once the extensions have loaded. */
    suspend fun ids(): Set<String> = extensions.loadedSources().keys

    /** [info] of the source [id], kept up to date as extensions are installed and removed. */
    fun infoFlow(id: String): Flow<SourceInfo?> = extensions.sources.map { it[id]?.sourceInfo() }

    /** The sources whose sites keep reading lists for their readers (see [ReadingListSource]), as extensions come and go. */
    val withReadingLists: Flow<List<SourceInfo>> =
        extensions.sources.map { sources -> sources.values.filterIsInstance<ReadingListSource>().map { (it as NovelSource).sourceInfo() }.sortedBy { it.name.lowercase() } }

    /** The reading lists of the source [id]'s site, once the extensions have loaded; null when it keeps none, or isn't installed. */
    suspend fun readingLists(id: String): ReadingListSource? = (extensions.loadedSources()[id] as? ReadingListSource)?.let { BackgroundReadingLists(it, io) }

    /** The installed sources, anew each time extensions are installed, updated or removed. */
    val loaded: Flow<Collection<NovelSource>> = extensions.sources.map { it.values }

    /** The id of the source that reads each site, by the site's name ("novelupdates.com"), as extensions come and go. */
    val sites: Flow<Map<String, String>> = extensions.sources.map { it.values.bySite() }

    /** [sites] once the extensions have loaded. */
    suspend fun sites(): Map<String, String> = extensions.loadedSources().values.bySite()

    private fun Collection<NovelSource>.bySite(): Map<String, String> =
        mapNotNull { source -> SourceUrls.siteOf(source.baseUrl)?.let { site -> site to source.id } }.toMap()
}

/** What the app shows of a source, and goes by. */
internal fun NovelSource.sourceInfo() =
    SourceInfo(
        id, name, lang, baseUrl, supportsPopular, supportsLatest, searchHint,
        searchesCreators = searchBy == SearchBy.Creators,
        groupsAreVersions = chapterGroupsAreVersions,
    )

/** No working extension reads from [sourceId]: it isn't installed, or it doesn't load. */
class UnknownSourceException(val sourceId: String) : IllegalStateException("No source with id $sourceId")

/**
 * A source that does its work (requests, and parsing pages that can run to megabytes) off the main
 * thread, whoever calls it.
 */
private class BackgroundSource(private val source: NovelSource, private val io: CoroutineDispatcher) : NovelSource by source {
    override suspend fun search(query: String, page: Int) = withContext(io) { source.search(query, page) }

    override suspend fun popular(page: Int) = withContext(io) { source.popular(page) }

    override suspend fun latest(page: Int) = withContext(io) { source.latest(page) }

    override suspend fun novelDetails(novelUrl: String) = withContext(io) { source.novelDetails(novelUrl) }

    override suspend fun chapterList(novelUrl: String) = withContext(io) { source.chapterList(novelUrl) }

    override suspend fun chapterPage(chapterUrl: String) = withContext(io) { source.chapterPage(chapterUrl) }
}

/** A source's reading lists, reached off the main thread, as [BackgroundSource] reaches its pages. */
private class BackgroundReadingLists(private val lists: ReadingListSource, private val io: CoroutineDispatcher) : ReadingListSource {
    override suspend fun readingLists(): List<ReadingList> = withContext(io) { lists.readingLists() }

    override suspend fun listedNovels(list: ReadingList): List<ListedNovel> = withContext(io) { lists.listedNovels(list) }

    override suspend fun putOnList(novelUrl: String, list: ReadingList) = withContext(io) { lists.putOnList(novelUrl, list) }

    override suspend fun setBookmark(novelUrl: String, chapterUrl: String) = withContext(io) { lists.setBookmark(novelUrl, chapterUrl) }

    override suspend fun takeOffLists(novelUrl: String) = withContext(io) { lists.takeOffLists(novelUrl) }
}

/**
 * Stable keys for stored novels and chapters. A URL on the source's own site is keyed by its path
 * and query, so the site can move to a new domain without its novels losing their chapters, read
 * state or saved content; a URL elsewhere is kept whole.
 */
object SourceUrls {
    fun key(baseUrl: String, url: String): String {
        val target = runCatching { URI(url) }.getOrNull() ?: return url
        val base = runCatching { URI(baseUrl) }.getOrNull() ?: return url
        if (target.host == null || base.host == null || site(target.host) != site(base.host)) return url
        val path = target.rawPath?.ifEmpty { "/" } ?: "/"
        return if (target.rawQuery != null) "$path?${target.rawQuery}" else path
    }

    /**
     * The key of [source]'s chapter at [url]: the chapter's own number on the site, where the source knows it (see
     * NovelSource.chapterKey), so a changed address keeps the chapter; else the address, as [key] makes it.
     */
    fun chapterKey(source: NovelSource, url: String): String = source.chapterKey(url)?.let { "$OWN_KEY$it" } ?: key(source.baseUrl, url)

    /** The key of [source]'s novel at [url], as [chapterKey] for chapters (see NovelSource.novelKey). */
    fun novelKey(source: NovelSource, url: String): String = source.novelKey(url)?.let { "$OWN_KEY$it" } ?: key(source.baseUrl, url)

    /** The site [url] is on: its host less "www." or "m." ("novelupdates.com"), or null when it has none. */
    fun siteOf(url: String): String? = runCatching { URI(url).host }.getOrNull()?.let(::site)

    private fun site(host: String) = host.lowercase().removePrefix("www.").removePrefix("m.")

    // Before a source's own chapter keys: no address key starts so, since those are paths or whole addresses.
    private const val OWN_KEY = "id:"
}
