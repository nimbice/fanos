package io.github.nimbice.fanos.core.data.repository

import io.github.nimbice.fanos.core.common.Clock
import io.github.nimbice.fanos.core.common.Dispatcher
import io.github.nimbice.fanos.core.common.ReaderDispatchers
import io.github.nimbice.fanos.core.common.suspendRunCatching
import io.github.nimbice.fanos.core.content.Block
import io.github.nimbice.fanos.core.content.ChapterContent
import io.github.nimbice.fanos.core.content.ChapterExtractor
import io.github.nimbice.fanos.core.data.source.SourceRegistry
import io.github.nimbice.fanos.core.data.source.SourceUrls
import io.github.nimbice.fanos.core.database.dao.ChapterDao
import io.github.nimbice.fanos.core.database.dao.ContentDao
import io.github.nimbice.fanos.core.database.dao.NovelDao
import io.github.nimbice.fanos.core.database.entity.ChapterContentEntity
import io.github.nimbice.fanos.core.database.entity.ChapterEntity
import io.github.nimbice.fanos.core.network.BrowserFetcher
import io.github.nimbice.fanos.source.api.ChallengeRequiredException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import io.github.nimbice.fanos.core.model.isBookUrl
import io.github.nimbice.fanos.core.content.wordCount

/**
 * Chapter content for the reader and for listening. A chapter is fetched and extracted once and
 * stored; content from an older extractor is extracted again when the page can be fetched, and is
 * still shown when it cannot.
 */
@Singleton
class ContentRepository @Inject constructor(
    private val chapters: ChapterDao,
    private val novels: NovelDao,
    private val contents: ContentDao,
    private val sources: SourceRegistry,
    private val browser: BrowserFetcher,
    private val extractor: ChapterExtractor,
    private val json: Json,
    private val clock: Clock,
    @Dispatcher(ReaderDispatchers.Default) private val cpu: CoroutineDispatcher,
) {
    suspend fun content(chapterId: Long, refresh: Boolean = false): ChapterContent {
        val chapter = chapters.get(chapterId) ?: throw NoSuchElementException("No chapter $chapterId")
        val stored = contents.get(chapterId)
        // A book's chapter is what the book had, short or not; there's nowhere to fetch it again from.
        if (isBookUrl(chapter.url)) return stored?.let { decode(it.json) } ?: throw BookTextMissingException()
        // A chapter stored without text (before blank pages were refused) is fetched again.
        if (!refresh && stored != null && stored.extractorVersion == ChapterContent.EXTRACTOR_VERSION) {
            decode(stored.json).takeUnless { it.isBlank() }?.let { return it }
        }
        val fetched = suspendRunCatching { fetch(chapter, saved = stored?.saved ?: false) }
        fetched.getOrNull()?.let { return it }
        if (stored != null && !refresh) decode(stored.json).takeUnless { it.isBlank() }?.let { return it }
        throw checkNotNull(fetched.exceptionOrNull())
    }

    /**
     * The words in the novel's [unread] chapters: counted where their text is on this device, and for the rest, as many
     * as the chapters on this device have on average. Null when none of the novel's chapters are, to go by.
     */
    suspend fun wordsToRead(novelId: Long, unread: List<Long>): Int? {
        if (unread.isEmpty()) return 0
        val stored = contents.storedIdsOf(novelId)
        if (stored.isEmpty()) return null
        val waiting = unread.toHashSet()
        // Those to read first, then others for the average: enough to go by without reading every chapter there is.
        val counting = (stored.filter { it in waiting }.take(MAX_COUNTED) + stored.filter { it !in waiting }.take(SAMPLE)).distinct()
        val words = HashMap<Long, Int>()
        for (id in counting) {
            val text = contents.get(id)?.json ?: continue
            words[id] = decode(text).wordCount()
        }
        if (words.isEmpty()) return null
        val average = words.values.average()
        return unread.sumOf { words[it] ?: average.toInt() }
    }

    /** Whether the chapter can be read without a connection. */
    suspend fun isStored(chapterId: Long): Boolean = contents.get(chapterId) != null

    /**
     * Keeps the chapter for reading offline: the stored copy when it is current, else one fetched now.
     * [SavedChapter.fetched] says whether the site was asked, which is what a download paces itself by.
     */
    suspend fun save(chapterId: Long): SavedChapter {
        val chapter = chapters.get(chapterId) ?: throw NoSuchElementException("No chapter $chapterId")
        val stored = contents.get(chapterId)
        if (isBookUrl(chapter.url)) return SavedChapter(chapter.url, stored?.let { decode(it.json) } ?: throw BookTextMissingException(), fetched = false)
        if (stored != null && stored.extractorVersion == ChapterContent.EXTRACTOR_VERSION) {
            val content = decode(stored.json)
            if (!content.isBlank()) {
                if (!stored.saved) contents.markSaved(chapterId)
                return SavedChapter(chapter.url, content, fetched = false)
            }
        }
        return SavedChapter(chapter.url, fetch(chapter, saved = true), fetched = true)
    }

    private suspend fun fetch(chapter: ChapterEntity, saved: Boolean): ChapterContent {
        val novel = checkNotNull(novels.get(chapter.novelId))
        val page = sources[novel.sourceId].chapterPage(chapter.url)
        val url = page.document.location()
        var content = withContext(cpu) { extractor.extract(page.document, page.content, chapter.title) }
        // A page whose text a script writes, or that a script sends on to the chapter ("Redirecting…"), has none for a
        // plain fetch: the browser runs its scripts. Only where the source left finding the text to the app; one that
        // knows where the text is would find it no better.
        if (content.isBlank() && page.content == null) {
            val rendered =
                try {
                    browser.render(url)
                } catch (check: ChallengeRequiredException) {
                    throw check
                } catch (_: IOException) {
                    null
                }
            // A script that sends the page to another site is sending it to ads (or worse), never to the chapter.
            if (rendered != null && SourceUrls.siteOf(rendered.url) == SourceUrls.siteOf(url)) {
                content = withContext(cpu) { extractor.extract(rendered.document(), null, chapter.title) }
            }
        }
        if (content.isBlank()) throw NoChapterTextException(url)
        val encoded = withContext(cpu) { json.encodeToString(ChapterContent.serializer(), content) }
        // Saved stays saved: a download may have finished while this fetch was on its way.
        val keep = saved || contents.get(chapter.id)?.saved == true
        contents.upsert(ChapterContentEntity(chapter.id, content.extractorVersion, encoded, clock.now(), keep))
        return content
    }

    private suspend fun decode(text: String): ChapterContent = withContext(cpu) { json.decodeFromString(ChapterContent.serializer(), text) }
}

/** A book's chapter whose text isn't kept any more. */
class BookTextMissingException : IOException("This chapter's text is gone. Open the book file again to read it.")

/** The chapter's page, at [url], had no chapter text to show: the page as fetched, and as the browser shows it. */
class NoChapterTextException(val url: String) : IOException("No chapter text at $url")

/** A page's text and pictures: no picture, and less text than a chapter has, is no chapter. */
private fun ChapterContent.isBlank(): Boolean = leaves.none { it is Block.Image } && plainText().count(Char::isLetterOrDigit) < MIN_TEXT

private const val MIN_TEXT = 100

/** Chapters to count the words of, at most, for the words left in a novel: the rest go by the average. */
private const val MAX_COUNTED = 300
private const val SAMPLE = 40

/** A chapter kept for reading offline; [url] is its page, which its images are fetched as coming from. */
class SavedChapter(val url: String, val content: ChapterContent, val fetched: Boolean)
