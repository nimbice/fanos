package io.github.nimbice.fanos.source.api

import org.jsoup.nodes.Element
import java.net.URLEncoder

/** Text of the first match, trimmed, or null when there is none or it is blank. */
fun Element.textOf(cssQuery: String): String? = selectFirst(cssQuery)?.text()?.trim()?.ifEmpty { null }

/** Absolute URL from the first match's [attribute], or null. */
fun Element.urlOf(cssQuery: String, attribute: String = "href"): String? =
    selectFirst(cssQuery)?.absUrl(attribute)?.ifEmpty { null }

/** An image URL, taking lazy-loading attributes into account. */
fun Element.imageUrl(): String? =
    sequenceOf("data-src", "data-lazy-src", "data-original", "src")
        .map { absUrl(it) }
        .firstOrNull { it.isNotEmpty() && !it.startsWith("data:") }

fun String.urlEncoded(): String = URLEncoder.encode(this, Charsets.UTF_8)

/** The element's text with its paragraphs kept apart by blank lines, for descriptions. */
fun Element.paragraphsText(): String? {
    val paragraphs = select("p").map { it.text().trim() }.filter { it.isNotEmpty() }
    val text = if (paragraphs.isNotEmpty()) paragraphs.joinToString("\n\n") else text().trim()
    return text.ifEmpty { null }
}

/** Reads the status words sites use ("Ongoing", "Completed", "Hiatus", "Dropped"). */
fun parseStatus(text: String?): NovelDetails.Status {
    val t = text?.lowercase() ?: return NovelDetails.Status.Unknown
    return when {
        "complete" in t || "finished" in t || "end" == t.trim() -> NovelDetails.Status.Completed
        "hiatus" in t || "paused" in t || "stub" in t -> NovelDetails.Status.Hiatus
        "drop" in t || "cancel" in t || "discontinue" in t -> NovelDetails.Status.Cancelled
        "ongoing" in t || "on going" in t || "active" in t || "updating" in t -> NovelDetails.Status.Ongoing
        else -> NovelDetails.Status.Unknown
    }
}
