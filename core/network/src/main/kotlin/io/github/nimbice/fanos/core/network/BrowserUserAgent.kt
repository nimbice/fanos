package io.github.nimbice.fanos.core.network

import android.content.Context
import android.webkit.WebSettings
import android.webkit.WebView

/**
 * The user agent the app's browser views and its own requests all go by: the device WebView's, less the marks that
 * say it's a view inside an app ("; wv", and the "Version/4.0" a WebView puts before Chrome's). Signing in with Google
 * turns a view in an app away by them ("disallowed_useragent"), so a site that asks for Google's sign-in (Patreon, for
 * an account made with Google) could never be signed in to. And a bot check's clearance holds only for the user agent
 * that earned it, so the views and the requests go by the same one.
 */
object BrowserUserAgent {

    @Volatile private var cached: String? = null

    fun of(context: Context): String =
        cached ?: (runCatching { WebSettings.getDefaultUserAgent(context) }.getOrNull()?.let(::plain) ?: FALLBACK).also { cached = it }

    /** Gives [view] the user agent. */
    fun applyTo(view: WebView) {
        view.settings.userAgentString = of(view.context)
    }

    /** [userAgent] without the marks of a view inside an app. */
    internal fun plain(userAgent: String): String = userAgent.replace(WEBVIEW_MARK, "").replace(VERSION_MARK, "")

    private val WEBVIEW_MARK = Regex(""";\s*wv\b""")
    private val VERSION_MARK = Regex("""Version/[\d.]+\s+(?=Chrome/)""")

    private const val FALLBACK =
        "Mozilla/5.0 (Linux; Android 16) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/140.0.0.0 Mobile Safari/537.36"
}
