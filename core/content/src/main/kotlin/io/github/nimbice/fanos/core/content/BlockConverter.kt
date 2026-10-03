package io.github.nimbice.fanos.core.content

import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode
import org.jsoup.select.NodeFilter
import org.jsoup.select.NodeTraversor
import org.jsoup.nodes.Document
import java.net.URLDecoder

/**
 * Stage 4 of extraction: turns the cleaned chapter element into [Block]s in one pass over it.
 *
 * Only an allowlist of elements becomes structure: headings, paragraphs, pictures, scene breaks,
 * quotes, boxes, lists, tables and preformatted text. Any other element passes its text through, so
 * markup a site invents never costs words and never reaches the reader. Emphasis comes from tags
 * and inline styles, alignment from `text-align`, `align` and `<center>`; every other style goes.
 * Two kinds of line are known by their text: a row of symbols is a scene break, and a line naming
 * the chapter's translator or editor is a credit.
 */
internal class BlockConverter private constructor(private val root: Element, private val page: Document?) {

    /** What an element passes down to the text inside it. */
    private data class Context(val style: Int, val href: String?, val align: Align)

    private var chapterWeight = -1

    private fun convertRoot(): List<Block> {
        val flow = Flow()
        children(root, flow, Context(styleOf(0, "", declarations(root)), null, Align.Start), 0)
        return flow.finish(trim = true)
    }

    private fun children(parent: Element, flow: Flow, context: Context, depth: Int) {
        if (depth >= MAX_DEPTH) return flatten(parent, flow, context)
        for (node in parent.childNodes()) {
            when (node) {
                is TextNode -> flow.text(node.wholeText, context)
                is Element -> element(node, flow, context, depth + 1)
            }
        }
    }

    private fun element(el: Element, flow: Flow, context: Context, depth: Int) {
        val tag = el.normalName()
        when {
            tag in SKIPPED -> Unit
            tag == "br" -> flow.lineBreak()
            tag == "hr" -> flow.block(Block.SceneBreak)
            tag == "img" -> image(el)?.let(flow::block)
            tag == "picture" -> el.getElementsByTag("img").firstOrNull()?.let(::image)?.let(flow::block)
            tag == "iframe" || tag == "object" || tag == "embed" -> embed(el, context)?.let(flow::block)
            HEADING.matches(tag) -> heading(el, tag[1] - '0', flow, context, depth)
            tag in PREFORMATTED -> preformatted(el)?.let(flow::block)
            tag in LISTS -> list(el, tag == "ol", flow, context, depth)
            tag == "table" -> table(el, flow, context, depth)
            tag == "blockquote" -> {
                val inner = convert(el, blockContext(el, tag, declarations(el), context), depth)
                if (inner.isNotEmpty()) flow.block(Block.Quote(inner))
            }
            tag in CONTAINERS -> {
                val css = declarations(el)
                val inner = blockContext(el, tag, css, context)
                if (isBox(el, tag, css)) {
                    box(convert(el, inner, depth), flow)
                } else {
                    flow.endParagraph()
                    children(el, flow, inner, depth)
                    flow.endParagraph()
                }
            }
            tag == "q" -> {
                val inner = inlineContext(el, tag, context)
                flow.text("\u201C", inner)
                children(el, flow, inner, depth)
                flow.text("\u201D", inner)
            }
            tag == "rt" -> if (el.hasText()) {
                val inner = inlineContext(el, tag, context)
                flow.text("(", inner)
                children(el, flow, inner, depth)
                flow.text(")", inner)
            }
            tag == "rp" || tag == "wbr" -> Unit
            // A footnote's note, hidden after its mark: the mark opens it.
            el.hasClass(MODERN_NOTE) -> Unit
            else -> children(el, flow, inlineContext(el, tag, context), depth)
        }
    }

    private fun convert(el: Element, context: Context, depth: Int): List<Block> {
        val flow = Flow()
        children(el, flow, context, depth)
        return flow.finish(trim = true)
    }

    private fun heading(el: Element, level: Int, flow: Flow, context: Context, depth: Int) {
        val inner = Flow()
        children(el, inner, blockContext(el, "h$level", declarations(el), context), depth)
        flow.endParagraph()
        for (block in inner.finish(trim = false)) flow.add(if (block is Block.Paragraph) Block.Heading(level, block.spans) else block)
    }

