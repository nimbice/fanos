package io.github.nimbice.fanos.core.content

/** A word (or words) the reader has replaced in the text: [find] shown and read aloud as [replace]. */
data class TextRule(val find: String, val replace: String, val wholeWord: Boolean = true, val matchCase: Boolean = false) {
    internal val regex: Regex? by lazy {
        val words = find.trim()
        if (words.isEmpty()) return@lazy null
        val pattern = if (wholeWord) "(?<![\\p{L}\\p{N}_])${Regex.escape(words)}(?![\\p{L}\\p{N}_])" else Regex.escape(words)
        Regex(pattern, if (matchCase) emptySet() else setOf(RegexOption.IGNORE_CASE))
    }
}

/**
 * The chapter with [rules] applied to its text, in order: paragraphs, headings, credits, preformatted text and table
 * cells, each span on its own (words split between two styles aren't matched). Leaves stay where they were, so a place
 * in the chapter stays in the same paragraph.
 */
fun ChapterContent.replaced(rules: List<TextRule>): ChapterContent {
    val active = rules.filter { it.regex != null }
    if (active.isEmpty()) return this
    fun text(value: String) = active.fold(value) { done, rule -> rule.regex!!.replace(done, Regex.escapeReplacement(rule.replace)) }
    fun spans(list: List<Span>) = list.map { span -> text(span.text).let { if (it == span.text) span else span.copy(text = it) } }
    fun block(block: Block): Block =
        when (block) {
            is Block.Paragraph -> block.copy(spans = spans(block.spans))
            is Block.Heading -> block.copy(spans = spans(block.spans))
            is Block.Credit -> block.copy(spans = spans(block.spans))
            is Block.Preformatted -> block.copy(text = text(block.text))
            is Block.Quote -> block.copy(blocks = block.blocks.map(::block))
            is Block.Box -> block.copy(blocks = block.blocks.map(::block))
            is Block.ListBlock -> block.copy(items = block.items.map { item -> item.map(::block) })
            is Block.Table -> block.copy(rows = block.rows.map { cells -> cells.map { cell -> cell.copy(blocks = cell.blocks.map(::block)) } })
            is Block.Image, Block.SceneBreak -> block
        }
    return copy(blocks = blocks.map(::block))
}
