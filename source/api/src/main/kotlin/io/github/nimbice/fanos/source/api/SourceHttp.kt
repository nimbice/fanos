package io.github.nimbice.fanos.source.api

import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.io.IOException

/**
 * How sources reach the web. The app's implementation shares cookies with its browser views, keeps
 * to per-site request limits, and turns bot checks and error statuses into the exceptions below.
 */
interface SourceHttp {
    suspend fun get(url: String, headers: Map<String, String> = emptyMap()): HttpResponse

    suspend fun post(url: String, form: Map<String, String>, headers: Map<String, String> = emptyMap()): HttpResponse

    suspend fun postJson(url: String, json: String, headers: Map<String, String> = emptyMap()): HttpResponse
}

class HttpResponse(
    /** Where the response came from after redirects. */
    val url: String,
    val code: Int,
    val body: String,
    val headers: Map<String, List<String>> = emptyMap(),
) {
    fun document(): Document = Jsoup.parse(body, url)

    fun header(name: String): String? = headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value?.firstOrNull()
}

open class SourceException(message: String, cause: Throwable? = null) : IOException(message, cause)

/** The site answered with an error status. */
class HttpStatusException(val url: String, val code: Int) : SourceException("HTTP $code from $url")

/** The site put a bot check (such as Cloudflare's) in front of the page; it has to be passed once in a browser. */
class ChallengeRequiredException(val url: String) : SourceException("$url asks for a browser check")

/**
 * The chapter is for the author's paying subscribers, such as Royal Road's early access. Sites
 * usually open such chapters to everyone later.
 */
class LockedChapterException(val url: String) : SourceException("$url is for subscribers only")

/**
 * The site shows this only to readers signed in to it, such as a Patreon post for the creator's
 * members. The reader signs in once in the app's browser, at [url], and the app's requests carry
 * the sign-in from then on. Since API level 2.
 */
class SignInRequiredException(val url: String) : SourceException("$url asks you to sign in")

/** The page did not have the expected shape, usually because the site changed. */
class ParseException(message: String) : SourceException(message)
