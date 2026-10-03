package io.github.nimbice.fanos.core.data.repository

import io.github.nimbice.fanos.core.common.Dispatcher
import io.github.nimbice.fanos.core.common.ReaderDispatchers
import io.github.nimbice.fanos.core.model.Definition
import io.github.nimbice.fanos.core.model.Meaning
import io.github.nimbice.fanos.core.model.Sense
import io.github.nimbice.fanos.core.network.await
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.net.ConnectException
import java.net.UnknownHostException
import java.util.Locale
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import io.github.nimbice.fanos.core.common.AppIdentity

/**
 * English words' meanings: from the dictionary built into the app, and for words it hasn't got (when the reader allows
 * it), from the Free Dictionary API (dictionaryapi.dev), which asks for no key or account, or from Wiktionary (whose
 * words those are) when that hasn't the word either or doesn't answer within a few seconds. A word as written
 * ("hammered") is also looked up as its likely base forms ("hammer"). Only words looked up online are sent anywhere.
 */
@Singleton
class Dictionary @Inject constructor(
    private val client: OkHttpClient,
    private val json: Json,
    private val offline: OfflineDictionary,
    private val settings: SettingsRepository,
    private val identity: AppIdentity,
    @Dispatcher(ReaderDispatchers.IO) private val io: CoroutineDispatcher,
) {
    /** For dictionaryapi.dev, which is given [API_WAIT_MS] before Wiktionary is asked instead. */
    private val apiClient by lazy { client.newBuilder().callTimeout(API_WAIT_MS, TimeUnit.MILLISECONDS).build() }
    private val known = LinkedHashMap<String, Definition>()

    /** Until when dictionaryapi.dev is passed over, having not answered. */
    @Volatile
    private var apiRestsUntil = 0L

    /** [word]'s entry, or a phrase's ("rib cage"); null when no dictionary asked has it or a base form of it. */
    suspend fun define(word: String): Definition? {
        val asked = word.replace('’', '\'').lowercase(Locale.ROOT).split(SPACES).filter { it.isNotEmpty() }.joinToString(" ")
        if (asked.isEmpty()) return null
        synchronized(known) { known[asked]?.let { return it } }
        val phrase = ' ' in asked
        val forms =
            if (phrase) {
                withContext(io) { phraseForms(asked) { first -> offline.entry(first)?.formOf.orEmpty().map { (lemma, _) -> lemma } } }
            } else {
                listOf(asked) + stemsOf(asked)
            }
        val found =
            withContext(io) { if (phrase) fromOfflinePhrase(forms) else fromOffline(asked) }
                ?: if (!settings.appSettings.first().lookUpOnline) {
                    null
                } else {
                    fromApi(forms) ?: forms.firstNotNullOfOrNull { fromWiktionary(it) }
                }
                ?: return null
        synchronized(known) {
            known[asked] = found
            if (known.size > KEPT) known.remove(known.keys.first())
        }
        return found
    }

    /** The built-in dictionary's entry for [asked], with that of the word it's a form of: irregularly ("went") first. */
    private fun fromOffline(asked: String): Definition? {
        val own = offline.entry(asked)
        val bases = own?.formOf.orEmpty().map { (lemma, part) -> lemma to setOf(part) } + stemsOf(asked).map { it to partsFor(asked) }
        val base = bases.firstNotNullOfOrNull { (lemma, parts) -> offline.entry(lemma)?.takeIf { it.meanings.isNotEmpty() }?.let { it to parts } }
        return offlineDefinition(asked, own, base?.first, base?.second, Locale.getDefault().country == "US")
    }

    /** The built-in dictionary's entry for the first of a phrase's [forms] it has: "gave up" as "give up". */
    private fun fromOfflinePhrase(forms: List<String>): Definition? {
        val us = Locale.getDefault().country == "US"
        return forms.firstNotNullOfOrNull { form -> offline.entry(form)?.let { offlineDefinition(form, it, null, null, us) } }
    }

    /** dictionaryapi.dev's entry for the first of [forms] it has; null when it has none, or doesn't answer in time. */
    private suspend fun fromApi(forms: List<String>): Definition? {
        if (System.currentTimeMillis() < apiRestsUntil) return null
        return try {
            withTimeout(API_WAIT_MS) { forms.firstNotNullOfOrNull { form -> get(API.toHttpUrl().newBuilder().addPathSegment(form).build(), apiClient)?.let { parse(it, json) } } }
        } catch (e: TimeoutCancellationException) {
            apiRestsUntil = System.currentTimeMillis() + API_REST_MS
            null
        } catch (e: IOException) {
            // Offline, Wiktionary can't be reached either, and that's what's said.
            if (e !is UnknownHostException && e !is ConnectException) apiRestsUntil = System.currentTimeMillis() + API_REST_MS
            null
        }
    }

    /** Wiktionary's English senses of [word], those only saying "simple past of move" given as move's own; null when it has none. */
    private suspend fun fromWiktionary(word: String): Definition? {
        val parts = wiktionary(word) ?: return null
        val base = baseOf(parts)?.takeIf { it != word }?.let { b -> wiktionary(b)?.let { b to it } }
        return wiktionaryDefinition(word, parts, base)
    }

    private suspend fun wiktionary(word: String): List<WikiPart>? =
        get(WIKTIONARY.toHttpUrl().newBuilder().addPathSegment(word.replace(' ', '_')).build(), client)?.let { parseWiktionary(it, json) }

    /** The body at [url]; null when there's nothing there. */
    private suspend fun get(url: HttpUrl, via: OkHttpClient): String? {
        val request = Request.Builder().url(url).header("User-Agent", identity.userAgent).build()
        return withContext(io) {
            via.newCall(request).await().use { response ->
                when {
                    response.code == 404 -> null
                    !response.isSuccessful -> throw IOException("The dictionary answered with HTTP ${response.code}")
                    else -> response.body.string()
                }
            }
        }
    }

    companion object {
        internal const val API_NAME = "Wiktionary via dictionaryapi.dev"
        internal const val WIKTIONARY_NAME = "Wiktionary"

        /** What Wiktionary's words are shared under; dictionaryapi.dev says which with each entry, this failing that. */
        internal const val WIKTIONARY_LICENCE = "CC BY-SA 4.0"
        private const val API_LICENCE = "CC BY-SA 3.0"
        private const val WIKTIONARY_PAGE = "https://en.wiktionary.org/wiki/"

        /** The entries in dictionaryapi.dev's [body] as one definition: their meanings gathered by part of speech. */
        internal fun parse(body: String, json: Json): Definition? {
            val entries = runCatching { json.decodeFromString(ENTRIES, body) }.getOrNull() ?: return null
            val first = entries.firstOrNull() ?: return null
            val parts = LinkedHashMap<String, MutableList<Sense>>()
            for (entry in entries) {
                for (meaning in entry.meanings) {
                    val senses = parts.getOrPut(meaning.partOfSpeech.ifBlank { "other" }) { mutableListOf() }
                    meaning.definitions.forEach { if (it.definition.isNotBlank() && senses.size < SENSES) senses += Sense(it.definition.trim(), it.example?.trim()?.ifEmpty { null }) }
                }
            }
            if (parts.values.all { it.isEmpty() }) return null
            val phonetic = first.phonetic?.takeIf { it.isNotBlank() } ?: entries.flatMap { it.phonetics }.firstNotNullOfOrNull { it.text?.takeIf(String::isNotBlank) }
            return Definition(
                first.word,
                phonetic,
                parts.filterValues { it.isNotEmpty() }.entries.take(PARTS).map { (part, senses) -> Meaning(part, senses) },
                API_NAME,
                first.license?.name?.takeIf { it.isNotBlank() } ?: API_LICENCE,
                first.sourceUrls.firstOrNull { it.startsWith("https://") },
            )
        }

        /** Wiktionary's English senses in its [body], by part of speech; null when it has none. */
        internal fun parseWiktionary(body: String, json: Json): List<WikiPart>? {
            val english = runCatching { json.parseToJsonElement(body).jsonObject["en"] }.getOrNull() ?: return null
            val entries = runCatching { json.decodeFromJsonElement(WIKI_ENTRIES, english) }.getOrNull() ?: return null
            return entries
                .map { entry ->
                    val senses =
                        entry.definitions.mapNotNull { sense ->
                            val text = textOf(sense.definition)
                            val example = (sense.parsedExamples.firstOrNull()?.example ?: sense.examples.firstOrNull())?.let(::textOf)?.ifEmpty { null }
                            if (text.isEmpty()) null else WikiSense(text, example, FORM_OF.find(sense.definition)?.groupValues?.get(1)?.let(::textOf))
                        }
                    WikiPart(entry.partOfSpeech.lowercase(Locale.ROOT).ifBlank { "other" }, senses)
                }.filter { it.senses.isNotEmpty() }
                .ifEmpty { null }
        }

        /** The word the first sense in [parts] that's only a form of another ("plural of story") is a form of. */
        internal fun baseOf(parts: List<WikiPart>): String? = parts.firstNotNullOfOrNull { part -> part.senses.firstNotNullOfOrNull { it.formOf } }

        /**
         * [word]'s definition from its Wiktionary [parts]. Senses only pointing to [base] give way to the base's own senses
         * as that part of speech ("verb · hammer"); a word that's nothing but forms of others ("stories") is the base's entry.
         */
        internal fun wiktionaryDefinition(word: String, parts: List<WikiPart>, base: Pair<String, List<WikiPart>>?): Definition {
            if (base != null && parts.all { part -> part.senses.all { it.formOf != null } }) return wiktionaryDefinition(base.first, base.second, null)
            val gathered = LinkedHashMap<String, MutableList<Sense>>()
            fun add(label: String, senses: List<WikiSense>) {
                val kept = gathered.getOrPut(label) { mutableListOf() }
                senses.forEach { if (kept.size < SENSES) kept += Sense(it.text, it.example) }
            }
            for (part in parts) {
                val (pointing, own) = part.senses.partition { base != null && it.formOf == base.first }
                add(part.partOfSpeech, own)
                if (base == null || pointing.isEmpty()) continue
                val baseSenses = base.second.filter { it.partOfSpeech == part.partOfSpeech }.flatMap { it.senses }.filter { it.formOf == null }
                if (baseSenses.isEmpty()) add(part.partOfSpeech, pointing) else add("${part.partOfSpeech} · ${base.first}", baseSenses)
            }
            return Definition(
                word,
                null,
                gathered.filterValues { it.isNotEmpty() }.entries.take(PARTS).map { (label, senses) -> Meaning(label, senses) },
                WIKTIONARY_NAME,
                WIKTIONARY_LICENCE,
                WIKTIONARY_PAGE + word.replace(' ', '_'),
            )
        }

        /** A phrase as written ("gave up") and as its likely base forms: by its first word ("give up"), then by its last ("rib cages": "rib cage"). */
        internal fun phraseForms(phrase: String, irregular: (String) -> List<String>): List<String> {
            val words = phrase.split(' ')
            val rest = words.drop(1).joinToString(" ")
            val head = words.dropLast(1).joinToString(" ")
            return (listOf(phrase) + (irregular(words.first()) + stemsOf(words.first())).map { "$it $rest" } + stemsOf(words.last()).map { "$head $it" }).distinct()
        }

        /** The parts of speech a word as written ("hammered") may be a form of its base as: null for any. */
        internal fun partsFor(word: String): Set<String>? =
            when {
                word.endsWith("ing") || word.endsWith("ed") -> setOf("verb")
                word.endsWith("'s") -> null
                word.endsWith("s") -> setOf("noun", "verb")
                word.endsWith("er") || word.endsWith("est") -> setOf("adjective", "adverb")
                word.endsWith("ly") -> setOf("adjective")
                else -> null
            }

        /**
         * [asked]'s definition from the built-in dictionary: its [own] entry, and its [base]'s senses as the [parts] of
         * speech it may be a form of ("verb · move" for "moved"), verbs before its own senses; or only the base's, when
         * it has none of its own. How words are said is given the [us] way or the British.
         */
        internal fun offlineDefinition(asked: String, own: OfflineEntry?, base: OfflineEntry?, parts: Set<String>?, us: Boolean): Definition? {
            fun said(entry: OfflineEntry) = (if (us) entry.us ?: entry.gb else entry.gb ?: entry.us)?.let { "/$it/" }
            val mine = own?.meanings.orEmpty()
            val theirs = base?.meanings.orEmpty().filter { parts == null || it.partOfSpeech in parts }
            return when {
                base == null -> if (own == null || mine.isEmpty()) null else Definition(asked, said(own), mine.take(PARTS), OfflineDictionary.NAME, OfflineDictionary.LICENCE, OfflineDictionary.URL)
                own == null || mine.isEmpty() -> Definition(base.word, said(base), (theirs + (base.meanings - theirs.toSet())).take(PARTS), OfflineDictionary.NAME, OfflineDictionary.LICENCE, OfflineDictionary.URL)
                theirs.isEmpty() -> Definition(asked, said(own), mine.take(PARTS), OfflineDictionary.NAME, OfflineDictionary.LICENCE, OfflineDictionary.URL)
                else -> {
                    val pointed = theirs.map { Meaning("${it.partOfSpeech} · ${base.word}", it.senses) }
                    val meanings = if (theirs.all { it.partOfSpeech == "verb" }) pointed + mine else mine + pointed
                    Definition(asked, said(own), meanings.take(PARTS), OfflineDictionary.NAME, OfflineDictionary.LICENCE, OfflineDictionary.URL)
                }
            }
        }

        /** [html]'s text: without tags, its entities read, its spaces collapsed. */
        internal fun textOf(html: String): String =
            ENTITY.replace(TAG.replace(html, "")) { m ->
                val name = m.groupValues[1]
                val code =
                    when {
                        name.startsWith("#x", ignoreCase = true) -> name.drop(2).toIntOrNull(16)
                        name.startsWith("#") -> name.drop(1).toIntOrNull()
                        else -> null
                    }
                if (code != null) {
                    if (Character.isValidCodePoint(code)) String(Character.toChars(code)) else m.value
                } else {
                    NAMED[name] ?: m.value
                }
            }.replace(SPACES, " ").trim()

        /** The likely base forms of an English [word] as written, most likely first: "hammered" to "hammer", "stories" to "story". */
        internal fun stemsOf(word: String): List<String> {
            val stems = LinkedHashSet<String>()
            fun add(stem: String) {
                if (stem.length >= 2 && stem != word) stems += stem
            }
            fun undouble(stem: String) = if (stem.length >= 3 && stem[stem.length - 1] == stem[stem.length - 2] && stem.last() !in "aeiouls") stem.dropLast(1) else stem
            val w = word.removeSuffix("'s").removeSuffix("’s")
            add(w)
            when {
                w.endsWith("ies") -> add(w.dropLast(3) + "y")
                w.endsWith("ied") -> add(w.dropLast(3) + "y")
                w.endsWith("ing") -> {
                    val base = w.dropLast(3)
                    add(base)
                    add(undouble(base))
                    add(base + "e")
                }
                // "hoped" is "hope" before it's "hop"; "hammered" isn't "hammere", which no dictionary has.
                w.endsWith("ed") -> {
                    val base = w.dropLast(2)
                    add(w.dropLast(1))
                    add(base)
                    add(undouble(base))
                }
                w.endsWith("est") -> {
                    add(w.dropLast(3))
                    add(undouble(w.dropLast(3)))
                    add(w.dropLast(2))
                }
                w.endsWith("er") -> {
                    add(w.dropLast(2))
                    add(undouble(w.dropLast(2)))
                    add(w.dropLast(1))
                }
                w.endsWith("ly") -> add(w.dropLast(2))
                w.endsWith("es") -> {
                    add(w.dropLast(2))
                    add(w.dropLast(1))
                }
                w.endsWith("s") && !w.endsWith("ss") -> add(w.dropLast(1))
            }
            return stems.toList()
        }

        private val ENTRIES = ListSerializer(Entry.serializer())
        private val WIKI_ENTRIES = ListSerializer(WikiEntry.serializer())
        private val FORM_OF = Regex("""form-of-definition-link.*?title="([^"]+)"""", RegexOption.DOT_MATCHES_ALL)
        private val TAG = Regex("<[^>]*>")
        private val ENTITY = Regex("&(#?[A-Za-z0-9]+);")
        private val SPACES = Regex("\\s+")
        private val NAMED = mapOf("amp" to "&", "lt" to "<", "gt" to ">", "quot" to "\"", "apos" to "'", "nbsp" to " ", "ndash" to "–", "mdash" to "—", "hellip" to "…")

        @Serializable
        private class Entry(
            val word: String,
            val phonetic: String? = null,
            val phonetics: List<Phonetic> = emptyList(),
            val meanings: List<EntryMeaning> = emptyList(),
            val license: EntryLicence? = null,
            val sourceUrls: List<String> = emptyList(),
        )

        @Serializable
        private class EntryLicence(val name: String = "", val url: String? = null)

        @Serializable
        private class Phonetic(val text: String? = null)

        @Serializable
        private class EntryMeaning(val partOfSpeech: String = "", val definitions: List<EntrySense> = emptyList())

        @Serializable
        private class EntrySense(val definition: String = "", val example: String? = null)

        @Serializable
        private class WikiEntry(val partOfSpeech: String = "", val definitions: List<WikiEntrySense> = emptyList())

        @Serializable
        private class WikiEntrySense(val definition: String = "", val parsedExamples: List<WikiExample> = emptyList(), val examples: List<String> = emptyList())

        @Serializable
        private class WikiExample(val example: String = "")

        private const val API = "https://api.dictionaryapi.dev/api/v2/entries/en"
        private const val WIKTIONARY = "https://en.wiktionary.org/api/rest_v1/page/definition"
        private const val API_WAIT_MS = 5_000L
        private const val API_REST_MS = 10 * 60_000L
        private const val SENSES = 3
        private const val PARTS = 3
        private const val KEPT = 50
    }
}

/** A sense in Wiktionary: its text, an example, and the word it's only a form of ("simple past of move": move), if so. */
internal class WikiSense(val text: String, val example: String?, val formOf: String?)

/** Wiktionary's senses of a word as one part of speech. */
internal class WikiPart(val partOfSpeech: String, val senses: List<WikiSense>)
