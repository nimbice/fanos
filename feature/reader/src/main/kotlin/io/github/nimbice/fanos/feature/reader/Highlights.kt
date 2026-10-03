package io.github.nimbice.fanos.feature.reader

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.nimbice.fanos.core.content.ChapterContent
import io.github.nimbice.fanos.core.content.leaves
import io.github.nimbice.fanos.core.content.text
import io.github.nimbice.fanos.core.model.Highlight
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.OutlinedButton
import io.github.nimbice.fanos.core.content.Block
import io.github.nimbice.fanos.core.content.Span

/**
 * A stretch of a chapter's text: from [start] of paragraph [leaf] to [end] of paragraph [endLeaf] (often the same one),
 * reading [text], paragraphs a line apart.
 */
internal data class Stretch(val chapterId: Long, val leaf: Int, val start: Int, val endLeaf: Int, val end: Int, val text: String)

/**
 * The text from [start] of leaf [leaf] to [end] of leaf [endLeaf], as [leafText] gives the leaves', paragraphs a line
 * apart; null when one is missing or too short.
 */
internal fun textBetween(leaf: Int, start: Int, endLeaf: Int, end: Int, leafText: (Int) -> String?): String? {
    if (endLeaf < leaf) return null
    val parts = ArrayList<String>()
    for (at in leaf..endLeaf) {
        val text = leafText(at) ?: return null
        val from = if (at == leaf) start else 0
        val to = if (at == endLeaf) end else text.length
        if (from < 0 || to > text.length || from > to) return null
        parts += text.substring(from, to)
    }
    return parts.joinToString("\n")
}

/**
 * The text from [start] of leaf [leaf] to [end] of leaf [endLeaf] of [leaves], as [textBetween] gives it, but without the
 * footnote marks in it ("lit[2] one" is "lit one"): for copying and sharing, where a mark means nothing. Null when a
 * leaf is missing or too short.
 */
internal fun unmarkedBetween(leaves: List<Block>, leaf: Int, start: Int, endLeaf: Int, end: Int): String? {
    if (endLeaf < leaf) return null
    val parts = ArrayList<String>()
    for (at in leaf..endLeaf) {
        val block = leaves.getOrNull(at) ?: return null
        val text = block.text()
        val from = if (at == leaf) start else 0
        val to = if (at == endLeaf) end else text.length
        if (from < 0 || to > text.length || from > to) return null
        val spans =
            when (block) {
                is Block.Paragraph -> block.spans
                is Block.Heading -> block.spans
                is Block.Credit -> block.spans
                else -> listOf(Span(text))
            }
        val kept = StringBuilder()
        var offset = 0
        for (span in spans) {
            val spanStart = offset
            offset += span.text.length
            if (span.note != null) continue
            val a = maxOf(spanStart, from)
            val b = minOf(offset, to)
            if (a < b) kept.append(text, a, b)
        }
        parts += kept.toString().replace(DOUBLE_SPACE, " ")
    }
    return parts.joinToString("\n")
}

/** Two spaces where a mark between them was left out. */
private val DOUBLE_SPACE = Regex(" {2,}")

/** A highlight where its text is in the chapter as shown: from [start] of its first paragraph to [end] of its last. */
internal data class ShownHighlight(val highlight: Highlight, val start: Int, val end: Int) {
    /** Whether character [offset] of paragraph [leaf] is in it. */
    fun covers(leaf: Int, offset: Int): Boolean =
        leaf in highlight.block..highlight.endBlock &&
            !(leaf == highlight.block && offset < start) &&
            !(leaf == highlight.endBlock && offset >= end)

    /** It as a stretch, to highlight again in another colour or to note. */
    fun stretch(): Stretch = Stretch(highlight.chapterId, highlight.block, start, highlight.endBlock, end, highlight.text)
}

/**
 * Where [highlight]'s text is in [content]: where it was kept, else found again in its paragraph (a word replaced
 * before it, say); null when it's gone from there. One running on into later paragraphs shows only where it was kept.
 */
internal fun placeOf(highlight: Highlight, content: ChapterContent): ShownHighlight? {
    val quote = highlight.text
    if (quote.isEmpty()) return null
    if (highlight.endBlock > highlight.block) {
        val kept = textBetween(highlight.block, highlight.start, highlight.endBlock, highlight.end) { content.leaves.getOrNull(it)?.text() }
        return if (kept == quote) ShownHighlight(highlight, highlight.start, highlight.end) else null
    }
    val text = content.leaves.getOrNull(highlight.block)?.text() ?: return null
    if (highlight.start >= 0 && text.regionMatches(highlight.start, quote, 0, quote.length)) {
        return ShownHighlight(highlight, highlight.start, highlight.start + quote.length)
    }
    val at = text.indexOf(quote)
    return if (at >= 0) ShownHighlight(highlight, at, at + quote.length) else null
}

/** A note being written: for the highlight [highlightId], or for [stretch] of text to highlight with it. */
internal data class NoteEdit(val stretch: Stretch, val highlightId: Long?, val note: String) {
    val quote: String get() = stretch.text
}

