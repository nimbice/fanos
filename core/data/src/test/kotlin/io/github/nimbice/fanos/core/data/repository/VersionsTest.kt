package io.github.nimbice.fanos.core.data.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VersionsTest {

    private fun upload(chapter: Int, version: String?, day: Long) = Upload("Chapter $chapter: A Name", version, day * 86_400_000)

    @Test
    fun `versions with the same chapters, the last uploaded`() {
        val uploads = (1..3).flatMap { listOf(upload(it, "v1", it.toLong()), upload(it, "v2", 100L + it)) } + upload(4, null, 200)
        assertEquals("v2", preferredVersion(uploads))
    }

    @Test
    fun `uneven versions, the one reaching the highest-numbered chapter, however old`() {
        val uploads = (1..5).map { upload(it, "v1", it.toLong()) } + (1..3).map { upload(it, "v2", 100L + it) }
        assertEquals("v1", preferredVersion(uploads))
    }

    @Test
    fun `reaching the same chapter, the fuller version first, then the newer`() {
        val uploads = (1..5).map { upload(it, "v1", it.toLong()) } + (4..5).map { upload(it, "v2", 100L + it) }
        assertEquals("v1", preferredVersion(uploads))
    }

    @Test
    fun `no versions, no default`() {
        assertNull(preferredVersion((1..3).map { upload(it, null, it.toLong()) }))
    }
}
