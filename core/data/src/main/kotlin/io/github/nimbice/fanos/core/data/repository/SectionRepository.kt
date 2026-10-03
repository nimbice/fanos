package io.github.nimbice.fanos.core.data.repository

import io.github.nimbice.fanos.core.database.TransactionRunner
import io.github.nimbice.fanos.core.database.dao.SectionDao
import io.github.nimbice.fanos.core.database.entity.NovelSectionEntity
import io.github.nimbice.fanos.core.database.entity.SectionEntity
import io.github.nimbice.fanos.core.model.LibrarySection
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The library's sections, and which novels are in them. A novel can be in several; one in none is in the
 * default section, so deleting a section never leaves a novel nowhere.
 */
@Singleton
class SectionRepository @Inject constructor(private val sections: SectionDao, private val database: TransactionRunner) {
    /** The sections in the reader's order, the default one among them. */
    fun observeSections(): Flow<List<LibrarySection>> = sections.observeAll().map { rows -> rows.map { LibrarySection(it.id, it.name) } }

    suspend fun sections(): List<LibrarySection> = sections.all().map { LibrarySection(it.id, it.name) }

    /** The sections [novelId] is in: the default one while it's in no other. */
    fun observeSectionsOf(novelId: Long): Flow<Set<Long>> = sections.observeSectionsOf(novelId).map { it.toSet().ifEmpty { DEFAULT } }

    /** A new section, after the others; returns its id. */
    suspend fun create(name: String): Long =
        database.transaction {
            val position = (sections.all().maxOfOrNull { it.position } ?: -1) + 1
            sections.insert(SectionEntity(name = name.trim(), position = position))
        }

    suspend fun rename(id: Long, name: String) = sections.rename(id, name.trim())

    /** Deletes a section (never the default one); novels in no other section are then in the default one. */
    suspend fun delete(id: Long) {
        if (id != LibrarySection.DEFAULT_ID) sections.delete(id)
    }

    /** Keeps the order the reader dragged the sections into. */
    suspend fun reorder(ids: List<Long>) {
        database.transaction { ids.forEachIndexed { position, id -> sections.setPosition(id, position) } }
    }

    /** Puts [novelId] in [sectionId] or takes it out; its last section can't be taken away. */
    suspend fun setInSection(novelId: Long, sectionId: Long, inSection: Boolean) {
        database.transaction {
            val current = sections.sectionsOf(novelId).toSet().ifEmpty { DEFAULT }
            val wanted = if (inSection) current + sectionId else current - sectionId
            if (wanted.isEmpty() || wanted == current) return@transaction
            // Written out whole: once the novel is in another section too, the default one is no longer implied.
            (current - wanted).forEach { sections.removeNovel(novelId, it) }
            sections.addNovel(wanted.map { NovelSectionEntity(novelId, it) })
        }
    }

    /** Adds [novelId] to [sectionIds] as well as the sections it's in, as restoring and importing do. */
    suspend fun addToSections(novelId: Long, sectionIds: Set<Long>) {
        if (sectionIds.isEmpty()) return
        database.transaction {
            val current = sections.sectionsOf(novelId).toSet().ifEmpty { DEFAULT }
            sections.addNovel((current + sectionIds).map { NovelSectionEntity(novelId, it) })
        }
    }

    private companion object {
        val DEFAULT = setOf(LibrarySection.DEFAULT_ID)
    }
}
