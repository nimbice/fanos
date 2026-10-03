package io.github.nimbice.fanos.core.network

import okhttp3.Response

/**
 * Whether [response] is a bot-check page rather than the page asked for. Cloudflare marks its
 * challenges with a `cf-mitigated: challenge` header; older Cloudflare setups, and DDoS-Guard, are
 * recognised by a 403 or 503 from their server that serves the check page. Any
 * other 403 is an ordinary error: the old app treated every 403 as a challenge and sent users to solve
 * checks that did not exist.
 */
fun isChallenge(response: Response): Boolean {
    if (response.header("cf-mitigated").equals("challenge", ignoreCase = true)) return true
    if (response.code != 403 && response.code != 503) return false
    val server = response.header("server").orEmpty()
    if (CHALLENGE_SERVERS.none { server.contains(it, ignoreCase = true) }) return false
    val start = response.peekBody(64L * 1024).string()
    return CHALLENGE_MARKERS.any { start.contains(it, ignoreCase = true) }
}

private val CHALLENGE_SERVERS = listOf("cloudflare", "ddos-guard")

internal val CHALLENGE_MARKERS = listOf("<title>Just a moment", "challenge-platform", "cf-chl-", "Attention Required! | Cloudflare")
