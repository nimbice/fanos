package io.github.nimbice.fanos.core.data.repository

import kotlinx.serialization.json.Json
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DictionaryTest {

    @Test
    fun `a word as written is looked up again as its likely base forms`() {
        assertTrue("hammer" in Dictionary.stemsOf("hammered"))
        assertEquals("hope", Dictionary.stemsOf("hoped").first())
        assertEquals(listOf("story"), Dictionary.stemsOf("stories"))
        assertTrue("run" in Dictionary.stemsOf("running"))
        assertTrue("stop" in Dictionary.stemsOf("stopped"))
        assertTrue("call" in Dictionary.stemsOf("called"))
        assertEquals(listOf("zeke"), Dictionary.stemsOf("zeke's"))
    }

    @Test
    fun `the dictionary's entries read as one definition, senses gathered by part of speech`() {
        val body =
            """[{"word":"hammer","phonetic":"/ˈhæmə/","meanings":[
                {"partOfSpeech":"noun","definitions":[{"definition":"A tool with a heavy head."}]},
                {"partOfSpeech":"verb","definitions":[{"definition":"To strike repeatedly.","example":"her heart hammered"}]}]},
               {"word":"hammer","phonetics":[{"text":"/ˈhæmɚ/"}],"meanings":[
                {"partOfSpeech":"noun","definitions":[{"definition":"A part of a gun."},{"definition":"A bone in the ear."},{"definition":"A fourth one."}]}]}]"""
        val found = Dictionary.parse(body, Json { ignoreUnknownKeys = true })!!
        assertEquals("hammer", found.word)
        assertEquals("/ˈhæmə/", found.phonetic)
        assertEquals(listOf("noun", "verb"), found.meanings.map { it.partOfSpeech })
        assertEquals(3, found.meanings[0].senses.size)
        assertEquals("her heart hammered", found.meanings[1].senses[0].example)
        assertNull(Dictionary.parse("""{"title":"No Definitions Found"}""", Json { ignoreUnknownKeys = true }))
    }

    @Test
    fun `wiktionary's english senses read as text, knowing the word a form points to`() {
        val json = Json { ignoreUnknownKeys = true }
        val body =
            """{"en":[
                {"partOfSpeech":"Adjective","language":"English","definitions":[
                  {"definition":""},
                  {"definition":"<span class=\"usage-label-sense\"></span> very <a rel=\"mw:WikiLink\" href=\"/wiki/drunk\" title=\"drunk\">drunk</a>; loud &amp; merry&#33;","parsedExamples":[{"example":"He was <b>hammered</b>."}]}]},
                {"partOfSpeech":"Verb","language":"English","definitions":[
                  {"definition":"<span class=\"form-of-definition use-with-mention\">simple past of <span class=\"form-of-definition-link\"><i class=\"Latn mention\" lang=\"en\"><a rel=\"mw:WikiLink\" href=\"/wiki/hammer#English\" title=\"hammer\">hammer</a></i></span></span>"}]}],
               "de":[{"partOfSpeech":"Noun","language":"German","definitions":[{"definition":"Hammer"}]}]}"""
        val parts = Dictionary.parseWiktionary(body, json)!!
        assertEquals(listOf("adjective", "verb"), parts.map { it.partOfSpeech })
        assertEquals("very drunk; loud & merry!", parts[0].senses.single().text)
        assertEquals("He was hammered.", parts[0].senses.single().example)
        assertEquals("simple past of hammer", parts[1].senses.single().text)
        assertEquals("hammer", Dictionary.baseOf(parts))
        assertNull(Dictionary.parseWiktionary("""{"de":[{"partOfSpeech":"Noun","definitions":[{"definition":"Hammer"}]}]}""", json))
    }

    @Test
    fun `senses only pointing to another word give way to that word's own`() {
        val hammer =
            listOf(
                WikiPart("noun", listOf(WikiSense("A tool.", null, null))),
                WikiPart("verb", listOf(WikiSense("To strike repeatedly.", "Tony hammered on the door.", null))),
            )
        val hammered =
            listOf(
                WikiPart("adjective", listOf(WikiSense("Very drunk.", null, null))),
                WikiPart("verb", listOf(WikiSense("simple past of hammer", null, "hammer"))),
            )
        val mixed = Dictionary.wiktionaryDefinition("hammered", hammered, "hammer" to hammer)
        assertEquals("hammered", mixed.word)
        assertEquals(listOf("adjective", "verb · hammer"), mixed.meanings.map { it.partOfSpeech })
        assertEquals("Tony hammered on the door.", mixed.meanings[1].senses.single().example)

        val stories = listOf(WikiPart("noun", listOf(WikiSense("plural of story", null, "story"), WikiSense("plural of storey", null, "storey"))))
        val story = listOf(WikiPart("noun", listOf(WikiSense("An account of events.", null, null))))
        val only = Dictionary.wiktionaryDefinition("stories", stories, "story" to story)
        assertEquals("story", only.word)
        assertEquals("An account of events.", only.meanings.single().senses.single().definition)
        assertEquals("Wiktionary", only.source)
    }
}
