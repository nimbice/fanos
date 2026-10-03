package io.github.nimbice.fanos.core.data.extension

import android.content.Context
import android.net.Uri
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.nimbice.fanos.core.common.Dispatcher
import io.github.nimbice.fanos.core.common.ReaderDispatchers
import io.github.nimbice.fanos.core.datastore.SettingsDataSource
import io.github.nimbice.fanos.core.model.ExtensionInfo
import io.github.nimbice.fanos.core.updater.BackgroundCheck
import io.github.nimbice.fanos.core.updater.ExtensionCatalog
import io.github.nimbice.fanos.core.updater.PublishedExtension
import io.github.nimbice.fanos.core.updater.UpdateNotifier
import io.github.nimbice.fanos.source.api.Extensions
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/** What a look at the published extensions found: the names of the installed ones with updates, and how many are installed. */
data class ExtensionLook(val updates: List<String>, val installed: Int)

/** Newer versions of installed extensions, and extensions not installed, among those published. */
data class PublishedExtensions(val updates: List<PublishedExtension>, val available: List<PublishedExtension>)

/**
 * The extensions published beside the app's builds, against the ones installed: updates to offer and
 * announce, and more to install. The daily update check announces each lot of updates once.
 */
@Singleton
class ExtensionUpdates @Inject constructor(
    @ApplicationContext context: Context,
    private val extensions: ExtensionManager,
    private val catalog: ExtensionCatalog,
    private val notifier: UpdateNotifier,
    private val settings: SettingsDataSource,
    @Dispatcher(ReaderDispatchers.IO) private val io: CoroutineDispatcher,
) : BackgroundCheck {
    private val downloads = File(context.cacheDir, "extension-downloads")

    /** The published extensions, public and (with a key) private. */
    suspend fun published(): List<PublishedExtension> = catalog.published()

    /** The installed extensions' updates, as the reader looks (nothing announced). */
    suspend fun look(): ExtensionLook {
        val published = catalog.published()
        val installed = extensions.extensions.first()
        return ExtensionLook(compare(installed, published).updates.map { it.name }, installed.size)
    }

    override suspend fun check() {
        val published = catalog.published()
        val updates = compare(extensions.extensions.first(), published).updates
        if (updates.isEmpty()) {
            notifier.dismissExtensionUpdates()
            return
        }
        val which = updates.joinToString(",") { "${it.id}:${it.versionCode}" }
        if (settings.updates.first().notifiedExtensions == which) return
        notifier.extensionUpdates(updates.map { it.name })
        settings.extensionUpdatesNotified(which)
    }

    /**
     * Fetches [published] and reads them as picked files are read: what comes back is ready for the
     * extensions screen to install (asking first about a key not yet trusted), or says why it isn't.
     */
    suspend fun fetch(published: List<PublishedExtension>): List<PickedExtension> {
        notifier.dismissExtensionUpdates()
        try {
            val files =
                published.mapIndexed { index, extension ->
                    // Named for the extension, which a message about the file names; a folder each, should two share a name.
                    val file = File(downloads, "$index/${extension.name.replace(UNSAFE, "_")}.apk")
                    withContext(io) {
                        file.parentFile?.deleteRecursively()
                        file.parentFile?.mkdirs()
                    }
                    catalog.download(extension, file)
                    Uri.fromFile(file)
                }
            return extensions.inspect(files)
        } finally {
            withContext(io) { downloads.deleteRecursively() }
        }
    }

    companion object {
        private val UNSAFE = Regex("[^A-Za-z0-9 ._-]")

        /** Which of [published] update [installed] extensions, and which aren't installed; ones this app can't load are left out. */
        fun compare(installed: List<ExtensionInfo>, published: List<PublishedExtension>): PublishedExtensions {
            val byId = installed.associateBy { it.id }
            val loadable = published.filter { it.apiLevel in Extensions.MIN_API_LEVEL..Extensions.API_LEVEL }
            return PublishedExtensions(
                updates = loadable.filter { extension -> byId[extension.id]?.let { it.versionCode < extension.versionCode } == true },
                available = loadable.filter { it.id !in byId }.sortedBy { it.name.lowercase() },
            )
        }
    }
}
