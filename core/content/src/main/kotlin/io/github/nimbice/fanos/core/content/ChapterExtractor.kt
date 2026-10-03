package io.github.nimbice.fanos.core.content

import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

/**
 * Turns a fetched chapter page into [ChapterContent].
 *
 * The stages, in order:
 * 1. Page-wide clean-up on [page]: scripts, text the page hides (hidden attributes, inline styles,
 *    rules in its <style> blocks), ad frames and ad slots.
 * 2. Narrowing to the chapter: [content] when the source knows it, otherwise the element holding
 *    most of the page's paragraph text.
 * 3. Chapter clean-up: site notices, navigation links, look-alike watermark domains.
 * 4. Conversion of an allowlist of elements into [Block]s, with emphasis taken from tags and inline
 *    styles, paragraphs split on double line breaks, translator/editor credit lines set apart as
 *    credits, and empty blocks dropped.
 * 5. Title detection: whether the chapter opens with its own title ([ChapterContent.hasOwnTitle]).
 */
interface ChapterExtractor {
    fun extract(page: Document, content: Element?, chapterTitle: String?): ChapterContent
}
