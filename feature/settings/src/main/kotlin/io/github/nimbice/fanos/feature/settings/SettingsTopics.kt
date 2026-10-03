package io.github.nimbice.fanos.feature.settings

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.text.format.Formatter
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.nimbice.fanos.core.designsystem.component.countOf
import io.github.nimbice.fanos.core.designsystem.icon.ReaderIcons
import io.github.nimbice.fanos.core.model.AppSettings
import io.github.nimbice.fanos.core.model.ReaderSettings
import io.github.nimbice.fanos.core.model.ThemeMode
import io.github.nimbice.fanos.core.model.UpdateInterval
import io.github.nimbice.fanos.core.model.RecapWait
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.OutlinedButton
import androidx.compose.runtime.produceState
import io.github.nimbice.fanos.core.common.AppIdentity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import android.widget.Toast
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.material3.TextButton
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.combinedClickable
import androidx.compose.runtime.saveable.rememberSaveable

/** What Settings is divided into, each topic on a page of its own. */
enum class SettingsTopic(val title: String) {
    Appearance("Appearance"),
    Reader("Reader"),
    NewChapters("New chapters"),
    Downloads("Downloads"),
    Backup("Backup"),
    ReadingLists("Reading lists"),
    Updates("Updates"),
    About("About"),

    /** Pages of text behind About, not listed in Settings. */
    Licence("Licence"),
    Notices("Third-party notices"),
}

internal val SettingsTopic.icon: ImageVector
    get() =
        when (this) {
            SettingsTopic.Appearance -> ReaderIcons.Palette
            SettingsTopic.Reader -> ReaderIcons.Book
            SettingsTopic.NewChapters -> Icons.Filled.Notifications
            SettingsTopic.Downloads -> ReaderIcons.Download
            SettingsTopic.Backup -> ReaderIcons.Backup
            SettingsTopic.ReadingLists -> ReaderIcons.Sync
            SettingsTopic.Updates -> ReaderIcons.Sync
            SettingsTopic.About -> Icons.Filled.Info
            SettingsTopic.Licence, SettingsTopic.Notices -> Icons.Filled.Info
        }

/** A topic's page: its settings, under a bar with its name and the way back to Settings. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SettingsTopicScreen(
    topic: SettingsTopic,
    versionName: String,
    onBack: () -> Unit,
    onOpenInBrowser: (String) -> Unit,
    onOpenDownloads: () -> Unit,
    onOpenExtensions: () -> Unit,
    onOpenTopic: (SettingsTopic) -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val app by viewModel.app.collectAsStateWithLifecycle()
    val reader by viewModel.reader.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(topic.title) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(top = 8.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            when (topic) {
                SettingsTopic.Appearance -> AppearanceSettings(app, viewModel)
                SettingsTopic.Reader -> ReadingSettings(reader, app, viewModel)
                SettingsTopic.NewChapters -> NewChapterSettings(app, viewModel)
                SettingsTopic.Downloads -> DownloadSettings(app, viewModel, onOpenDownloads)
                SettingsTopic.Backup -> BackupSettings(snackbar)
                SettingsTopic.ReadingLists -> ReadingListSettings(onOpenInBrowser)
                SettingsTopic.Updates -> UpdateSettings(versionName, app, viewModel, onOpenExtensions)
                SettingsTopic.About -> AboutSettings(versionName, viewModel, onOpenTopic)
                SettingsTopic.Licence -> LegalText("legal/LICENSE")
                SettingsTopic.Notices -> LegalText("legal/THIRD_PARTY_NOTICES.md")
            }
        }
    }
}

@Composable
private fun AppearanceSettings(app: AppSettings, viewModel: SettingsViewModel) {
    SettingsGroup {
        item {
            ChoiceItem(
                "Theme",
                if (app.themeMode == ThemeMode.Reader) "Dark while the reader's pages are: Dusk, Night, Black, or your own dark colours. Light while they're light." else null,
                ThemeMode.entries,
                app.themeMode,
                label = { if (it == ThemeMode.Reader) "Like the reader" else it.name },
            ) { mode ->
                viewModel.updateApp { it.copy(themeMode = mode) }
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            item {
                SwitchItem("Colours from wallpaper", "Use the system's dynamic colours", app.dynamicColor) { on ->
                    viewModel.updateApp { it.copy(dynamicColor = on) }
                }
            }
        }
    }
}

@Composable
private fun ReadingSettings(reader: ReaderSettings, app: AppSettings, viewModel: SettingsViewModel) {
    SettingsGroup {
        item {
            SwitchItem("Page mode", "Turn pages sideways instead of scrolling", reader.pageMode) { on ->
                viewModel.updateReader { it.copy(pageMode = on) }
            }
        }
    }
    Text(
        "Text size, fonts and colours, keeping the screen on and the volume keys are set from the reader itself.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 32.dp),
    )
    SettingsGroup {
        item {
            ChoiceItem("Where you left off", "Back after a while: the last lines you read, before you carry on", RecapWait.entries, app.recapAfter, label = { it.label }) { wait ->
                viewModel.updateApp { it.copy(recapAfter = wait) }
            }
        }
        item {
            SwitchItem("Next chapter at a chapter's end", "What comes next, whether it's downloaded, and about how long it takes to read", app.nextChapterCard) { on ->
                viewModel.updateApp { it.copy(nextChapterCard = on) }
            }
        }
    }
    SettingsGroup {
        item {
            SwitchItem("Look up missing words online", "Words the built-in dictionary hasn't got go to dictionaryapi.dev or Wiktionary", app.lookUpOnline) { on ->
                viewModel.updateApp { it.copy(lookUpOnline = on) }
            }
        }
    }
    Text(
        "The built-in dictionary is made from Open English WordNet (CC BY 4.0). Online look-ups show Wiktionary's definitions (CC BY-SA), credited on the card.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 32.dp),
    )
    SettingsGroup {
        item {
            SwitchItem("Drag to choose words", "Hold on a word, then slide over more, on into other paragraphs and pages", app.dragToChoose) { on ->
                viewModel.updateApp { it.copy(dragToChoose = on) }
            }
        }
        item {
            SwitchItem("Tap a highlight for its menu", "Otherwise a tap there does what a tap anywhere else does", app.tapHighlightsForMenu) { on ->
                viewModel.updateApp { it.copy(tapHighlightsForMenu = on) }
            }
        }
    }
    Text(
        "What holding on the text does: turn off what gets in your way. A plain long-press always opens the menu.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 32.dp),
    )
}

@Composable
private fun NewChapterSettings(app: AppSettings, viewModel: SettingsViewModel) {
    SettingsGroup {
        item {
            ChoiceItem("Check for new chapters", "In library novels, in the background", UpdateInterval.entries, app.libraryUpdateInterval, label = { it.label }) { interval ->
                viewModel.updateApp { it.copy(libraryUpdateInterval = interval) }
            }
        }
        item { NewChapterNotificationsItem(app, viewModel) }
    }
}

private val UpdateInterval.label: String
    get() =
        when (this) {
            UpdateInterval.SixHours -> "6 hours"
            UpdateInterval.TwelveHours -> "12 hours"
            UpdateInterval.Daily -> "Daily"
            UpdateInterval.Off -> "Off"
        }

/**
 * The switch shows on only while Android lets the notifications through too. Turning it on asks
 * Android's permission (13 and later); where Android no longer asks, it opens Android's notification
 * settings for the app instead.
 */
