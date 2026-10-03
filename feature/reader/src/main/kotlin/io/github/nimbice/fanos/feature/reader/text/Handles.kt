package io.github.nimbice.fanos.feature.reader.text

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/** One end of a choice of words: its paragraph and character, and where the caret there is on the screen. */
internal class ChoiceEnd(val leaf: Int, val offset: Int, val caret: Rect)

/**
 * Handles under the two ends of the words chosen, [start] and [end] (null for an end not on the screen), in the screen's
 * own coordinates. Dragging one moves that end to the character over the finger ([onMove], with whether it's the start,
 * the paragraph and the character, as [at] finds them); [onDragging] says when a drag begins and ends, with the caret
 * the dragged end is at by then.
 */
@Composable
internal fun ChoiceHandles(
    start: ChoiceEnd?,
    end: ChoiceEnd?,
    at: (Offset) -> Hit?,
    onMove: (isStart: Boolean, leaf: Int, offset: Int) -> Unit,
    onDragging: (Boolean) -> Unit,
) {
    for ((isStart, choiceEnd) in listOf(true to start, false to end)) {
        if (choiceEnd == null) continue
        key(isStart) { Handle(isStart, choiceEnd, at, onMove, onDragging) }
    }
}

@Composable
private fun Handle(
    isStart: Boolean,
    choiceEnd: ChoiceEnd,
    at: (Offset) -> Hit?,
    onMove: (isStart: Boolean, leaf: Int, offset: Int) -> Unit,
    onDragging: (Boolean) -> Unit,
) {
    val current by rememberUpdatedState(choiceEnd)
    val currentAt by rememberUpdatedState(at)
    val currentOnMove by rememberUpdatedState(onMove)
    val currentOnDragging by rememberUpdatedState(onDragging)
    val placed = remember { arrayOfNulls<LayoutCoordinates>(1) }
    val touch = with(LocalDensity.current) { TOUCH.toPx() }
    val caret = choiceEnd.caret
    // The handle hangs under the caret, its point there: the start's to the left of it, the end's to the right, in a box big
    // enough to take a finger.
    val left = if (isStart) caret.left - touch else caret.left
    Box(
        Modifier
            .offset { IntOffset(left.roundToInt(), caret.bottom.roundToInt()) }
            .size(TOUCH)
            .onGloballyPositioned { placed[0] = it }
            .semantics { contentDescription = if (isStart) "Start of the words chosen" else "End of the words chosen" }
            .pointerInput(isStart) {
                // How far above the finger the caret it moves is: kept as it moves, so the text isn't under the finger.
                var grab = Offset.Zero
                detectDragGestures(
                    onDragStart = { down ->
                        val where = placed[0]
                        if (where != null) grab = where.localToWindow(down) - current.caret.center
                        currentOnDragging(true)
                    },
                    onDrag = { change, _ ->
                        change.consume()
                        val where = placed[0] ?: return@detectDragGestures
                        currentAt(where.localToWindow(change.position) - grab)?.let { hit -> hit.leaf?.let { currentOnMove(isStart, it, hit.offset) } }
                    },
                    onDragEnd = { currentOnDragging(false) },
                    onDragCancel = { currentOnDragging(false) },
                )
            },
        contentAlignment = if (isStart) Alignment.TopEnd else Alignment.TopStart,
    ) {
        Box(
            Modifier
                .size(TEAR)
                .background(HANDLE, if (isStart) RoundedCornerShape(50, 0, 50, 50) else RoundedCornerShape(0, 50, 50, 50)),
        )
    }
}

private val TOUCH = 44.dp
private val TEAR = 22.dp

/** The handles' blue, as the words chosen are tinted (stronger). */
private val HANDLE = Color(0xFF3FA9D6)
