package io.github.nimbice.fanos.core.updater

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The updater's notifications: a build is ready (with Install and Later), Android wants the reader to
 * confirm, the app was updated, or an update failed while the app wasn't in front to say so.
 */
@Singleton
class UpdateNotifier @Inject constructor(@ApplicationContext private val context: Context) {
    private val manager: NotificationManager = context.getSystemService(NotificationManager::class.java)

    fun available(update: AvailableUpdate) {
        val install = broadcast(UpdateActionReceiver.INSTALL, REQUEST_INSTALL)
        val later = broadcast(UpdateActionReceiver.LATER, REQUEST_LATER)
        post(
            AVAILABLE,
            builder("Update ready: ${update.versionName}", update.notes.lineSequence().firstOrNull { it.isNotBlank() } ?: "Tap Install to update Fanos.")
                .setStyle(NotificationCompat.BigTextStyle().bigText(update.notes.ifBlank { "Tap Install to update Fanos." }))
                .addAction(0, "Install", install)
                .addAction(0, "Later", later)
                .setContentIntent(openUpdates()),
        )
    }

    fun dismissAvailable() = manager.cancel(AVAILABLE)

    /** Newer versions of installed extensions are out: tapping this opens the extensions, to update them. */
    fun extensionUpdates(names: List<String>) {
        val intent = context.packageManager.getLaunchIntentForPackage(context.packageName) ?: return
        intent.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP).putExtra(EXTRA_EXTENSIONS, true)
        val open = PendingIntent.getActivity(context, REQUEST_EXTENSIONS, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val them = if (names.size == 1) "it" else "them"
        post(EXTENSIONS, builder("Extension updates: ${names.joinToString(", ")}", "Tap to update $them in Browse › Extensions").setContentIntent(open))
    }

    fun dismissExtensionUpdates() = manager.cancel(EXTENSIONS)

    /** Android wants the reader to confirm the update: tapping this shows its question. */
    fun confirm(versionName: String, confirmation: Intent) {
        val tap = PendingIntent.getActivity(context, REQUEST_CONFIRM, confirmation, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        post(RESULT, builder("Update ready to install", "Tap to install $versionName").setContentIntent(tap))
    }

    fun updated(versionName: String) {
        post(RESULT, builder("Fanos updated", "Now running $versionName").setContentIntent(openUpdates()))
    }

    /** Said here only when the app isn't in front: in front, the settings show it. */
    fun failed(message: String) {
        if (ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) return
        post(RESULT, builder("Update failed", message).setStyle(NotificationCompat.BigTextStyle().bigText(message)).setContentIntent(openUpdates()))
    }

    private fun post(id: Int, builder: NotificationCompat.Builder) {
        if (manager.areNotificationsEnabled()) manager.notify(id, builder.build())
    }

    private fun builder(title: String, text: String): NotificationCompat.Builder =
        NotificationCompat.Builder(context, channel().id)
            .setSmallIcon(R.drawable.ic_notification_app_update)
            .setColor(ACCENT)
            .setContentTitle(title)
            .setContentText(text)
            .setAutoCancel(true)

    private fun channel(): NotificationChannel =
        manager.getNotificationChannel(CHANNEL)
            ?: NotificationChannel(CHANNEL, "App updates", NotificationManager.IMPORTANCE_DEFAULT)
                .apply { description = "When a new build of Fanos is ready" }
                .also(manager::createNotificationChannel)

    private fun broadcast(action: String, request: Int): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            request,
            Intent(context, UpdateActionReceiver::class.java).setAction(action),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    /** Opens the app on its settings, where updates are. */
    private fun openUpdates(): PendingIntent? {
        val intent = context.packageManager.getLaunchIntentForPackage(context.packageName) ?: return null
        intent.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP).putExtra(EXTRA_UPDATES, true)
        return PendingIntent.getActivity(context, REQUEST_OPEN, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    companion object {
        /** On the intent a notification opens the app with: show the updates, in the settings. */
        const val EXTRA_UPDATES = "io.github.nimbice.fanos.UPDATES"

        /** On the intent a notification opens the app with: show the extensions, which have updates. */
        const val EXTRA_EXTENSIONS = "io.github.nimbice.fanos.EXTENSIONS"

        private const val CHANNEL = "app_updates"
        private const val AVAILABLE = 4001
        private const val RESULT = 4002
        private const val EXTENSIONS = 4003

        // Apart from the new-chapter notifications' request codes, which are novel ids.
        private const val REQUEST_INSTALL = -41
        private const val REQUEST_LATER = -42
        private const val REQUEST_CONFIRM = -43
        private const val REQUEST_OPEN = -44
        private const val REQUEST_EXTENSIONS = -45
        private const val ACCENT = 0xFF7A5A00.toInt()
    }
}

/** The update notification's buttons. */
@AndroidEntryPoint
class UpdateActionReceiver : BroadcastReceiver() {
    @Inject lateinit var updater: Updater

    @Inject lateinit var notifier: UpdateNotifier

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            INSTALL -> updater.install()
            LATER -> notifier.dismissAvailable()
        }
    }

    internal companion object {
        const val INSTALL = "io.github.nimbice.fanos.update.INSTALL"
        const val LATER = "io.github.nimbice.fanos.update.LATER"
    }
}
