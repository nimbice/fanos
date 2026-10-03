package io.github.nimbice.fanos.core.data.repository

import io.github.nimbice.fanos.core.common.Clock
import io.github.nimbice.fanos.core.content.TextRule
import io.github.nimbice.fanos.core.database.dao.ReplacementDao
import io.github.nimbice.fanos.core.database.entity.ReplacementEntity
import io.github.nimbice.fanos.core.model.Replacement
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/** Words the reader replaced in novels' text: a novel's own, then those for every novel. */
@Singleton
class ReplacementRepository @Inject constructor(private val replacements: ReplacementDao, private val clock: Clock) {

    fun observe(novelId: Long): Flow<List<Replacement>> = replacements.observeFor(novelId).map { rows -> rows.map { it.toModel() } }

    /** The rules a novel's text is shown and read aloud with, in the order they apply. */
    suspend fun rulesFor(novelId: Long): List<TextRule> = replacements.forNovel(novelId).map { TextRule(it.find, it.replace, it.wholeWord, it.matchCase) }

    suspend fun add(novelId: Long, find: String, replace: String, everywhere: Boolean, wholeWord: Boolean, matchCase: Boolean) {
        if (find.isBlank()) return
        replacements.insert(ReplacementEntity(novelId = if (everywhere) null else novelId, find = find.trim(), replace = replace, wholeWord = wholeWord, matchCase = matchCase, createdAt = clock.now()))
    }

    suspend fun remove(id: Long) = replacements.delete(id)

    private fun ReplacementEntity.toModel() = Replacement(id, find, replace, novelId == null, wholeWord, matchCase)
}

/** The rules [replacements] stand for, in order. */
fun List<Replacement>.rules(): List<TextRule> = map { TextRule(it.find, it.replace, it.wholeWord, it.matchCase) }