@Composable
private fun NewChapterNotificationsItem(app: AppSettings, viewModel: SettingsViewModel) {
    val context = LocalContext.current
    val activity = LocalActivity.current
    var allowed by remember { mutableStateOf(viewModel.canNotify()) }
    // Back from Android's settings, the answer may have changed.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { allowed = viewModel.canNotify() }
    val permission = Manifest.permission.POST_NOTIFICATIONS
    fun couldExplain() = activity != null && ActivityCompat.shouldShowRequestPermissionRationale(activity, permission)
    var explainedBefore by remember { mutableStateOf(false) }
    val request =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            allowed = viewModel.canNotify()
            viewModel.updateApp { it.copy(newChapterNotifications = granted) }
            // No dialog came up (Android stops asking after the reader has said no twice): the way on is its settings.
            if (!granted && !explainedBefore && !couldExplain()) context.startActivity(viewModel.notificationSettings())
        }
    SwitchItem(
        "New chapter notifications",
        "When a background check finds new chapters",
        checked = app.newChapterNotifications && allowed,
        enabled = app.libraryUpdateInterval != UpdateInterval.Off,
    ) { on ->
        val mustAsk =
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                ContextCompat.checkSelfPermission(context, permission) != PackageManager.PERMISSION_GRANTED
        when {
            !on || allowed -> viewModel.updateApp { it.copy(newChapterNotifications = on) }
            mustAsk -> {
                explainedBefore = couldExplain()
                request.launch(permission)
            }
            // Turned off in Android's settings, for the app or for this kind of notification.
            else -> {
                viewModel.updateApp { it.copy(newChapterNotifications = true) }
                context.startActivity(viewModel.notificationSettings())
            }
        }
    }
}

/**
 * Saving chapters for reading offline: new ones automatically, the next ones while reading, over Wi-Fi
 * only or not, and what the saved chapters take up, with ways to delete them: each once it's read, or
 * all at once.
 */
