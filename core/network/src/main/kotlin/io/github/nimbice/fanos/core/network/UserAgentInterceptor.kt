package io.github.nimbice.fanos.core.network

import android.content.Context
import android.webkit.WebSettings
import okhttp3.Interceptor
import okhttp3.Response

/**
 * Sends the device WebView's own user agent. Cloudflare ties a clearance cookie to the browser that
 * earned it, so OkHttp has to present the same user agent as the WebView that passed the check.
 */
class UserAgentInterceptor(context: Context) : Interceptor {

    private val userAgent by lazy {
        runCatching { WebSettings.getDefaultUserAgent(context) }.getOrNull() ?: FALLBACK
    }

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        if (request.header("User-Agent") != null) return chain.proceed(request)
        return chain.proceed(
            request.newBuilder()
                .header("User-Agent", userAgent)
                .header("Accept-Language", "en-US,en;q=0.9")
                .build(),
        )
    }

    private companion object {
        const val FALLBACK =
            "Mozilla/5.0 (Linux; Android 16) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/140.0.0.0 Mobile Safari/537.36"
    }
}
