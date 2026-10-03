package io.github.nimbice.fanos.core.content

import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node

/**
 * The app's [ChapterExtractor], running the five documented stages.
 *
 * It works on a copy of [Document]: the page as fetched is what downloads keep and what a later
 * extractor version extracts again, so extraction must not change it. [chapterTitle], when given,
 * helps recognise a title the chapter opens with; without it the page's own title is used.
 */
class DefaultChapterExtractor : ChapterExtractor {

    override fun extract(page: Document, content: Element?, chapterTitle: String?): ChapterContent {
        val (doc, chapter) = workingCopy(page, content)
        PageCleanup.clean(doc, chapter)
        val root = chapter ?: ContentLocator.locate(doc)
        ChapterCleanup.clean(root)
        // Footnotes' notes as the page had them: cleaning may take the list at its end away.
        val blocks = BlockConverter.convert(root, page)
        val title = chapterTitle ?: TitleDetection.pageTitle(doc)
        return ChapterContent(blocks, hasOwnTitle = TitleDetection.opensWithOwnTitle(blocks, title))
    }

    /** A copy of [page], and the copy of [content] in it. Content from elsewhere is added to the copy's body, so the page's rules apply to it. */
    private fun workingCopy(page: Document, content: Element?): Pair<Document, Element?> {
        val path = content?.let { pathFrom(page, it) }
        val copy = page.clone()
        val chapter = when {
            content == null -> null
            path != null -> follow(copy, path)
            else -> content.clone().also {
                it.setBaseUri(content.baseUri())
                copy.body().appendChild(it)
            }
        }
        return copy to chapter
    }

    /** Child indexes leading from [ancestor] down to [node], or null when [node] is not inside it. */
    private fun pathFrom(ancestor: Node, node: Node): IntArray? {
        val path = ArrayList<Int>()
        var current = node
        while (current !== ancestor) {
            path += current.siblingIndex()
            current = current.parentNode() ?: return null
        }
        return path.asReversed().toIntArray()
    }

    private fun follow(root: Node, path: IntArray): Element? {
        var node = root
        for (index in path) node = node.childNode(index)
        return node as? Element
    }
}