    /**
     * Adds [inner] as a [Block.Box], unless it holds most of the chapter: then the frame is the
     * site's layout (a whole chapter in a one-cell table or a bordered wrapper), not a status window.
     */
    private fun box(inner: List<Block>, flow: Flow) {
        if (inner.isEmpty()) return
        flow.endParagraph()
        val weight = inner.sumOf { block -> block.leaves().sumOf { visibleLength(it.text()) } }
        if (weight >= WRAPPER_SHARE * chapterWeight()) inner.forEach(flow::add) else flow.add(Block.Box(inner))
    }

    private fun list(el: Element, ordered: Boolean, flow: Flow, context: Context, depth: Int) {
        val itemContext = blockContext(el, el.normalName(), declarations(el), context)
        val items = ArrayList<List<Block>>()
        for (node in el.childNodes()) {
            val item = Flow()
            when (node) {
                is Element -> element(node, item, itemContext, depth + 1)
                is TextNode -> item.text(node.wholeText, itemContext)
                else -> continue
            }
            item.finish(trim = true).takeIf { it.isNotEmpty() }?.let(items::add)
        }
        if (items.isNotEmpty()) flow.block(Block.ListBlock(ordered, items))
    }

    /** A table with one cell is a frame around text (a LitRPG status window) and becomes a [Block.Box]. */
    private fun table(el: Element, flow: Flow, context: Context, depth: Int) {
        val caption = el.children().firstOrNull { it.normalName() == "caption" }
            ?.let { convert(it, blockContext(it, "caption", declarations(it), context), depth + 1) }
            .orEmpty()
        val rows = ArrayList<List<TableCell>>()
        var cellCount = 0
        for (row in rowsOf(el)) {
            val cells = ArrayList<TableCell>()
            for (cell in row.children()) {
                val tag = cell.normalName()
                if (tag != "td" && tag != "th") continue
                cellCount++
                cells += TableCell(
                    blocks = convert(cell, blockContext(cell, tag, declarations(cell), context), depth + 2),
                    header = tag == "th",
                    colspan = spanOf(cell, "colspan", MAX_COLSPAN),
                    rowspan = spanOf(cell, "rowspan", MAX_ROWSPAN),
                )
            }
            if (cells.any { it.blocks.isNotEmpty() }) rows += cells
        }
        flow.endParagraph()
        caption.forEach(flow::add)
        when {
            rows.isEmpty() -> Unit
            cellCount == 1 -> box(rows[0][0].blocks, flow)
            else -> flow.add(Block.Table(rows))
        }
    }

    private fun rowsOf(table: Element): List<Element> {
        val rows = ArrayList<Element>()
        for (child in table.children()) {
            when (child.normalName()) {
                "tr" -> rows.add(child)
                "thead", "tbody", "tfoot" -> child.children().filterTo(rows) { it.normalName() == "tr" }
            }
        }
        return rows
    }

    private fun spanOf(cell: Element, attribute: String, max: Int): Int =
        cell.attr(attribute).trim().toIntOrNull()?.coerceIn(1, max) ?: 1

    private fun preformatted(el: Element): Block? {
        val text = StringBuilder()
        NodeTraversor.filter(
            object : NodeFilter {
                override fun head(node: Node, depth: Int): NodeFilter.FilterResult {
                    when {
                        node is TextNode -> for (c in node.wholeText) {
                            if (c in INVISIBLE_CHARS || (c.isISOControl() && c != '\n' && c != '\t')) continue
                            text.append(c)
                        }
                        node !is Element || node === el -> Unit
                        node.normalName() in SKIPPED -> return NodeFilter.FilterResult.SKIP_ENTIRELY
                        node.normalName() == "br" -> text.append('\n')
                        node.isBlock && text.isNotEmpty() && text.last() != '\n' -> text.append('\n')
                    }
                    return NodeFilter.FilterResult.CONTINUE
                }
            },
            el,
        )
        val lines = text.toString().split('\n')
            .dropWhile { it.isBlank() }
            .dropLastWhile { it.isBlank() }
        return if (lines.isEmpty()) null else Block.Preformatted(lines.joinToString("\n") { it.trimEnd() })
    }

