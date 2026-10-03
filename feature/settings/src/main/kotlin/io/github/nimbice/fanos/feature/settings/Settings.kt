package io.github.nimbice.fanos.feature.settings

import android.content.Context
import android.os.Build
import android.text.format.Formatter
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import androidx.navigation.toRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.nimbice.fanos.core.data.download.DownloadRepository
import io.github.nimbice.fanos.core.data.download.DownloadStorage
import io.github.nimbice.fanos.core.data.notification.NewChapterNotifier
import io.github.nimbice.fanos.core.data.repository.SettingsRepository
import io.github.nimbice.fanos.core.designsystem.component.countOf
import io.github.nimbice.fanos.core.model.AppSettings
import io.github.nimbice.fanos.core.model.BackupInterval
import io.github.nimbice.fanos.core.model.ReaderSettings
import io.github.nimbice.fanos.core.model.ThemeMode
import io.github.nimbice.fanos.core.model.UpdateInterval
import io.github.nimbice.fanos.core.updater.UpdateState
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import javax.inject.Inject
import io.github.nimbice.fanos.core.network.SiteData
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import io.github.nimbice.fanos.core.data.report.CrashLog
import io.github.nimbice.fanos.core.data.report.CrashReport

/** Settings: a line for each topic, opening the topic's own page. */
@Serializable
data object SettingsRoute

/** One topic's page of settings; [topic] names a [SettingsTopic]. */
@Serializable
data class SettingsTopicRoute(val topic: String)

fun NavGraphBuilder.settingsScreens(
    versionName: String,
    onOpenTopic: (SettingsTopic) -> Unit,
    onOpenInBrowser: (String) -> Unit,
    onOpenDownloads: () -> Unit,
    onOpenExtensions: () -> Unit,
    onBack: () -> Unit,
) {
    composable<SettingsRoute> { SettingsScreen(versionName, onOpenTopic) }
    composable<SettingsTopicRoute> { entry ->
        SettingsTopicScreen(SettingsTopic.valueOf(entry.toRoute<SettingsTopicRoute>().topic), versionName, onBack, onOpenInBrowser, onOpenDownloads, onOpenExtensions, onOpenTopic)
    }
}

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settings: SettingsRepository,
    private val notifier: NewChapterNotifier,
    private val downloads: DownloadRepository,
    private val siteData: SiteData,
    private val crashLog: CrashLog,
) : ViewModel() {

    private val _lastCrash = MutableStateFlow<CrashReport?>(null)

    /** The last crash's report, kept for About, until cleared. */
    val lastCrash: StateFlow<CrashReport?> = _lastCrash.asStateFlow()

    init {
        viewModelScope.launch { _lastCrash.value = crashLog.last() }
    }

    fun clearCrash() {
        viewModelScope.launch {
            crashLog.clear()
            _lastCrash.value = null
        }
    }

    /** What downloads take up, once known. */
    val downloaded: StateFlow<DownloadStorage?> = downloads.observeStorage().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val _cached = MutableStateFlow<Long?>(null)

    /** What the cache of pages and pictures holds, in bytes, once measured. */
    val cached: StateFlow<Long?> = _cached.asStateFlow()

    fun measureCache() {
        viewModelScope.launch { _cached.value = siteData.cacheSize() }
    }

    fun clearCache() {
        viewModelScope.launch {
            siteData.clearCache()
            _cached.value = siteData.cacheSize()
        }
    }

    /** Signs out of every site: cookies, the browser's storage and the cache go. [done] hears when they have. */
    fun signOutOfSites(done: () -> Unit) {
        viewModelScope.launch {
            siteData.signOutOfSites()
            _cached.value = siteData.cacheSize()
            done()
        }
    }

    val app: StateFlow<AppSettings> = settings.appSettings.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AppSettings())

    val reader: StateFlow<ReaderSettings> = settings.readerSettings.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ReaderSettings())

    fun updateApp(transform: (AppSettings) -> AppSettings) {
        viewModelScope.launch { settings.updateAppSettings(transform) }
    }

    fun updateReader(transform: (ReaderSettings) -> ReaderSettings) {
        viewModelScope.launch { settings.updateReaderSettings(transform) }
    }

    /** Whether Android lets new-chapter notifications through. */
    fun canNotify(): Boolean = notifier.canNotify()

    fun notificationSettings() = notifier.settingsIntent()
}

/**
 * Settings: each topic with what it's set to under its name, or what needs the reader there (a failed
 * backup, an update ready), opening the topic's page.
 */
