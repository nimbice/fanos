package io.github.nimbice.fanos.core.data.notification

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationCompat
import androidx.core.os.bundleOf
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.nimbice.fanos.core.data.R
import io.github.nimbice.fanos.core.data.repository.NovelUpdate
import io.github.nimbice.fanos.core.data.repository.SettingsRepository
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton
import android.content.BroadcastReceiver
import dagger.hilt.android.AndroidEntryPoint
import io.github.nimbice.fanos.core.common.ApplicationScope
import io.github.nimbice.fanos.core.data.download.DownloadRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Tells the reader about the new chapters a background check found: a notification for each novel,
 * which opens that novel, gathered under a summary, which opens the Updates tab. A novel's notification
 * has Read, which opens the first new chapter, and Download, which saves them (when they aren't saved
 * anyway). One still showing when more chapters come is added to rather than replaced, and it goes once
 * the novel is opened, or they're downloaded from it.
 */
@Singleton
class NewChapterNotifier @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settings: SettingsRepository,
) {
    private val manager: NotificationManager = context.getSystemService(NotificationManager::class.java)
    private val lock = Mutex()

    /** Whether Android lets the notifications through: allowed for the app, and their channel not turned off. */
    fun canNotify(): Boolean = manager.areNotificationsEnabled() && channel().importance != NotificationManager.IMPORTANCE_NONE

    /** Android's settings page where the notifications can be let through. */
    fun settingsIntent(): Intent =
        if (manager.areNotificationsEnabled()) {
            Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_CHANNEL_ID, channel().id)
        } else {
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
        }.putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)

    suspend fun notify(updates: List<NovelUpdate>) {
        val app = settings.appSettings.first()
        if (updates.isEmpty() || !app.newChapterNotifications || !canNotify()) return
        lock.withLock {
            val shown = shown(manager.activeNotifications)
            updates.forEach { update ->
                val before = shown[update.novelId]
                val now = Shown(update.title, before?.chapters.orEmpty() + update.chapters, before?.ids.orEmpty() + update.chapterIds)
                shown[update.novelId] = now
                manager.notify(tag(update.novelId), NOVEL, novelNotification(update.novelId, now, downloading = app.downloadNewChapters))
                // Android drops a notification that updates another too soon after the last.
                delay(POST_GAP_MS)
            }
            manager.notify(SUMMARY, summary(shown, silent = false))
        }
    }

    /** Takes down the notification for [novelId], which the reader has opened, and brings the summary in line. */
    suspend fun dismiss(novelId: Long) {
        lock.withLock {
            val active = manager.activeNotifications
            val shown = shown(active)
            if (shown.remove(novelId) != null) manager.cancel(tag(novelId), NOVEL)
            // A tapped notification takes itself down before the novel opens, so the summary can be
            // behind even when there was nothing left to cancel here.
            val summary = active.firstOrNull { it.id == SUMMARY && it.tag == null } ?: return
            val summarised = summary.notification.extras.getLongArray(EXTRA_NOVELS)?.toSet().orEmpty()
            when {
                shown.isEmpty() -> manager.cancel(SUMMARY)
                summarised != shown.keys -> manager.notify(SUMMARY, summary(shown, silent = true))
            }
        }
    }

    private data class Shown(val title: String, val chapters: List<String>, val ids: List<Long> = emptyList())

    /** The novels whose notifications are among [active], oldest first. */
    private fun shown(active: Array<StatusBarNotification>): MutableMap<Long, Shown> =
        active
            .filter { it.id == NOVEL && it.tag?.startsWith(TAG_PREFIX) == true }
            .sortedBy { it.postTime }
            .mapNotNull { notice ->
                val novelId = notice.tag.removePrefix(TAG_PREFIX).toLongOrNull() ?: return@mapNotNull null
                val extras = notice.notification.extras
                novelId to
                    Shown(
                        extras.getString(EXTRA_TITLE).orEmpty(),
                        extras.getStringArrayList(EXTRA_CHAPTERS).orEmpty(),
                        extras.getLongArray(EXTRA_CHAPTER_IDS)?.toList().orEmpty(),
                    )
            }.toMap(LinkedHashMap())

    private fun novelNotification(novelId: Long, shown: Shown, downloading: Boolean): Notification {
        val (title, chapters, ids) = shown
        val style =
            if (chapters.size == 1) {
                NotificationCompat.BigTextStyle().bigText(chapters.single())
            } else {
                NotificationCompat.InboxStyle().also { inbox ->
                    chapters.take(MAX_LINES).forEach(inbox::addLine)
                    if (chapters.size > MAX_LINES) inbox.setSummaryText("+${chapters.size - MAX_LINES} more")
                }
            }
        val notice =
            builder(openNovel(novelId))
                .setContentTitle(title)
                .setContentText(chapters.singleOrNull() ?: newChapters(chapters.size))
                .setStyle(style)
                // The summary makes the sound, once for the lot.
                .setGroupAlertBehavior(NotificationCompat.GROUP_ALERT_SUMMARY)
                .addExtras(bundleOf(EXTRA_TITLE to title, EXTRA_CHAPTERS to ArrayList(chapters), EXTRA_CHAPTER_IDS to ids.toLongArray()))
        // Read: the first of them, in the reader. Download: all of them, unless new chapters are downloaded anyway.
        ids.firstOrNull()?.let { first -> readChapter(novelId, first)?.let { notice.addAction(0, "Read", it) } }
        if (ids.isNotEmpty() && !downloading) notice.addAction(0, "Download", downloadChapters(novelId, ids))
        return notice.build()
    }

    private fun summary(shown: Map<Long, Shown>, silent: Boolean): Notification {
        val inbox = NotificationCompat.InboxStyle()
        shown.values.forEach { inbox.addLine("${it.title} · ${newChapters(it.chapters.size)}") }
        return builder(openUpdates())
            .setContentTitle(newChapters(shown.values.sumOf { it.chapters.size }))
            .setContentText(shown.values.joinToString { it.title })
            .setStyle(inbox)
            .setGroupSummary(true)
            .setSilent(silent)
            .addExtras(bundleOf(EXTRA_NOVELS to shown.keys.toLongArray()))
            .build()
    }

    private fun builder(onTap: PendingIntent?): NotificationCompat.Builder =
        NotificationCompat.Builder(context, channel().id)
            .setSmallIcon(R.drawable.ic_notification_lamp)
            .setColor(ACCENT)
            .setGroup(GROUP)
            .setContentIntent(onTap)
            .setAutoCancel(true)

    private fun channel(): NotificationChannel =
        manager.getNotificationChannel(CHANNEL)
            ?: NotificationChannel(CHANNEL, "New chapters", NotificationManager.IMPORTANCE_DEFAULT)
                .apply { description = "When a background check finds new chapters in the library" }
                .also(manager::createNotificationChannel)

    private fun openUpdates(): PendingIntent? = open(requestCode = 0) { putExtra(EXTRA_NEW_CHAPTERS, true) }

    // One request code per novel, so each notification keeps its own novel: pending intents that
    // differ only in their extras are the same one.
    private fun openNovel(novelId: Long): PendingIntent? = open(requestCode = novelId.toInt()) { putExtra(EXTRA_NOVEL_ID, novelId) }

    // Its own action, so it isn't the same pending intent as the novel's (they differ only in extras otherwise).
    private fun readChapter(novelId: Long, chapterId: Long): PendingIntent? =
        open(requestCode = novelId.toInt()) {
            action = ACTION_READ
            putExtra(EXTRA_NOVEL_ID, novelId)
            putExtra(EXTRA_CHAPTER_ID, chapterId)
        }

    private fun downloadChapters(novelId: Long, ids: List<Long>): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            novelId.toInt(),
            Intent(context, NewChapterActionReceiver::class.java)
                .setAction(ACTION_DOWNLOAD)
                .putExtra(EXTRA_NOVEL_ID, novelId)
                .putExtra(EXTRA_CHAPTER_IDS, ids.toLongArray()),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    private fun open(requestCode: Int, where: Intent.() -> Unit): PendingIntent? {
        val intent = context.packageManager.getLaunchIntentForPackage(context.packageName) ?: return null
        // Single top: an app that is open takes the tap in onNewIntent, rather than stacking a second copy of itself.
        intent.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP).where()
        return PendingIntent.getActivity(context, requestCode, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    private fun tag(novelId: Long) = "$TAG_PREFIX$novelId"

    private fun newChapters(count: Int) = if (count == 1) "1 new chapter" else "$count new chapters"

    companion object {
        /** On the intent a novel's notification opens the app with: the novel to show. */
        const val EXTRA_NOVEL_ID = "io.github.nimbice.fanos.NOVEL_ID"

        /** Beside [EXTRA_NOVEL_ID], on an intent to read (from the widget, the icon's shortcuts, listening): the chapter. */
        const val EXTRA_CHAPTER_ID = "io.github.nimbice.fanos.CHAPTER_ID"

        /** On the intent the summary opens the app with: show the new chapters, in the Updates tab. */
        const val EXTRA_NEW_CHAPTERS = "io.github.nimbice.fanos.NEW_CHAPTERS"

        private const val CHANNEL = "new_chapters"
        private const val GROUP = "new_chapters"
        private const val TAG_PREFIX = "novel:"
        private const val NOVEL = 2001
        private const val SUMMARY = 2000
        internal const val EXTRA_CHAPTER_IDS = "io.github.nimbice.fanos.CHAPTER_IDS"
        internal const val ACTION_DOWNLOAD = "io.github.nimbice.fanos.DOWNLOAD_NEW_CHAPTERS"
        private const val ACTION_READ = "io.github.nimbice.fanos.READ_NEW_CHAPTER"
        private const val EXTRA_TITLE = "io.github.nimbice.fanos.NOVEL_TITLE"
        private const val EXTRA_CHAPTERS = "io.github.nimbice.fanos.CHAPTERS"
        private const val EXTRA_NOVELS = "io.github.nimbice.fanos.NOVELS"
        private const val MAX_LINES = 5
        private const val POST_GAP_MS = 250L

        /** The app's amber, as the light theme's primary colour. */
        private const val ACCENT = 0xFF7A5A00.toInt()
    }
}

/** A new-chapter notification's Download: saves the novel's new chapters, and the notification goes. */
@AndroidEntryPoint
class NewChapterActionReceiver : BroadcastReceiver() {
    @Inject lateinit var downloads: DownloadRepository

    @Inject lateinit var notifier: NewChapterNotifier

    @Inject @ApplicationScope
    lateinit var scope: CoroutineScope

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != NewChapterNotifier.ACTION_DOWNLOAD) return
        val novelId = intent.getLongExtra(NewChapterNotifier.EXTRA_NOVEL_ID, 0L)
        val ids = intent.getLongArrayExtra(NewChapterNotifier.EXTRA_CHAPTER_IDS)?.toList().orEmpty()
        if (novelId <= 0 || ids.isEmpty()) return
        val pending = goAsync()
        scope.launch {
            try {
                downloads.download(novelId, ids)
                notifier.dismiss(novelId)
            } finally {
                pending.finish()
            }
        }
    }
}