    private fun image(el: Element): Block.Image? {
        val src = imageSource(el) ?: return null
        if (isTiny(el)) return null
        return Block.Image(src, collapse(el.attr("alt")).ifEmpty { null })
    }

    /** The picture's address, preferring what lazy-loading scripts would swap in over a placeholder `src`. */
    private fun imageSource(el: Element): String? {
        for (attribute in IMAGE_SOURCES) {
            if (!el.hasAttr(attribute)) continue
            val url = el.absUrl(attribute)
            if (isWebUrl(url)) return url
        }
        for (attribute in IMAGE_SOURCE_SETS) {
            val candidate = firstCandidate(el.attr(attribute)) ?: continue
            el.attr(RESOLVE_KEY, candidate)
            val url = el.absUrl(RESOLVE_KEY)
            el.removeAttr(RESOLVE_KEY)
            if (isWebUrl(url)) return url
        }
        return null
    }

    private fun firstCandidate(srcset: String): String? =
        srcset.trimStart { it.isWhitespace() || it == ',' }.takeWhile { !it.isWhitespace() }.trimEnd(',').ifEmpty { null }

    /** Declared at 2 px or smaller: a tracking pixel or a spacer, not a picture. */
    private fun isTiny(el: Element): Boolean {
        val css = declarations(el)
        return sequenceOf(el.attr("width"), el.attr("height"), css["width"], css["height"])
            .any { value -> value?.trim()?.lowercase()?.removeSuffix("px")?.trim()?.toDoubleOrNull()?.let { it <= 2.0 } == true }
    }

    /** A frame that survived clean-up is a picture, a player or a shared document; the latter two become links. */
    private fun embed(el: Element, context: Context): Block? {
        val url = el.absUrl(PageCleanup.sourceAttribute(el))
        if (!isWebUrl(url)) return null
        if (PageCleanup.isImageEmbed(el)) {
            if (isTiny(el)) return null
            val alt = (boundedText(el, MAX_ALT) ?: "").ifEmpty { collapse(el.attr("title")) }
            return Block.Image(url, alt.ifEmpty { null })
        }
        return Block.Paragraph(listOf(Span(collapse(el.attr("title")).ifEmpty { url }, 0, url)), context.align)
    }

    private fun blockContext(el: Element, tag: String, css: Map<String, String>, context: Context): Context {
        val style = styleOf(context.style, tag, css)
        // The chapter element's own alignment is the site's layout, not the text's.
        val align = if (el === root) Align.Start else alignOf(el, tag, css, context.align)
        return if (style == context.style && align == context.align) context else context.copy(style = style, align = align)
    }

    private fun inlineContext(el: Element, tag: String, context: Context): Context {
        val style = styleOf(context.style, tag, declarations(el))
        val href =
            when {
                // Inside a footnote's mark, all of it opens the note.
                context.href?.startsWith(Span.NOTE) == true -> context.href
                else -> noteOf(el, tag, style)?.let { Span.NOTE + it } ?: if (tag == "a" && el.hasAttr("href")) linkOf(el) else context.href
            }
        return if (style == context.style && href == context.href) context else context.copy(style = style, href = href)
    }

    /**
     * The note [el] marks, when it's a footnote's mark ("1", "[2]", "*"): Modern Footnotes' note hidden after it
     * (WordPress sites, Scribble Hub), the place on the page it links to (a list of notes at the end, say), or its title.
     */
    private fun noteOf(el: Element, tag: String, style: Int): String? {
        if (tag !in NOTE_MARKS) return null
        val mark = collapse(el.text())
        if (!isMark(mark, raised = style and Span.SUPERSCRIPT != 0)) return null
        modernNote(el)?.let { return it }
        if (tag == "a") fragmentOf(el)?.let(::noteAt)?.let { return it }
        return collapse(el.attr("title")).takeIf { it.length in MIN_NOTE..MAX_NOTE && !it.equals(mark, ignoreCase = true) }
    }

