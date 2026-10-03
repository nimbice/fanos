package io.github.nimbice.fanos.core.data.work

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import io.github.nimbice.fanos.core.data.download.Downloader
import io.github.nimbice.fanos.core.data.notification.DownloadNotifier

/**
 * Works through the download queue. It runs as a foreground service with a progress notification
 * where Android allows (started from the app); started in the background (after a library check)
 * it runs as ordinary work, which Android may stop part way, and the queue picks up next time.
 */
@HiltWorker
class DownloadWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val downloader: Downloader,
    private val notifier: DownloadNotifier,
) : CoroutineWorker(context, params) {

    private var foreground = true

    override suspend fun doWork(): Result {
        show(notifier.foregroundInfo(null))
        try {
            val outcome = downloader.run { progress -> show(notifier.foregroundInfo(progress)) }
            notifier.finished(outcome)
            return if (outcome.retryLater) Result.retry() else Result.success()
        } finally {
            if (!foreground) notifier.dismissProgress()
        }
    }

    override suspend fun getForegroundInfo(): ForegroundInfo = notifier.foregroundInfo(null)

    private suspend fun show(info: ForegroundInfo) {
        if (foreground) {
            try {
                setForeground(info)
                return
            } catch (e: IllegalStateException) {
                // Android 12 and later won't start a foreground service from the background.
                foreground = false
            }
        }
        notifier.showProgress(info)
    }
}
