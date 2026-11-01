package io.github.nimbice.fanos.core.network

import okhttp3.Response

/**
 * Whether [response] is a bot-check page rather than the page asked for. Cloudflare marks its
 * challenges with a `cf-mitigated: challenge` header; older Cloudflare setups, and DDoS-Guard, are
 * recognised by a 403 or 503 from their server that serves the check page. Anubis (in front of Royal
 * Road) sends its check as an ordinary page, known by the challenge it carries; and some sites (partway
 * through a long novel's chapter pages) send theirs as one too, known by its title ([isCheckTitle]). Any
 * other 403 is an ordinary error: the old app treated every 403 as a challenge and sent users to solve
 * checks that did not exist.
 */
fun isChallenge(response: Response): Boolean {
    if (response.header("cf-mitigated").equals("challenge", ignoreCase = true)) return true
    if (response.isSuccessful && response.header("content-type").orEmpty().contains("html", ignoreCase = true)) {
        val start = response.peekBody(CHECK_PEEK).string()
        return ANUBIS_MARKER in start || isCheckTitle(TITLE.find(start)?.groupValues?.get(1).orEmpty())
    }
    if (response.code != 403 && response.code != 503) return false
    val server = response.header("server").orEmpty()
    if (CHALLENGE_SERVERS.none { server.contains(it, ignoreCase = true) }) return false
    val start = response.peekBody(64L * 1024).string()
    return CHALLENGE_MARKERS.any { start.contains(it, ignoreCase = true) }
}

private val CHALLENGE_SERVERS = listOf("cloudflare", "ddos-guard")

/** In Anubis's check page: the challenge its script works out. */
internal const val ANUBIS_MARKER = "id=\"anubis_challenge\""

/** How much of an ordinary page is looked at for a check: its marks are in the check page's head. */
private const val CHECK_PEEK = 16L * 1024

private val TITLE = Regex("<title[^>]*>([^<]*)</title>", RegexOption.IGNORE_CASE)

/**
 * Whether a page titled [title] is a site's bot check: Cloudflare's ("Just a moment..."), Anubis's ("Making sure you're
 * not a bot!"), DDoS-Guard's, and other common ones ("Bot Verification", "You are being
 * redirected...", "Un instant..."), by how they start; a bare "Redirecting..." only as the whole title.
 */
fun isCheckTitle(title: String): Boolean {
    // The page's own title, as written: "Redirecting&hellip;", "Making sure you&#39;re...".
    val plain = ENTITIES.entries.fold(title) { text, (entity, char) -> text.replace(entity, char, ignoreCase = true) }.trim()
    return CHECK_TITLE_STARTS.any { plain.startsWith(it, ignoreCase = true) } ||
        plain.trimEnd('.', '\u2026', ' ').equals("Redirecting", ignoreCase = true)
}

private val ENTITIES = mapOf("&hellip;" to "\u2026", "&#8230;" to "\u2026", "&#39;" to "'", "&#x27;" to "'", "&rsquo;" to "\u2019", "&amp;" to "&", "&nbsp;" to " ")

private val CHECK_TITLE_STARTS =
    listOf("Just a moment", "Attention Required", "Making sure you", "Bot Verification", "You are being redirected", "Un instant", "DDoS-Guard")

internal val CHALLENGE_MARKERS = listOf("<title>Just a moment", "challenge-platform", "cf-chl-", "Attention Required! | Cloudflare")
