package io.github.nimbice.fanos.core.data.notification

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.ForegroundInfo
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.nimbice.fanos.core.common.ApplicationScope
import io.github.nimbice.fanos.core.data.R
import io.github.nimbice.fanos.core.data.download.DownloadOutcome
import io.github.nimbice.fanos.core.data.download.DownloadProgress
import io.github.nimbice.fanos.core.data.download.DownloadRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The downloads' notifications: progress while chapters come in, with a way to cancel, and a word
 * afterwards about chapters that couldn't be saved. A run that saved everything leaves nothing
 * behind: the chapters show as saved in their novels. Either opens the Downloads tab.
 */
@Singleton
class DownloadNotifier @Inject constructor(@ApplicationContext private val context: Context) {
    private val manager: NotificationManager = context.getSystemService(NotificationManager::class.java)

    fun foregroundInfo(progress: DownloadProgress?): ForegroundInfo {
        val notification = progressNotification(progress)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(PROGRESS, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(PROGRESS, notification)
        }
    }

    /** Shows progress as a plain notification, for a run Android didn't let into the foreground. */
    fun showProgress(info: ForegroundInfo) {
        if (manager.areNotificationsEnabled()) manager.notify(PROGRESS, info.notification)
    }

    fun dismissProgress() = manager.cancel(PROGRESS)

    fun finished(outcome: DownloadOutcome) {
        val failed = outcome.failed
        if (failed.isEmpty() || !manager.areNotificationsEnabled()) return
        val title = if (failed.size == 1) "A chapter wasn't downloaded" else "${failed.size} chapters weren't downloaded"
        val lines = failed.take(MAX_LINES).map { "${it.novelTitle} · ${it.chapterTitle}: ${it.reason}" }
        val notification =
            NotificationCompat.Builder(context, channel().id)
                .setSmallIcon(R.drawable.ic_notification_lamp)
                .setColor(ACCENT)
                .setContentTitle(title)
                .setContentText(failed.first().reason)
                .setStyle(NotificationCompat.InboxStyle().also { inbox -> lines.forEach(inbox::addLine) })
                .setContentIntent(openDownloads())
                .setAutoCancel(true)
                .build()
        manager.notify(FAILED, notification)
    }

    private fun progressNotification(progress: DownloadProgress?) =
        NotificationCompat.Builder(context, channel().id)
            .setSmallIcon(R.drawable.ic_notification_lamp)
            .setColor(ACCENT)
            .setContentTitle(if (progress == null || progress.left == 0) "Downloading chapters" else "Downloading chapters · ${progress.left} left")
            .setContentText(progress?.latest?.let { "${it.novelTitle} · ${it.chapterTitle}" })
            .setProgress(
                (progress?.let { it.done + it.left } ?: 0),
                progress?.done ?: 0,
                progress == null || progress.done + progress.left == 0,
            )
            .setContentIntent(openDownloads())
            .setOngoing(true)
            .setSilent(true)
            .setOnlyAlertOnce(true)
            .addAction(0, "Cancel", cancel())
            .build()

    private fun channel(): NotificationChannel =
        manager.getNotificationChannel(CHANNEL)
            ?: NotificationChannel(CHANNEL, "Downloads", NotificationManager.IMPORTANCE_LOW)
                .apply { description = "Chapters being saved for reading offline" }
                .also(manager::createNotificationChannel)

    private fun cancel(): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            0,
            Intent(context, DownloadCancelReceiver::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    /** Opens the Downloads tab, where what's downloading and what failed shows, with Try again. */
    private fun openDownloads(): PendingIntent? {
        val intent = context.packageManager.getLaunchIntentForPackage(context.packageName) ?: return null
        intent.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP).putExtra(EXTRA_DOWNLOADS, true)
        return PendingIntent.getActivity(context, REQUEST_OPEN, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    companion object {
        /** On the intent the notifications open the app with: show the Downloads tab. */
        const val EXTRA_DOWNLOADS = "io.github.nimbice.fanos.DOWNLOADS"

        private const val CHANNEL = "downloads"
        private const val PROGRESS = 4001
        private const val FAILED = 4002
        private const val MAX_LINES = 5

        // Apart from the other notifications' codes: novel ids, and the backup's -1.
        private const val REQUEST_OPEN = -2
        private const val ACCENT = 0xFF7A5A00.toInt()
    }
}

/** The progress notification's Cancel: empties the queue and stops the run. */
@AndroidEntryPoint
class DownloadCancelReceiver : BroadcastReceiver() {
    @Inject lateinit var downloads: DownloadRepository

    @Inject @ApplicationScope
    lateinit var scope: CoroutineScope

    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        scope.launch {
            try {
                downloads.cancelAll()
            } finally {
                pending.finish()
            }
        }
    }
}
