package io.github.nimbice.fanos.core.network

import org.junit.Assert.assertEquals
import org.junit.Test

class BrowserUserAgentTest {

    @Test
    fun `a WebView's marks come off`() {
        val webView = "Mozilla/5.0 (Linux; Android 16; Pixel 9 Build/BP2A.250605.031; wv) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Version/4.0 Chrome/141.0.7390.122 Mobile Safari/537.36"
        assertEquals(
            "Mozilla/5.0 (Linux; Android 16; Pixel 9 Build/BP2A.250605.031) AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/141.0.7390.122 Mobile Safari/537.36",
            BrowserUserAgent.plain(webView),
        )
    }

    @Test
    fun `a browser's own is left as it is`() {
        val chrome = "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/141.0.0.0 Mobile Safari/537.36"
        assertEquals(chrome, BrowserUserAgent.plain(chrome))
    }
}
