package io.github.nimbice.fanos.feature.library

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TriStateCheckbox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.toRect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.nimbice.fanos.core.designsystem.component.NovelCover
import io.github.nimbice.fanos.core.designsystem.component.WithRecentSearches
import io.github.nimbice.fanos.core.designsystem.component.chapterSummary
import io.github.nimbice.fanos.core.designsystem.component.countOf
import io.github.nimbice.fanos.core.designsystem.component.number
import io.github.nimbice.fanos.core.designsystem.icon.ReaderIcons
import io.github.nimbice.fanos.core.model.LibraryDisplay
import io.github.nimbice.fanos.core.model.LibraryFilter
import io.github.nimbice.fanos.core.model.LibraryNovel
import io.github.nimbice.fanos.core.model.LibrarySection
import io.github.nimbice.fanos.core.model.LibrarySort
import io.github.nimbice.fanos.core.designsystem.component.FittingText
import kotlinx.coroutines.delay

/** The library's header: the unread count, and its buttons. */
@Composable
internal fun LibraryHeader(
    text: String,
    filtered: Boolean,
    onSearch: () -> Unit,
    onFilter: () -> Unit,
    onOpenSections: () -> Unit,
    onMoveNovels: () -> Unit,
) {
    // A little room between the buttons, so none is pressed for its neighbour.
    Row(
        Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        IconButton(onClick = onSearch) { Icon(Icons.Filled.Search, contentDescription = "Search the library") }
        IconButton(onClick = onFilter) {
            Box {
                Icon(ReaderIcons.Tune, contentDescription = if (filtered) "Filters and order (a filter is on)" else "Filters and order")
                // A filter is on: the library shows less than all of it.
                if (filtered) Box(Modifier.align(Alignment.TopEnd).size(8.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary))
            }
        }
        IconButton(onClick = onOpenSections) { Icon(ReaderIcons.Folder, contentDescription = "Sections") }
        IconButton(onClick = onMoveNovels) { Icon(ReaderIcons.Move, contentDescription = "Move novels to another site") }
    }
}

/** The header while searching: the search itself, which narrows every section. */
@Composable
internal fun SearchBar(
    query: String,
    onQueryChange: (String) -> Unit,
    onClose: () -> Unit,
    recent: List<String>,
    onSearch: () -> Unit,
    onPick: (String) -> Unit,
    onForget: (String) -> Unit,
) {
    val focus = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    LaunchedEffect(Unit) { focus.requestFocus() }
    WithRecentSearches(query, recent, onPick, onForget, Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)) { anchor ->
        TextField(
            value = query,
            onValueChange = onQueryChange,
            placeholder = { FittingText("Search the library") },
            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
            trailingIcon = { IconButton(onClick = onClose) { Icon(Icons.Filled.Close, contentDescription = "Stop searching") } },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            // The library narrows as it's typed in: the search key puts the keyboard away, and keeps the words.
            keyboardActions =
                KeyboardActions(onSearch = {
                    focusManager.clearFocus()
                    onSearch()
                }),
            shape = RoundedCornerShape(28.dp),
            colors =
                TextFieldDefaults.colors(
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                    focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                ),
            modifier = anchor.fillMaxWidth().focusRequester(focus),
        )
    }
}

/** The header while novels are selected: how many, and what can be done with them. */
@Composable
internal fun SelectionBar(
    count: Int,
    onClose: () -> Unit,
    onSelectAll: () -> Unit,
    onSections: () -> Unit,
    onMarkRead: () -> Unit,
    onDownload: () -> Unit,
    onRemove: () -> Unit,
) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh) {
        Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onClose) { Icon(Icons.Filled.Close, contentDescription = "Stop selecting") }
            Text("${number(count)} selected", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f).padding(start = 4.dp))
            IconButton(onClick = onSelectAll) { Icon(ReaderIcons.SelectAll, contentDescription = "Select all") }
            IconButton(onClick = onSections) { Icon(ReaderIcons.Folder, contentDescription = "Sections") }
            IconButton(onClick = onMarkRead) { Icon(ReaderIcons.DoneAll, contentDescription = "Mark every chapter read") }
            IconButton(onClick = onDownload) { Icon(ReaderIcons.Download, contentDescription = "Download the unread chapters") }
            IconButton(onClick = onRemove) { Icon(ReaderIcons.LibraryRemove, contentDescription = "Take out of the library") }
        }
    }
}

