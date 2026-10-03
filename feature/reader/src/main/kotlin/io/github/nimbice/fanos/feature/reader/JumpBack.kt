package io.github.nimbice.fanos.feature.reader

import android.os.SystemClock
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.nimbice.fanos.core.designsystem.icon.ReaderIcons
import io.github.nimbice.fanos.core.model.ReadingPosition

/**
 * Where the reader was before a jump: the chapter and the place in it, the [page] it was (from 1, as the bar counts
 * them), and how to get back there without opening the chapter afresh: in page mode its [pageIndex] (from 0), in scroll
 * mode the [fraction] of the way through, while the same [document] shows.
 */
internal data class BackPlace(
    val chapterId: Long,
    val position: ReadingPosition?,
    val page: Int,
    val pageIndex: Int?,
    val fraction: Float?,
    val chapterTitle: String?,
    val document: Int?,
)

/**
 * The way back after a jump (the slider, the chapters panel, a bookmark or a highlight): where the reader was, offered
 * for a while, until they turn a page from where the jump took them. Jumps while it's offered keep the first place.
 */
@Stable
internal class JumpBack {
    /** Where the reader was before the jump; null with no jump to go back from. */
    var from by mutableStateOf<BackPlace?>(null)
        private set

    /** When the latest jump was made (uptime): the way back is offered for a while after. */
    var jumpedAt by mutableLongStateOf(0L)
        private set

    // The page a jump took the reader to, as the reading view reports it once settled, and till when it's settling.
    private var arrived: Pair<Long, Int>? = null
    private var settleUntil = 0L

    /** A jump is starting from [here]. */
    fun jumping(here: BackPlace?) {
        if (from == null) from = here
        jumpedAt = SystemClock.uptimeMillis()
        arrived = null
        settleUntil = jumpedAt + SETTLE_MS
    }

    /** The reading view shows [page] (from 1) of [chapter]: a page turned after the jump has settled ends the way back. */
    fun at(chapter: Long, page: Int) {
        if (from == null) return
        val now = SystemClock.uptimeMillis()
        val here = chapter to page
        when {
            arrived == null -> {
                arrived = here
                settleUntil = maxOf(settleUntil, now + SETTLE_MS)
            }
            now < settleUntil -> arrived = here
            here != arrived -> forget()
        }
    }

    fun forget() {
        from = null
        arrived = null
    }
}

/** "Back to page 12", or with the chapter's title when the jump left it: a tap goes back. */
@Composable
internal fun BackChip(back: BackPlace, otherChapter: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(22.dp),
        color = MaterialTheme.colorScheme.inverseSurface,
        contentColor = MaterialTheme.colorScheme.inverseOnSurface,
        shadowElevation = 4.dp,
        modifier = modifier,
    ) {
        Row(Modifier.padding(start = 14.dp, end = 18.dp, top = 11.dp, bottom = 11.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(ReaderIcons.Undo, contentDescription = null, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
            val style = MaterialTheme.typography.bodyMedium
            val title = back.chapterTitle?.takeIf { otherChapter && it.isNotBlank() }
            if (title == null) {
                Text("Back to page ${back.page}", style = style, maxLines = 1)
            } else {
                // The title gives way, not the page.
                Text("Back to ", style = style, maxLines = 1)
                Text(title, style = style, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                Text(", page ${back.page}", style = style, maxLines = 1)
            }
        }
    }
}

/** How long after a jump its page reports are still the jump's, not a turn. */
private const val SETTLE_MS = 800L
