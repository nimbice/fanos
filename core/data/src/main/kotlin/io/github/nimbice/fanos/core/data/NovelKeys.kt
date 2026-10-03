package io.github.nimbice.fanos.core.data

import io.github.nimbice.fanos.core.data.source.SourceRegistry
import io.github.nimbice.fanos.core.data.source.SourceUrls
import io.github.nimbice.fanos.core.database.TransactionRunner
import io.github.nimbice.fanos.core.database.dao.NovelDao
import io.github.nimbice.fanos.core.database.entity.NovelEntity
import io.github.nimbice.fanos.source.api.NovelSource
import javax.inject.Inject
import javax.inject.Singleton

/** Finds stored novels by the keys their sources give them now (see NovelSource.novelKey), and keeps them under those keys. */
@Singleton
class NovelKeys @Inject constructor(
    private val database: TransactionRunner,
    private val novels: NovelDao,
    private val sources: SourceRegistry,
) {

    /** The key [source]'s novel at [url] is stored under. */
    fun keyOf(source: NovelSource, url: String): String = SourceUrls.novelKey(source, url)

    /**
     * The stored novel of [source] at [url]: by its key, or, one stored before the site renamed it in its address and
     * not yet under the key the source gives it now, by its stored address; that one is put under the key.
     */
    suspend fun find(source: NovelSource, url: String): NovelEntity? {
        val key = keyOf(source, url)
        novels.find(source.id, key)?.let { return it }
        if (source.novelKey(url) == null) return null
        val renamed = novels.ofSource(source.id).firstOrNull { keyOf(source, it.url) == key } ?: return null
        novels.setKey(renamed.id, key)
        return novels.get(renamed.id)
    }

    /** [find], for a novel of [sourceId], which may not be installed: then by [storedKey], the key it was stored under. */
    suspend fun find(sourceId: String, url: String, storedKey: String): NovelEntity? {
        val source = runCatching { sources[sourceId] }.getOrNull() ?: return novels.find(sourceId, storedKey)
        return find(source, url)
    }

    /** The key a novel of [sourceId] at [url] is stored under: the source's, when it is installed, else [storedKey]. */
    suspend fun keyOf(sourceId: String, url: String, storedKey: String): String =
        runCatching { sources[sourceId] }.getOrNull()?.let { keyOf(it, url) } ?: storedKey

    /** Puts every installed source's stored novels under the keys it gives them now (see [NovelRekey]). */
    suspend fun rekeyAll() {
        for (id in sources.ids()) {
            val source = runCatching { sources[id] }.getOrNull() ?: continue
            database.transaction {
                val stored = novels.ofSource(id)
                val plan = NovelRekey.plan(stored, stored.associate { it.id to keyOf(source, it.url) })
                if (!plan.isEmpty) {
                    // The strays first, then the keys, which one of them may have held.
                    plan.drops.forEach { novels.deleteNovel(it) }
                    plan.keys.forEach { (novelId, key) -> novels.setKey(novelId, key) }
                }
            }
        }
    }
}