/**
 * Filters (novels with unread chapters, downloaded ones, completed ones), the order (the reader's own, which dragging
 * keeps, or by last read, new chapters, title or most unread), and grid or list.
 */
@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
internal fun FilterSheet(
    filters: Set<LibraryFilter>,
    sort: LibrarySort,
    display: LibraryDisplay,
    onFilter: (LibraryFilter, Boolean) -> Unit,
    onSort: (LibrarySort) -> Unit,
    onDisplay: (LibraryDisplay) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
            SheetLabel("Show only")
            FlowRow(Modifier.padding(horizontal = 24.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                LibraryFilter.entries.forEach { filter ->
                    val on = filter in filters
                    FilterChip(
                        selected = on,
                        onClick = { onFilter(filter, !on) },
                        label = { Text(filter.label) },
                        leadingIcon = if (on) ({ Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.size(FilterChipDefaults.IconSize)) }) else null,
                    )
                }
            }
            SheetLabel("Order")
            LibrarySort.entries.forEach { choice ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .selectable(selected = choice == sort, role = Role.RadioButton, onClick = { onSort(choice) })
                        .padding(horizontal = 12.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(selected = choice == sort, onClick = null, modifier = Modifier.padding(12.dp))
                    Column {
                        Text(choice.label, style = MaterialTheme.typography.bodyLarge)
                        if (choice == LibrarySort.Yours) {
                            Text("The one you drag novels into", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
            SheetLabel("Show as")
            Row(Modifier.padding(horizontal = 24.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = display == LibraryDisplay.Grid,
                    onClick = { onDisplay(LibraryDisplay.Grid) },
                    label = { Text("Grid") },
                    leadingIcon = { Icon(ReaderIcons.GridView, contentDescription = null, modifier = Modifier.size(FilterChipDefaults.IconSize)) },
                )
                FilterChip(
                    selected = display == LibraryDisplay.List,
                    onClick = { onDisplay(LibraryDisplay.List) },
                    label = { Text("List") },
                    leadingIcon = { Icon(ReaderIcons.ViewList, contentDescription = null, modifier = Modifier.size(FilterChipDefaults.IconSize)) },
                )
            }
        }
    }
}

@Composable
private fun SheetLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 16.dp, bottom = 8.dp),
    )
}

private val LibraryFilter.label: String
    get() =
        when (this) {
            LibraryFilter.Unread -> "With unread"
            LibraryFilter.Downloaded -> "Downloaded"
            LibraryFilter.Completed -> "Completed"
        }

private val LibrarySort.label: String
    get() =
        when (this) {
            LibrarySort.Yours -> "Your order"
            LibrarySort.LastRead -> "Last read"
            LibrarySort.NewChapters -> "New chapters"
            LibrarySort.Title -> "Title"
            LibrarySort.Unread -> "Most unread"
        }

/**
 * The sections of the novels selected: ticked when all of them are in it, half when some are. What's changed is put
 * right for all of them; a section left half ticked stays as it is for each.
 */
