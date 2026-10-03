package io.github.nimbice.fanos.core.data.source

import org.junit.Assert.assertEquals
import org.junit.Test

class SourceUrlsTest {

    @Test
    fun `urls on the source's site are keyed by path and query`() {
        assertEquals(
            "/fiction/21220/mother-of-learning",
            SourceUrls.key("https://www.royalroad.com", "https://www.royalroad.com/fiction/21220/mother-of-learning"),
        )
        assertEquals("/novel/search?keyword=magic&page=2", SourceUrls.key("https://novelfull.com", "https://novelfull.com/novel/search?keyword=magic&page=2"))
    }

    @Test
    fun `www and mobile subdomains and the scheme do not change the key`() {
        val key = "/supreme-magus.html"
        assertEquals(key, SourceUrls.key("https://novelfull.com", "http://www.novelfull.com/supreme-magus.html"))
        assertEquals(key, SourceUrls.key("https://www.novelfull.com", "https://m.novelfull.com/supreme-magus.html"))
    }

    @Test
    fun `urls on other sites are kept whole`() {
        val url = "https://translator.example.com/chapter-3/"
        assertEquals(url, SourceUrls.key("https://www.novelupdates.com", url))
    }

    @Test
    fun `a source that knows its chapters' own numbers keys chapters by them, others by address`() {
        val site = object : SourceInfoTest.Site() {
            override val baseUrl = "https://www.royalroad.com"

            override fun chapterKey(url: String) = Regex("""/chapter/(\d+)""").find(url)?.groupValues?.get(1)
        }
        val before = "https://www.royalroad.com/fiction/107917/sky-pride/chapter/2113501/chapter-1"
        val after = "https://www.royalroad.com/fiction/107917/sky-pride-vol-1/chapter/2113501/chapter-1-renamed"
        assertEquals("id:2113501", SourceUrls.chapterKey(site, before))
        assertEquals(SourceUrls.chapterKey(site, before), SourceUrls.chapterKey(site, after))
        // An address it has no number in is keyed as any other.
        assertEquals("/fiction/107917/sky-pride", SourceUrls.chapterKey(site, "https://www.royalroad.com/fiction/107917/sky-pride"))
        assertEquals("/fiction/1/chapter-1", SourceUrls.chapterKey(SourceInfoTest.Site(), "https://example.com/fiction/1/chapter-1"))
    }

    @Test
    fun `a source that knows its novels' own numbers keys novels by them`() {
        val site = object : SourceInfoTest.Site() {
            override val baseUrl = "https://www.royalroad.com"

            override fun novelKey(url: String) = Regex("""/fiction/(\d+)""").find(url)?.groupValues?.get(1)
        }
        assertEquals("id:107917", SourceUrls.novelKey(site, "https://www.royalroad.com/fiction/107917/sky-pride"))
        assertEquals("id:107917", SourceUrls.novelKey(site, "https://www.royalroad.com/fiction/107917/sky-pride-vol-1-stubbing-october-17-read-now"))
        assertEquals("/fiction/1/a-story", SourceUrls.novelKey(SourceInfoTest.Site(), "https://example.com/fiction/1/a-story"))
    }

    @Test
    fun `a bare domain is keyed as the root path`() {
        assertEquals("/", SourceUrls.key("https://www.royalroad.com", "https://www.royalroad.com"))
    }

    @Test
    fun `a url's site is its host, less www or m`() {
        assertEquals("novelupdates.com", SourceUrls.siteOf("https://www.novelupdates.com/series/lantern-keeper/"))
        assertEquals("novelfull.com", SourceUrls.siteOf("https://m.NovelFull.com"))
        assertEquals("translator.example.com", SourceUrls.siteOf("https://translator.example.com/chapter-3/"))
        assertEquals(null, SourceUrls.siteOf("not a url"))
    }
}
