package io.github.nimbice.fanos.feature.library

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.nimbice.fanos.core.data.repository.LibraryRepository
import io.github.nimbice.fanos.core.data.repository.SectionRepository
import io.github.nimbice.fanos.core.designsystem.component.NameDialog
import io.github.nimbice.fanos.core.designsystem.component.ReorderableItem
import io.github.nimbice.fanos.core.designsystem.component.countOf
import io.github.nimbice.fanos.core.designsystem.component.rememberReorderState
import io.github.nimbice.fanos.core.designsystem.component.reorderable
import io.github.nimbice.fanos.core.designsystem.icon.ReaderIcons
import io.github.nimbice.fanos.core.model.LibrarySection
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import javax.inject.Inject

/** The library's sections, to add, rename, reorder and delete. */
@Serializable
data object SectionsRoute

/** A section and how many library novels are in it. */
data class SectionRow(val section: LibrarySection, val novels: Int)

@HiltViewModel
class SectionsViewModel @Inject constructor(private val sections: SectionRepository, library: LibraryRepository) : ViewModel() {
    /** Null until loaded. */
    val rows: StateFlow<List<SectionRow>?> =
        combine(sections.observeSections(), library.observeLibrary()) { sections, novels ->
            sections.map { section -> SectionRow(section, novels.count { section.id in it.sectionIds }) }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun add(name: String) {
        viewModelScope.launch { sections.create(name) }
    }

    fun rename(id: Long, name: String) {
        viewModelScope.launch { sections.rename(id, name) }
    }

    fun delete(id: Long) {
        viewModelScope.launch { sections.delete(id) }
    }

    fun reorder(ids: List<Long>) {
        viewModelScope.launch { sections.reorder(ids) }
    }
}

/**
 * The sections as cards in one group, in the library's tab order: a long press and drag reorders them.
 * The default section can be renamed but not deleted; deleting another moves the novels only in it to the
 * default one.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SectionsScreen(onBack: () -> Unit, viewModel: SectionsViewModel = hiltViewModel()) {
    val saved = viewModel.rows.collectAsStateWithLifecycle().value
    var arranged by remember { mutableStateOf<List<SectionRow>?>(null) }
    val rows = arranged ?: saved.orEmpty()
    LaunchedEffect(saved) {
        val local = arranged ?: return@LaunchedEffect
        val ids = saved.orEmpty().map { it.section.id }
        if (ids == local.map { it.section.id } || ids.toSet() != local.map { it.section.id }.toSet()) arranged = null
    }
    val listState = rememberLazyListState()
    val padding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp)
    val reorder =
        rememberReorderState(
            listState,
            padding,
            onMove = { from, to -> if (from < rows.size && to < rows.size) arranged = rows.toMutableList().apply { add(to, removeAt(from)) } },
            onDrop = { arranged?.let { local -> viewModel.reorder(local.map { it.section.id }) } },
        )
    var adding by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf<LibrarySection?>(null) }
    var deleting by remember { mutableStateOf<SectionRow?>(null) }
    val names = rows.map { it.section.name }
    val defaultName = rows.firstOrNull { it.section.isDefault }?.section?.name ?: "the default section"

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Sections") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } },
            )
        },
    ) { inner ->
        LazyColumn(
            state = listState,
            contentPadding = padding,
            verticalArrangement = Arrangement.spacedBy(2.dp),
            modifier = Modifier.fillMaxSize().padding(inner).reorderable(reorder),
        ) {
            items(rows.size, key = { rows[it].section.id }) { index ->
                val row = rows[index]
                ReorderableItem(reorder, key = row.section.id) { _ ->
                    SectionCard(
                        row,
                        first = index == 0,
                        last = index == rows.lastIndex,
                        onRename = { renaming = row.section },
                        onDelete = { deleting = row },
                    )
                }
            }
            item(key = "add") {
                Column(Modifier.fillMaxWidth().padding(top = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedButton(onClick = { adding = true }, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Filled.Add, contentDescription = null)
                        Text("Add section", modifier = Modifier.padding(start = 8.dp))
                    }
                    Text(
                        "Long-press and drag to reorder. Deleting a section moves its novels to $defaultName, " +
                            "unless they're in another section too.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 8.dp),
                    )
                }
            }
        }
    }

    if (adding) {
        NameDialog(
            title = "New section",
            initial = "",
            confirm = "Add",
            taken = names,
            onSave = { name ->
                adding = false
                viewModel.add(name)
            },
            onDismiss = { adding = false },
        )
    }
    renaming?.let { section ->
        NameDialog(
            title = "Rename section",
            initial = section.name,
            confirm = "Rename",
            taken = names,
            onSave = { name ->
                renaming = null
                viewModel.rename(section.id, name)
            },
            onDismiss = { renaming = null },
        )
    }
    deleting?.let { row ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("Delete ${row.section.name}?") },
            text = {
                Text(
                    when (row.novels) {
                        0 -> "It has no novels in it."
                        1 -> "Its novel stays in the library, in $defaultName if it's in no other section."
                        else -> "Its ${row.novels} novels stay in the library; any in no other section go to $defaultName."
                    },
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    deleting = null
                    viewModel.delete(row.section.id)
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Cancel") } },
        )
    }
}

/** A section as a card of the group, round where the group begins and ends. */
@Composable
private fun SectionCard(row: SectionRow, first: Boolean, last: Boolean, onRename: () -> Unit, onDelete: () -> Unit) {
    val top = if (first) 20.dp else 4.dp
    val bottom = if (last) 20.dp else 4.dp
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(topStart = top, topEnd = top, bottomStart = bottom, bottomEnd = bottom))
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .heightIn(min = 64.dp)
            .padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(ReaderIcons.DragHandle, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Column(Modifier.weight(1f).padding(start = 16.dp)) {
            Text(row.section.name, style = MaterialTheme.typography.bodyLarge)
            Text(
                countOf(row.novels, "novel") + if (row.section.isDefault) " · where new novels go" else "",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        IconButton(onClick = onRename) { Icon(Icons.Filled.Edit, contentDescription = "Rename ${row.section.name}") }
        if (!row.section.isDefault) {
            IconButton(onClick = onDelete) { Icon(Icons.Filled.Delete, contentDescription = "Delete ${row.section.name}") }
        }
    }
}
