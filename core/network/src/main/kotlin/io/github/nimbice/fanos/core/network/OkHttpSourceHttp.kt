package io.github.nimbice.fanos.core.network

import io.github.nimbice.fanos.source.api.ChallengeRequiredException
import io.github.nimbice.fanos.source.api.HttpResponse
import io.github.nimbice.fanos.source.api.HttpStatusException
import io.github.nimbice.fanos.source.api.SourceHttp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import javax.net.ssl.SSLHandshakeException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * [SourceHttp] over the app's OkHttp client, with typed errors for bot checks and error statuses.
 * When a site's bot check turns OkHttp away, the request goes through [browser] instead, and so do
 * the site's later requests: some checks judge OkHttp itself and let a browser straight through.
 */
class OkHttpSourceHttp(private val client: OkHttpClient, private val browser: BrowserFetcher? = null) : SourceHttp {

    // Sites that turned OkHttp away but let the browser through, for the rest of the session.
    private val browserHosts: MutableSet<String> = ConcurrentHashMap.newKeySet()

    override suspend fun get(url: String, headers: Map<String, String>): HttpResponse =
        execute(Request.Builder().url(url).withHeaders(headers).get().build())

    override suspend fun post(url: String, form: Map<String, String>, headers: Map<String, String>): HttpResponse {
        val body = FormBody.Builder().apply { form.forEach { (name, value) -> add(name, value) } }.build()
        return execute(Request.Builder().url(url).withHeaders(headers).post(body).build())
    }

    override suspend fun postJson(url: String, json: String, headers: Map<String, String>): HttpResponse =
        execute(Request.Builder().url(url).withHeaders(headers).post(json.toRequestBody(JSON)).build())

    private suspend fun execute(request: Request, hops: Int = 0): HttpResponse {
        val host = request.url.host
        if (browser != null && host in browserHosts) return viaBrowser(browser, request, hops)
        // Reading the body is network I/O as well, so all of it happens off the main thread, whoever calls.
        val result =
            withContext(Dispatchers.IO) {
                try {
                    client.newCall(request).await().use { response ->
                        val url = response.request.url.toString()
                        when {
                            isChallenge(response) -> Result.failure(ChallengeRequiredException(url))
                            response.code >= 400 -> throw HttpStatusException(url, response.code)
                            else -> Result.success(HttpResponse(url, response.code, response.body.string(), response.headers.toMultimap()))
                        }
                    }
                } catch (untrusted: SSLHandshakeException) {
                    Result.failure(untrusted)
                }
            }
        val refused = result.exceptionOrNull() ?: return result.getOrThrow()
        // The site's bot check turned OkHttp away: ask through the browser. If the check stops the
        // browser as well, that throws ChallengeRequiredException too, for the reader to pass. So for a
        // certificate the phone doesn't trust yet but the browser, with Chrome's own, newer roots, may
        // (Let's Encrypt's 2026 roots): the browser refuses one Chrome doesn't trust either.
        if (refused !is ChallengeRequiredException && refused !is SSLHandshakeException) throw refused
        return viaBrowser(browser ?: throw refused, request, hops).also { browserHosts += host }
    }

    /** [request] through the browser. A page that forwards to another site is fetched there, as any page is. */
    private suspend fun viaBrowser(browser: BrowserFetcher, request: Request, hops: Int): HttpResponse =
        try {
            browser.fetch(request)
        } catch (forward: BrowserFetcher.Redirected) {
            if (hops >= MAX_HOPS) throw IOException("${request.url} forwards too many times")
            execute(request.newBuilder().url(forward.url).get().build(), hops + 1)
        }

    private fun Request.Builder.withHeaders(headers: Map<String, String>) = apply { headers.forEach { (name, value) -> header(name, value) } }

    private companion object {
        val JSON = "application/json; charset=utf-8".toMediaType()
        const val MAX_HOPS = 5
    }
}

/** Runs the call on OkHttp's dispatcher, cancelling it when the coroutine is cancelled. */
suspend fun Call.await(): Response =
    suspendCancellableCoroutine { continuation ->
        continuation.invokeOnCancellation { cancel() }
        enqueue(
            object : Callback {
                override fun onResponse(call: Call, response: Response) {
                    continuation.resume(response) { _, value, _ -> value.close() }
                }

                override fun onFailure(call: Call, e: IOException) {
                    continuation.resumeWithException(e)
                }
            },
        )
    }