    private fun modernNote(el: Element): String? {
        if (!el.hasClass(MODERN_MARK)) return null
        val note: Element = el.nextElementSibling() ?: return null
        if (!note.hasClass(MODERN_NOTE)) return null
        return collapse(note.text()).takeIf { it.length in MIN_NOTE..MAX_NOTE }
    }

    /** Where on this page [el] links to: the id after its #, when the link stays on the page. */
    private fun fragmentOf(el: Element): String? {
        val href = el.attr("href").trim()
        val hash = href.indexOf('#')
        if (hash < 0) return null
        if (hash > 0) {
            val to = el.absUrl("href").substringBefore('#')
            if (to.isEmpty() || to != el.baseUri().substringBefore('#')) return null
        }
        val id = href.substring(hash + 1)
        return runCatching { URLDecoder.decode(id, "UTF-8") }.getOrDefault(id).takeIf { it.isNotBlank() }
    }

    /** The note at [id], as the page had it, without its link back up to the mark. */
    private fun noteAt(id: String): String? {
        val page: Document = page ?: root.ownerDocument() ?: return null
        var block: Element = page.getElementById(id) ?: page.getElementsByAttributeValue("name", id).firstOrNull() ?: return null
        // A bare anchor, or a number, at the note's start: the note is the line or item it's in.
        var climbs = 0
        while (collapse(block.text()).length <= MAX_MARK && climbs++ < MAX_CLIMBS) block = block.parent() ?: break
        val note = block.clone()
        note.getElementsByTag("a").filter { '#' in it.attr("href") && collapse(it.text()).length <= MAX_BACKLINK }.forEach { it.remove() }
        val text = collapse(note.text()).replace(LEADING_MARK, "").replace(TRAILING_BACK, "")
        return text.takeIf { it.length in MIN_NOTE..MAX_NOTE }
    }

    /** A footnote's mark: a number or a symbol, maybe bracketed; letters too when raised. */
    private fun isMark(text: String, raised: Boolean): Boolean = text.length in 1..MAX_MARK && (MARK.matches(text) || raised && LETTER_MARK.matches(text))

    private fun linkOf(el: Element): String? =
        el.absUrl("href").takeIf { isWebUrl(it) || it.startsWith("mailto:", ignoreCase = true) }

    /** [inherited] with the emphasis [tag] adds and its inline style sets or clears, as a browser would show it. */
    private fun styleOf(inherited: Int, tag: String, css: Map<String, String>): Int {
        val own = TAG_STYLES[tag] ?: 0
        var style = inherited or own
        if (css.isEmpty()) return style
        css["font-weight"]?.let { style = withWeight(style, it) }
        css["font-style"]?.let { value ->
            if (value.startsWith("italic") || value.startsWith("oblique")) style = style or Span.ITALIC
            else if (value.startsWith("normal")) style = style and Span.ITALIC.inv()
        }
        css["font"]?.let { value ->
            val tokens = value.split(' ')
            if (tokens.any { it == "bold" || it == "bolder" || (it.toIntOrNull() ?: 0) >= BOLD_WEIGHT }) style = style or Span.BOLD
            if (tokens.any { it == "italic" || it == "oblique" }) style = style or Span.ITALIC
        }
        // Decorations reach into descendants however they are styled, so "none" only undoes the tag's own.
        val ownOnly = own and inherited.inv()
        (css["text-decoration"] ?: css["text-decoration-line"])?.let { value ->
            if ("underline" in value) style = style or Span.UNDERLINE
            if ("line-through" in value) style = style or Span.STRIKE
            if (value.startsWith("none")) style = style and (ownOnly and (Span.UNDERLINE or Span.STRIKE)).inv()
        }
        css["vertical-align"]?.let { value ->
            style = when {
                value.startsWith("super") -> (style or Span.SUPERSCRIPT) and Span.SUBSCRIPT.inv()
                value.startsWith("sub") -> (style or Span.SUBSCRIPT) and Span.SUPERSCRIPT.inv()
                value.startsWith("baseline") -> style and (ownOnly and (Span.SUPERSCRIPT or Span.SUBSCRIPT)).inv()
                else -> style
            }
        }
        return style
    }

