package io.github.nimbice.fanos.core.data

import io.github.nimbice.fanos.core.data.repository.NoChapterTextException
import io.github.nimbice.fanos.core.data.source.UnknownSourceException
import io.github.nimbice.fanos.source.api.ChallengeRequiredException
import io.github.nimbice.fanos.source.api.HttpStatusException
import io.github.nimbice.fanos.source.api.LockedChapterException
import io.github.nimbice.fanos.source.api.ParseException
import io.github.nimbice.fanos.source.api.SignInRequiredException
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.URI
import java.net.UnknownHostException
import javax.net.ssl.SSLException

/** A short explanation of [error] for the screen, saying what the user can do about it where possible. */
fun userMessage(error: Throwable): String =
    when (error) {
        is ChallengeRequiredException -> "The site wants a browser check first."
        is SignInRequiredException -> "Sign in to ${site(error.url)} to read this."
        is LockedChapterException -> "This chapter is for the author's paying supporters for now."
        is HttpStatusException ->
            when (error.code) {
                404, 410 -> "The site no longer has this page."
                401, 403 -> "The site refused the request (HTTP ${error.code})."
                429 -> "The site is limiting requests. Try again in a minute."
                in 500..599 -> "The site is having trouble (HTTP ${error.code}). Try again later."
                else -> "The site answered with HTTP ${error.code}."
            }
        is UnknownHostException, is ConnectException -> "Can't reach the site. Check your connection."
        is SocketTimeoutException, is InterruptedIOException -> "The site took too long to answer."
        is SSLException -> "Couldn't make a secure connection to the site."
        is ParseException -> "The site's pages have changed; this source needs an update."
        is UnknownSourceException -> "The extension for this site isn't installed."
        is NoChapterTextException -> "Fanos couldn't find this chapter's text on ${site(error.url)}."
        else -> error.message ?: error.javaClass.simpleName
    }

/**
 * The page to open in the browser for [error], otherwise null: a bot check to pass there, a site's
 * sign-in page, a chapter only the author's subscribers can read (a subscriber can sign in there), or a
 * chapter page Fanos found no text on, to read there.
 */
fun browserUrl(error: Throwable): String? =
    when (error) {
        is ChallengeRequiredException -> error.url
        is SignInRequiredException -> error.url
        is LockedChapterException -> error.url
        is NoChapterTextException -> error.url
        else -> null
    }

/**
 * What went wrong in the source's own words, for a report, when the message alone can't say: a site
 * whose pages have changed. Null otherwise.
 */
fun errorDetail(error: Throwable): String? = (error as? ParseException)?.message

/** What the button that opens [browserUrl] says. */
fun browserLabel(error: Throwable): String = if (error is SignInRequiredException) "Sign in" else "Open in browser"

private fun site(url: String): String = runCatching { URI(url).host?.removePrefix("www.") }.getOrNull() ?: "the site"
