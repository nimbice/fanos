package io.github.nimbice.fanos.core.designsystem.component

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitLongPressOrCancellation
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridItemScope
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChangeIgnoreConsumed
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.toOffset
import androidx.compose.ui.unit.toSize
import androidx.compose.ui.zIndex
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Long-press and drag to reorder a lazy grid or list. The lazy layout takes [Modifier.reorderable];
 * each item sits in a ReorderableItem under its key. As the dragged item passes others, onMove moves
 * it in the caller's list (by index); onDrop follows when the finger lifts. Holding the dragged item
 * near the top or bottom edge scrolls. A long press let go without dragging is a press of its own:
 * onPress hears of it, with the item's key.
 *
 * Positions are those of the lazy layout's own item info ("item space"): measured from the start of
 * the content, after the content padding.
 */
@Stable
class ReorderState internal constructor(
    private val layout: ItemLayout,
    private val scope: CoroutineScope,
    private val edgePx: Float,
    private val maxScrollPx: Float,
    private val pressSlopPx: Float,
    private val onStart: () -> Unit,
    private val onMove: (from: Int, to: Int) -> Unit,
    private val onDrop: () -> Unit,
    private val onPress: (key: Any) -> Unit,
) {
    /** The key of the item being dragged, or null. */
    var draggingKey by mutableStateOf<Any?>(null)
        private set

    // Where the dragged item and the finger were when the drag began, and how far the finger has moved.
    private var itemStart by mutableStateOf(Offset.Zero)
    private var pointerStart by mutableStateOf(Offset.Zero)
    private var dragged by mutableStateOf(Offset.Zero)

    // Whether the drag has moved the item: a long press that hasn't is a press, not a drag.
    private var moved = false

    /** How far from its slot the dragged item is drawn: under the finger, wherever its slot has moved to. */
    internal val draggingTranslation: Offset
        get() = draggingSlot()?.let { itemStart + dragged - it.rect.topLeft } ?: Offset.Zero

    /** The item just dropped, springing back into its slot. */
    internal var settlingKey by mutableStateOf<Any?>(null)
        private set
    internal val settling = Animatable(Offset.Zero, Offset.VectorConverter)

    private fun draggingSlot(): ItemSlot? = draggingKey?.let { key -> layout.slots().firstOrNull { it.key == key } }

    internal fun start(pointer: Offset) {
        val point = pointer - layout.contentOrigin()
        val slot = layout.slots().firstOrNull { point in it.rect } ?: return
        draggingKey = slot.key
        itemStart = slot.rect.topLeft
        pointerStart = point
        dragged = Offset.Zero
        moved = false
        onStart()
    }

    internal fun drag(delta: Offset) {
        if (draggingKey == null) return
        dragged += delta
        follow()
    }

    /** Moves the dragged item into the slot under its centre. */
    internal fun follow() {
        val slot = draggingSlot() ?: return
        val centre = slot.rect.translate(draggingTranslation).center
        val target = layout.slots().firstOrNull { it.key != slot.key && centre in it.rect } ?: return
        // The layout keeps its place by the first visible item; when that item is the one moving it
        // would scroll after it, so hold the place by position for this move.
        layout.holdScroll()
        moved = true
        onMove(slot.index, target.index)
    }

    internal fun end() {
        val key = draggingKey ?: return
        val pressed = !moved && dragged.getDistance() < pressSlopPx
        val from = draggingTranslation
        draggingKey = null
        settlingKey = key
        scope.launch {
            settling.snapTo(from)
            settling.animateTo(Offset.Zero, spring(stiffness = Spring.StiffnessMediumLow))
            if (settlingKey == key) settlingKey = null
        }
        onDrop()
        if (pressed) onPress(key)
    }

    /**
     * How far to scroll this frame: once the finger has moved towards an edge and is within [edgePx]
     * of it, faster the closer it gets. A drag that starts near an edge does not scroll until it
     * moves towards it.
     */
    internal fun edgeScroll(): Float {
        if (draggingKey == null) return 0f
        val y = (pointerStart + dragged).y
        val (start, end) = layout.viewport()
        return when {
            dragged.y > 0 && y > end - edgePx -> ((y - (end - edgePx)) / edgePx).coerceIn(0f, 1f) * maxScrollPx
            dragged.y < 0 && y < start + edgePx -> -((start + edgePx - y) / edgePx).coerceIn(0f, 1f) * maxScrollPx
            else -> 0f
        }
    }

    internal suspend fun scrollBy(px: Float): Float = layout.scrollBy(px)

    internal fun itemModifier(key: Any, placement: Modifier): Modifier =
        when (key) {
            draggingKey ->
                Modifier.zIndex(1f).graphicsLayer {
                    val t = draggingTranslation
                    translationX = t.x
                    translationY = t.y
                }
            settlingKey ->
                Modifier.zIndex(1f).graphicsLayer {
                    val t = settling.value
                    translationX = t.x
                    translationY = t.y
                }
            else -> placement
        }
}

internal class ItemSlot(val index: Int, val key: Any, val rect: Rect)

/** What reordering needs from a lazy grid or list. */
internal interface ItemLayout {
    fun slots(): List<ItemSlot>

    /** The visible stretch of the main axis, in item space. */
    fun viewport(): Pair<Float, Float>

    /** Where item space starts, in the lazy layout's own coordinates: its content padding. */
    fun contentOrigin(): Offset

    fun holdScroll()

    suspend fun scrollBy(px: Float): Float
}

private class GridLayout(private val state: LazyGridState, private val origin: Offset) : ItemLayout {
    override fun slots() = state.layoutInfo.visibleItemsInfo.map { ItemSlot(it.index, it.key, Rect(it.offset.toOffset(), it.size.toSize())) }

