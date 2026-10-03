package io.github.nimbice.fanos.core.data.repository

import io.github.nimbice.fanos.core.model.Sense
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OfflineDictionaryTest {
    /** sample.xml as tools/dictionary/make_dictionary.py makes it, in blocks small enough that each holds a word or two. */
    private val file =
        javaClass.getResourceAsStream("/dictionary/sample.dict")!!.use { it.readBytes() }.let { bytes ->
            DictionaryFile { position, length -> bytes.copyOfRange(position.toInt(), position.toInt() + length) }
        }

    @Test
    fun `words are read from their blocks, with how they're said and the words they're forms of`() {
        val go = file.entry("go")!!
        assertEquals("ɡəʊ", go.gb)
        assertEquals("ɡoʊ", go.us)
        assertEquals(Sense("Move away from a place into another direction.", "Go away before I start to cry"), go.meanings.single().senses.single())
        val went = file.entry("went")!!
        assertTrue(went.meanings.isEmpty())
        assertEquals(listOf("go" to "verb"), went.formOf)
        val hammer = file.entry("hammer")!!
        assertEquals(listOf("noun", "verb"), hammer.meanings.map { it.partOfSpeech })
        assertEquals("hammer the metal flat", hammer.meanings[1].senses[0].example)
        assertEquals("adjective", file.entry("hammered")!!.meanings.single().partOfSpeech)
        // The word in lower case before the name: "march" the walk, then "March" the month.
        assertEquals(listOf("The act of marching.", "The month following February."), file.entry("march")!!.meanings.single().senses.map { it.definition })
        // Phrases written in lower case are kept; names aren't.
        assertEquals("The bony enclosure of the chest.", file.entry("rib cage")!!.meanings.single().senses.single().definition)
        assertNull(file.entry("abraham lincoln"))
        assertNull(file.entry("aardvark"))
        assertNull(file.entry("zebra"))
    }

    @Test
    fun `a form of another word shows that word's senses, verbs first`() {
        val hammer = file.entry("hammer")
        val hammered = Dictionary.offlineDefinition("hammered", file.entry("hammered"), hammer, Dictionary.partsFor("hammered"), us = false)!!
        assertEquals("hammered", hammered.word)
        assertEquals(listOf("verb · hammer", "adjective"), hammered.meanings.map { it.partOfSpeech })

        val went = Dictionary.offlineDefinition("went", file.entry("went"), file.entry("go"), setOf("verb"), us = true)!!
        assertEquals("go", went.word)
        assertEquals("/ɡoʊ/", went.phonetic)
        assertEquals("Open English WordNet", went.source)

        val hammers = Dictionary.offlineDefinition("hammers", null, hammer, Dictionary.partsFor("hammers"), us = false)!!
        assertEquals("hammer", hammers.word)
        assertEquals("/ˈhæ.mə(ɹ)/", hammers.phonetic)
        assertNull(Dictionary.offlineDefinition("zebra", null, null, null, us = false))
        assertNull(Dictionary.partsFor("zeke's"))
    }

    @Test
    fun `a phrase is found as written or by its words' base forms`() {
        val irregular = { word: String -> file.entry(word)?.formOf.orEmpty().map { it.first } }
        val forms = Dictionary.phraseForms("gave up", irregular)
        assertEquals("give up", forms.first { file.entry(it) != null })
        assertTrue("rib cage" in Dictionary.phraseForms("rib cages", irregular))
    }
}
