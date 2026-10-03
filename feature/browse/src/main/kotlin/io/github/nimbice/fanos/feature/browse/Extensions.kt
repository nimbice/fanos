package io.github.nimbice.fanos.feature.browse

import android.net.Uri
import android.os.SystemClock
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.nimbice.fanos.core.common.suspendRunCatching
import io.github.nimbice.fanos.core.data.extension.ExtensionManager
import io.github.nimbice.fanos.core.data.extension.ExtensionUpdates
import io.github.nimbice.fanos.core.data.extension.PickedExtension
import io.github.nimbice.fanos.core.data.userMessage
import io.github.nimbice.fanos.core.designsystem.component.countOf
import io.github.nimbice.fanos.core.designsystem.icon.ReaderIcons
import io.github.nimbice.fanos.core.model.ExtensionInfo
import io.github.nimbice.fanos.core.model.ExtensionStatus
import io.github.nimbice.fanos.core.updater.PublishedExtension
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.net.URI
import java.util.Locale
import javax.inject.Inject

/** A key the reader is asked about: extensions they picked are signed with it, and it isn't trusted yet. */
data class TrustRequest(val signer: String, val extensions: List<String>)

/** A newer version of an installed extension. */
data class ExtensionUpdate(val installed: ExtensionInfo, val published: PublishedExtension)

/** The extensions published beside the app's builds, against the installed ones. */
sealed interface PublishedUiState {
    data object Looking : PublishedUiState

    data class Failed(val message: String) : PublishedUiState

    data class Found(val updates: List<ExtensionUpdate>, val available: List<PublishedExtension>) : PublishedUiState
}

data class ExtensionsUiState(
    /** Null until the installed extensions have loaded. */
    val installed: List<ExtensionInfo>? = null,
    /** Picked files or published extensions being fetched, read, asked about or installed. */
    val working: Boolean = false,
    val trust: TrustRequest? = null,
    val published: PublishedUiState = PublishedUiState.Looking,
    /** The keys the reader has trusted, besides the app's own. */
    val trustedKeys: Set<String> = emptySet(),
)

@HiltViewModel
class ExtensionsViewModel @Inject constructor(private val extensions: ExtensionManager, private val updates: ExtensionUpdates) : ViewModel() {

    private val working = MutableStateFlow(false)
    private val trust = MutableStateFlow<TrustRequest?>(null)
    private val _messages = Channel<String>(Channel.BUFFERED)

    private val published = MutableStateFlow<Published>(Published.Looking)
    private var looking: Job? = null
    private var foundAt = 0L

    /** What happened to picked files and removed extensions, to tell the reader once. */
    val messages: Flow<String> = _messages.receiveAsFlow()

    val state: StateFlow<ExtensionsUiState> =
        combine(extensions.extensions.onStart<List<ExtensionInfo>?> { emit(null) }, working, trust, published, extensions.trustedSigners) { installed, working, trust, published, keys ->
            ExtensionsUiState(installed, working, trust, published.against(installed.orEmpty()), keys)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ExtensionsUiState())

    /**
     * Looks for published extensions, unless they were found in the last minute ([again] looks
     * regardless); what was found before stays shown meanwhile.
     */
    fun lookForPublished(again: Boolean = false) {
        if (looking?.isActive == true) return
        if (!again && published.value is Published.Found && SystemClock.elapsedRealtime() - foundAt < FRESH_MILLIS) return
        looking =
            viewModelScope.launch {
                if (published.value !is Published.Found) published.value = Published.Looking
                published.value =
                    suspendRunCatching { updates.published() }.fold(
                        onSuccess = { found ->
                            foundAt = SystemClock.elapsedRealtime()
                            Published.Found(found)
                        },
                        onFailure = { Published.Failed(userMessage(it)) },
                    )
            }
    }

