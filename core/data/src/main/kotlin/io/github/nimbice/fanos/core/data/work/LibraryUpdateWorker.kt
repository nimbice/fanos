package io.github.nimbice.fanos.core.data.work

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import io.github.nimbice.fanos.core.data.R
import io.github.nimbice.fanos.core.data.notification.NewChapterNotifier
import io.github.nimbice.fanos.core.data.repository.LibraryRepository
import io.github.nimbice.fanos.core.data.repository.NovelUpdate
import io.github.nimbice.fanos.core.data.widget.Widgets
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/**
 * Checks the library for new chapters: on a schedule, and right away for pull-to-refresh. The
 * scheduled check posts what it found.
 */
@HiltWorker
class LibraryUpdateWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val library: LibraryRepository,
    private val notifier: NewChapterNotifier,
    private val widgets: Widgets,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val found = mutableListOf<NovelUpdate>()
        try {
            // How it's going is the repository's to tell (LibraryRepository.progress): novel by novel, more than work data holds.
            val result = library.update(onFound = { found += it })
            return Result.success(workDataOf(KEY_NEW_CHAPTERS to result.newChapters, KEY_FAILED to result.failures.size))
        } finally {
            // Also when Android stops the check part way (the connection went, say): the chapters found
            // by then are saved, so no later check will find them new again.
            if (inputData.getBoolean(KEY_NOTIFY, false)) withContext(NonCancellable) { notifier.notify(found.toList()) }
            // The continue-reading widget counts what is unread.
            if (found.isNotEmpty()) widgets.refresh()
        }
    }

    // Expedited work runs as a foreground service before Android 12, which needs a notification.
    override suspend fun getForegroundInfo(): ForegroundInfo {
        val manager = applicationContext.getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL) == null) {
            manager.createNotificationChannel(NotificationChannel(CHANNEL, "Library updates", NotificationManager.IMPORTANCE_LOW))
        }
        val notification =
            NotificationCompat.Builder(applicationContext, CHANNEL)
                .setSmallIcon(R.drawable.ic_notification_update)
                .setContentTitle("Checking for new chapters")
                .setOngoing(true)
                .setSilent(true)
                .build()
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(NOTIFICATION_ID, notification)
        }
    }

    companion object {
        /** Input: post a notification about the new chapters found. */
        const val KEY_NOTIFY = "notify"
        const val KEY_NEW_CHAPTERS = "new_chapters"
        const val KEY_FAILED = "failed"
        private const val CHANNEL = "library_updates"
        private const val NOTIFICATION_ID = 1001
    }
}
