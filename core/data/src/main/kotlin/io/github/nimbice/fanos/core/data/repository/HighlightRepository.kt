package io.github.nimbice.fanos.core.data.repository

import io.github.nimbice.fanos.core.common.Clock
import io.github.nimbice.fanos.core.database.dao.HighlightDao
import io.github.nimbice.fanos.core.database.entity.HighlightEntity
import io.github.nimbice.fanos.core.model.HIGHLIGHT_COLORS
import io.github.nimbice.fanos.core.model.Highlight
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/** Text the reader highlighted in a novel's chapters, a colour each, with notes. */
@Singleton
class HighlightRepository @Inject constructor(private val highlights: HighlightDao, private val clock: Clock) {

    /** The novel's highlights, in reading order. */
    fun observe(novelId: Long): Flow<List<Highlight>> =
        highlights.observeForNovel(novelId).map { rows ->
            rows.map { Highlight(it.id, it.chapterId, it.chapterTitle, it.block, it.start, it.end, it.text, it.color, it.note, it.createdAt, maxOf(it.block, it.endBlock)) }
        }

    /**
     * Highlights [start] of paragraph [block] of [chapterId] to [end] of paragraph [endBlock], which reads [text], in
     * [color], with [note] when one is given. A highlight there already (any it overlaps) takes the colour, and the note,
     * instead.
     */
    suspend fun add(novelId: Long, chapterId: Long, block: Int, start: Int, endBlock: Int, end: Int, text: String, color: Int, note: String? = null) {
        val shade = color.coerceIn(0, HIGHLIGHT_COLORS - 1)
        val there = highlights.inBlocks(chapterId, block, endBlock).firstOrNull { overlaps(block, start, endBlock, end, it.block, it.start, maxOf(it.block, it.endBlock), it.end) }
        if (there != null) {
            highlights.setColor(there.id, shade)
            if (note != null) highlights.setNote(there.id, note.trim().ifEmpty { null })
            return
        }
        highlights.insert(
            HighlightEntity(
                novelId = novelId,
                chapterId = chapterId,
                block = block,
                start = start,
                end = end,
                text = text,
                color = shade,
                note = note?.trim()?.ifEmpty { null },
                createdAt = clock.now(),
                endBlock = endBlock,
            ),
        )
    }

    /** Sets the highlight's note; an empty one takes it off. */
    suspend fun setNote(id: Long, note: String) = highlights.setNote(id, note.trim().ifEmpty { null })

    suspend fun remove(id: Long) = highlights.delete(id)
}

/** Whether two stretches of a chapter, each from a block's character to a block's character, share any text. */
internal fun overlaps(aBlock: Int, aStart: Int, aEndBlock: Int, aEnd: Int, bBlock: Int, bStart: Int, bEndBlock: Int, bEnd: Int): Boolean =
    before(aBlock, aStart, bEndBlock, bEnd) && before(bBlock, bStart, aEndBlock, aEnd)

/** Whether character [aAt] of block [aBlock] comes before character [bAt] of block [bBlock]. */
private fun before(aBlock: Int, aAt: Int, bBlock: Int, bAt: Int): Boolean = aBlock < bBlock || (aBlock == bBlock && aAt < bAt)