    // Picked files waiting on the reader's answer about a new key, and the keys answered so far.
    private var ready: List<PickedExtension.Ready> = emptyList()
    private var refused: List<PickedExtension.Refused> = emptyList()
    private val trusted = mutableSetOf<String>()
    private val declined = mutableSetOf<String>()

    /** Reads the picked [files] and installs the extensions among them, asking first about any new key. */
    fun install(files: List<Uri>) {
        if (files.isEmpty() || working.value) return
        working.value = true
        viewModelScope.launch { take { extensions.inspect(files) } }
    }

    /** Fetches the [chosen] published extensions and installs them as picked files are installed. */
    fun installPublished(chosen: List<PublishedExtension>) {
        if (chosen.isEmpty() || working.value) return
        working.value = true
        viewModelScope.launch { take { updates.fetch(chosen) } }
    }

    private suspend fun take(pick: suspend () -> List<PickedExtension>) {
        suspendRunCatching { pick() }
            .onSuccess { picked ->
                ready = picked.filterIsInstance<PickedExtension.Ready>()
                refused = picked.filterIsInstance<PickedExtension.Refused>()
                askOrInstall()
            }.onFailure { error ->
                working.value = false
                _messages.send(userMessage(error))
            }
    }

    /** Trusts the key asked about and goes on: its extensions install. */
    fun trustKey() {
        val request = trust.value ?: return
        trust.value = null
        viewModelScope.launch {
            extensions.trust(setOf(request.signer))
            trusted += request.signer
            askOrInstall()
        }
    }

    /** Leaves the key asked about untrusted: its extensions aren't installed, the others are. */
    fun declineKey() {
        val request = trust.value ?: return
        trust.value = null
        declined += request.signer
        viewModelScope.launch { askOrInstall() }
    }

    fun remove(extension: ExtensionInfo) {
        viewModelScope.launch {
            suspendRunCatching { extensions.uninstall(extension.id) }
                .onSuccess { _messages.send("Removed ${extension.name}") }
                .onFailure { _messages.send(userMessage(it)) }
        }
    }

    /** Forgets a trusted key: its extensions stop loading until it's trusted again. */
    fun forgetKey(signer: String) {
        viewModelScope.launch {
            suspendRunCatching { extensions.forget(signer) }
                .onSuccess { _messages.send("Forgot the key. Its extensions stay installed but don't load.") }
                .onFailure { _messages.send(userMessage(it)) }
        }
    }

    private suspend fun askOrInstall() {
        val unanswered = ready.filter { !it.signerTrusted && it.apk.signer !in trusted && it.apk.signer !in declined }
        val signer = unanswered.firstOrNull()?.apk?.signer
        if (signer != null) {
            trust.value = TrustRequest(signer, unanswered.filter { it.apk.signer == signer }.map { it.apk.name })
            return
        }
        val (installing, dropped) = ready.partition { it.signerTrusted || it.apk.signer in trusted }
        extensions.discard(dropped)
        val result = suspendRunCatching { if (installing.isNotEmpty()) extensions.install(installing) }
        val message =
            result.exceptionOrNull()?.let { userMessage(it) }
                ?: summary(installing, dropped.size, refused)
        ready = emptyList()
        refused = emptyList()
        trusted.clear()
        declined.clear()
        working.value = false
        message?.let { _messages.send(it) }
    }