@Composable
fun SettingsScreen(
    versionName: String,
    onOpenTopic: (SettingsTopic) -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
    backupViewModel: BackupViewModel = hiltViewModel(),
    updatesViewModel: UpdatesViewModel = hiltViewModel(),
    readingListsViewModel: ReadingListsViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val readingLists by readingListsViewModel.statuses.collectAsStateWithLifecycle()
    val app by viewModel.app.collectAsStateWithLifecycle()
    val reader by viewModel.reader.collectAsStateWithLifecycle()
    val downloaded by viewModel.downloaded.collectAsStateWithLifecycle()
    val backup by backupViewModel.ui.collectAsStateWithLifecycle()
    val updates by updatesViewModel.state.collectAsStateWithLifecycle()
    var notifying by remember { mutableStateOf(viewModel.canNotify()) }
    // Back from Android's settings, the answers may have changed.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        notifying = viewModel.canNotify()
        backupViewModel.recheck()
    }
    // No title bar: the tab bar already says this is Settings.
    Scaffold { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            TOPIC_GROUPS.forEach { group ->
                SettingsGroup {
                    // Reading lists only once an extension keeps them.
                    group.filter { it != SettingsTopic.ReadingLists || readingLists.isNotEmpty() }.forEach { topic ->
                        val summary =
                            when (topic) {
                                SettingsTopic.Appearance -> Summary(appearance(app))
                                SettingsTopic.Reader -> Summary(reading(reader))
                                SettingsTopic.NewChapters -> Summary(newChapters(app, notifying))
                                SettingsTopic.Downloads -> Summary(downloads(context, app, downloaded))
                                SettingsTopic.Backup -> backup(context, backup)
                                SettingsTopic.ReadingLists ->
                                    readingListsSummary(readingLists).let { (text, trouble) ->
                                        Summary(text, if (trouble == null) Tone.Plain else if (trouble) Tone.Problem else Tone.Notice)
                                    }
                                SettingsTopic.Updates -> updates(versionName, updates)
                                SettingsTopic.About -> Summary("Licences and fonts")
                                // Pages behind About, never listed here.
                                SettingsTopic.Licence, SettingsTopic.Notices -> Summary("")
                            }
                        item { TopicItem(topic, summary, onClick = { onOpenTopic(topic) }) }
                    }
                }
            }
        }
    }
}

/** The topics as Settings groups them: how you read, the library's chapters and backups, and the app. */
private val TOPIC_GROUPS =
    listOf(
        listOf(SettingsTopic.Appearance, SettingsTopic.Reader),
        listOf(SettingsTopic.NewChapters, SettingsTopic.Downloads, SettingsTopic.Backup, SettingsTopic.ReadingLists),
        listOf(SettingsTopic.Updates, SettingsTopic.About),
    )

/** The rows of a [SettingsGroup], each added with [item]. */
internal class SettingsGroupScope {
    internal val items = mutableListOf<@Composable () -> Unit>()

    fun item(content: @Composable () -> Unit) {
        items += content
    }
}

/**
 * Rows as cards in a group, as Android's own settings show them: round where the group begins and ends,
 * nearly square against each other, 2 dp apart.
 */
@Composable
internal fun SettingsGroup(content: SettingsGroupScope.() -> Unit) {
    val items = SettingsGroupScope().apply(content).items
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        items.forEachIndexed { index, item ->
            Box(Modifier.fillMaxWidth().clip(groupedShape(index, items.size)).background(MaterialTheme.colorScheme.surfaceContainer)) { item() }
        }
    }
}

/** A grouped row's corners: round where its group begins and ends, nearly square against its neighbours. */
private fun groupedShape(index: Int, count: Int): Shape {
    val top = if (index == 0) GROUP_CORNER else ROW_CORNER
    val bottom = if (index == count - 1) GROUP_CORNER else ROW_CORNER
    return RoundedCornerShape(topStart = top, topEnd = top, bottomStart = bottom, bottomEnd = bottom)
}

private val GROUP_CORNER = 20.dp
private val ROW_CORNER = 4.dp

/** What a topic's line says under its name, and whether that needs the reader. */
private data class Summary(val text: String, val tone: Tone = Tone.Plain)

private enum class Tone {
    Plain,

    /** Something to do: an update to install, a folder to choose. */
    Notice,

    /** Something went wrong. */
    Problem,
}

/**
 * A topic's line: a row of its own rather than a ListItem, which moves its icons to the top once the
 * summary wraps onto a second line. Here they stay in the middle of the row.
 */
@Composable
private fun TopicItem(topic: SettingsTopic, summary: Summary, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .heightIn(min = 72.dp)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Icon(topic.icon, contentDescription = null, tint = colors.onSurfaceVariant)
        Column(Modifier.weight(1f)) {
            Text(topic.title, style = MaterialTheme.typography.bodyLarge, color = colors.onSurface)
            Text(
                summary.text,
                style = MaterialTheme.typography.bodyMedium,
                color =
                    when (summary.tone) {
                        Tone.Plain -> colors.onSurfaceVariant
                        Tone.Notice -> colors.primary
                        Tone.Problem -> colors.error
                    },
            )
        }
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = colors.onSurfaceVariant)
    }
}

