package io.github.nimbice.fanos.core.data.notification

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.nimbice.fanos.core.common.AppIdentity
import io.github.nimbice.fanos.core.data.R
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Test builds: says when something went wrong that the app got over by itself (memory running high, the app closed for
 * not responding), which otherwise nobody would know of, so the phone can be connected for a look at its logs. Quiet,
 * on a channel of its own; tapping it opens About, which has the details to share.
 */
@Singleton
class DiagnosticsNotifier @Inject constructor(
    @ApplicationContext private val context: Context,
    identity: AppIdentity,
) {
    private val manager: NotificationManager = context.getSystemService(NotificationManager::class.java)
    private val testBuild = "-test" in identity.versionName

    /** Tells of [title], with [text]; one of the same [kind] already showing is replaced. */
    fun show(kind: Kind, title: String, text: String) {
        if (!testBuild || !manager.areNotificationsEnabled()) return
        val body = "$text Connect the phone for a log scan."
        val notification =
            NotificationCompat.Builder(context, channel().id)
                .setSmallIcon(R.drawable.ic_notification_lamp)
                .setColor(ACCENT)
                .setContentTitle(title)
                .setContentText(body)
                .setStyle(NotificationCompat.BigTextStyle().bigText(body))
                .setContentIntent(openAbout())
                .setOnlyAlertOnce(true)
                .setAutoCancel(true)
                .build()
        manager.notify(kind.id, notification)
    }

    /** What's told of: each its own notification. */
    enum class Kind(val id: Int) { Memory(5001), Closed(5002) }

    private fun channel(): NotificationChannel =
        manager.getNotificationChannel(CHANNEL)
            ?: NotificationChannel(CHANNEL, "Diagnostics", NotificationManager.IMPORTANCE_LOW)
                .apply { description = "Test builds: problems Fanos got over by itself, worth a look at its logs" }
                .also(manager::createNotificationChannel)

    private fun openAbout(): PendingIntent? {
        val intent = context.packageManager.getLaunchIntentForPackage(context.packageName) ?: return null
        intent.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP).putExtra(EXTRA_ABOUT, true)
        return PendingIntent.getActivity(context, REQUEST_ABOUT, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    companion object {
        /** On the intent the notification opens the app with: show About. */
        const val EXTRA_ABOUT = "io.github.nimbice.fanos.ABOUT"

        private const val CHANNEL = "diagnostics"

        // Apart from the new-chapter notifications' codes, which are novel ids, and the backups' settings request.
        private const val REQUEST_ABOUT = -2
        private const val ACCENT = 0xFF7A5A00.toInt()
    }
}