    private fun summary(installed: List<PickedExtension.Ready>, untrusted: Int, refused: List<PickedExtension.Refused>): String? {
        val new = installed.filter { it.installedVersionCode == null }
        val updated = installed.filter { picked -> picked.installedVersionCode?.let { it < picked.apk.versionCode } == true }
        val same = installed.filter { it.installedVersionCode == it.apk.versionCode }
        fun done(verb: String, extensions: List<PickedExtension.Ready>, to: Boolean = false): String? =
            when (extensions.size) {
                0 -> null
                1 -> "$verb ${extensions[0].apk.name}" + if (to) " to ${extensions[0].apk.versionName}" else ""
                else -> "$verb ${countOf(extensions.size, "extension")}"
            }
        val parts =
            buildList {
                listOfNotNull(done("installed", new), done("updated", updated, to = true), done("reinstalled", same))
                    .takeIf { it.isNotEmpty() }
                    ?.let { add(it.joinToString(", ").replaceFirstChar(Char::uppercase) + ".") }
                if (untrusted > 0) add("Left out ${countOf(untrusted, "extension")} signed with the key you didn't trust.")
                refused.firstOrNull()?.let { first ->
                    val more = if (refused.size > 1) " (and ${countOf(refused.size - 1, "other file")})" else ""
                    add("Couldn't install ${first.fileName}$more: ${first.reason}.")
                }
            }
        return parts.joinToString(" ").ifEmpty { null }
    }

    /** What was last found published, before it's set against the installed extensions. */
    private sealed interface Published {
        data object Looking : Published

        data class Failed(val message: String) : Published

        data class Found(val extensions: List<PublishedExtension>) : Published

        fun against(installed: List<ExtensionInfo>): PublishedUiState =
            when (this) {
                Looking -> PublishedUiState.Looking
                is Failed -> PublishedUiState.Failed(message)
                is Found -> {
                    val compared = ExtensionUpdates.compare(installed, extensions)
                    val byId = installed.associateBy { it.id }
                    PublishedUiState.Found(compared.updates.mapNotNull { update -> byId[update.id]?.let { ExtensionUpdate(it, update) } }, compared.available)
                }
            }
    }

    private companion object {
        const val FRESH_MILLIS = 60_000L
    }
}

/**
 * The installed extensions, newer versions of them and more to install from those published beside the
 * app's builds, and the way to add more from files. [onShown] looks for published ones.
 */
@Composable
internal fun ExtensionsTab(
    state: ExtensionsUiState,
    onShown: () -> Unit,
    onInstall: () -> Unit,
    onInstallPublished: (List<PublishedExtension>) -> Unit,
    onLookAgain: () -> Unit,
    onRemove: (ExtensionInfo) -> Unit,
    onForgetKey: (String) -> Unit,
) {
    LaunchedEffect(Unit) { onShown() }
    val installed = state.installed ?: return
    val found = state.published as? PublishedUiState.Found
    val updates = found?.updates.orEmpty()
    val available = found?.available.orEmpty()
    val updating = updates.map { it.installed.id }.toSet()
    val rest = installed.filter { it.id !in updating }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 8.dp)) {
        item {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                OutlinedButton(onClick = onInstall, enabled = !state.working) {
                    Icon(ReaderIcons.FileAdd, contentDescription = null, modifier = Modifier.size(18.dp))
                    Text("Install from file", modifier = Modifier.padding(start = 8.dp))
                }
                if (state.working) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 12.dp))
                if (installed.isEmpty()) {
                    Note(
                        if (available.isEmpty()) {
                            "An extension adds a site to read from. Pick one or more extension files to install."
                        } else {
                            "An extension adds a site to read from. Install one below, or from a file."
                        },
                    )
                }
                when (val published = state.published) {
                    is PublishedUiState.Failed -> Note("Extension updates: ${published.message}", error = true, action = "Try again", onAction = onLookAgain)
                    else -> Unit
                }
            }
        }
        if (updates.isNotEmpty()) {
            item(key = "updates") {
                SectionHeader(
                    "Updates · ${updates.size}",
                    action = "Update all".takeIf { updates.size > 1 },
                    enabled = !state.working,
                    onAction = { onInstallPublished(updates.map { it.published }) },
                )
            }
            items(updates, key = { "update:${it.installed.id}" }) { update ->
                UpdateRow(update, enabled = !state.working, onUpdate = { onInstallPublished(listOf(update.published)) })
            }
        }
        if (rest.isNotEmpty() && (updates.isNotEmpty() || available.isNotEmpty())) item(key = "installed") { SectionHeader("Installed") }
        items(rest, key = { it.id }) { extension -> ExtensionRow(extension, onRemove = { onRemove(extension) }) }
        if (available.isNotEmpty()) {
            item(key = "available") { SectionHeader("Available · ${available.size}") }
            items(available, key = { "available:${it.id}" }) { extension ->
                AvailableRow(extension, enabled = !state.working, onInstall = { onInstallPublished(listOf(extension)) })
            }
        }
        // Whose extensions may run: the app's own key, and the keys the reader trusted, each forgettable.
        item(key = "keys") { SectionHeader("Trusted keys") }
        item(key = "key:own") { KeyRow("Fanos's own key, built in", onForget = null) }
        items(state.trustedKeys.sorted(), key = { "key:$it" }) { signer -> KeyRow(fingerprint(signer), onForget = { onForgetKey(signer) }) }
    }
}

