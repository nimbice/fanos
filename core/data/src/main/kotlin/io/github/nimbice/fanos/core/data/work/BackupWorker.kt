package io.github.nimbice.fanos.core.data.work

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import io.github.nimbice.fanos.core.common.suspendRunCatching
import io.github.nimbice.fanos.core.data.backup.BackupRepository
import io.github.nimbice.fanos.core.data.notification.BackupNotifier
import kotlinx.coroutines.flow.first

/** The automatic backup: into the backup folder, telling the reader when it fails. */
@HiltWorker
class BackupWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val backups: BackupRepository,
    private val notifier: BackupNotifier,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        if (backups.status.first().folder == null && !backups.hasDefaultFolder) return Result.success()
        return suspendRunCatching { backups.backUpToFolderIfDue() }.fold(
            onSuccess = { Result.success() },
            onFailure = { error ->
                notifier.failed(error.message ?: "The backup couldn't be written.")
                // The next scheduled run tries again; retrying sooner won't bring a folder back.
                Result.failure()
            },
        )
    }
}
