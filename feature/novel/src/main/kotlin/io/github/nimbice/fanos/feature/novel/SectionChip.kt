package io.github.nimbice.fanos.feature.novel

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.nimbice.fanos.core.designsystem.component.NameDialog
import io.github.nimbice.fanos.core.designsystem.icon.ReaderIcons
import io.github.nimbice.fanos.core.model.LibrarySection

/**
 * The sections a library novel is in, as a chip. It opens them as checkboxes (the menu stays open, for
 * ticking several), with a way to start another section with the novel in it. The last section ticked
 * can't be unticked: a novel is always in one.
 */
@Composable
internal fun SectionChip(
    sections: List<LibrarySection>,
    inSections: Set<Long>,
    onToggle: (sectionId: Long, inSection: Boolean) -> Unit,
    onCreate: (name: String) -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    var naming by remember { mutableStateOf(false) }
    Box {
        AssistChip(
            onClick = { menu = true },
            label = {
                Text(
                    sections.filter { it.id in inSections }.joinToString(", ") { it.name },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            },
            leadingIcon = { Icon(ReaderIcons.Folder, contentDescription = null, modifier = Modifier.size(18.dp)) },
            trailingIcon = { Icon(Icons.Filled.ArrowDropDown, contentDescription = "Sections") },
        )
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            sections.forEach { section ->
                val checked = section.id in inSections
                val last = checked && inSections.size == 1
                DropdownMenuItem(
                    text = { Text(section.name) },
                    leadingIcon = { Checkbox(checked = checked, onCheckedChange = null, enabled = !last) },
                    onClick = { onToggle(section.id, !checked) },
                    enabled = !last,
                )
            }
            HorizontalDivider()
            DropdownMenuItem(
                text = { Text("New section…") },
                leadingIcon = { Icon(Icons.Filled.Add, contentDescription = null) },
                onClick = {
                    menu = false
                    naming = true
                },
            )
        }
    }
    if (naming) {
        NameDialog(
            title = "New section",
            initial = "",
            confirm = "Add",
            taken = sections.map { it.name },
            onSave = { name ->
                naming = false
                onCreate(name)
            },
            onDismiss = { naming = false },
        )
    }
}