/** The colours to highlight in, for the long-press menu; [chosen] ringed, when the text is highlighted already. */
@Composable
internal fun HighlightColors(chosen: Int?, onChoose: (Int) -> Unit) {
    Row(Modifier.padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Highlight", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        HIGHLIGHT_HUES.forEachIndexed { index, hue ->
            val ring = MaterialTheme.colorScheme.onSurface
            Box(
                Modifier
                    .size(30.dp)
                    .then(if (index == chosen) Modifier.border(2.dp, ring, CircleShape).padding(4.dp) else Modifier)
                    .clip(CircleShape)
                    .background(hue)
                    .semantics { contentDescription = "Highlight ${HUE_NAMES[index]}" }
                    .clickable(role = Role.Button) { onChoose(index) },
            )
        }
    }
}

/** Writing a note on highlighted text, [quote]: Save keeps it, and a highlight there can be taken off here too. */
@Composable
internal fun NoteDialog(quote: String, note: String, highlighted: Boolean, onSave: (String) -> Unit, onRemove: () -> Unit, onDismiss: () -> Unit) {
    var text by rememberSaveable { mutableStateOf(note) }
    val focus = remember { FocusRequester() }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Note") },
        text = {
            Column {
                Text(
                    "“$quote”",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis,
                )
                OutlinedTextField(value = text, onValueChange = { text = it }, minLines = 3, modifier = Modifier.fillMaxWidth().padding(top = 12.dp).focusRequester(focus))
            }
        },
        confirmButton = { TextButton(onClick = { onSave(text) }) { Text("Save") } },
        dismissButton = {
            Row {
                if (highlighted) TextButton(onClick = onRemove) { Text("Remove highlight", color = MaterialTheme.colorScheme.error) }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        },
    )
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
}

/** The novel's highlights as text to share: by chapter, each quoted, with its note under it. */
internal fun highlightsText(novelTitle: String, highlights: List<Highlight>): String =
    buildString {
        append(novelTitle.ifBlank { "Highlights" })
        if (novelTitle.isNotBlank()) append(" \u2014 highlights")
        for (group in highlights.groupBy { it.chapterId }.values) {
            append("\n\n").append(group.first().chapterTitle)
            for (highlight in group) {
                append("\n\u201c").append(highlight.text).append("\u201d")
                highlight.note?.let { append("\n   Note: ").append(it) }
            }
        }
    }

/** A quote to send on: the words, then the novel and chapter they're from. */
internal fun quoteText(quote: String, novelTitle: String, chapterTitle: String): String =
    buildString {
        append('“').append(quote).append('”')
        val from = listOf(novelTitle, chapterTitle).filter { it.isNotBlank() }.joinToString(", ")
        if (from.isNotEmpty()) append("\n— ").append(from)
    }

/**
 * The novel's highlights by chapter, in reading order, with their notes: a tap goes to one, the cross takes it off, and
 * Share all sends them on as text.
 */
@Composable
internal fun HighlightList(highlights: List<Highlight>, onOpen: (Highlight) -> Unit, onRemove: (Highlight) -> Unit, modifier: Modifier, onShare: () -> Unit = {}) {
    if (highlights.isEmpty()) {
        Text(
            "Long-press a sentence and choose a colour to highlight it. Highlights and their notes gather here.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = modifier.padding(20.dp),
        )
        return
    }
    val chapters = remember(highlights) { highlights.groupBy { it.chapterId }.values.toList() }
    LazyColumn(modifier) {
        item(key = "share") {
            Box(Modifier.fillMaxWidth().padding(end = 12.dp, top = 4.dp), contentAlignment = Alignment.CenterEnd) {
                OutlinedButton(onClick = onShare) {
                    Icon(Icons.Filled.Share, contentDescription = null, modifier = Modifier.size(18.dp))
                    Text("Share all", modifier = Modifier.padding(start = 6.dp))
                }
            }
        }
        for (group in chapters) {
            item(key = "chapter:${group.first().chapterId}") {
                Text(
                    group.first().chapterTitle,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 2.dp),
                )
            }
            items(group, key = { it.id }) { highlight ->
                Row(
                    Modifier.fillMaxWidth().height(IntrinsicSize.Min).clickable { onOpen(highlight) }.padding(start = 20.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.width(4.dp).fillMaxHeight().padding(vertical = 2.dp).clip(RoundedCornerShape(2.dp)).background(HIGHLIGHT_HUES[highlight.color.coerceIn(0, HIGHLIGHT_HUES.lastIndex)]))
                    Column(Modifier.weight(1f).padding(start = 12.dp)) {
                        Text(highlight.text, style = MaterialTheme.typography.bodyMedium, maxLines = 3, overflow = TextOverflow.Ellipsis)
                        highlight.note?.let {
                            Text(it, style = MaterialTheme.typography.bodySmall, fontStyle = FontStyle.Italic, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 3, overflow = TextOverflow.Ellipsis)
                        }
                    }
                    IconButton(onClick = { onRemove(highlight) }) { Icon(Icons.Filled.Close, contentDescription = "Remove highlight") }
                }
            }
        }
    }
}

private val HUE_NAMES = listOf("yellow", "green", "blue", "pink")
