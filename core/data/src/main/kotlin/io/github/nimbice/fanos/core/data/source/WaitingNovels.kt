package io.github.nimbice.fanos.core.data.source

import io.github.nimbice.fanos.core.common.ApplicationScope
import io.github.nimbice.fanos.core.common.Dispatcher
import io.github.nimbice.fanos.core.common.ReaderDispatchers
import io.github.nimbice.fanos.core.data.NovelKeys
import io.github.nimbice.fanos.core.database.dao.NovelDao
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Novels from a site no extension read when they came in, such as NovelLibrary's Novel Updates novels, are
 * kept under the site's name ("novelupdates.com"), waiting. Once an extension reads that site they become its
 * source's: their keys are paths on the site already, so their chapters, read marks and saved text carry on
 * as they were. A novel the source has already (added from it since) stays waiting beside it, untouched.
 */
@Singleton
class WaitingNovels @Inject constructor(
    private val sources: SourceRegistry,
    private val novels: NovelDao,
    private val novelKeys: NovelKeys,
    @ApplicationScope private val scope: CoroutineScope,
    @Dispatcher(ReaderDispatchers.IO) private val io: CoroutineDispatcher,
) {
    /**
     * Adopts the waiting novels now and whenever extensions change; then puts every source's novels under the keys it
     * gives them now (see [NovelKeys.rekeyAll]), adopted ones too: an update can bring a source that knows its novels'
     * own numbers.
     */
    fun start() {
        scope.launch(io) {
            sources.loaded.collect {
                sources.sites().forEach { (site, sourceId) -> novels.adopt(site, sourceId) }
                novelKeys.rekeyAll()
            }
        }
    }
}
