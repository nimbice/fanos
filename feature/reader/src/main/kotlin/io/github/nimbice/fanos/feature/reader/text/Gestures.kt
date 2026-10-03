package io.github.nimbice.fanos.feature.reader.text

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.mandatorySystemGestures
import androidx.compose.foundation.layout.navigationBarsIgnoringVisibility
import androidx.compose.foundation.layout.statusBarsIgnoringVisibility
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.changedToUp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import kotlin.math.abs
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Taps on the page, and only taps: a touch that moves further than the touch slop, stays down past
 * the long-press timeout, or is joined by a second finger isn't one. (Compose's own tap detector
 * takes any touch nothing else claimed, so a swipe down from the top edge to pull the system bars
 * down counted as a tap where it lifted, and turned the page.)
 */
internal suspend fun PointerInputScope.detectTaps(onTap: (Offset) -> Unit) {
    awaitEachGesture {
        val down = awaitFirstDown()
        val up = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) { awaitTapUp(down, viewConfiguration.touchSlop) }
        if (up != null) {
            up.consume()
            onTap(up.position)
        }
    }
}

/** The lift that ends a tap begun with [down], or null when the touch turns out to be something else. */
private suspend fun AwaitPointerEventScope.awaitTapUp(down: PointerInputChange, slop: Float): PointerInputChange? {
    while (true) {
        val event = awaitPointerEvent()
        if (event.changes.any { it.id != down.id && it.pressed }) return null
        val change = event.changes.firstOrNull { it.id == down.id } ?: return null
        // A lift something under the finger took (a tap on a highlight) isn't the page's.
        if (change.changedToUp()) return change.takeUnless { it.isConsumed }
        if (change.isConsumed || (change.position - down.position).getDistance() > slop) return null
    }
}

/**
 * A long press, and only that: nothing is taken from a touch unless it turns into one, so a tap still turns the page and
 * a swipe still scrolls. Once it has, the rest of the touch is its own, and nothing under it moves: where the finger
 * slides goes to [onMove], where it rests a while to [onStill] (again for each while it stays), and its letting go to
 * [onRelease], as does the touch's end any other way. A tap goes to [onTap] as well, which takes it from the page by
 * answering true.
 */
internal suspend fun PointerInputScope.detectHold(
    onHold: (Offset) -> Unit,
    onMove: (Offset) -> Unit = {},
    onRelease: () -> Unit = {},
    onTap: ((Offset) -> Boolean)? = null,
    onStill: ((Offset) -> Unit)? = null,
) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        val ended =
            withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                var over = false
                while (!over) {
                    val event = awaitPointerEvent()
                    val change = event.changes.firstOrNull { it.id == down.id }
                    if (onTap != null && change != null && change.changedToUp() && !change.isConsumed && event.changes.size == 1 &&
                        (change.position - down.position).getDistance() <= viewConfiguration.touchSlop && onTap(change.position)
                    ) {
                        change.consume()
                    }
                    over = change == null || !change.pressed || change.isConsumed || event.changes.size > 1 ||
                        (change.position - down.position).getDistance() > viewConfiguration.touchSlop
                }
                true
            }
        if (ended != null) return@awaitEachGesture
        onHold(down.position)
        var last = down.position
        try {
            while (true) {
                val event = if (onStill == null) awaitPointerEvent() else withTimeoutOrNull(STILL_MS) { awaitPointerEvent() }
                if (event == null) {
                    onStill?.invoke(last)
                    continue
                }
                val change = event.changes.firstOrNull { it.id == down.id }
                val moved = change != null && change.pressed && change.position != change.previousPosition
                event.changes.forEach { it.consume() }
                if (moved) {
                    last = change!!.position
                    onMove(last)
                }
                if (event.changes.none { it.pressed }) break
            }
        } finally {
            // Let go, or the text under the finger taken off the screen (its page turned well away): over either way.
            onRelease()
        }
    }
}

/** How long a held finger rests before it counts as still. */
private const val STILL_MS = 700L

/** The strips along the top and bottom edges where the system's own swipes start, in pixels. */
internal data class SystemEdges(val top: Int, val bottom: Int)

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun systemEdges(): SystemEdges {
    val density = LocalDensity.current
    return SystemEdges(
        top = maxOf(WindowInsets.mandatorySystemGestures.getTop(density), WindowInsets.statusBarsIgnoringVisibility.getTop(density)),
        bottom = maxOf(WindowInsets.mandatorySystemGestures.getBottom(density), WindowInsets.navigationBarsIgnoringVisibility.getBottom(density)),
    )
}

/**
 * Keeps the system's swipes away from the page. A touch that starts in the strip along the top or
 * bottom edge (pulling the status bar and notifications down, going home) is left to the system,
 * and with [sidewaysOnly], so is one that goes more up or down than sideways: a thumb pulling the
 * notifications down drifts sideways as well, and page mode's pager took the drift for a swipe to
 * the next page. Such a touch is taken from everything under this before it can act on it.
 */
internal fun Modifier.systemSwipesIgnored(edges: SystemEdges, sidewaysOnly: Boolean): Modifier =
    pointerInput(edges, sidewaysOnly) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            var taken = down.position.y < edges.top || down.position.y > size.height - edges.bottom
            if (taken) down.consume()
            val slop = viewConfiguration.touchSlop
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                if (!taken) {
                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                    if (!change.pressed) break
                    val moved = change.position - down.position
                    if (moved.getDistance() <= slop) continue
                    // Sideways, or any way at all in scroll mode: the page's to take.
                    if (!sidewaysOnly || abs(moved.y) <= abs(moved.x)) break
                    taken = true
                }
                event.changes.forEach { it.consume() }
                if (event.changes.none { it.pressed }) break
            }
        }
    }
