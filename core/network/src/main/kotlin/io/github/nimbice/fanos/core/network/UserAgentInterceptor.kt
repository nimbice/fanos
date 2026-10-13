package io.github.nimbice.fanos.core.network

import android.content.Context
import okhttp3.Interceptor
import okhttp3.Response

/**
 * Sends the user agent the app's WebViews go by ([BrowserUserAgent]). Cloudflare ties a clearance cookie to the
 * browser that earned it, so OkHttp has to present the same user agent as the WebView that passed the check.
 */
class UserAgentInterceptor(context: Context) : Interceptor {

    private val userAgent by lazy { BrowserUserAgent.of(context) }

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
}
