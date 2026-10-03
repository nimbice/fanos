package io.github.nimbice.fanos.core.data.work

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.nimbice.fanos.core.common.ApplicationScope
import io.github.nimbice.fanos.core.data.repository.SettingsRepository
import io.github.nimbice.fanos.core.model.LibraryUpdateProgress
import io.github.nimbice.fanos.core.model.NovelCheck
import io.github.nimbice.fanos.core.model.UpdateInterval
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/** Schedules library updates: in the background as often as the settings say, and right away on request. */
@Singleton
class LibraryUpdateScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settings: SettingsRepository,
    @ApplicationScope private val scope: CoroutineScope,
) {

    private val workManager get() = WorkManager.getInstance(context)

    /**
     * Keeps the background check on the interval the settings ask for, from app start on: the old app
     * only scheduled it when the setting was toggled, so fresh installs never checked for chapters.
     */
    fun start() {
        scope.launch {
            settings.appSettings.map { it.libraryUpdateInterval }.distinctUntilChanged().collect(::schedulePeriodic)
        }
    }

    private fun schedulePeriodic(interval: UpdateInterval) {
        if (interval == UpdateInterval.Off) {
            workManager.cancelUniqueWork(PERIODIC)
            return
        }
        val request =
            PeriodicWorkRequestBuilder<LibraryUpdateWorker>(interval.hours.toLong(), TimeUnit.HOURS)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .setRequiresBatteryNotLow(true)
                        .build(),
                )
                // What a background check finds is news; a check the reader started, they are watching.
                .setInputData(workDataOf(LibraryUpdateWorker.KEY_NOTIFY to true))
                .build()
        workManager.enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.UPDATE, request)
    }

    /** Checks now; does nothing if a manual update is already queued or running. */
    fun updateNow() {
        val request =
            OneTimeWorkRequestBuilder<LibraryUpdateWorker>()
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
        workManager.enqueueUniqueWork(MANUAL, ExistingWorkPolicy.KEEP, request)
    }

    /** How the running update (manual or scheduled) is going, or null when none is running. */
    val progress: Flow<LibraryUpdateProgress?> =
        running().map { work ->
            work ?: return@map null
            val data = work.progress
            val titles = data.getStringArray(LibraryUpdateWorker.KEY_CHECKING).orEmpty()
            val since = data.getLongArray(LibraryUpdateWorker.KEY_CHECKING_SINCE) ?: LongArray(0)
            LibraryUpdateProgress(
                done = data.getInt(LibraryUpdateWorker.KEY_DONE, 0),
                total = data.getInt(LibraryUpdateWorker.KEY_TOTAL, 0),
                checking = titles.zip(since.toList()) { title, startedAt -> NovelCheck(title, startedAt) },
            )
        }.distinctUntilChanged()

    private fun running(): Flow<WorkInfo?> =
        combine(
            workManager.getWorkInfosForUniqueWorkFlow(MANUAL),
            workManager.getWorkInfosForUniqueWorkFlow(PERIODIC),
        ) { manual, periodic -> (manual + periodic).firstOrNull { it.state == WorkInfo.State.RUNNING } }

    private companion object {
        const val PERIODIC = "library-update"
        const val MANUAL = "library-update-now"
    }
}
