package io.github.nimbice.fanos.core.data.repository

import io.github.nimbice.fanos.core.data.source.SourceRegistry
import io.github.nimbice.fanos.core.data.toCatalogPage
import io.github.nimbice.fanos.core.model.CatalogPage
import io.github.nimbice.fanos.core.model.SourceInfo
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject

/** Browsing a source: its lists and search. Nothing here is stored until the user opens a novel. */
class CatalogRepository @Inject constructor(private val sources: SourceRegistry) {

    /** Every source there is to read from, as extensions come and go. */
    val allSources: Flow<List<SourceInfo>> = sources.all

    /** The source [id], kept up to date; null while no extension reads from it. */
    fun source(id: String): Flow<SourceInfo?> = sources.infoFlow(id)

    /** The source [id] as it is once the extensions have loaded; null when no extension reads from it. */
    suspend fun sourceInfo(id: String): SourceInfo? = sources.info(id)

    suspend fun search(sourceId: String, query: String, page: Int): CatalogPage =
        sources[sourceId].search(query, page).toCatalogPage(sourceId)

    suspend fun popular(sourceId: String, page: Int): CatalogPage = sources[sourceId].popular(page).toCatalogPage(sourceId)

    suspend fun latest(sourceId: String, page: Int): CatalogPage = sources[sourceId].latest(page).toCatalogPage(sourceId)
}
