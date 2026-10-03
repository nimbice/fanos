package io.github.nimbice.fanos.core.data.work

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.nimbice.fanos.core.common.ApplicationScope
import io.github.nimbice.fanos.core.data.backup.BackupRepository
import io.github.nimbice.fanos.core.data.repository.SettingsRepository
import io.github.nimbice.fanos.core.model.BackupInterval
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Keeps automatic backups on the interval the settings ask for, while there is a folder to back up to:
 * the one picked, or the default one (Android 10 and later), so backups run from the start.
 */
@Singleton
class BackupScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settings: SettingsRepository,
    private val backups: BackupRepository,
    @ApplicationScope private val scope: CoroutineScope,
) {
    private val workManager get() = WorkManager.getInstance(context)

    fun start() {
        scope.launch {
            combine(settings.appSettings, backups.status) { app, status -> app.backupInterval.takeIf { status.folder != null || backups.hasDefaultFolder } }
                .distinctUntilChanged()
                .collect(::schedule)
        }
    }

    /** Backs up every [interval], or not at all when it's null (no folder) or off. */
    private fun schedule(interval: BackupInterval?) {
        if (interval == null || interval == BackupInterval.Off) {
            workManager.cancelUniqueWork(PERIODIC)
            return
        }
        val request =
            PeriodicWorkRequestBuilder<BackupWorker>(interval.hours.toLong(), TimeUnit.HOURS)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiresBatteryNotLow(true)
                        .setRequiresStorageNotLow(true)
                        .build(),
                )
                .build()
        workManager.enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.UPDATE, request)
    }

    private companion object {
        const val PERIODIC = "backup"
    }
}
