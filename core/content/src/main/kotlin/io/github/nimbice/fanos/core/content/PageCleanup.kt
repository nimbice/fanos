// Derived from NovelLibrary's HtmlCleaner (Apache-2.0), modified.
package io.github.nimbice.fanos.core.content

import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URI
import java.util.Collections
import java.util.IdentityHashMap

/**
 * Stage 1 of extraction: clean-up on the whole page. What marks text as not the chapter's is often
 * outside it: Royal Road hides the notice it plants in every chapter with a rule in a <style>
 * block in the head, and aggregators put ad frames around the text as well as inside it.
 */
internal object PageCleanup {

    // Embedded frames that stay: players and shared documents a chapter may embed on purpose. Every
    // other frame is treated as an ad; judging frames by where they point keeps up with ad networks
    // better than a list of them would.
    private val CONTENT_EMBED_HOSTS = setOf(
        "youtube.com", "youtube-nocookie.com", "vimeo.com", "dailymotion.com",
        "soundcloud.com", "spotify.com", "docs.google.com", "drive.google.com",
    )

    // Ads that are not frames: AdSense slots, WordPress.com (WordAds) blocks, and links marked as
    // paid that hold no text (banner images, click-catchers laid over an ad frame).
    private const val AD_SLOT_SELECTOR = "ins.adsbygoogle,div.wpcnt,a[rel~=sponsored]:not(:matches(\\S))"

    // Wrappers dropped along with a removed ad, notice or hidden text when it was all they held.
    private val WRAPPER_TAGS = setOf("div", "p", "span", "a", "center", "section", "aside", "figure", "ins")

    // A declaration that hides its element, in an inline style or a <style> rule.
    private val HIDDEN_DECLARATION = Regex("""(?:^|[\s;{])(?:display\s*:\s*none|visibility\s*:\s*hidden)""", RegexOption.IGNORE_CASE)