    override fun viewport() = state.layoutInfo.viewportStartOffset.toFloat() to state.layoutInfo.viewportEndOffset.toFloat()

    override fun contentOrigin() = origin

    override fun holdScroll() = state.requestScrollToItem(state.firstVisibleItemIndex, state.firstVisibleItemScrollOffset)

    override suspend fun scrollBy(px: Float) = state.scrollBy(px)
}

private class ListLayout(private val state: LazyListState, private val origin: Offset) : ItemLayout {
    override fun slots(): List<ItemSlot> {
        val width = state.layoutInfo.viewportSize.width.toFloat()
        return state.layoutInfo.visibleItemsInfo.map { ItemSlot(it.index, it.key, Rect(0f, it.offset.toFloat(), width, (it.offset + it.size).toFloat())) }
    }

    override fun viewport() = state.layoutInfo.viewportStartOffset.toFloat() to state.layoutInfo.viewportEndOffset.toFloat()

    override fun contentOrigin() = origin

    override fun holdScroll() = state.requestScrollToItem(state.firstVisibleItemIndex, state.firstVisibleItemScrollOffset)

    override suspend fun scrollBy(px: Float) = state.scrollBy(px)
}

@Composable
fun rememberReorderState(
    state: LazyGridState,
    contentPadding: PaddingValues,
    onMove: (from: Int, to: Int) -> Unit,
    onDrop: () -> Unit,
    onPress: (key: Any) -> Unit = {},
): ReorderState {
    val origin = contentOrigin(contentPadding)
    return rememberReorderState(remember(state, origin) { GridLayout(state, origin) }, onMove, onDrop, onPress)
}

@Composable
fun rememberReorderState(
    state: LazyListState,
    contentPadding: PaddingValues,
    onMove: (from: Int, to: Int) -> Unit,
    onDrop: () -> Unit,
    onPress: (key: Any) -> Unit = {},
): ReorderState {
    val origin = contentOrigin(contentPadding)
    return rememberReorderState(remember(state, origin) { ListLayout(state, origin) }, onMove, onDrop, onPress)
}

@Composable
private fun contentOrigin(padding: PaddingValues): Offset {
    val direction = LocalLayoutDirection.current
    return with(LocalDensity.current) { Offset(padding.calculateStartPadding(direction).toPx(), padding.calculateTopPadding().toPx()) }
}

@Composable
private fun rememberReorderState(layout: ItemLayout, onMove: (Int, Int) -> Unit, onDrop: () -> Unit, onPress: (Any) -> Unit): ReorderState {
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val haptics = LocalHapticFeedback.current
    val move by rememberUpdatedState(onMove)
    val drop by rememberUpdatedState(onDrop)
    val press by rememberUpdatedState(onPress)
    val state =
        remember(layout) {
            ReorderState(
                layout = layout,
                scope = scope,
                edgePx = with(density) { 64.dp.toPx() },
                maxScrollPx = with(density) { 14.dp.toPx() },
                pressSlopPx = with(density) { 8.dp.toPx() },
                onStart = { haptics.performHapticFeedback(HapticFeedbackType.LongPress) },
                onMove = { from, to -> move(from, to) },
                onDrop = { drop() },
                onPress = { key -> press(key) },
            )
        }
    LaunchedEffect(state) {
        snapshotFlow { state.draggingKey != null }.collectLatest { dragging ->
            while (dragging) {
                withFrameNanos {}
                val step = state.edgeScroll()
                if (step != 0f && state.scrollBy(step) != 0f) state.follow()
            }
        }
    }
    return state
}

/**
 * Long-press an item and drag it: the lazy grid or list this is on reorders as it goes.
 *
 * Once the long press has started a drag, the finger's events are taken in the first (Initial) pass,
 * before the lazy layout and its items see them: the layout cannot start scrolling, or pulling to
 * refresh, under a quick first move, and the item under the finger does not take the lift as a tap.
 * Before the long press, a moving finger scrolls as usual and cancels it.
 */
fun Modifier.reorderable(state: ReorderState): Modifier =
    pointerInput(state) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            val press = awaitLongPressOrCancellation(down.id) ?: return@awaitEachGesture
            state.start(press.position)
            if (state.draggingKey == null) return@awaitEachGesture
            try {
                while (true) {
                    val change = awaitPointerEvent(PointerEventPass.Initial).changes.firstOrNull { it.id == press.id } ?: break
                    val lifted = change.changedToUpIgnoreConsumed()
                    val delta = change.positionChangeIgnoreConsumed()
                    change.consume()
                    if (lifted) break
                    if (delta != Offset.Zero) state.drag(delta)
                }
            } finally {
                state.end()
            }
        }
    }

/** A grid item that can be dragged; [content] learns whether it is the one being dragged. */
@Composable
fun LazyGridItemScope.ReorderableItem(
    state: ReorderState,
    key: Any,
    modifier: Modifier = Modifier,
    content: @Composable (dragging: Boolean) -> Unit,
) {
    Box(modifier.then(state.itemModifier(key, Modifier.animateItem())), propagateMinConstraints = true) { content(state.draggingKey == key) }
}

/** A list item that can be dragged; [content] learns whether it is the one being dragged. */
@Composable
fun LazyItemScope.ReorderableItem(
    state: ReorderState,
    key: Any,
    modifier: Modifier = Modifier,
    content: @Composable (dragging: Boolean) -> Unit,
) {
    Box(modifier.then(state.itemModifier(key, Modifier.animateItem())), propagateMinConstraints = true) { content(state.draggingKey == key) }
}
