package io.github.nimbice.fanos.core.data

import io.github.nimbice.fanos.core.database.entity.NovelEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NovelRekeyTest {

    // Royal Road's addresses: the story's name in them, which changes; its number, which doesn't.
    private fun stored(id: Long, name: String, inLibrary: Boolean = false, lastReadAt: Long? = null): NovelEntity {
        val url = "https://www.royalroad.com/fiction/107917/$name"
        return NovelEntity(id = id, sourceId = "royalroad", urlKey = url.removePrefix("https://www.royalroad.com"), url = url, title = "Sky Pride", inLibrary = inLibrary, lastReadAt = lastReadAt, createdAt = 0)
    }

    private fun plan(stored: List<NovelEntity>) = NovelRekey.plan(stored, stored.associate { it.id to "id:" + Regex("""/fiction/(\d+)""").find(it.url)!!.groupValues[1] })

    @Test
    fun `novels already under their keys need nothing`() {
        val novel = stored(1, "sky-pride").copy(urlKey = "id:107917")
        assertTrue(plan(listOf(novel)).isEmpty)
    }

    @Test
    fun `a novel only changes its key`() {
        assertEquals(NovelRekey.Plan(keys = mapOf(1L to "id:107917")), plan(listOf(stored(1, "sky-pride", inLibrary = true))))
    }

    @Test
    fun `the library's copy takes the key, and a stray opened after the rename goes`() {
        val library = stored(1, "sky-pride", inLibrary = true, lastReadAt = 5)
        val stray = stored(2, "sky-pride-vol-1-stubbing-october-17-read-now")
        assertEquals(NovelRekey.Plan(keys = mapOf(1L to "id:107917"), drops = listOf(2L)), plan(listOf(stray, library)))
    }

    @Test
    fun `a second copy with something of the reader's in it stays as it was`() {
        val library = stored(1, "sky-pride", inLibrary = true)
        val read = stored(2, "sky-pride-vol-1", lastReadAt = 9)
        assertEquals(NovelRekey.Plan(keys = mapOf(1L to "id:107917")), plan(listOf(library, read)))
    }

    @Test
    fun `without one in the library, the read one is kept, then the oldest`() {
        assertEquals(NovelRekey.Plan(keys = mapOf(2L to "id:107917"), drops = listOf(1L)), plan(listOf(stored(1, "a"), stored(2, "b", lastReadAt = 3))))
        assertEquals(NovelRekey.Plan(keys = mapOf(1L to "id:107917"), drops = listOf(2L)), plan(listOf(stored(1, "a"), stored(2, "b"))))
    }
}