@Composable
private fun DownloadSettings(app: AppSettings, viewModel: SettingsViewModel, onOpenDownloads: () -> Unit) {
    val context = LocalContext.current
    val saved by viewModel.downloaded.collectAsStateWithLifecycle()
    val cached by viewModel.cached.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { viewModel.measureCache() }
    SettingsGroup {
        item {
            SwitchItem("Download new chapters", "When a check finds them in library novels", app.downloadNewChapters) { on ->
                viewModel.updateApp { it.copy(downloadNewChapters = on) }
            }
        }
        item {
            ChoiceItem(
                "Download ahead while reading",
                "The next chapters of a library novel, as you read it",
                DOWNLOAD_AHEAD_CHOICES,
                app.downloadAhead,
                label = { if (it == 0) "Off" else "$it" },
            ) { count -> viewModel.updateApp { it.copy(downloadAhead = count) } }
        }
        item {
            SwitchItem("Only on Wi-Fi", "Downloads wait for Wi-Fi", app.downloadOnlyOnWifi) { on ->
                viewModel.updateApp { it.copy(downloadOnlyOnWifi = on) }
            }
        }
    }
    SettingsGroup {
        item {
            SwitchItem("Delete after reading", "Downloads are deleted as you finish chapters", app.deleteAfterReading) { on ->
                viewModel.updateApp { it.copy(deleteAfterReading = on) }
            }
        }
        // The Downloads tab lists them by novel, and deletes them.
        item {
            val stored = saved
            SettingsItem(
                "Saved chapters",
                Modifier.clickable(onClick = onOpenDownloads),
                summary = {
                    Text(
                        when {
                            stored == null -> ""
                            stored.chapters == 0 -> "None"
                            else -> "${countOf(stored.chapters, "chapter")} · ${Formatter.formatShortFileSize(context, stored.bytes)}"
                        },
                    )
                },
                trailing = { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null) },
            )
        }
    }
    SettingsGroup {
        item {
            SettingsItem(
                "Cached pages",
                summary = { Text(cached?.let { "${Formatter.formatShortFileSize(context, it)} of pages and pictures, kept for speed" } ?: "Pages and pictures, kept for speed") },
                trailing = { OutlinedButton(onClick = viewModel::clearCache, enabled = (cached ?: 0L) > 0L) { Text("Clear") } },
            )
        }
        item {
            SettingsItem(
                "Site sign-ins",
                summary = { Text("Cookies and sign-ins from the built-in browser, for every site") },
                trailing = {
                    OutlinedButton(onClick = { viewModel.signOutOfSites { Toast.makeText(context, "Signed out of every site", Toast.LENGTH_SHORT).show() } }) {
                        Text("Sign out")
                    }
                },
            )
        }
    }
}

/** The chapters "Download ahead while reading" offers; 0 is off. */
private val DOWNLOAD_AHEAD_CHOICES = listOf(0, 2, 5, 10)

/**
 * This build, whether there's a newer one, and whether to look by itself. A long press on the Version row opens the
 * dialog for the key private builds are read with: the public sees no key anywhere.
 */
@Composable
private fun UpdateSettings(versionName: String, app: AppSettings, viewModel: SettingsViewModel, onOpenExtensions: () -> Unit) {
    val updates: UpdatesViewModel = hiltViewModel()
    val extensions by updates.extensions.collectAsStateWithLifecycle()
    val hasKey by updates.hasKey.collectAsStateWithLifecycle()
    val testBuilds by updates.testBuilds.collectAsStateWithLifecycle()
    var settingUpKey by rememberSaveable { mutableStateOf(false) }
    SettingsGroup {
        item { SettingsItem("Version", Modifier.combinedClickable(onLongClick = { settingUpKey = true }) {}, summary = { Text(versionName) }) }
        item { UpdatesItem(settingUp = settingUpKey, onSettingUp = { settingUpKey = it }, viewModel = updates) }
        // With a key, the reader may follow the private test builds instead of the public releases.
        if (hasKey) {
            item {
                SwitchItem("Follow test builds", "Private builds from fanos-builds instead of the public releases", testBuilds) { on -> updates.setTestBuilds(on) }
            }
        }
        // The extensions' updates, looked for with the app's (nothing to show without the key).
        if (extensions != ExtensionsLook.None) item { ExtensionUpdatesItem(extensions, onOpenExtensions) }
        item {
            SwitchItem("Check for updates automatically", "Daily, and when Fanos opens", app.checkForUpdates) { on ->
                viewModel.updateApp { it.copy(checkForUpdates = on) }
            }
        }
    }
}

/**
 * About: what this build is and whose work it holds, with the notices the GPL asks for: the copyright, that it comes
 * with no warranty, and where the licence, the third-party notices and the source are.
 */
