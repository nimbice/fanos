package io.github.nimbice.fanos.core.content

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * A chapter's text as the reader shows it: extracted once from the site's page, cleaned, and
 * stored as blocks. Every renderer, the reading position and text-to-speech work from this, never
 * from the page's HTML.
 *
 * Positions refer to [leaves], the blocks that hold text or an image, numbered in reading order.
 */
@Serializable
data class ChapterContent(
    val blocks: List<Block>,
    /** The chapter opens with its own title (as its first leaf), so no title needs adding above it. */
    val hasOwnTitle: Boolean = false,
    val extractorVersion: Int = EXTRACTOR_VERSION,
) {
    /** Paragraphs, headings, credits, images and preformatted text in reading order, flattened out of containers. */
    val leaves: List<Block> by lazy { blocks.flatMap { it.leaves() } }

    /**
     * The leaf that is the chapter's own title, shown as its title: its first leaf of text, when the
     * chapter opens with its own title and that leaf is a heading or a short line (a first paragraph
     * that merely starts "Chapter 5" stays a paragraph). -1 for none.
     */
    val titleLeaf: Int by lazy {
        val index = if (hasOwnTitle) leaves.indexOfFirst { !it.mayPrecedeTitle } else -1
        val leaf = leaves.getOrNull(index)
        val text = leaf?.text()?.trim().orEmpty()
        when {
            leaf is Block.Heading -> index
            leaf is Block.Paragraph && text.length <= TITLE_MAX_LENGTH && '\n' !in text -> index
            else -> -1
        }
    }

    /** The chapter as plain text, one leaf per line, for search and text-to-speech. */
    fun plainText(): String = leaves.joinToString("\n") { it.text() }

    companion object {
        /** Bump when extraction changes, so cached content is extracted again from the stored page. */
        const val EXTRACTOR_VERSION = 3

        private const val TITLE_MAX_LENGTH = 120
    }
}

@Serializable
sealed interface Block {
    @Serializable
    @SerialName("heading")
    data class Heading(val level: Int, val spans: List<Span>) : Block

    @Serializable
    @SerialName("paragraph")
    data class Paragraph(val spans: List<Span>, val align: Align = Align.Start) : Block

    /**
     * The chapter's translator, editor or proofreader, credited where the site put it, a line each
     * ("Translator: X", "Editor: Y"), and shown more quietly than the chapter's own text.
     */
    @Serializable
    @SerialName("credit")
    data class Credit(val spans: List<Span>, val align: Align = Align.Start) : Block

    @Serializable
    @SerialName("image")
    data class Image(val src: String, val alt: String? = null) : Block

    /** A scene break: an <hr> or a line of asterisks. */
    @Serializable
    @SerialName("break")
    data object SceneBreak : Block

    @Serializable
    @SerialName("quote")
    data class Quote(val blocks: List<Block>) : Block

    /** Framed text: LitRPG status windows, system messages, letters. */
    @Serializable
    @SerialName("box")
    data class Box(val blocks: List<Block>) : Block

    @Serializable
    @SerialName("list")
    data class ListBlock(val ordered: Boolean, val items: List<List<Block>>) : Block

    /** Text whose line breaks and spacing matter, such as ASCII tables and stat blocks. */
    @Serializable
    @SerialName("pre")
    data class Preformatted(val text: String) : Block

    @Serializable
    @SerialName("table")
    data class Table(val rows: List<List<TableCell>>) : Block {
        /** How many columns wide the table is. */
        val columns: Int get() = rows.maxOfOrNull { cells -> cells.sumOf { it.colspan.coerceAtLeast(1) } } ?: 0

        /**
         * Whether the table reads better as a stack of rows, one under another, than as a grid: it
         * has cells of prose (a skill's description, say), which in a column a third of a phone's
         * width wrap a word or two to a line. Both reading engines lay a table out by this.
         */
        val stacks: Boolean get() = columns >= 2 && rows.any { cells -> cells.any { it.text().length > PROSE_CELL } }
    }
}

@Serializable
data class TableCell(
    val blocks: List<Block>,
    val header: Boolean = false,
    val colspan: Int = 1,
    val rowspan: Int = 1,
) {
    fun text(): String = blocks.flatMap { it.leaves() }.joinToString("\n") { it.text() }

    /**
     * Short enough to share a line with its neighbours when its table stacks ("Blaze L1 · Skill"):
     * a single line of text.
     */
    val isShort: Boolean
        get() {
            val leaves = blocks.flatMap { it.leaves() }
            val leaf = leaves.singleOrNull() ?: return false
            val text = leaf.text()
            return (leaf is Block.Paragraph || leaf is Block.Heading) && text.length <= SHORT_CELL && '\n' !in text
        }
}

// Out of the serializable classes' companions, which the serialization plugin uses.
private const val PROSE_CELL = 60
private const val SHORT_CELL = 40

/**
 * A run of text with one style. [style] combines the flags in the companion. [href] is where a link goes, or for a
 * footnote's mark, [NOTE] and the note's text.
 */
@Serializable
data class Span(
    val text: String,
    val style: Int = 0,
    val href: String? = null,
) {
    fun has(flag: Int): Boolean = style and flag != 0

    /** The note this span marks, when it's a footnote's mark. */
    val note: String? get() = href?.takeIf { it.startsWith(NOTE) }?.substring(NOTE.length)

    companion object {
        const val BOLD = 1
        const val ITALIC = 2
        const val UNDERLINE = 4
        const val STRIKE = 8
        const val SUPERSCRIPT = 16
        const val SUBSCRIPT = 32
        const val CODE = 64

        /** What a footnote's mark links to: this, then its note's text. */
        const val NOTE = "note:"
    }
}

enum class Align { Start, Center, End, Justify }

/** This block if it holds text or an image itself, otherwise the leaves inside it. */
fun Block.leaves(): List<Block> =
    when (this) {
        is Block.Heading, is Block.Paragraph, is Block.Credit, is Block.Image, is Block.Preformatted, Block.SceneBreak -> listOf(this)
        is Block.Quote -> blocks.flatMap { it.leaves() }
        is Block.Box -> blocks.flatMap { it.leaves() }
        is Block.ListBlock -> items.flatten().flatMap { it.leaves() }
        is Block.Table -> rows.flatten().flatMap { cell -> cell.blocks.flatMap { it.leaves() } }
    }

/** The block's text without styling; empty for images and scene breaks. */
fun Block.text(): String =
    when (this) {
        is Block.Heading -> spans.joinToString("") { it.text }
        is Block.Paragraph -> spans.joinToString("") { it.text }
        is Block.Credit -> spans.joinToString("") { it.text }
        is Block.Preformatted -> text
        is Block.Image, Block.SceneBreak -> ""
        else -> leaves().joinToString("\n") { it.text() }
    }

/** A leaf that may come before a chapter's own title: a picture, a scene break or a credit line. */
internal val Block.mayPrecedeTitle: Boolean
    get() = this is Block.Image || this == Block.SceneBreak || this is Block.Credit

/** How many words the chapter has: its text's, a dash or a row of stars being none. */
fun ChapterContent.wordCount(): Int = plainText().split(WORD_GAP).count { word -> word.any(Char::isLetterOrDigit) }

private val WORD_GAP = Regex("\\s+")
