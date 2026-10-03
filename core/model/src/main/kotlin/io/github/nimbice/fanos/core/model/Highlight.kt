package io.github.nimbice.fanos.core.model

/**
 * Text the reader highlighted in a chapter: from [start] of paragraph [block] (a leaf of the chapter's content) to [end]
 * of paragraph [endBlock], mostly the same one, which read [text] then (paragraphs a line apart), in colour [color] (one
 * of [HIGHLIGHT_COLORS] choices), with a [note] perhaps.
 */
data class Highlight(
    val id: Long,
    val chapterId: Long,
    val chapterTitle: String,
    val block: Int,
    val start: Int,
    val end: Int,
    val text: String,
    val color: Int,
    val note: String?,
    val createdAt: Long,
    val endBlock: Int = block,
)

/** How many colours a highlight can be. */
const val HIGHLIGHT_COLORS = 4
