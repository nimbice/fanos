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
    fun `ddos-guard, in front of Ranobes, is known by its check page`() {
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
}
