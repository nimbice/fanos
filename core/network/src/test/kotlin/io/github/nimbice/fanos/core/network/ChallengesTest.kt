package io.github.nimbice.fanos.core.network

import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChallengesTest {

    private fun response(code: Int, server: String?, body: String, vararg headers: Pair<String, String>): Response =
        Response.Builder()
            .request(Request.Builder().url("https://example.com/novel").build())
            .protocol(Protocol.HTTP_1_1)
            .code(code)
            .message("")
            .apply { server?.let { header("Server", it) } }
            .apply { headers.forEach { (name, value) -> header(name, value) } }
            .body(body.toResponseBody())
            .build()

    private val checkPage = "<html><head><title>Just a moment...</title></head><body></body></html>"

    @Test
    fun `cloudflare marks its challenges with a header`() {
        assertTrue(isChallenge(response(403, "cloudflare", "", "cf-mitigated" to "challenge")))
    }

    @Test
    fun `older cloudflare setups are known by their check page`() {
        assertTrue(isChallenge(response(503, "cloudflare", checkPage)))
    }

    @Test
    fun `ddos-guard is known by its check page`() {
        assertTrue(isChallenge(response(503, "ddos-guard", checkPage)))
    }

    @Test
    fun `an ordinary 403 is not a challenge`() {
        assertFalse(isChallenge(response(403, "nginx", checkPage)))
        assertFalse(isChallenge(response(403, "cloudflare", "<html><title>Forbidden</title></html>")))
    }

    @Test
    fun `a check page served with 200 is the page asked for`() {
        assertFalse(isChallenge(response(200, "ddos-guard", checkPage)))
    }

    private val anubisPage =
        """<!doctype html><html><head><title>Making sure you&#39;re not a bot!</title>
           <script id="anubis_challenge" type="application/json">{"rules":{"algorithm":"fast","difficulty":4}}</script>
           </head><body><h1>Making sure you&#39;re not a bot!</h1></body></html>"""

    @Test
    fun `anubis, in front of Royal Road, sends its check as an ordinary page`() {
        assertTrue(isChallenge(response(200, "cloudflare", anubisPage, "Content-Type" to "text/html; charset=utf-8")))
    }

    @Test
    fun `an ordinary page is not a challenge`() {
        assertFalse(isChallenge(response(200, "cloudflare", "<html><title>The Lamplit Archive</title></html>", "Content-Type" to "text/html")))
        // Not a page at all: the site's JSON, whatever is in it.
        assertFalse(isChallenge(response(200, "cloudflare", anubisPage, "Content-Type" to "application/json")))
    }

    @Test
    fun `a check sent as an ordinary page is known by its title`() {
        val botVerification = "<html><head><title>Bot Verification</title></head><body></body></html>"
        assertTrue(isChallenge(response(200, "nginx", botVerification, "Content-Type" to "text/html")))
        assertTrue(isChallenge(response(200, "ddos-guard", "<html><head><title>DDoS-Guard</title></head></html>", "Content-Type" to "text/html")))
    }

    @Test
    fun `check titles, and titles that only look like them`() {
        listOf("Just a moment...", "Bot Verification", "You are being redirected...", "Redirecting...", "Redirecting&hellip;", "Un instant...", "DDoS-Guard", "Making sure you&#39;re not a bot!")
            .forEach { assertTrue(it, isCheckTitle(it)) }
        listOf("Redirecting Fate | Novel Index", "Lamp Magus | Chapters » Page 14", "A Moment Too Late", "")
            .forEach { assertFalse(it, isCheckTitle(it)) }
    }
}
