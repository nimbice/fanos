package io.github.nimbice.fanos.core.data.notification

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.nimbice.fanos.core.data.R
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Says when an automatic backup fails, since nobody watches them: a backup that quietly stopped
 * working is found out only when it's needed. Tapping it opens the settings, where the backup
 * folder can be chosen again.
 */
@Singleton
class BackupNotifier @Inject constructor(@ApplicationContext private val context: Context) {
    private val manager: NotificationManager = context.getSystemService(NotificationManager::class.java)

    fun failed(reason: String) {
        if (!manager.areNotificationsEnabled()) return
        val notification =
            NotificationCompat.Builder(context, channel().id)
                .setSmallIcon(R.drawable.ic_notification_lamp)
                .setColor(ACCENT)
                .setContentTitle("Automatic backup failed")
                .setContentText(reason)
                .setStyle(NotificationCompat.BigTextStyle().bigText(reason))
                .setContentIntent(openSettings())
                .setAutoCancel(true)
                .build()
        manager.notify(FAILED, notification)
    }

    fun dismissFailure() = manager.cancel(FAILED)

    private fun channel(): NotificationChannel =
        manager.getNotificationChannel(CHANNEL)
            ?: NotificationChannel(CHANNEL, "Backups", NotificationManager.IMPORTANCE_LOW)
                .apply { description = "When an automatic backup fails" }
                .also(manager::createNotificationChannel)

    private fun openSettings(): PendingIntent? {
        val intent = context.packageManager.getLaunchIntentForPackage(context.packageName) ?: return null
        intent.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP).putExtra(EXTRA_SETTINGS, true)
        return PendingIntent.getActivity(context, REQUEST_SETTINGS, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    companion object {
        /** On the intent the notification opens the app with: show the settings. */
        const val EXTRA_SETTINGS = "io.github.nimbice.fanos.SETTINGS"

        private const val CHANNEL = "backups"
        private const val FAILED = 3001

        // Apart from the new-chapter notifications' codes, which are novel ids.
        private const val REQUEST_SETTINGS = -1
        private const val ACCENT = 0xFF7A5A00.toInt()
    }
}
