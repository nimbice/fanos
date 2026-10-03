package io.github.nimbice.fanos.feature.reader.text

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntSize
import io.github.nimbice.fanos.core.content.ChapterContent
import io.github.nimbice.fanos.core.model.ReaderSettings
import io.github.nimbice.fanos.core.model.ReadingPosition
import io.github.nimbice.fanos.feature.reader.LoadedChapter
import io.github.nimbice.fanos.feature.reader.ReaderCommand
import io.github.nimbice.fanos.feature.reader.ReaderController
import io.github.nimbice.fanos.feature.reader.readingFamily
import io.github.nimbice.fanos.feature.reader.ReaderEvent
import io.github.nimbice.fanos.feature.reader.ReaderPage
import kotlinx.coroutines.flow.Flow
import java.io.File
import androidx.compose.ui.geometry.Offset
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.geometry.Rect
import androidx.compose.runtime.CompositionLocalProvider
import io.github.nimbice.fanos.core.content.text

/** Where the reader is: the chapter, and the leaf and character at the top of the text. */
internal data class Place(val chapterId: Long, val position: ReadingPosition)

/**
 * What a stretch of marked text is: text the reader highlighted, the sentence being read aloud, a search's match, or the
 * match the search is at.
 */
/** What a [Mark] is: a highlight, what's read aloud, a search's matches, or the word pressed while its menu or card is open. */
internal enum class MarkKind { Highlight, Spoken, SpokenWord, Found, Current, Pressed }

/**
 * Auto-scroll going, at [speed] (1 to 10): scroll mode scrolls on, page mode turns pages on a timer. With
 * [stopAtChapterEnd] it stops at the chapter's end rather than going on into the next.
 */
internal data class AutoScroll(val speed: Int, val stopAtChapterEnd: Boolean = false) {
    /** Lines of text a minute, in scroll mode. */
    val linesPerMinute: Float get() = 6f + 4f * speed

    /** How long a page stays, in page mode. */
    val secondsPerPage: Float get() = 150f / (speed + 1)
}

/**
 * Text marked on the page: in a chapter, a leaf (-1 for the title above a chapter without its own), from [start] to [end];
 * a highlight's in its [color].
 */
internal data class Mark(val chapterId: Long, val leaf: Int, val start: Int, val end: Int, val kind: MarkKind, val color: Int = 0)

/** A place the page keeps in view: character [at] of [leaf] of a chapter. */
internal data class Follow(val chapterId: Long, val leaf: Int, val at: Int)

/** The marks on the page (the sentence being read aloud, a search's matches) and what the page follows, if anything. */
internal class Marks(
    marks: List<Mark> = emptyList(),
    val follow: Follow? = null,
    private val bookmarked: Set<Pair<Long, Int>> = emptySet(),
    private val noted: Set<Pair<Long, Int>> = emptySet(),
) {
    private val byLeaf = marks.groupBy { it.chapterId to it.leaf }

    fun of(chapterId: Long, leaf: Int): List<Mark> = byLeaf[chapterId to leaf].orEmpty()

    /** Whether the reader bookmarked paragraph [leaf] of the chapter. */
    fun isBookmarked(chapterId: Long, leaf: Int): Boolean = leaf >= 0 && (chapterId to leaf) in bookmarked

    /** Whether a highlight in paragraph [leaf] of the chapter has a note. */
    fun hasNote(chapterId: Long, leaf: Int): Boolean = leaf >= 0 && (chapterId to leaf) in noted

    /** Whether character [offset] of paragraph [leaf] is highlighted. */
    fun isHighlighted(chapterId: Long, leaf: Int, offset: Int): Boolean = of(chapterId, leaf).any { it.kind == MarkKind.Highlight && offset in it.start until it.end }

    companion object {
        val None = Marks()
    }
}

/**
 * A long press on the text: the chapter, leaf and character pressed, the leaf's text, and where it was on the screen: the
 * lines pressed, top to bottom, at the finger. What opens for it opens above or below them, not over the words. As the
 * finger slides on, [end] is the character it's at, and [done] says it has let go.
 */
