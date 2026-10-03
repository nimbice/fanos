package io.github.nimbice.fanos.core.data.work

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.nimbice.fanos.core.common.ApplicationScope
import io.github.nimbice.fanos.core.data.repository.SettingsRepository
import io.github.nimbice.fanos.core.database.dao.DownloadDao
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.withIndex
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Runs the download queue as background work: when chapters are queued, from app start while any
 * wait, and again with the new rule when "only on Wi-Fi" changes. The work waits for a connection
 * (an unmetered one when so set), so downloads carry on by themselves once there is one.
 */
@Singleton
class DownloadScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settings: SettingsRepository,
    private val queue: DownloadDao,
    @ApplicationScope private val scope: CoroutineScope,
) {
    private val workManager get() = WorkManager.getInstance(context)

    fun start() {
        scope.launch {
            settings.appSettings.map { it.downloadOnlyOnWifi }.distinctUntilChanged().withIndex().collect { (index, wifiOnly) ->
                if (queue.waitingCount() == 0) return@collect
                // At start, work already waiting stays as it is; after a change it takes the new rule.
                enqueue(wifiOnly, if (index == 0) ExistingWorkPolicy.KEEP else ExistingWorkPolicy.REPLACE)
            }
        }
    }

    /**
     * Makes sure a run will see chapters just queued. Appended rather than kept: a run already
     * ending may have looked at the queue before they were added.
     */
    suspend fun kick() {
        enqueue(settings.appSettings.first().downloadOnlyOnWifi, ExistingWorkPolicy.APPEND_OR_REPLACE)
    }

    fun cancel() {
        workManager.cancelUniqueWork(NAME)
    }

    private fun enqueue(wifiOnly: Boolean, policy: ExistingWorkPolicy) {
        val request =
            OneTimeWorkRequestBuilder<DownloadWorker>()
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(if (wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED)
                        .setRequiresStorageNotLow(true)
                        .build(),
                )
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, BACKOFF_MINUTES, TimeUnit.MINUTES)
                .build()
        workManager.enqueueUniqueWork(NAME, policy, request)
    }

    private companion object {
        const val NAME = "downloads"
        const val BACKOFF_MINUTES = 5L
    }
}