@Composable
internal fun SectionsDialog(sections: List<LibrarySection>, novels: List<LibraryNovel>, onApply: (Map<Long, Boolean>) -> Unit, onDismiss: () -> Unit) {
    val initial =
        remember(sections, novels) {
            sections.associate { section ->
                val inIt = novels.count { section.id in it.sectionIds }
                section.id to
                    when (inIt) {
                        0 -> ToggleableState.Off
                        novels.size -> ToggleableState.On
                        else -> ToggleableState.Indeterminate
                    }
            }
        }
    val states = remember(initial) { mutableStateMapOf<Long, ToggleableState>().apply { putAll(initial) } }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Sections of ${countOf(novels.size, "novel")}") },
        text = {
            Column {
                sections.forEach { section ->
                    val state = states[section.id] ?: ToggleableState.Off
                    val next = if (state == ToggleableState.On) ToggleableState.Off else ToggleableState.On
                    Row(
                        Modifier.fillMaxWidth().clickable(role = Role.Checkbox) { states[section.id] = next },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        TriStateCheckbox(state = state, onClick = { states[section.id] = next })
                        Text(section.name, style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onApply(states.filter { (id, state) -> state != initial[id] && state != ToggleableState.Indeterminate }.mapValues { it.value == ToggleableState.On })
            }) { Text("Done") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** How many unread chapters downloading the selected novels' would fetch, and the go-ahead. */
@Composable
internal fun DownloadDialog(novels: Int, count: suspend () -> Int, waitsForWifi: suspend () -> Boolean, onDownload: (Int) -> Unit, onDismiss: () -> Unit) {
    var chapters by remember { mutableStateOf<Int?>(null) }
    var wifi by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        wifi = waitsForWifi()
        chapters = count()
    }
    val found = chapters
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Download the unread chapters?") },
        text = {
            Text(
                when {
                    found == null -> "Counting the unread chapters of ${countOf(novels, "novel")}…"
                    found == 0 -> "The unread chapters of ${if (novels == 1) "this novel" else "these novels"} are all downloaded already."
                    else -> "${countOf(found, "unread chapter")} of ${countOf(novels, "novel")}" + if (wifi) ", over Wi-Fi." else "."
                },
            )
        },
        confirmButton = { TextButton(onClick = { onDownload(found ?: 0) }, enabled = found != null && found > 0) { Text("Download") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/**
 * A novel in the grid. While an update has yet to finish checking it ([waiting]) it's greyed out, and once its check
 * ([checkingSince]) is holding the update up, says so over the cover in red. Why the last update couldn't check it
 * stays under it, in red, until a check goes through.
 */
@Composable
internal fun LibraryItem(
    item: LibraryNovel,
    waiting: Boolean,
    checkingSince: Long?,
    dragging: Boolean,
    selected: Boolean,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)?,
) {
    val grey by animateFloatAsState(if (waiting) 1f else 0f, label = "waiting")
    val slow = slowSeconds(checkingSince)
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = if (dragging) MaterialTheme.colorScheme.surfaceContainerHigh else Color.Transparent,
        shadowElevation = if (dragging) 6.dp else 0.dp,
    ) {
        Column(Modifier.pressable(onClick, onLongClick).padding(6.dp)) {
            Box {
                Box(Modifier.greyed(grey)) {
                    NovelCover(item.novel.coverUrl, item.novel.title, Modifier.fillMaxWidth())
                    if (selected) {
                        Box(
                            Modifier
                                .matchParentSize()
                                .clip(RoundedCornerShape(8.dp))
                                .background(Color.Black.copy(alpha = 0.45f))
                                .border(3.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(8.dp)),
                        )
                        SelectedMark(Modifier.align(Alignment.TopStart).padding(6.dp))
                    }
                    if (item.unreadCount > 0) {
                        Badge(
                            containerColor = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.align(Alignment.TopEnd).padding(6.dp),
                        ) { Text(item.unreadCount.toString()) }
                    }
                }
                // Over the greyed cover, not greyed with it.
                if (slow != null) SlowMark(slow, Modifier.align(Alignment.Center).padding(4.dp))
            }
            Text(
                item.novel.title,
                style = MaterialTheme.typography.labelLarge,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 6.dp).greyed(grey),
            )
            item.updateError?.let { UpdateError(it, Modifier.padding(top = 4.dp).fillMaxWidth().greyed(grey)) }
        }
    }
}

/**
 * A novel in the list, greyed out while an update has yet to finish checking it ([waiting]). At its right, in red: how
 * long its check ([checkingSince]) has taken once that holds the update up, else why the last update couldn't check it.
 */
@Composable
internal fun LibraryRow(
    item: LibraryNovel,
    sourceName: String?,
    waiting: Boolean,
    checkingSince: Long?,
    dragging: Boolean,
    selected: Boolean,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)?,
) {
    val grey by animateFloatAsState(if (waiting) 1f else 0f, label = "waiting")
    val slow = slowSeconds(checkingSince)
    Surface(
        shape = RectangleShape,
        color =
            when {
                dragging -> MaterialTheme.colorScheme.surfaceContainerHigh
                selected -> MaterialTheme.colorScheme.secondaryContainer
                else -> Color.Transparent
            },
        shadowElevation = if (dragging) 6.dp else 0.dp,
    ) {
        Row(
            Modifier.fillMaxWidth().pressable(onClick, onLongClick).padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.greyed(grey)) {
                NovelCover(item.novel.coverUrl, item.novel.title, Modifier.width(48.dp))
                if (selected) SelectedMark(Modifier.align(Alignment.Center))
            }
            Column(Modifier.weight(1f).greyed(grey)) {
                Text(item.novel.title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(
                    listOfNotNull(item.novel.author, sourceName).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    chapterSummary(item.chapterCount, item.unreadCount),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (slow != null) {
                SlowMark(slow)
            } else {
                item.updateError?.let { UpdateError(it, Modifier.widthIn(max = 120.dp).greyed(grey)) }
            }
            if (item.unreadCount > 0) {
                Badge(containerColor = MaterialTheme.colorScheme.primary, modifier = Modifier.greyed(grey)) { Text(item.unreadCount.toString()) }
            }
        }
    }
}

/**
 * How long the check started at [since] has taken, in seconds, once that's long enough to be holding the update up;
 * otherwise null. Counts up each second.
 */
@Composable
private fun slowSeconds(since: Long?): Long? {
    if (since == null) return null
    var now by remember(since) { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(since) {
        while (true) {
            delay(1_000)
            now = System.currentTimeMillis()
        }
    }
    return if (now - since >= SLOW_CHECK_MS) (now - since) / 1_000 else null
}

private const val SLOW_CHECK_MS = 10_000L

/** A check holding the update up, in red: how long it has taken so far. */
@Composable
private fun SlowMark(seconds: Long, modifier: Modifier = Modifier) {
    Text(
        "Slow · $seconds s",
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.error,
        maxLines = 1,
        modifier =
            modifier
                .clip(RoundedCornerShape(4.dp))
                .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.9f))
                .padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

/** Why the last update couldn't check a novel, as a red block. */
@Composable
private fun UpdateError(message: String, modifier: Modifier = Modifier) {
    Text(
        message,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.error,
        maxLines = 4,
        overflow = TextOverflow.Ellipsis,
        modifier =
            modifier
                .clip(RoundedCornerShape(4.dp))
                .background(MaterialTheme.colorScheme.errorContainer)
                .padding(horizontal = 6.dp, vertical = 4.dp),
    )
}

/**
 * Drawn without colour and faded, by [amount] from 0 (as it is) to 1 (fully): a novel the running update has yet to
 * finish checking. Animated, it fades back in as the check finishes.
 */
private fun Modifier.greyed(amount: Float): Modifier =
    if (amount <= 0f) {
        this
    } else {
        drawWithCache {
            val paint =
                Paint().apply {
                    colorFilter = ColorFilter.colorMatrix(ColorMatrix().apply { setToSaturation(1f - amount) })
                    alpha = 1f - GREYED_FADE * amount
                }
            onDrawWithContent {
                drawContext.canvas.saveLayer(size.toRect(), paint)
                drawContent()
                drawContext.canvas.restore()
            }
        }
    }

/** How much of a greyed novel fades away. */
private const val GREYED_FADE = 0.6f

/** A tap, and a long press where the library can't be dragged (where it can, the drag takes long presses). */
@OptIn(ExperimentalFoundationApi::class)
private fun Modifier.pressable(onClick: () -> Unit, onLongClick: (() -> Unit)?): Modifier =
    if (onLongClick == null) clickable(onClick = onClick) else combinedClickable(onClick = onClick, onLongClick = onLongClick)

/** The tick on a selected novel's cover. */
@Composable
private fun SelectedMark(modifier: Modifier = Modifier) {
    Box(modifier.size(26.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary), contentAlignment = Alignment.Center) {
        Icon(Icons.Filled.Check, contentDescription = "Selected", tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(18.dp))
    }
}
