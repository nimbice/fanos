package io.github.nimbice.fanos.feature.settings

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.nimbice.fanos.core.data.readinglist.ReadingListStatus
import io.github.nimbice.fanos.core.data.readinglist.ReadingListSync
import io.github.nimbice.fanos.core.designsystem.component.countOf
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ReadingListsViewModel @Inject constructor(private val sync: ReadingListSync) : ViewModel() {

    /** The sources whose sites keep reading lists, and how keeping each in step is going. */
    val statuses: StateFlow<List<ReadingListStatus>> = sync.status.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun setEnabled(sourceId: String, on: Boolean) {
        viewModelScope.launch { sync.setEnabled(sourceId, on) }
    }

    fun syncNow(sourceId: String) = sync.syncNow(sourceId)

    /** Back from signing in, perhaps: lists waiting for it go on by themselves. */
    fun retrySignedOut() {
        statuses.value.filter { it.enabled && it.signInUrl != null && !it.syncing }.forEach { sync.syncNow(it.source.id) }
    }
}

/**
 * For each source whose site keeps reading lists: the switch that keeps them in step with the library, what that does,
 * and Sync, which puts every novel there now. Signed out of the site, it waits, and says so, with the way to sign in.
 */
@Composable
internal fun ReadingListSettings(onOpenInBrowser: (String) -> Unit, viewModel: ReadingListsViewModel = hiltViewModel()) {
    val context = LocalContext.current
    val statuses by viewModel.statuses.collectAsStateWithLifecycle()
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.retrySignedOut() }
    statuses.forEach { status ->
        val name = status.source.name
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SettingsGroup {
                item {
                    SwitchItem(name, "Keep my $name reading list in step with the library", status.enabled) { on ->
                        viewModel.setEnabled(status.source.id, on)
                    }
                }
                item {
                    Text(
                        "Its novels in your library go on your $name reading list: under the list named like their section, or your " +
                            "first list when none is. Each one's bookmark follows the last chapter you've read. Novels you take out of " +
                            "the library come off the list.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(16.dp),
                    )
                }
                item {
                    SettingsItem(
                        "Put them all there now",
                        enabled = status.enabled,
                        summary = {
                            // Waiting to sign in is something to do, not something gone wrong.
                            Text(
                                progress(context, status),
                                color =
                                    when {
                                        status.syncing || status.problem == null -> MaterialTheme.colorScheme.onSurfaceVariant
                                        status.signInUrl != null -> MaterialTheme.colorScheme.primary
                                        else -> MaterialTheme.colorScheme.error
                                    },
                            )
                        },
                        trailing = {
                            OutlinedButton(onClick = { viewModel.syncNow(status.source.id) }, enabled = status.enabled && !status.syncing) { Text("Sync") }
                        },
                    )
                }
            }
            val signIn = status.signInUrl
            Row(Modifier.padding(start = 32.dp, end = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (status.enabled && signIn != null) {
                        "Signed out of $name, so the list waits: sign in and it goes on."
                    } else {
                        "It goes by your sign-in to $name in Fanos's browser, and only ever tells $name: nothing done there changes the library."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                if (status.enabled && signIn != null) TextButton(onClick = { onOpenInBrowser(signIn) }) { Text("Sign in") }
            }
        }
    }
}

/** How the last try went, under "Put them all there now". */
private fun progress(context: Context, status: ReadingListStatus): String {
    val syncedAt = status.syncedAt
    val problem = status.problem
    return when {
        status.syncing -> "Putting them there…"
        problem != null -> problem
        syncedAt != null -> "Last in step ${ago(context, syncedAt)} · ${countOf(status.novels, "novel")}"
        status.enabled -> "Not in step yet"
        else -> "Off"
    }
}

/** What the Settings line says of the reading lists: each one kept in step, and how. */
internal fun readingListsSummary(statuses: List<ReadingListStatus>): Pair<String, Boolean?> {
    val on = statuses.filter { it.enabled }
    if (on.isEmpty()) return "Off" to null
    // null: nothing to see to; false: something to do (sign in); true: something went wrong.
    var trouble: Boolean? = null
    val text =
        on.joinToString(" · ") { status ->
            val how =
                when {
                    status.syncing -> "putting novels there…"
                    status.signInUrl != null -> "sign in to go on".also { if (trouble == null) trouble = false }
                    status.problem != null -> "not in step".also { trouble = true }
                    status.syncedAt != null -> "in step"
                    else -> "starting"
                }
            "${status.source.name}: $how"
        }
    return text to trouble
}