    private fun withWeight(style: Int, value: String): Int {
        val number = value.takeWhile { it.isDigit() }.toIntOrNull()
        return when {
            value.startsWith("bold") -> style or Span.BOLD
            number != null -> if (number >= BOLD_WEIGHT) style or Span.BOLD else style and Span.BOLD.inv()
            value.startsWith("normal") || value.startsWith("lighter") -> style and Span.BOLD.inv()
            else -> style
        }
    }

    private fun alignOf(el: Element, tag: String, css: Map<String, String>, inherited: Align): Align {
        if (tag == "center") return Align.Center
        val value = css["text-align"] ?: el.attr("align").trim().lowercase().ifEmpty { return inherited }
        return when (value) {
            "center", "-webkit-center", "-moz-center", "middle" -> Align.Center
            "right", "end", "-webkit-right", "-moz-right" -> Align.End
            "justify", "justify-all" -> Align.Justify
            "left", "start", "-webkit-left", "-moz-left", "initial" -> Align.Start
            else -> inherited
        }
    }

    /** Framed text: a border or a background of its own, or a name that says so ("system", "status-window"). */
    private fun isBox(el: Element, tag: String, css: Map<String, String>): Boolean =
        el !== root && (tag in ALWAYS_BOXES || (tag in BOX_CANDIDATES && (hasVisibleBorder(css) || hasBackground(css) || hasBoxName(el))))

    private fun hasVisibleBorder(css: Map<String, String>): Boolean = css.any { (key, value) ->
        val border = key == "border" || key in BORDER_SIDES || (key.startsWith("border") && key.endsWith("-style"))
        border && value.split(' ', ',').let { tokens -> tokens.any { it in BORDER_STYLES } && tokens.none { ZERO_LENGTH.matches(it) } }
    }

    private fun hasBackground(css: Map<String, String>): Boolean {
        val value = css["background-color"] ?: css["background"] ?: return false
        return !NO_BACKGROUND.containsMatchIn(value)
    }

    private fun hasBoxName(el: Element): Boolean {
        if (el.hasClass("has-background")) return true
        val names = (el.className() + " " + el.id()).lowercase()
        if (names.isBlank()) return false
        return names.split(NAME_SEPARATOR).any { token -> token in BOX_NAMES || BOX_NAME_PREFIXES.any(token::startsWith) }
    }

    /** Past [MAX_DEPTH] the markup is broken; its text is kept, one paragraph per block, without structure. */
    private fun flatten(el: Element, flow: Flow, context: Context) {
        NodeTraversor.filter(
            object : NodeFilter {
                override fun head(node: Node, depth: Int): NodeFilter.FilterResult {
                    when {
                        node is TextNode -> flow.text(node.wholeText, context)
                        node !is Element || node === el -> Unit
                        node.normalName() in SKIPPED -> return NodeFilter.FilterResult.SKIP_ENTIRELY
                        node.normalName() == "br" -> flow.lineBreak()
                        node.isBlock -> flow.endParagraph()
                    }
                    return NodeFilter.FilterResult.CONTINUE
                }

                override fun tail(node: Node, depth: Int): NodeFilter.FilterResult {
                    if (node is Element && node !== el && node.isBlock) flow.endParagraph()
                    return NodeFilter.FilterResult.CONTINUE
                }
            },
            el,
        )
    }

    private fun chapterWeight(): Int {
        if (chapterWeight < 0) {
            var weight = 0
            root.forEachNode { if (it is TextNode) weight += visibleLength(it.wholeText) }
            chapterWeight = weight
        }
        return chapterWeight
    }

    /**
     * Collects inline content into paragraphs: collapses whitespace, turns one <br> into a line
     * break and two or more into a new paragraph, and merges neighbouring text of the same style.
     */
    private class Flow {
        private val blocks = ArrayList<Block>()
        private val spans = ArrayList<Span>()
        private val run = StringBuilder()
        private var runStyle = 0
        private var runHref: String? = null
        private var align = Align.Start
        private var breaks = 0
        private var space = false

        private val empty get() = spans.isEmpty() && run.isEmpty()

        fun text(raw: String, context: Context) {
            for (c in raw) {
                when {
                    c.isWhitespace() -> space = true
                    c in INVISIBLE_CHARS || c.isISOControl() -> Unit
                    else -> {
                        if (breaks >= 2) endParagraph()
                        when {
                            empty -> align = context.align
                            breaks == 1 -> separate('\n', context)
                            space -> separate(' ', context)
                        }
                        useRun(context.style, context.href)
                        run.append(c)
                        breaks = 0
                        space = false
                    }
                }
            }
        }

