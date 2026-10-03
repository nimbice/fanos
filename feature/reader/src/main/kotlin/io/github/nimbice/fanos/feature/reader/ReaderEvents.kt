package io.github.nimbice.fanos.feature.reader

/**
 * What the reading view reports. Each event carries the id of the document it came from (the reader
 * opened afresh starts a new one), and those about a place in the text the chapter it is in.
 */
sealed interface ReaderEvent {
    val document: Int

    /** The reader moved on (or back) into another chapter; also said on opening. */
    data class ChapterChanged(override val document: Int, val chapter: Long) : ReaderEvent

    data class Position(override val document: Int, val chapter: Long, val block: Int, val offset: Int) : ReaderEvent

    /** Page mode: the page of [chapter] shown, counted from 0, of [total]. */
    data class Page(override val document: Int, val chapter: Long, val page: Int, val total: Int, val last: Int = page) : ReaderEvent

    /** Scroll mode: how far through the chapter the reader is, in thousandths, and how many screens long it is. */
    data class Progress(override val document: Int, val chapter: Long, val permille: Int, val screens: Int) : ReaderEvent

    /** The reader reached the end of the chapter. */
    data class End(override val document: Int, val chapter: Long) : ReaderEvent

    /** Auto-scroll came to the end of the chapter it was to stop at, and stopped short of the next. */
    data class AutoScrollEnded(override val document: Int) : ReaderEvent

    data class CenterTap(override val document: Int) : ReaderEvent
}