    // The parts of a <style> block: comments, at-rules (@media and the like apply only sometimes, or
    // not to elements), and plain "selectors { declarations }" rules. The quantifiers are
    // possessive so a malformed sheet cannot make the matching backtrack.
    private val CSS_COMMENT = Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL)
    private val CSS_AT_RULE = Regex("""@[^{};]*+(?:;|\{(?:[^{}]*+\{[^{}]*+\})*+[^{}]*+\})""")
    private val CSS_RULE = Regex("""([^{}]++)\{([^{}]*+)\}""")

    // Selectors plain enough to act on: an optional tag with classes and ids, nothing that depends
    // on state or position.
    private val SIMPLE_SELECTOR = Regex("""[a-zA-Z0-9]*(?:[.#][\w-]+)+""")

    // Hidden text goes only in short runs that add up to a small share of the page.
    private const val MAX_HIDDEN_TEXT = 500
    private const val MAX_HIDDEN_SHARE = 0.1

    // Hidden elements holding this many paragraphs are content a script would reveal, not a notice.
    private const val CONTENT_PARAGRAPHS = 3

    // Objects and embeds that are pictures rather than plugins or ads.
    private val IMAGE_EXTENSIONS = setOf(".svg", ".png", ".jpg", ".jpeg", ".gif", ".webp")

    /** Cleans [doc] in place. [keep], the chapter when the source knows it, is never removed, nor anything around it. */
    fun clean(doc: Document, keep: Element?) {
        val protected = keep?.let(::selfAndAncestors) ?: emptySet()
        doc.getElementsByTag("script").remove()
        doc.getElementsByTag("noscript").filterNot { it in protected }.forEach { it.remove() }
        removeHiddenText(doc, protected)
        removeAds(doc, protected)
    }

    /**
     * Removes text the page hides with the `hidden` attribute, an inline style, or a rule in one of
     * its <style> blocks. Dropping the site's CSS would reveal it: Royal Road plants a hidden notice
     * about stolen copies in every chapter. Only short runs go, and only while they add up to a
     * small share of the page, because some sites hide the chapter itself until a script shows it.
     */
    private fun removeHiddenText(doc: Document, protected: Set<Element>) {
        val body = doc.body()
        val hidden = LinkedHashSet<Element>(body.select("[hidden]"))
        body.select("[style]").filterTo(hidden) { HIDDEN_DECLARATION.containsMatchIn(it.attr("style")) }
        for (style in doc.select("style")) {
            for (selector in hidingSelectors(style.data())) {
                runCatching { body.select(selector) }.getOrNull()?.let { hidden.addAll(it) }
            }
        }
        val short = hidden.filter { it !in protected }.mapNotNull { element ->
            val length = boundedText(element, MAX_HIDDEN_TEXT)?.length ?: return@mapNotNull null
            if (element.getElementsByTag("p").size >= CONTENT_PARAGRAPHS) null else element to length
        }
        if (short.isEmpty() || short.sumOf { it.second } > body.text().length * MAX_HIDDEN_SHARE) return
        short.forEach { (element, _) -> removeWithEmptyWrappers(element, protected) }
    }

    /** Simple selectors of the rules in [css] that hide their elements, outside at-rules. */
    private fun hidingSelectors(css: String): List<String> {
        var plain = css.replace(CSS_COMMENT, "")
        // Each pass strips the innermost at-rules, so nested @media / @supports blocks go too.
        do {
            val before = plain
            plain = plain.replace(CSS_AT_RULE, "")
        } while (plain != before)
        return CSS_RULE.findAll(plain)
            .filter { HIDDEN_DECLARATION.containsMatchIn(it.groupValues[2]) }
            .flatMap { rule -> rule.groupValues[1].split(',').map { it.trim() } }
            .filter { SIMPLE_SELECTOR.matches(it) }
            .toList()
    }

    /**
     * Strips ads: embedded frames other than the players and documents in [CONTENT_EMBED_HOSTS],
     * and ad placeholders, together with any wrapper they leave empty. Sites put ad frames between
     * the paragraphs, which would otherwise show as blank or ad-filled patches.
     */
    private fun removeAds(doc: Document, protected: Set<Element>) {
        doc.select("iframe,object,embed").filterNot(::isContentEmbed).forEach { removeWithEmptyWrappers(it, protected) }
        doc.select(AD_SLOT_SELECTOR).forEach { removeWithEmptyWrappers(it, protected) }
    }

    /** Removes [element], then each wrapper it leaves without content, so no empty gap remains. */
    fun removeWithEmptyWrappers(element: Element, protected: Set<Element>) {
        if (element in protected) return
        var wrapper = element.parent()
        element.remove()
        while (wrapper != null && wrapper !in protected && wrapper.hasParent() && wrapper.normalName() in WRAPPER_TAGS &&
            wrapper.childrenSize() == 0 && !wrapper.hasText()
        ) {
            val next = wrapper.parent()
            wrapper.remove()
            wrapper = next
        }
    }

    private fun isContentEmbed(frame: Element): Boolean {
        if (isImageEmbed(frame)) return true
        val host = hostOf(frame.absUrl(sourceAttribute(frame))) ?: return false
        return CONTENT_EMBED_HOSTS.any { host == it || host.endsWith(".$it") }
    }

    /** Whether [frame], an <object> or <embed>, shows a picture (an author's map, a diagram) rather than a plugin. */
    fun isImageEmbed(frame: Element): Boolean {
        if (frame.normalName() == "iframe") return false
        val path = frame.absUrl(sourceAttribute(frame)).substringBefore('?').substringBefore('#').lowercase()
        return frame.attr("type").startsWith("image/") || IMAGE_EXTENSIONS.any { path.endsWith(it) }
    }

    fun sourceAttribute(frame: Element): String = if (frame.normalName() == "object") "data" else "src"

    private fun hostOf(url: String): String? =
        if (url.isEmpty()) null else runCatching { URI(url).host?.lowercase() }.getOrNull()

    private fun selfAndAncestors(element: Element): Set<Element> {
        val set = Collections.newSetFromMap(IdentityHashMap<Element, Boolean>())
        var current: Element? = element
        while (current != null) {
            set.add(current)
            current = current.parent()
        }
        return set
    }
}