        fun lineBreak() {
            if (!empty) breaks++
            space = false
        }

        fun endParagraph() {
            closeRun()
            breaks = 0
            space = false
            if (spans.isEmpty()) return
            val paragraph = ArrayList(spans)
            spans.clear()
            add(
                when {
                    isSceneBreak(paragraph) -> Block.SceneBreak
                    isCredit(paragraph) -> Block.Credit(creditLines(paragraph), align)
                    else -> Block.Paragraph(paragraph, align)
                },
            )
        }

        fun block(block: Block) {
            endParagraph()
            add(block)
        }

        /**
         * Adds [block] after the current paragraph; repeated scene breaks collapse, and neighbouring boxes
         * join, as do neighbouring credits, a line each.
         */
        fun add(block: Block) {
            val last = blocks.lastOrNull()
            when {
                block == Block.SceneBreak && last == Block.SceneBreak -> Unit
                block is Block.Box && last is Block.Box -> blocks[blocks.size - 1] = Block.Box(last.blocks + block.blocks)
                block is Block.Credit && last is Block.Credit && block.align == last.align ->
                    blocks[blocks.size - 1] = Block.Credit(last.spans + Span("\n") + block.spans, block.align)
                else -> blocks += block
            }
        }

        /** The blocks, without scene breaks at either end when [trim] (they separate nothing there). */
        fun finish(trim: Boolean): List<Block> {
            endParagraph()
            if (trim) {
                while (blocks.firstOrNull() == Block.SceneBreak) blocks.removeAt(0)
                while (blocks.lastOrNull() == Block.SceneBreak) blocks.removeAt(blocks.size - 1)
            }
            return blocks
        }

        /**
         * Puts [separator] between the text so far and text in [context]. A space goes where it does
         * not show: into the run before it unless that run is linked, lined, raised, lowered or
         * code, then into the run after it, and otherwise into a plain span of its own.
         */
        private fun separate(separator: Char, context: Context) {
            when {
                separator == '\n' -> useRun(context.style, context.href)
                !spaceShows(runStyle, runHref) -> Unit
                !spaceShows(context.style, context.href) -> useRun(context.style, context.href)
                runStyle == context.style && runHref == context.href -> Unit
                else -> useRun(runStyle and SPACE_SHOWING.inv(), null)
            }
            run.append(separator)
        }

        private fun spaceShows(style: Int, href: String?): Boolean = href != null || style and SPACE_SHOWING != 0

        private fun useRun(style: Int, href: String?) {
            if (run.isNotEmpty() && (style != runStyle || href != runHref)) closeRun()
            if (run.isEmpty()) {
                runStyle = style
                runHref = href
            }
        }

        private fun closeRun() {
            if (run.isEmpty()) return
            val last = spans.lastOrNull()
            if (last != null && last.style == runStyle && last.href == runHref) {
                spans[spans.size - 1] = last.copy(text = last.text + run)
            } else {
                spans += Span(run.toString(), runStyle, runHref)
            }
            run.setLength(0)
        }
    }

