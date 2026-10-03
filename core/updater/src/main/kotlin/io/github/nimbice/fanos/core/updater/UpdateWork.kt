package io.github.nimbice.fanos.core.updater

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.nimbice.fanos.core.common.ApplicationScope
import io.github.nimbice.fanos.core.datastore.SettingsDataSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Looks for new builds while "Check for updates automatically" is on: daily in the background, and as the
 * app opens when the last look was a while ago.
 */
@Singleton
class UpdateScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settings: SettingsDataSource,
    private val updater: Updater,
    private val checks: Set<@JvmSuppressWildcards BackgroundCheck>,
    @ApplicationScope private val scope: CoroutineScope,
) {
    fun start() {
        scope.launch {
            updater.restore()
            settings.appSettings.map { it.checkForUpdates }.distinctUntilChanged().collect { automatic ->
                schedule(automatic)
                if (automatic && updater.checkDue()) {
                    updater.check(quietly = true)
                    checks.runAll()
                }
            }
        }
    }

    private fun schedule(automatic: Boolean) {
        val workManager = WorkManager.getInstance(context)
        if (!automatic) {
            workManager.cancelUniqueWork(PERIODIC)
            return
        }
        val request =
            PeriodicWorkRequestBuilder<UpdateCheckWorker>(1, TimeUnit.DAYS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).setRequiresBatteryNotLow(true).build())
                .build()
        workManager.enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.KEEP, request)
    }

    private companion object {
        const val PERIODIC = "app-update-check"
    }
}

@HiltWorker
class UpdateCheckWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val updater: Updater,
    private val checks: Set<@JvmSuppressWildcards BackgroundCheck>,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        updater.check(quietly = true)
        checks.runAll()
        return Result.success()
    }
}

/** Runs every check; one that fails doesn't stop the others, and is tried again at the next check. */
private suspend fun Set<BackgroundCheck>.runAll() {
    for (check in this) {
        try {
            check.check()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            continue
        }
    }
}

/** Fetches and installs a build once the reader chose to, carrying on if they leave the app meanwhile. */
@HiltWorker
class UpdateInstallWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val updater: Updater,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = if (updater.downloadAndInstall()) Result.success() else Result.failure()
}