/** A trusted key's fingerprint, with Forget beside it; the built-in one has no button. */
@Composable
private fun KeyRow(label: String, onForget: (() -> Unit)?) {
    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = if (onForget == null) null else FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        if (onForget != null) TextButton(onClick = onForget) { Text("Forget") }
    }
}

/** A line of explanation under the install button, with a button to act on it. */
@Composable
private fun Note(text: String, error: Boolean = false, action: String? = null, onAction: () -> Unit = {}) {
    Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium,
            color = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        if (action != null) TextButton(onClick = onAction) { Text(action) }
    }
}

@Composable
private fun SectionHeader(title: String, action: String? = null, enabled: Boolean = true, onAction: () -> Unit = {}) {
    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 8.dp).heightIn(min = 40.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.weight(1f))
        if (action != null) TextButton(onClick = onAction, enabled = enabled) { Text(action) }
    }
}

@Composable
private fun UpdateRow(update: ExtensionUpdate, enabled: Boolean, onUpdate: () -> Unit) {
    val extension = update.installed
    val working = extension.status == ExtensionStatus.Working
    ListItem(
        leadingContent = { Initial(extension.name, working) },
        headlineContent = { Text(extension.name) },
        supportingContent = {
            Text(
                describe(update),
                color = if (working) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
            )
        },
        trailingContent = { Button(onClick = onUpdate, enabled = enabled) { Text("Update") } },
    )
}

@Composable
private fun AvailableRow(extension: PublishedExtension, enabled: Boolean, onInstall: () -> Unit) {
    ListItem(
        leadingContent = { Initial(extension.name, working = true, installed = false) },
        headlineContent = { Text(extension.name) },
        supportingContent = {
            Text(
                listOf(extension.versionName, language(extension.lang)).filter { it.isNotEmpty() }.joinToString(" · "),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        trailingContent = { OutlinedButton(onClick = onInstall, enabled = enabled) { Text("Install") } },
    )
}

@Composable
private fun ExtensionRow(extension: ExtensionInfo, onRemove: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    var confirming by remember { mutableStateOf(false) }
    val working = extension.status == ExtensionStatus.Working
    ListItem(
        leadingContent = { Initial(extension.name, working) },
        headlineContent = { Text(extension.name) },
        supportingContent = {
            Text(
                describe(extension),
                color = if (working) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
            )
        },
        trailingContent = {
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, contentDescription = "More for ${extension.name}") }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(
                        text = { Text("Remove") },
                        onClick = {
                            menu = false
                            confirming = true
                        },
                    )
                }
            }
        },
    )
    if (confirming) {
        AlertDialog(
            onDismissRequest = { confirming = false },
            title = { Text("Remove ${extension.name}?") },
            text = { Text("Novels read from it stay in your library, and chapters you downloaded can still be read.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirming = false
                        onRemove()
                    },
                ) { Text("Remove") }
            },
            dismissButton = { TextButton(onClick = { confirming = false }) { Text("Cancel") } },
        )
    }
}