    companion object {
        /** [root]'s blocks; footnotes' notes are looked for in [page] (the page before cleaning), else in [root]'s document. */
        fun convert(root: Element, page: Document? = null): List<Block> = BlockConverter(root, page).convertRoot()

        // Deeper than this, markup is broken rather than structured (the parser allows 512 levels).
        private const val MAX_DEPTH = 100
        private const val MAX_COLSPAN = 1000
        private const val MAX_ROWSPAN = 65534
        private const val MAX_ALT = 300
        private const val BOLD_WEIGHT = 600

        // Styles under which a space looks different from a plain one.
        private const val SPACE_SHOWING = Span.UNDERLINE or Span.STRIKE or Span.SUPERSCRIPT or Span.SUBSCRIPT or Span.CODE

        // A frame holding this share of the chapter's text is layout, not a status window.
        private const val WRAPPER_SHARE = 0.6

        private const val RESOLVE_KEY = "data-reader-resolve"

        // Footnotes: the elements that may be a mark, Modern Footnotes' classes, and how marks and notes look.
        private val NOTE_MARKS = setOf("a", "sup", "span", "abbr")
        private const val MODERN_MARK = "modern-footnotes-footnote"
        private const val MODERN_NOTE = "modern-footnotes-footnote__note"
        private val MARK = Regex("""[\[(]?(?:\d{1,3}|[*†‡§¶]{1,3})[\])]?""")
        private val LETTER_MARK = Regex("""[\[(]?[A-Za-z]{1,4}[\])]?""")
        private val LEADING_MARK = Regex("""^[\[(]?\d{1,3}[\])]?[.:)]?\s+""")
        private val TRAILING_BACK = Regex("""[\s↩↑⤴^\uFE0E\uFE0F]+$""")
        private const val MAX_MARK = 8
        private const val MAX_BACKLINK = 12
        private const val MAX_CLIMBS = 3
        private const val MIN_NOTE = 2
        private const val MAX_NOTE = 1500

        private val SKIPPED = setOf(
            "script", "style", "noscript", "template", "head", "title", "meta", "link", "base",
            "nav", "button", "select", "option", "optgroup", "datalist", "textarea", "input", "output",
            "progress", "meter", "svg", "math", "canvas", "video", "audio", "source", "track", "map", "area",
            "dialog", "frame", "frameset", "noframes", "param",
        )
        private val CONTAINERS = setOf(
            "p", "div", "section", "article", "main", "header", "footer", "aside", "center", "figure", "figcaption",
            "details", "summary", "form", "fieldset", "legend", "hgroup", "address", "dl", "dt", "dd", "li",
            "body", "html", "search", "caption", "td", "th", "tr", "thead", "tbody", "tfoot",
        )
        private val PREFORMATTED = setOf("pre", "xmp", "listing", "plaintext")
        private val LISTS = setOf("ul", "ol", "menu", "dir")

        private val TAG_STYLES = mapOf(
            "b" to Span.BOLD, "strong" to Span.BOLD,
            "i" to Span.ITALIC, "em" to Span.ITALIC, "cite" to Span.ITALIC, "dfn" to Span.ITALIC, "var" to Span.ITALIC,
            "u" to Span.UNDERLINE, "ins" to Span.UNDERLINE,
            "s" to Span.STRIKE, "strike" to Span.STRIKE, "del" to Span.STRIKE,
            "sup" to Span.SUPERSCRIPT, "sub" to Span.SUBSCRIPT,
            "code" to Span.CODE, "kbd" to Span.CODE, "samp" to Span.CODE, "tt" to Span.CODE,
        )

        private val IMAGE_SOURCES = listOf("data-src", "data-lazy-src", "data-original", "src")
        private val IMAGE_SOURCE_SETS = listOf("data-srcset", "data-lazy-srcset", "srcset")

        private val ALWAYS_BOXES = setOf("aside", "fieldset")
        private val BOX_CANDIDATES = setOf("div", "p", "section", "article", "center", "details")
        private val BORDER_SIDES = setOf(
            "border-top", "border-right", "border-bottom", "border-left", "border-block", "border-inline",
            "border-block-start", "border-block-end", "border-inline-start", "border-inline-end",
        )
        private val BORDER_STYLES = setOf("solid", "dashed", "dotted", "double", "groove", "ridge", "inset", "outset")
        private val ZERO_LENGTH = Regex("""0(?:\.0+)?(?:px|em|rem|pt)?""")
        private val NO_BACKGROUND = Regex(
            """^(?:transparent|none|initial|inherit|unset|revert|white|currentcolor|url\(|#fff\b|#ffffff\b|""" +
                """rgba?\(\s*255\s*,\s*255\s*,\s*255\s*[,)]|rgba\([^)]*,\s*0(?:\.0+)?\s*\))""",
        )
        private val BOX_NAMES = setOf(
            "stats", "statblock", "statbox", "notification", "gamebox", "msgbox", "messagebox", "box", "window", "panel", "callout",
        )
        private val BOX_NAME_PREFIXES = listOf("system", "sysmsg", "status")
        private val NAME_SEPARATOR = Regex("[^a-z0-9]+")

        // Scene-break rows: a few symbols repeated ("***", "◇◇◇", "~~~"), or a row of o's. Emoticons
        // such as "-_-" and "^_^" use characters left out here, and ellipses are words' punctuation.
        private const val BREAK_SYMBOLS = "*~-‐‑‒–—―=#+•·◦∙°○●◎◇◆◈□■▪▫△▲▽▼☆★✦✧✩✪✫✬✭✮✯✰❖❋❊❉❈❇❅✽✼✻✺✹✸✷✶✵✴✳❀❁❃❂§¤♦♢♠♤♣♧♥♡"
        private val O_BREAK = Regex("""[-~*=+#]*(?:o{3,5}|oOo|OoO|o0o)[-~*=+#]*""")
        private const val MAX_BREAK_LENGTH = 30

        private fun isSceneBreak(spans: List<Span>): Boolean {
            if (spans.any { it.href != null }) return false
            val text = StringBuilder()
            for (span in spans) for (c in span.text) if (!c.isWhitespace()) text.append(c)
            if (text.length == 1) return text[0] == '⁂'
            if (text.length !in 3..MAX_BREAK_LENGTH) return false
            if (O_BREAK.matches(text)) return true
            return text.all { it in BREAK_SYMBOLS } && text.toSet().size <= 3
        }

        // Translator and editor credits ("Translator: X Editor: Y"): roles followed by names of up to
        // four words, so a translator's note ("Translator: the title is a pun in the original") stays
        // the chapter's text.
        private const val CREDIT_ROLE = """(?:translator|translated by|tl|tlc|editor|edited by|proofreader|proofread by)"""
        private const val CREDIT_NAME = """\S+(?:\s+\S+){0,3}"""
        private val CREDIT = Regex("""$CREDIT_ROLE\s*:\s*$CREDIT_NAME(?:\s+$CREDIT_ROLE\s*:\s*$CREDIT_NAME)*""", RegexOption.IGNORE_CASE)
        private const val MAX_CREDIT_LENGTH = 300

        // What goes between one credit and the next on a line: blanks, and the "|" or "/" sites put there.
        private val BETWEEN_CREDITS = Regex("""[\s|/•·,;–—-]+(?=$CREDIT_ROLE\s*:)""", RegexOption.IGNORE_CASE)

        private fun isCredit(spans: List<Span>): Boolean {
            if (spans.sumOf { it.text.length } > MAX_CREDIT_LENGTH) return false
            return CREDIT.matches(spans.joinToString("") { it.text })
        }

        /** Credit [spans] with each credit on a line of its own: "Translator: X", then "Editor: Y" below. */
        private fun creditLines(spans: List<Span>): List<Span> {
            val text = spans.joinToString("") { it.text }
            val cuts = BETWEEN_CREDITS.findAll(text).map { it.range }.toList()
            if (cuts.isEmpty()) return spans
            val lines = ArrayList<Span>(spans.size)
            var start = 0
            for (span in spans) {
                val kept = StringBuilder()
                for (i in start until start + span.text.length) {
                    val cut = cuts.firstOrNull { i in it }
                    when {
                        cut == null -> kept.append(text[i])
                        i == cut.first -> kept.append('\n')
                    }
                }
                if (kept.isNotEmpty()) lines += span.copy(text = kept.toString())
                start += span.text.length
            }
            return lines
        }

        private fun declarations(el: Element): Map<String, String> {
            val style = el.attr("style")
            if (style.isBlank()) return emptyMap()
            val map = HashMap<String, String>(4)
            for (part in style.split(';')) {
                val colon = part.indexOf(':')
                if (colon <= 0) continue
                val key = part.substring(0, colon).trim().lowercase()
                val value = part.substring(colon + 1).replace("!important", "", ignoreCase = true).trim().lowercase()
                if (key.isNotEmpty() && value.isNotEmpty()) map[key] = value
            }
            return map
        }

        private fun isWebUrl(url: String): Boolean = url.startsWith("https://", ignoreCase = true) || url.startsWith("http://", ignoreCase = true)

        private fun collapse(text: String): String = text.trim().replace(WHITESPACE, " ")

        private val WHITESPACE = Regex("""\s+""")
    }
}