@Composable
private fun AboutSettings(versionName: String, viewModel: SettingsViewModel, onOpenTopic: (SettingsTopic) -> Unit) {
    val context = LocalContext.current
    val crash by viewModel.lastCrash.collectAsStateWithLifecycle()
    // The last crash, for a report: nothing leaves the device unless the reader shares it.
    crash?.let { report ->
        SettingsGroup {
            item {
                SettingsItem(
                    "Fanos crashed ${ago(context, report.at)}",
                    summary = { Text("Share the report to help get it fixed. It names the build, the device and what went wrong, nothing you read.") },
                    trailing = {
                        Row {
                            TextButton(onClick = viewModel::clearCrash) { Text("Clear") }
                            OutlinedButton(onClick = {
                                val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_SUBJECT, "Fanos crash report").putExtra(Intent.EXTRA_TEXT, report.text)
                                context.startActivity(Intent.createChooser(send, null))
                            }) { Text("Share") }
                        }
                    },
                )
            }
        }
    }
    SettingsGroup {
        item {
            SettingsItem(
                "Fanos $versionName",
                summary = { Text("Copyright \u00a9 2026 nimbice. Free software under the GNU General Public License, version 3 or later: it comes with no warranty.") },
                trailing = {
                    OutlinedButton(onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(AppIdentity.HOMEPAGE))) }) { Text("Source") }
                },
            )
        }
        item {
            SettingsItem(
                "Licence",
                Modifier.clickable { onOpenTopic(SettingsTopic.Licence) },
                summary = { Text("The GNU General Public License, version 3. Includes code derived from NovelLibrary (Apache-2.0).") },
                trailing = { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null) },
            )
        }
        item {
            SettingsItem(
                "Third-party notices",
                Modifier.clickable { onOpenTopic(SettingsTopic.Notices) },
                summary = { Text("The libraries, speech engine, fonts, icons and dictionaries Fanos is made with, and their licences.") },
                trailing = { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null) },
            )
        }
        item {
            SettingsItem(
                "Fonts",
                summary = {
                    Text("Literata, Lora, Crimson Pro, EB Garamond, Bitter, Atkinson Hyperlegible Next and Lexend, under the SIL Open Font License 1.1.")
                },
            )
        }
        item {
            SettingsItem(
                "Dictionary",
                summary = {
                    Text(
                        "Made from Open English WordNet 2025 (The Open English WordNet Team, CC BY 4.0; built on Princeton WordNet): its words and phrases, " +
                            "their first senses and pronunciations, converted into a format of Fanos's own. Online look-ups show Wiktionary's definitions (CC BY-SA), " +
                            "directly or through dictionaryapi.dev.",
                    )
                },
            )
        }
    }
    SettingsGroup {
        item {
            SettingsItem(
                "Privacy",
                summary = {
                    Text(
                        "Fanos collects nothing. It talks to the sites you read through the extensions you install, to GitHub for updates and voices, to " +
                            "dictionaryapi.dev and Wiktionary for words the built-in dictionary hasn't got (Reader settings turn that off), to Open Library for " +
                            "covers you search for, and to a site's reading list only when you turn its sync on. Your library stays on this device and in your backups. " +
                            "Fanos is an independent project, not affiliated with any site, publisher or company, including any other business or product called Fanos.",
                    )
                },
            )
        }
    }
}

/** A page of legal text from the app's assets: the licence, or the third-party notices. */
@Composable
private fun LegalText(asset: String) {
    val context = LocalContext.current
    val text by produceState("") { value = withContext(Dispatchers.IO) { unwrapped(context.assets.open(asset).bufferedReader().use { it.readText() }) } }
    SelectionContainer {
        Text(text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(horizontal = 16.dp))
    }
}

/**
 * [text] with its paragraphs' hard line breaks taken out, so they wrap to the screen: a line carries on the one before
 * unless it's blank, a heading, a list item, a numbered clause, or indented (a notice quoted as it was).
 */
private fun unwrapped(text: String): String {
    val out = StringBuilder()
    var joinable = false
    for (raw in text.lines()) {
        // A Markdown heading, shown without its marks.
        val heading = raw.startsWith("#")
        val line = if (heading) raw.trimStart('#').trim() else raw.trimEnd()
        val indented = line.startsWith("    ")
        val starts = line.isEmpty() || indented || heading || line.startsWith("- ") || CLAUSE.containsMatchIn(line)
        if (joinable && !starts) {
            out.append(' ').append(line.trim())
        } else {
            if (out.isNotEmpty()) out.append('\n')
            out.append(if (indented) line else line.trim())
        }
        joinable = line.isNotEmpty() && !indented && !heading
    }
    return out.toString()
}

/** A numbered clause's start, as the GPL has them ("  0. Definitions.", "    a) ..."), and headings in capitals. */
private val CLAUSE = Regex("""^\s*(\d+\.\s|[a-z]\)\s|[A-Z][A-Z ,.'-]{8,}$)""")

private val RecapWait.label: String get() = if (this == RecapWait.Off) "Off" else "$hours h"