internal data class TextHold(
    val chapterId: Long,
    val leaf: Int,
    val offset: Int,
    val text: String,
    val line: Rect,
    val end: Int = offset,
    val done: Boolean = true,
    /** The paragraph [end] is in: a later one (or an earlier) when the finger slid on into it. */
    val endLeaf: Int = leaf,
    /** A tap on a highlight rather than a long press: for the highlight, with nothing chosen to change. */
    val tapped: Boolean = false,
)

/**
 * A long press on a row's text as it goes: the character pressed, the one the finger is at (in paragraph [endLeaf] when
 * it has slid into another; null for the one pressed), the lines between, and whether it has let go.
 */
internal class Press(val offset: Int, val end: Int, val line: Rect, val done: Boolean, val endLeaf: Int? = null, val tapped: Boolean = false)

/**
 * The chapters as the reader reads them, in scroll or page mode. Switching between the two keeps the
 * reader's place: both report it, and whichever opens starts there.
 */
@Composable
internal fun ReadingView(
    page: ReaderPage.Ready,
    settings: ReaderSettings,
    commands: Flow<ReaderCommand>,
    controller: ReaderController,
    onEvent: (ReaderEvent) -> Unit,
    onNeed: (Long) -> Unit,
    savedImage: (chapterId: Long, name: String) -> File,
    onOpenInBrowser: (String) -> Unit,
    marks: Marks = Marks.None,
    onHold: (TextHold) -> Unit = {},
    autoScroll: AutoScroll? = null,
    choosing: Choosing = Choosing(),
    shownTexts: ShownTexts? = null,
    nextCard: Boolean = false,
    nextChapter: (Long) -> NextChapter? = { null },
    onOpenPicture: ((Any, String?) -> Unit)? = null,
    onOpenNote: ((mark: String, note: String) -> Unit)? = null,
    topRoom: androidx.compose.ui.unit.Dp = androidx.compose.ui.unit.Dp(0f),
    bottomRoom: androidx.compose.ui.unit.Dp = androidx.compose.ui.unit.Dp(0f),
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val family = remember(settings.font, settings.ownFont) { readingFamily(context, settings) }
    val currentOnNote by rememberUpdatedState(onOpenNote)
    val style = remember(settings, family, density) { ReadingStyle(settings, family, density) { mark, note -> currentOnNote?.invoke(mark, note) } }
    val currentOnEvent by rememberUpdatedState(onEvent)

    // All of it afresh for a new document: the reader opened again, elsewhere.
    key(page.documentId) {
        // Kept through the screen turning (the activity made again), or the reader would be back where the document opened.
        val place = rememberSaveable(stateSaver = PLACE_SAVER) { mutableStateOf(Place(page.openChapter, page.openPosition)) }
        val imageSizes = remember { mutableStateMapOf<String, IntSize>() }
        // The rows of text on the screen, for a long press that slides on from one paragraph into the next.
        val texts = shownTexts ?: remember { ShownTexts() }
        val rowsCache = remember { HashMap<Long, CachedRows>() }
        val rowsOf: (LoadedChapter) -> ChapterRows = { chapter ->
            val cached = rowsCache[chapter.chapter.id]
            if (cached != null && cached.content === chapter.content && cached.spacing == settings.paragraphSpacing && cached.next == nextCard) {
                cached.rows
            } else {
                RowLayout.rows(chapter.content, chapter.chapter.title, settings.paragraphSpacing)
                    .let { rows -> if (nextCard) rows.withNext(chapter.content) else rows }
                    .also { rowsCache[chapter.chapter.id] = CachedRows(chapter.content, settings.paragraphSpacing, it, nextCard) }
            }
        }
        // Where the reader is, kept here too, for the other mode to open at.
        val events: (ReaderEvent) -> Unit = { event ->
            if (event is ReaderEvent.Position) place.value = Place(event.chapter, ReadingPosition(event.block, event.offset))
            currentOnEvent(event)
        }
        key(settings.pageMode) {
            val open = place.value.takeIf { it.chapterId in page.loaded } ?: Place(page.openChapter, page.openPosition)
            if (settings.pageMode) {
                CompositionLocalProvider(LocalShownTexts provides texts, LocalChoosing provides choosing, LocalNextChapter provides nextChapter, LocalOpenPicture provides onOpenPicture) {
                    PagedReader(page, open, settings, style, rowsOf, imageSizes, commands, controller, events, onNeed, savedImage, onOpenInBrowser, marks, onHold, topRoom, bottomRoom, autoScroll)
                }
            } else {
                CompositionLocalProvider(LocalShownTexts provides texts, LocalChoosing provides choosing, LocalNextChapter provides nextChapter, LocalOpenPicture provides onOpenPicture) {
                    ScrollReader(page, open, settings, style, rowsOf, imageSizes, commands, controller, events, onNeed, savedImage, onOpenInBrowser, marks, onHold, topRoom, bottomRoom, autoScroll)
                }
            }
        }
    }
}

