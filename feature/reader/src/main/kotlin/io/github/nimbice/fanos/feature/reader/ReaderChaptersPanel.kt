package io.github.nimbice.fanos.feature.reader

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsIgnoringVisibility
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.waterfall
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.nimbice.fanos.core.designsystem.icon.ReaderIcons
import android.text.format.DateUtils
import androidx.compose.material3.SecondaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import io.github.nimbice.fanos.core.model.Bookmark
import io.github.nimbice.fanos.core.model.Highlight
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.foundation.layout.BoxWithConstraints
import io.github.nimbice.fanos.core.designsystem.component.fittingStyle

/**
 * The novel's chapters, over the lower part of the reader as the settings are, so the page stays in view
 * above; in the order the novel's page lists them, oldest or newest first. It opens on the chapter being
 * read; a tap goes to another, where the reader left it. Read chapters are dimmed, saved ones marked, and
 * ones the reader can't read now (members only) locked. Its other tabs list the novel's bookmarks and highlights.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ReaderChaptersPanel(
    list: ChapterList,
    currentId: Long?,
    onOpen: (Long) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    bookmarks: List<Bookmark> = emptyList(),
    onOpenBookmark: (Bookmark) -> Unit = {},
    onRemoveBookmark: (Bookmark) -> Unit = {},
    highlights: List<Highlight> = emptyList(),
    onOpenHighlight: (Highlight) -> Unit = {},
    onRemoveHighlight: (Highlight) -> Unit = {},
    onShareHighlights: () -> Unit = {},
) {
    val listState = rememberLazyListState()
    // Chapters, bookmarks or highlights.
    var tab by rememberSaveable { mutableIntStateOf(0) }
    // The chapter being read in view, a couple of rows down so the ones before it show too.
    LaunchedEffect(list.chapters.isNotEmpty()) {
        val at = list.chapters.indexOfFirst { it.id == currentId }
        if (at >= 0) listState.scrollToItem((at - 2).coerceAtLeast(0))
    }
    // Where the rows would run into a rounded screen's corners, a curved edge, a cutout, or the navigation bar's place,
    // which stays while the reader hides the bar.
    val edges = WindowInsets.navigationBarsIgnoringVisibility.union(WindowInsets.displayCutout).union(WindowInsets.waterfall)
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shadowElevation = 8.dp,
    ) {
        Column(Modifier.windowInsetsPadding(edges.only(WindowInsetsSides.Horizontal))) {
            Row(Modifier.fillMaxWidth().padding(end = 4.dp, top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                // Three tabs to a row: their labels a size smaller when one wouldn't fit.
                BoxWithConstraints(Modifier.weight(1f)) {
                    val labels =
                        listOf(
                            "Chapters \u00b7 ${list.chapters.size}",
                            if (bookmarks.isEmpty()) "Bookmarks" else "Bookmarks \u00b7 ${bookmarks.size}",
                            if (highlights.isEmpty()) "Highlights" else "Highlights \u00b7 ${highlights.size}",
                        )
                    val style = fittingStyle(labels, maxWidth / labels.size - TAB_PADDING * 2, MaterialTheme.typography.titleSmall)
                    SecondaryTabRow(selectedTabIndex = tab, containerColor = Color.Transparent) {
                        labels.forEachIndexed { index, label -> Tab(selected = tab == index, onClick = { tab = index }, text = { Text(label, style = style, maxLines = 1) }) }
                    }
                }
                IconButton(onClick = onClose) { Icon(Icons.Filled.Close, contentDescription = "Close") }
            }
            if (tab == 1) {
                Bookmarks(bookmarks, onOpenBookmark, onRemoveBookmark, Modifier.fillMaxWidth().weight(1f).windowInsetsPadding(edges.only(WindowInsetsSides.Bottom)))
                return@Column
            }
            if (tab == 2) {
                HighlightList(highlights, onOpenHighlight, onRemoveHighlight, Modifier.fillMaxWidth().weight(1f).windowInsetsPadding(edges.only(WindowInsetsSides.Bottom)), onShareHighlights)
                return@Column
            }
            LazyColumn(state = listState, modifier = Modifier.fillMaxWidth().weight(1f).windowInsetsPadding(edges.only(WindowInsetsSides.Bottom))) {
                items(list.chapters, key = { it.id }) { chapter ->
                    val current = chapter.id == currentId
                    val colors = MaterialTheme.colorScheme
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable { onOpen(chapter.id) }
                            .drawBehind {
                                if (current) {
                                    drawRect(colors.primaryContainer)
                                    drawRect(colors.primary, size = Size(4.dp.toPx(), size.height))
                                }
                            }
                            .padding(horizontal = 20.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            chapter.title,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = if (current) FontWeight.SemiBold else null,
                            color =
                                when {
                                    current -> colors.onPrimaryContainer
                                    chapter.isRead || chapter.locked -> colors.onSurfaceVariant.copy(alpha = 0.7f)
                                    else -> colors.onSurface
                                },
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        if (chapter.id in list.saved) {
                            Icon(ReaderIcons.DownloadDone, contentDescription = "Saved", tint = colors.onSurfaceVariant, modifier = Modifier.padding(start = 8.dp).size(18.dp))
                        }
                        if (chapter.locked) {
                            Icon(Icons.Filled.Lock, contentDescription = "Members only", tint = colors.onSurfaceVariant, modifier = Modifier.padding(start = 8.dp).size(18.dp))
                        }
                    }
                }
            }
        }
    }
}

/** The novel's bookmarks, the latest first: a tap goes to one, the cross takes it off. */
@Composable
private fun Bookmarks(bookmarks: List<Bookmark>, onOpen: (Bookmark) -> Unit, onRemove: (Bookmark) -> Unit, modifier: Modifier) {
    if (bookmarks.isEmpty()) {
        Text(
            "Long-press a paragraph and choose Bookmark to come back to it here.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = modifier.padding(20.dp),
        )
        return
    }
    val now = System.currentTimeMillis()
    LazyColumn(modifier) {
        items(bookmarks, key = { it.id }) { bookmark ->
            Row(Modifier.fillMaxWidth().clickable { onOpen(bookmark) }.padding(start = 20.dp, end = 4.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        "${bookmark.chapterTitle} \u00b7 ${DateUtils.getRelativeTimeSpanString(bookmark.createdAt, now, DateUtils.DAY_IN_MILLIS)}",
                        style = MaterialTheme.typography.labelLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text("\u201c${bookmark.snippet}\u201d", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                IconButton(onClick = { onRemove(bookmark) }) { Icon(Icons.Filled.Close, contentDescription = "Remove bookmark") }
            }
        }
    }
}

/** A tab's room either side of its label. */
private val TAB_PADDING = 16.dp
