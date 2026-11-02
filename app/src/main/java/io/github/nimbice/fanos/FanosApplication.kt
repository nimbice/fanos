package io.github.nimbice.fanos

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.crossfade
import dagger.Lazy
import dagger.hilt.android.HiltAndroidApp
import io.github.nimbice.fanos.core.data.extension.ExtensionManager
import io.github.nimbice.fanos.core.data.readinglist.ReadingListSync
import io.github.nimbice.fanos.core.data.source.WaitingNovels
import io.github.nimbice.fanos.core.data.work.BackupScheduler
import io.github.nimbice.fanos.core.data.work.DownloadScheduler
import io.github.nimbice.fanos.core.data.work.LibraryUpdateScheduler
import io.github.nimbice.fanos.core.data.work.ReleaseCheckScheduler
import io.github.nimbice.fanos.core.updater.UpdateScheduler
import okhttp3.OkHttpClient
import javax.inject.Inject
import io.github.nimbice.fanos.core.data.report.CrashLog
import io.github.nimbice.fanos.core.data.report.MemoryGuard
import io.github.nimbice.fanos.core.common.MemoryLedger

@HiltAndroidApp
class FanosApplication : Application(), Configuration.Provider, SingletonImageLoader.Factory {

    @Inject lateinit var workerFactory: HiltWorkerFactory

    @Inject lateinit var okHttpClient: Lazy<OkHttpClient>

    @Inject lateinit var libraryUpdates: LibraryUpdateScheduler

    @Inject lateinit var releaseChecks: ReleaseCheckScheduler

    @Inject lateinit var backups: BackupScheduler

    @Inject lateinit var downloads: DownloadScheduler

    @Inject lateinit var extensions: ExtensionManager

    @Inject lateinit var updates: UpdateScheduler

    @Inject lateinit var waitingNovels: WaitingNovels

    @Inject lateinit var readingLists: ReadingListSync

    @Inject lateinit var crashLog: CrashLog

    @Inject lateinit var memoryGuard: MemoryGuard

    override fun onCreate() {
        super.onCreate()
        crashLog.install()
        memoryGuard.start()
        // Loading takes a moment; started now, the sources are ready by the time anything asks.
        extensions.start()
        waitingNovels.start()
        libraryUpdates.start()
        releaseChecks.start()
        backups.start()
        downloads.start()
        updates.start()
        readingLists.start()
    }

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().setWorkerFactory(workerFactory).build()

    // Covers are fetched with the app's client, so they get the same cookies and user agent as
    // pages (some sites put images behind the same bot check).
    override fun newImageLoader(context: PlatformContext): ImageLoader =
        ImageLoader.Builder(context)
            .components { add(OkHttpNetworkFetcherFactory(callFactory = { okHttpClient.get() })) }
            .crossfade(true)
            .build()
            .also { loader ->
                // Covers and pictures kept in memory: let go of when memory runs short, as they can be loaded again.
                MemoryLedger.hold("images in memory", shed = { loader.memoryCache?.clear() }) {
                    loader.memoryCache?.let { "${it.size shr 20} MB of ${it.maxSize shr 20} MB" } ?: "none kept"
                }
            }
}
