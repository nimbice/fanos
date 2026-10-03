package io.github.nimbice.fanos.core.content

import org.jsoup.Jsoup

// Fixtures copy the markup of real sites, never their chapters: all text here is invented.

internal const val PAGE_URL = "https://novels.example.com/glass-orchard/chapter-3"

/** About 600 characters, more than the hidden-text rules treat as a short run. */
internal val PARAGRAPH = "The ferryman counted the lanterns twice before he let the grey barge drift from the pier. ".repeat(7).trim()

internal fun extract(html: String, content: String? = null, title: String? = null, url: String = PAGE_URL): ChapterContent {
    val page = Jsoup.parse(html, url)
    val element = content?.let { requireNotNull(page.selectFirst(it)) { "no $it in the page" } }
    return DefaultChapterExtractor().extract(page, element, title)
}

internal fun ChapterContent.texts(): List<String> = leaves.map { it.text() }

internal fun p(text: String, align: Align = Align.Start) = Block.Paragraph(listOf(Span(text)), align)

internal fun p(vararg spans: Span, align: Align = Align.Start) = Block.Paragraph(spans.toList(), align)
