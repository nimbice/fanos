package io.github.nimbice.fanos.core.designsystem.component

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.pullToRefresh
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput

/**
 * Pull-to-refresh that only a deliberate pull triggers: the touch has to start with the content at
 * the top and keep it there. A touch that starts further down, or scrolls the content at any point
 * (scrolling down and back up in one long swipe, say), cannot refresh until the finger lifts.
 *
 * [scrolledFromTop] reports whether the content is scrolled away from its top, for example
 * `{ listState.canScrollBackward }`.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TopPullToRefreshBox(
    isRefreshing: Boolean,
    onRefresh: () -> Unit,
    scrolledFromTop: () -> Boolean,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    val state = rememberPullToRefreshState()
    val scrolled by rememberUpdatedState(scrolledFromTop)
    var pullAllowed by remember { mutableStateOf(true) }
    Box(
        modifier
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    pullAllowed = !scrolled()
                    // Checked on each event before the content takes it, so the content is back at
                    // the top only after this touch has already lost its pull.
                    do {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        if (pullAllowed && scrolled()) pullAllowed = false
                    } while (event.changes.any { it.pressed })
                }
            }.pullToRefresh(isRefreshing = isRefreshing, state = state, enabled = pullAllowed, onRefresh = onRefresh),
    ) {
        content()
        PullToRefreshDefaults.Indicator(
            state = state,
            isRefreshing = isRefreshing,
            modifier = Modifier.align(Alignment.TopCenter),
        )
    }
}