private fun appearance(app: AppSettings): String {
    val theme =
        when (app.themeMode) {
            ThemeMode.System -> "System theme"
            ThemeMode.Light -> "Light theme"
            ThemeMode.Dark -> "Dark theme"
            ThemeMode.Reader -> "Theme like the reader's"
        }
    return if (app.dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) "$theme · wallpaper colours" else theme
}

private fun reading(reader: ReaderSettings): String = if (reader.pageMode) "Page mode" else "Scrolling"

private fun newChapters(app: AppSettings, notifying: Boolean): String {
    val every =
        when (app.libraryUpdateInterval) {
            UpdateInterval.SixHours -> "every 6 hours"
            UpdateInterval.TwelveHours -> "every 12 hours"
            UpdateInterval.Daily -> "daily"
            UpdateInterval.Off -> return "Not checked automatically"
        }
    return "Checked $every" + if (app.newChapterNotifications && notifying) " · notified" else ""
}

private fun downloads(context: Context, app: AppSettings, saved: DownloadStorage?): String =
    listOfNotNull(
        "${app.downloadAhead} ahead".takeIf { app.downloadAhead > 0 },
        "Wi-Fi only".takeIf { app.downloadOnlyOnWifi },
        saved?.takeIf { it.chapters > 0 }?.let { "${countOf(it.chapters, "chapter")}, ${Formatter.formatShortFileSize(context, it.bytes)}" },
    ).joinToString(" · ").ifEmpty { "Nothing downloaded" }

private fun backup(context: Context, ui: BackupUi): Summary {
    val folder = ui.folder
    return when {
        !ui.loaded -> Summary("")
        folder == null -> Summary("Choose a folder to back up to", Tone.Notice)
        !ui.writable -> Summary("Fanos can't write to $folder any more", Tone.Problem)
        ui.failure != null -> Summary(ui.failure, Tone.Problem)
        else -> {
            val last = ui.lastBackupAt?.let { "last one ${ago(context, it)}" } ?: "none yet"
            val how = if (ui.interval == BackupInterval.Off) "Not automatic" else "${ui.interval.label} to $folder"
            Summary("$how · $last")
        }
    }
}

private fun updates(versionName: String, state: UpdateState): Summary =
    when (state) {
        UpdateState.NotSetUp -> Summary("$versionName · updates not set up")
        is UpdateState.UpToDate -> Summary(if (state.checkedAt == null) versionName else "$versionName · up to date")
        UpdateState.Checking -> Summary("$versionName · checking…")
        is UpdateState.Available -> Summary("Update ready: ${state.update.versionName}", Tone.Notice)
        is UpdateState.Downloading -> Summary("Downloading ${state.update.versionName}")
        is UpdateState.Installing -> Summary("Installing ${state.update.versionName}")
        is UpdateState.Failed -> Summary(state.message, Tone.Problem)
    }

/**
 * A settings row: its name, what's under it, and whatever is at its end (a switch, a button) kept level
 * with the middle of the row. A ListItem moves the end to the top once the lines under the name wrap.
 * Greyed out unless [enabled].
 */
@Composable
internal fun SettingsItem(
    title: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    summary: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    val colors = MaterialTheme.colorScheme
    Row(
        modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Column(Modifier.weight(1f).alpha(if (enabled) 1f else DISABLED_ALPHA)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = colors.onSurface)
            if (summary != null) {
                CompositionLocalProvider(LocalContentColor provides colors.onSurfaceVariant, LocalTextStyle provides MaterialTheme.typography.bodyMedium) {
                    summary()
                }
            }
        }
        trailing?.invoke()
    }
}

@Composable
internal fun SwitchItem(title: String, summary: String, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    // The whole row flips the switch: a tap on the words did nothing before, which read as broken.
    SettingsItem(
        title,
        Modifier.toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onChange),
        enabled = enabled,
        summary = { Text(summary) },
        trailing = { Switch(checked = checked, onCheckedChange = null, enabled = enabled) },
    )
}

/** A setting with a few choices, as chips under its name, each a tap away; greyed out unless [enabled]. */
@Composable
internal fun <T> ChoiceItem(
    title: String,
    summary: String?,
    choices: List<T>,
    selected: T,
    label: (T) -> String,
    enabled: Boolean = true,
    onSelect: (T) -> Unit,
) {
    // The chips grey themselves out; the words go with them.
    val alpha = if (enabled) 1f else DISABLED_ALPHA
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
        Text(title, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.alpha(alpha))
        summary?.let {
            Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.alpha(alpha))
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
            choices.forEach { choice ->
                FilterChip(selected = choice == selected, onClick = { onSelect(choice) }, label = { Text(label(choice)) }, enabled = enabled)
            }
        }
    }
}

internal const val DISABLED_ALPHA = 0.38f
