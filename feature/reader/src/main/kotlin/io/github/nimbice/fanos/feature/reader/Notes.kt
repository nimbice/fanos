package io.github.nimbice.fanos.feature.reader

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import kotlin.math.roundToInt
import androidx.compose.foundation.BorderStroke
import androidx.compose.ui.graphics.luminance

/** A footnote opened from its mark: "Note 1" (or "Note"), its text, and where on the reader the mark was tapped. */
internal data class OpenNote(val label: String, val text: String, val at: Offset)

/** The label for the note a mark ("1", "[2]", "*") opens: its number or letters, when it has some. */
internal fun noteLabel(mark: String): String = mark.filter { it.isLetterOrDigit() }.let { if (it.isEmpty()) "Note" else "Note $it" }

/**
 * A footnote's card, under the line its mark is on (over it near the bottom), as the long-press menu opens: a tap
 * anywhere else, or back, puts it away. A long note scrolls in it.
 */
@Composable
internal fun NoteCard(note: OpenNote, onClose: () -> Unit) {
    val gap = with(LocalDensity.current) { NOTE_GAP.roundToPx() }
    Popup(
        popupPositionProvider = remember(note, gap) { NotePlace(note.at, gap) },
        onDismissRequest = onClose,
        properties = PopupProperties(focusable = true),
    ) {
        // Over a dark page a shadow hardly shows: the card is lighter there, with an edge.
        val dark = MaterialTheme.colorScheme.surface.luminance() < 0.5f
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = if (dark) MaterialTheme.colorScheme.surfaceContainerHighest else MaterialTheme.colorScheme.surfaceContainerLow,
            border = if (dark) BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant) else null,
            shadowElevation = 8.dp,
            modifier = Modifier.padding(horizontal = 14.dp).fillMaxWidth().heightIn(max = NOTE_MAX_HEIGHT),
        ) {
            Column(Modifier.verticalScroll(rememberScrollState()).padding(start = 18.dp, end = 18.dp, top = 14.dp, bottom = 16.dp)) {
                Text(
                    note.label.uppercase(),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(note.text, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(top = 6.dp))
            }
        }
    }
}

/** Below the point tapped, by [gap] (clear of its line), or above it when there's no room below. */
private class NotePlace(private val at: Offset, private val gap: Int) : PopupPositionProvider {
    override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize, layoutDirection: LayoutDirection, popupContentSize: IntSize): IntOffset {
        val y = anchorBounds.top + at.y.roundToInt()
        val below = y + gap
        val top = if (below + popupContentSize.height <= windowSize.height) below else y - gap - popupContentSize.height
        return IntOffset((windowSize.width - popupContentSize.width) / 2, top.coerceIn(0, (windowSize.height - popupContentSize.height).coerceAtLeast(0)))
    }
}

/** From the point tapped to the card's edge: about half a line, and a little more. */
private val NOTE_GAP = 28.dp
private val NOTE_MAX_HEIGHT = 320.dp
