package io.github.nimbice.fanos.core.network

import android.webkit.CookieManager
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl

/**
 * Keeps OkHttp's cookies in the WebView cookie store, so a bot check passed in the app's browser
 * view (which sets cf_clearance there) also covers requests made through OkHttp, and the other way
 * round.
 */
class WebViewCookieJar(
    private val manager: () -> CookieManager = { CookieManager.getInstance() },
) : CookieJar {

    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        val store = manager()
        val target = url.toString()
        cookies.forEach { store.setCookie(target, it.toString()) }
    }

    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        val header = manager().getCookie(url.toString()) ?: return emptyList()
        return header.split(';').mapNotNull { Cookie.parse(url, it.trim()) }
    }

    /**
     * Expires every cookie sent to [url]'s host. A Max-Age=0 cookie only replaces one with the same
     * name, domain and path, so each is expired host-only and for the host's parent domain at the
     * root path, which covers how sites and Cloudflare set them. The old app wrote the expiry
     * without a domain or path, so cf_clearance could never be cleared.
     */
    fun clear(url: HttpUrl) {
        val store = manager()
        val names = loadForRequest(url).map { it.name }.distinct()
        val parent = url.host.substringAfter('.', "")
        val root = "${url.scheme}://${url.host}/"
        for (name in names) {
            store.setCookie(root, "$name=; Max-Age=0; Path=/")
            store.setCookie(root, "$name=; Max-Age=0; Path=/; Domain=${url.host}")
            if (parent.contains('.')) store.setCookie(root, "$name=; Max-Age=0; Path=/; Domain=$parent")
        }
        store.flush()
    }
}