/** A place as saved state: its chapter, block and offset. */
private val PLACE_SAVER =
    Saver<Place, LongArray>(
        save = { longArrayOf(it.chapterId, it.position.block.toLong(), it.position.offset.toLong()) },
        restore = { Place(it[0], ReadingPosition(it[1].toInt(), it[2].toInt())) },
    )

/** Rows made for a chapter, kept while its content and the paragraph spacing stay the same. */
private class CachedRows(val content: ChapterContent, val spacing: Float, val rows: ChapterRows, val next: Boolean)

/** The rows with the next chapter's card after the last: standing for the place after all the text. */
private fun ChapterRows.withNext(content: ChapterContent): ChapterRows {
    val last = content.leaves.lastIndex
    if (last < 0) return this
    return copy(rows = rows + NextRow(last, content.leaves[last].text().length, NEXT_ROW_GAP))
}

/** Space above the next chapter's card, in ems. */
private const val NEXT_ROW_GAP = 2f

/** The stretches of [row]'s text marked, with their colours: only rows of text that can show them. */
internal fun Marks.inRow(chapterId: Long, row: Row, style: ReadingStyle): List<Pair<IntRange, androidx.compose.ui.graphics.Color>> {
    if (row !is TitleRow && (row !is TextRow || row.kind == TextKind.Pre)) return emptyList()
    return of(chapterId, row.leaf).map { mark ->
        val color =
            when (mark.kind) {
                MarkKind.Highlight -> style.highlight(mark.color)
                MarkKind.Spoken -> style.spoken
                MarkKind.SpokenWord -> style.spokenWord
                MarkKind.Found -> style.found
                MarkKind.Current -> style.foundCurrent
                MarkKind.Pressed -> style.pressed
            }
        (mark.start until mark.end) to color
    }
}

/** What a tap on [row]'s text does: on a highlight, what a long press does; elsewhere nothing, and the page has the tap. */
internal fun tapOn(chapterId: Long, row: Row, marks: Marks, onHold: (TextHold) -> Unit): ((Int, Rect) -> Boolean)? {
    val hold = holdOn(chapterId, row, onHold) ?: return null
    if (marks.of(chapterId, row.leaf).none { it.kind == MarkKind.Highlight }) return null
    return { offset, line -> marks.isHighlighted(chapterId, row.leaf, offset).also { if (it) hold(Press(offset, offset, line, done = true, tapped = true)) } }
}

/** What a long press on [row]'s text does, for rows of text read aloud; null for the others. */
internal fun holdOn(chapterId: Long, row: Row, onHold: (TextHold) -> Unit): ((Press) -> Unit)? =
    when {
        row is TitleRow -> { press -> onHold(TextHold(chapterId, row.leaf, press.offset, row.text, press.line, press.end, press.done, press.endLeaf ?: row.leaf, press.tapped)) }
        row is TextRow && row.kind != TextKind.Pre -> { press -> onHold(TextHold(chapterId, row.leaf, press.offset, row.text, press.line, press.end, press.done, press.endLeaf ?: row.leaf, press.tapped)) }
        else -> null
    }
