package io.github.nimbice.fanos.core.data

import io.github.nimbice.fanos.source.api.ChallengeRequiredException
import io.github.nimbice.fanos.source.api.ParseException
import io.github.nimbice.fanos.source.api.SignInRequiredException
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ErrorMessagesTest {

    @Test
    fun `a site that wants the reader signed in says which, and its button signs in`() {
        val error = SignInRequiredException("https://www.patreon.com/login")

        assertEquals("Sign in to patreon.com to read this.", userMessage(error))
        assertEquals("https://www.patreon.com/login", browserUrl(error))
        assertEquals("Sign in", browserLabel(error))
    }

    @Test
    fun `a bot check opens the page in the browser, and a changed site has no page to open`() {
        val check = ChallengeRequiredException("https://novels.example.com/chapter-3")

        assertEquals("https://novels.example.com/chapter-3", browserUrl(check))
        assertEquals("Open in browser", browserLabel(check))
        assertNull(browserUrl(ParseException("no chapter list")))
    }
}
