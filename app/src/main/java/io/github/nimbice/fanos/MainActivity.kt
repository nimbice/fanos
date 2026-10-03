package io.github.nimbice.fanos

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dagger.hilt.android.AndroidEntryPoint
import io.github.nimbice.fanos.core.data.notification.BackupNotifier
import io.github.nimbice.fanos.core.data.notification.DownloadNotifier
import io.github.nimbice.fanos.core.data.notification.NewChapterNotifier
import io.github.nimbice.fanos.core.data.repository.SettingsRepository
import io.github.nimbice.fanos.core.data.widget.Widgets
import io.github.nimbice.fanos.core.designsystem.theme.FanosTheme
import io.github.nimbice.fanos.core.model.AppSettings
import io.github.nimbice.fanos.core.model.ThemeMode
import io.github.nimbice.fanos.core.updater.UpdateNotifier
import io.github.nimbice.fanos.ui.AppLink
import io.github.nimbice.fanos.ui.FanosApp
import io.github.nimbice.fanos.widget.ReadingShortcuts
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import javax.inject.Inject
import io.github.nimbice.fanos.core.model.ReaderSettings
import io.github.nimbice.fanos.core.model.darkPages
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject lateinit var settings: SettingsRepository

    @Inject lateinit var widgets: Widgets

    @Inject lateinit var shortcuts: ReadingShortcuts

    @Inject lateinit var newChapters: NewChapterNotifier

    /** Places to show, from taps on notifications and the widget. */
    private val links = Channel<AppLink>(Channel.CONFLATED)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Recreated (turned, or brought back after Android closed it), the app is already where the tap took it.
        if (savedInstanceState == null) openFrom(intent)
        enableEdgeToEdge()
        setContent {
            val app by settings.appSettings.collectAsStateWithLifecycle(initialValue = AppSettings())
            val reader by settings.readerSettings.collectAsStateWithLifecycle(initialValue = ReaderSettings())
            // Like the reader: dark while its pages are, light while they're light.
            val mode = if (app.themeMode == ThemeMode.Reader) (if (reader.darkPages()) ThemeMode.Dark else ThemeMode.Light) else app.themeMode
            val dark =
                when (mode) {
                    ThemeMode.System, ThemeMode.Reader -> isSystemInDarkTheme()
                    ThemeMode.Light -> false
                    ThemeMode.Dark -> true
                }
            // Status bar icons follow the app's theme rather than the system's.
            LaunchedEffect(dark) {
                val style =
                    if (dark) {
                        SystemBarStyle.dark(Color.TRANSPARENT)
                    } else {
                        SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
                    }
                enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
            }
            FanosTheme(themeMode = mode, dynamicColor = app.dynamicColor) {
                FanosApp(links = links.receiveAsFlow())
            }
        }
    }

    // Leaving the app is when what was read shows on the home screen: in the widget, and the icon's shortcuts.
    override fun onStop() {
        super.onStop()
        if (!isChangingConfigurations) {
            widgets.refresh()
            shortcuts.refresh()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        openFrom(intent)
    }

    private fun openFrom(intent: Intent) {
        // Opened again from recents, the intent is still the notification's, but that tap was answered.
        if (intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY != 0) return
        val novelId = intent.getLongExtra(NewChapterNotifier.EXTRA_NOVEL_ID, 0L)
        val chapterId = intent.getLongExtra(NewChapterNotifier.EXTRA_CHAPTER_ID, 0L)
        // A novel opened from its new-chapter notification's Read, say: the notification has done its job.
        if (novelId > 0) lifecycleScope.launch { newChapters.dismiss(novelId) }
        when {
            novelId > 0 && chapterId > 0 -> links.trySend(AppLink.Chapter(novelId, chapterId))
            novelId > 0 -> links.trySend(AppLink.Novel(novelId))
            intent.getBooleanExtra(NewChapterNotifier.EXTRA_NEW_CHAPTERS, false) -> links.trySend(AppLink.NewChapters)
            intent.getBooleanExtra(DownloadNotifier.EXTRA_DOWNLOADS, false) -> links.trySend(AppLink.Downloads)
            intent.getBooleanExtra(BackupNotifier.EXTRA_SETTINGS, false) -> links.trySend(AppLink.Backup)
            intent.getBooleanExtra(UpdateNotifier.EXTRA_UPDATES, false) -> links.trySend(AppLink.AppUpdates)
            intent.getBooleanExtra(UpdateNotifier.EXTRA_EXTENSIONS, false) -> links.trySend(AppLink.Extensions)
        }
    }
}
