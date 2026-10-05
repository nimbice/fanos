package io.github.nimbice.fanos.core.network

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.os.SystemClock
import android.net.http.SslError
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.RenderProcessGoneDetail
import android.webkit.SslErrorHandler
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.nimbice.fanos.source.api.ChallengeRequiredException
import io.github.nimbice.fanos.source.api.HttpResponse
import io.github.nimbice.fanos.source.api.HttpStatusException
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import okio.Buffer
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.SocketTimeoutException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Makes requests through a hidden WebView, for sites whose bot protection judges the client itself.
 * Some Cloudflare setups challenge OkHttp on every request (its TLS handshake is not a browser's)
 * yet let Chrome, and so the app's WebView, straight through: no challenge runs, so no clearance
 * cookie is left for OkHttp to reuse. A fetch() from a page of the site goes out through Chrome's
 * network stack with the WebView's cookies, and gets through.
 *
 * Some pages the check lets a browser open but not fetch() (Scribble Hub's chapters): those are
 * opened as pages and read from the finished page, and so are the site's later pages. So is a page
 * that fetch() may not read because it forwards to another site, such as a Novel Updates release link;
 * the browser stops at the forward, and the page it leads to is fetched as any other ([Redirected]).
 *
 * A site whose check the browser can't pass by itself (one that wants a box ticked) is turned back at
 * once when asked again, until its cookies change, as they do once the reader passes the check.
 *
 * One WebView serves every request, one at a time, from a blank page given the site's origin. It is
 * dropped after a minute and a half unused.
 */
@Singleton
class BrowserFetcher @Inject constructor(@ApplicationContext private val context: Context) {

    private val lock = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val pending = ConcurrentHashMap<Int, CancellableContinuation<HttpResponse>>()
    private val ids = AtomicInteger()
    private val pageLoads = Channel<String>(Channel.CONFLATED)
    private val pageStarts = Channel<String>(Channel.CONFLATED)
    private var view: WebView? = null
    private var origin: String? = null
    private var pageStatus = 200
    private var pageError: String? = null
    private var pageHost: String? = null
    private var release: Job? = null

    // While a page is read, the site it's on; where it went instead, when it forwarded to another site.
    private var readingSite: String? = null
    private val forwards = Channel<String>(Channel.CONFLATED)

    // Sites whose check turns fetch() away from pages a browser may open: their GETs are opened as pages.
    private val pageHosts: MutableSet<String> = ConcurrentHashMap.newKeySet()

    // How fetch() answers come back. Only the blank page of a site has it: a site's own pages, read or rendered, must not
    // be able to answer for a request.
    private val bridge = Bridge()

    // Sites whose check the browser couldn't pass, with when and the cookies they had then.
    private val stuck = ConcurrentHashMap<String, Stuck>()

    private class Stuck(val at: Long, val cookies: String?)

    /**
     * Runs [request] from a page of its site. Throws [ChallengeRequiredException] when the check stops the browser
     * too, and [Redirected] when the page forwards to another site.
     */
    suspend fun fetch(request: Request): HttpResponse {
        val host = request.url.host
        val url = request.url.toString()
        stuck[host]?.let { then ->
            if (SystemClock.elapsedRealtime() - then.at < STUCK_MS && cookies(url) == then.cookies) throw ChallengeRequiredException(url)
        }
        return try {
            lock.withLock {
                withContext(Dispatchers.Main.immediate) {
                    release?.cancel()
                    try {
                        val webView = view ?: create().also { view = it }
                        val response = request(webView, request)
                        if (response.isCheck()) throw ChallengeRequiredException(response.url)
                        if (response.code >= 400) throw HttpStatusException(response.url, response.code)
                        response
                    } finally {
                        release =
                            scope.launch {
                                delay(IDLE_MS)
                                lock.withLock { drop() }
                            }
                    }
                }
            }
        } catch (check: ChallengeRequiredException) {
            stuck[host] = Stuck(SystemClock.elapsedRealtime(), cookies(url))
            throw check
        }
    }

    private fun cookies(url: String): String? = CookieManager.getInstance().getCookie(url)

    /**
     * Opens [url] as a page and returns it as its scripts leave it: for a page whose text a script writes, or that a
     * script sends on to another ("Redirecting…"), which a plain fetch gets nothing of. Waits out a bot check on the way.
     */
    suspend fun render(url: String): HttpResponse =
        lock.withLock {
            withContext(Dispatchers.Main.immediate) {
                release?.cancel()
                try {
                    val webView = view ?: create().also { view = it }
                    val response = read(webView, url, emptyMap(), url.toHttpUrl().root(), rendering = true)
                    if (response.code >= 400) throw HttpStatusException(response.url, response.code)
                    response
                } finally {
                    release =
                        scope.launch {
                            delay(IDLE_MS)
                            lock.withLock { drop() }
                        }
                }
            }
        }

    private suspend fun request(webView: WebView, request: Request): HttpResponse {
        val host = request.url.host
        val site = request.url.root()
        if (request.method == "GET" && host in pageHosts) return read(webView, request.url.toString(), request.pageHeaders(), site)
        if (origin != site) open(webView, site)
        val response =
            try {
                send(webView, request)
            } catch (failed: FetchFailedException) {
                if (request.method != "GET") throw failed
                return read(webView, request.url.toString(), request.pageHeaders(), site)
            }
        if (!response.isCheck()) return response
        if (request.method == "GET") return read(webView, request.url.toString(), request.pageHeaders(), site).also { pageHosts += host }
        // Let the check's script run on the site's front page, then ask again.
        read(webView, site, emptyMap(), site)
        return send(webView, request)
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun create(): WebView =
        WebView(context).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true // bot checks keep state there
            settings.blockNetworkImage = true
            settings.loadsImagesAutomatically = false
            webViewClient =
                object : WebViewClient() {
                    override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                        pageStatus = 200
                        pageError = null
                        pageHost = url.toHttpUrlOrNull()?.host
                        pageStarts.trySend(url)
                    }

                    // A certificate even the browser doesn't trust: the page is refused (the default), and a refused
                    // page mustn't pass for an empty one.
                    override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) {
                        if (error.url.toHttpUrlOrNull()?.host == pageHost) pageError = "the site's certificate isn't trusted"
                        handler.cancel()
                    }

                    override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, errorResponse: WebResourceResponse) {
                        if (request.isForMainFrame) pageStatus = errorResponse.statusCode
                    }

                    // The page didn't load at all (no connection, no such site): the browser shows its own
                    // error page, which must not pass for the page asked for.
                    override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                        if (request.isForMainFrame) pageError = error.description?.toString()?.ifEmpty { null } ?: "error ${error.errorCode}"
                    }

                    override fun onPageFinished(view: WebView, url: String) {
                        pageLoads.trySend(url)
                    }

                    // A page being read that forwards to another site isn't followed there: whoever asked
                    // fetches that page as any other.
                    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                        val site = readingSite ?: return false
                        val target = request.url
                        if (!request.isForMainFrame || !request.isRedirect || target.scheme !in setOf("http", "https")) return false
                        if (siteOf(target.host.orEmpty()) == site) return false
                        forwards.trySend(target.toString())
                        return true
                    }

                    // A crashed renderer would otherwise take the app down: drop the view and fail what
                    // was waiting on it; the next request makes a new one.
                    override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                        if (this@BrowserFetcher.view === view) drop()
                        return true
                    }
                }
        }

    private fun drop() {
        view?.destroy()
        view = null
        origin = null
        val waiting = pending.values.toList()
        pending.clear()
        waiting.forEach { it.resumeWithException(IOException("The browser used for this site stopped")) }
    }

    /** Loads a blank page that has [site] as its origin, so fetch() calls to the site are same-origin. */
    private suspend fun open(webView: WebView, site: String) {
        while (pageLoads.tryReceive().isSuccess) Unit
        webView.addJavascriptInterface(bridge, BRIDGE)
        webView.loadDataWithBaseURL(site, "<!DOCTYPE html><title></title>", "text/html", "utf-8", null)
        within(PAGE_MS) { pageLoads.receive() }
        origin = site
    }

    /**
     * Opens [url] as a page, waits out a bot check's "Just a moment" page on the way, and returns the
     * finished page's HTML, or the text itself of one the browser shows as text, such as an API's JSON;
     * then goes back to a blank page of [site], so the page's scripts stop. A page that forwards to
     * another site stops there, with [Redirected], unless it's [rendering]: then it's followed wherever it
     * goes, scripts' redirects included, and read where it settles.
     */
    private suspend fun read(webView: WebView, url: String, headers: Map<String, String>, site: String, rendering: Boolean = false): HttpResponse {
        while (pageLoads.tryReceive().isSuccess) Unit
        while (forwards.tryReceive().isSuccess) Unit
        readingSite = if (rendering) null else url.toHttpUrlOrNull()?.host?.let(::siteOf)
        var forward: String? = null
        val finished =
            try {
                // Off the blank page, and without the bridge: whatever asks next opens the blank page again.
                webView.removeJavascriptInterface(BRIDGE)
                origin = null
                webView.loadUrl(url, headers)
                withTimeoutOrNull(CHECK_MS) {
                    do {
                        forward =
                            select<String?> {
                                forwards.onReceive { it }
                                pageLoads.onReceive { null }
                            }
                    } while (forward == null && onCheck(webView))
                    true
                } ?: false
            } finally {
                readingSite = null
            }
        forward?.let { target ->
            open(webView, site)
            throw Redirected(target)
        }
        if (rendering && finished) settle(webView)
        val finalUrl = webView.url ?: url
        if (!finished) {
            // Still on the check after all this time: the reader has to pass it. Otherwise the page is just slow.
            if (onCheck(webView)) throw ChallengeRequiredException(finalUrl)
            open(webView, site)
            throw SocketTimeoutException("$finalUrl took too long to load")
        }
        pageError?.let { error ->
            open(webView, site)
            throw IOException("$finalUrl didn't load: $error")
        }
        val status = pageStatus
        val body = evaluate(webView, BODY)
        open(webView, site)
        return HttpResponse(finalUrl, status, body)
    }

    private fun onCheck(webView: WebView) = CHECK_TITLES.any { webView.title.orEmpty().startsWith(it, ignoreCase = true) }

    /** Follows the page while a script sends it on to others, which happens soon after it loads or not at all. */
    private suspend fun settle(webView: WebView) {
        while (pageStarts.tryReceive().isSuccess) Unit
        repeat(MAX_SETTLES) {
            withTimeoutOrNull(SETTLE_MS) { pageStarts.receive() } ?: return
            withTimeoutOrNull(CHECK_MS) {
                do {
                    pageLoads.receive()
                } while (onCheck(webView))
            } ?: return
        }
    }

    private suspend fun evaluate(webView: WebView, expression: String): String =
        within(REQUEST_MS) {
            suspendCancellableCoroutine { continuation ->
                // The result comes back as JSON: a string literal here.
                webView.evaluateJavascript(expression) { result -> continuation.resume(JSONArray("[$result]").optString(0)) }
            }
        }

    private suspend fun send(webView: WebView, request: Request): HttpResponse {
        val id = ids.incrementAndGet()
        val script = script(id, request)
        return within(REQUEST_MS) {
            suspendCancellableCoroutine { continuation ->
                pending[id] = continuation
                continuation.invokeOnCancellation { pending.remove(id) }
                webView.evaluateJavascript(script, null)
            }
        }
    }

    /**
     * [block], given [millis] to finish. Running out of time is a [SocketTimeoutException], as for any request that takes
     * too long: withTimeout's own cancellation would stop whoever asked, a whole library update with it, not just this.
     */
    private suspend fun <T : Any> within(millis: Long, block: suspend CoroutineScope.() -> T): T =
        withTimeoutOrNull(millis, block) ?: throw SocketTimeoutException("The browser waited ${millis / 1_000} s for an answer")

    private fun script(id: Int, request: Request): String {
        val headers = JSONObject()
        val init = JSONObject().put("method", request.method).put("credentials", "include").put("cache", "no-cache")
        for ((name, value) in request.headers) {
            when {
                name.equals("Referer", ignoreCase = true) -> init.put("referrer", value)
                name.lowercase() !in BROWSER_HEADERS -> headers.put(name, value)
            }
        }
        request.body?.let { body ->
            body.contentType()?.let { headers.put("Content-Type", it.toString()) }
            init.put("body", Buffer().also(body::writeTo).readUtf8())
        }
        init.put("headers", headers)
        return """
            (async () => {
              try {
                const r = await fetch(${JSONObject.quote(request.url.toString())}, $init);
                $BRIDGE.done($id, r.status, r.url, await r.text());
              } catch (e) {
                $BRIDGE.fail($id, String(e));
              }
            })();
        """.trimIndent()
    }

    private inner class Bridge {
        @JavascriptInterface
        fun done(id: Int, status: Int, url: String, body: String) {
            pending.remove(id)?.resume(HttpResponse(url, status, body))
        }

        @JavascriptInterface
        fun fail(id: Int, message: String) {
            pending.remove(id)?.resumeWithException(FetchFailedException(message))
        }
    }

    /** fetch() gave no answer: the network failed, or the answer was one the page may not read. */
    private class FetchFailedException(message: String) : IOException(message)

    /** The page asked for forwards to another site's page, at [url], which is to be fetched as any other. */
    class Redirected(val url: String) : IOException("Forwarded to $url")

    private companion object {
        const val BRIDGE = "FanosFetch"
        const val PAGE_MS = 15_000L
        const val CHECK_MS = 15_000L
        const val REQUEST_MS = 60_000L
        const val IDLE_MS = 90_000L

        /** How long a rendered page has, once loaded, to be sent on by a script; and how many times it may be. */
        const val SETTLE_MS = 2_500L
        const val MAX_SETTLES = 3

        /** How long a site whose check the browser couldn't pass is turned back at once, its cookies unchanged. */
        const val STUCK_MS = 3 * 60_000L

        /** Headers the browser sets itself; fetch() and page loads ignore or refuse them. */
        val BROWSER_HEADERS = setOf("user-agent", "cookie", "host", "connection", "content-length", "accept-encoding", "origin")

        val CHECK_TITLES = listOf("Just a moment", "Attention Required")

        // What the page was sent as: its HTML, or for JSON and plain text, which the browser wraps in a
        // page of its own (<html><body><pre>…), the text inside.
        const val BODY =
            "document.contentType.indexOf('html') >= 0 ? document.documentElement.outerHTML : " +
                "(document.querySelector('body > pre') || document.body || document.documentElement).textContent"

        fun HttpUrl.root(): String = newBuilder().encodedPath("/").query(null).fragment(null).build().toString()

        fun siteOf(host: String): String = host.lowercase().removePrefix("www.").removePrefix("m.")

        fun Request.pageHeaders(): Map<String, String> = headers.filter { (name, _) -> name.lowercase() !in BROWSER_HEADERS }.toMap()

        fun HttpResponse.isCheck(): Boolean =
            (code == 403 || code == 503) && CHALLENGE_MARKERS.any { body.contains(it, ignoreCase = true) }
    }
}