/** The extension's first letter in a circle: extensions carry no icons of their own. Grey for one not installed. */
@Composable
private fun Initial(name: String, working: Boolean, installed: Boolean = true) {
    val colors = MaterialTheme.colorScheme
    val (container, content) =
        when {
            !installed -> colors.surfaceVariant to colors.onSurfaceVariant
            working -> colors.primaryContainer to colors.onPrimaryContainer
            else -> colors.errorContainer to colors.onErrorContainer
        }
    Box(
        Modifier
            .size(40.dp)
            .clip(CircleShape)
            .background(container),
        contentAlignment = Alignment.Center,
    ) {
        Text(name.firstOrNull()?.uppercase().orEmpty(), style = MaterialTheme.typography.titleMedium, color = content)
    }
}

// What a source in many languages, such as Patreon, gives as its language.
private const val ALL_LANGUAGES = "all"

private fun describe(extension: ExtensionInfo): String =
    when (extension.status) {
        ExtensionStatus.Working -> (listOf(extension.versionName) + reads(extension)).filter { it.isNotEmpty() }.joinToString(" · ")
        ExtensionStatus.NeedsNewerApp -> "Needs a newer version of Fanos"
        ExtensionStatus.Outdated -> "Made for an older version of Fanos"
        ExtensionStatus.Untrusted -> "Signed with a key you haven't trusted"
        ExtensionStatus.Broken -> "Couldn't load: ${extension.problem ?: "unknown error"}"
    }

/** The version installed and the one to update to, then what the installed one reads, or why it doesn't. */
private fun describe(update: ExtensionUpdate): String {
    val extension = update.installed
    val versions = listOf(extension.versionName, update.published.versionName).filter { it.isNotEmpty() }.joinToString(" → ")
    val rest = if (extension.status == ExtensionStatus.Working) reads(extension) else listOf(describe(extension))
    return (listOf(versions) + rest).filter { it.isNotEmpty() }.joinToString(" · ")
}

/** The languages and sites [extension]'s sources read. */
private fun reads(extension: ExtensionInfo): List<String> {
    val languages = extension.sources.map { it.lang }.distinct().map(::language)
    val sites = extension.sources.mapNotNull { runCatching { URI(it.baseUrl).host?.removePrefix("www.") }.getOrNull() }.distinct()
    return languages + sites
}

private fun language(lang: String): String =
    when (lang) {
        "" -> ""
        ALL_LANGUAGES -> "All languages"
        else -> Locale.forLanguageTag(lang).getDisplayLanguage(Locale.getDefault())
    }

/** Asks whether to trust a key extensions the reader picked are signed with. */
@Composable
internal fun TrustDialog(request: TrustRequest, onTrust: () -> Unit, onDecline: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDecline,
        icon = { Icon(ReaderIcons.VerifiedUser, contentDescription = null) },
        title = { Text("Trust this signer?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                val what = if (request.extensions.size == 1) "${request.extensions[0]} is" else "${countOf(request.extensions.size, "extension")} are"
                Text("$what signed with a key Fanos hasn't seen before. Extensions run inside Fanos, so only trust a key you know.")
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.secondaryContainer)
                        .padding(10.dp),
                ) {
                    Text(
                        fingerprint(request.signer),
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = onTrust) { Text("Trust and install") } },
        dismissButton = { TextButton(onClick = onDecline) { Text("Cancel") } },
    )
}

/** A key's SHA-256 in groups of four, four groups to a line, as the key's owner would read it out. */
private fun fingerprint(signer: String): String =
    signer.split('+').joinToString("\n\n") { key -> key.chunked(4).chunked(4).joinToString("\n") { it.joinToString(" ") } }

/** What the file picker offers: APKs, and files whose type the phone doesn't know. */
internal val EXTENSION_FILE_TYPES = arrayOf("application/vnd.android.package-archive", "application/octet-stream")
