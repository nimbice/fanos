package io.github.nimbice.fanos.feature.settings

import android.text.format.Formatter
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.nimbice.fanos.core.updater.UpdateState
import io.github.nimbice.fanos.core.updater.Updater
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject
import io.github.nimbice.fanos.core.data.extension.ExtensionUpdates
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn

/** What a look for extension updates found, from Settings: nothing to show (no key), looking, what it found, or that it couldn't. */
sealed interface ExtensionsLook {
    data object None : ExtensionsLook

    data object Looking : ExtensionsLook

    data class Found(val updates: List<String>, val installed: Int) : ExtensionsLook

    data object Failed : ExtensionsLook
}

@HiltViewModel
class UpdatesViewModel @Inject constructor(private val updater: Updater, private val extensionUpdates: ExtensionUpdates) : ViewModel() {
    val state: StateFlow<UpdateState> = updater.state

    /** Whether a key for private builds is set: the key dialog then offers to remove it. */
    val hasKey: StateFlow<Boolean> = updater.hasKey.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    private val _extensions = MutableStateFlow<ExtensionsLook>(ExtensionsLook.None)

    /** Updates to the installed extensions, looked for as Updates opens and with "Check now". */
    val extensions: StateFlow<ExtensionsLook> = _extensions.asStateFlow()

    init {
        // Opening the settings looks again when the last look was a while ago; the extensions are looked at each time.
        viewModelScope.launch { if (updater.checkDue()) updater.check() }
        lookAtExtensions()
    }

    fun check() {
        viewModelScope.launch { updater.check() }
        lookAtExtensions()
    }

    private fun lookAtExtensions() {
        viewModelScope.launch {
            if (_extensions.value !is ExtensionsLook.Found) _extensions.value = ExtensionsLook.Looking
            _extensions.value =
                try {
                    extensionUpdates.look().let { ExtensionsLook.Found(it.updates, it.installed) }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    ExtensionsLook.Failed
                }
        }
    }

    fun install() = updater.install()

    fun cancel() = updater.cancelInstall()

    fun setToken(token: String?) {
        viewModelScope.launch {
            updater.setToken(token)
            lookAtExtensions()
        }
    }
}

/**
 * The app's updates: whether there's a newer build and installing it. What the updater is doing shows as it
 * happens. The key private builds are read with has no button of its own: the Version row opens its dialog on a
 * long press ([settingUp]), so nothing asks a public reader for a key.
 */
@Composable
internal fun UpdatesItem(settingUp: Boolean, onSettingUp: (Boolean) -> Unit, modifier: Modifier = Modifier, viewModel: UpdatesViewModel = hiltViewModel()) {
    val context = LocalContext.current
    val state by viewModel.state.collectAsStateWithLifecycle()
    val hasKey by viewModel.hasKey.collectAsStateWithLifecycle()
    when (val update = state) {
        UpdateState.NotSetUp ->
            SettingsItem(
                "No build published yet",
                modifier,
                summary = { Text("Fanos looks for public builds on GitHub.") },
                trailing = { OutlinedButton(onClick = viewModel::check) { Text("Check now") } },
            )
        is UpdateState.UpToDate ->
            SettingsItem(
                "Up to date",
                modifier,
                summary = {
                    Text(update.checkedAt?.let { "Checked ${ago(context, it)}" } ?: "Not checked yet")
                },
                trailing = { OutlinedButton(onClick = viewModel::check) { Text("Check now") } },
            )
        UpdateState.Checking ->
            SettingsItem(
                "Checking for updates…",
                modifier,
                trailing = { CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp) },
            )
        is UpdateState.Available ->
            SettingsItem(
                "Update available",
                modifier,
                summary = { Text("${update.update.versionName} · ${Formatter.formatShortFileSize(context, update.update.size)}") },
                trailing = { Button(onClick = viewModel::install) { Text("Install") } },
            )
        is UpdateState.Downloading ->
            SettingsItem(
                "Downloading ${update.update.versionName}",
                modifier,
                summary = { LinearProgressIndicator(progress = { update.progress }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) },
                trailing = { OutlinedButton(onClick = viewModel::cancel) { Text("Cancel") } },
            )
        is UpdateState.Installing ->
            SettingsItem(
                "Installing ${update.update.versionName}",
                modifier,
                summary = { Text("Android may ask you to confirm") },
            )
        is UpdateState.Failed ->
            SettingsItem(
                "Update problem",
                modifier,
                summary = { Text(update.message) },
                trailing = { OutlinedButton(onClick = viewModel::check) { Text("Try again") } },
            )
    }
    if (settingUp) {
        KeyDialog(
            hasKey = hasKey,
            onSave = { key ->
                viewModel.setToken(key)
                onSettingUp(false)
            },
            onDismiss = { onSettingUp(false) },
        )
    }
}

/** Whether the installed extensions have updates, looked for with the app's; Open goes to the extensions to install them. */
@Composable
internal fun ExtensionUpdatesItem(look: ExtensionsLook, onOpenExtensions: () -> Unit, modifier: Modifier = Modifier) {
    when (look) {
        ExtensionsLook.None -> Unit
        ExtensionsLook.Looking ->
            SettingsItem(
                "Looking for extension updates\u2026",
                modifier,
                trailing = { CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp) },
            )
        is ExtensionsLook.Found ->
            if (look.updates.isEmpty()) {
                SettingsItem("Extensions up to date", modifier, summary = { Text(if (look.installed == 1) "1 installed" else "${look.installed} installed") })
            } else {
                SettingsItem(
                    if (look.updates.size == 1) "1 extension update" else "${look.updates.size} extension updates",
                    modifier,
                    summary = { Text(look.updates.joinToString(", ")) },
                    trailing = { OutlinedButton(onClick = onOpenExtensions) { Text("Open") } },
                )
            }
        ExtensionsLook.Failed ->
            SettingsItem("Extension updates", modifier, summary = { Text("Couldn't look for them. Check your connection.") })
    }
}

/** Asks for the read-only GitHub token builds are read with; saving checks for an update at once. */
@Composable
private fun KeyDialog(hasKey: Boolean, onSave: (String?) -> Unit, onDismiss: () -> Unit) {
    var key by rememberSaveable { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Update key") },
        text = {
            Column {
                Text(
                    "A GitHub token that can read nimbice/fanos-builds and nothing else. It stays on this device and isn't backed up.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                OutlinedTextField(
                    value = key,
                    onValueChange = { key = it.trim() },
                    label = { Text(if (hasKey) "New token" else "Token") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                )
            }
        },
        confirmButton = { TextButton(onClick = { onSave(key) }, enabled = key.isNotBlank()) { Text("Save") } },
        dismissButton = {
            Row {
                if (hasKey) TextButton(onClick = { onSave(null) }) { Text("Remove") }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        },
    )
}
