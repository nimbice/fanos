package io.github.nimbice.fanos.core.data.source

import io.github.nimbice.fanos.source.api.ChapterInfo
import io.github.nimbice.fanos.source.api.ChapterPage
import io.github.nimbice.fanos.source.api.NovelDetails
import io.github.nimbice.fanos.source.api.NovelSource
import io.github.nimbice.fanos.source.api.NovelsPage
import io.github.nimbice.fanos.source.api.SearchBy
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SourceInfoTest {

    internal open class Site : NovelSource {
        override val id = "site"
        override val name = "Site"
        override val lang = "en"
        override val baseUrl = "https://example.com"

        override suspend fun search(query: String, page: Int) = NovelsPage(emptyList(), hasNextPage = false)

        override suspend fun novelDetails(novelUrl: String): NovelDetails = error("unused")

        override suspend fun chapterList(novelUrl: String): List<ChapterInfo> = error("unused")

        override suspend fun chapterPage(chapterUrl: String): ChapterPage = error("unused")
    }

    @Test
    fun `a source searches titles unless it says it searches creators`() {
        assertFalse(Site().sourceInfo().searchesCreators)
        val creators = object : Site() {
            override val searchBy = SearchBy.Creators
        }
        assertTrue(creators.sourceInfo().searchesCreators)
    }
}
