package io.github.nimbice.fanos.core.database

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import androidx.room.immediateTransaction
import androidx.room.useWriterConnection
import io.github.nimbice.fanos.core.database.dao.BookmarkDao
import io.github.nimbice.fanos.core.database.entity.BookmarkEntity
import io.github.nimbice.fanos.core.database.dao.StatsDao
import io.github.nimbice.fanos.core.database.dao.ChapterDao
import io.github.nimbice.fanos.core.database.dao.ContentDao
import io.github.nimbice.fanos.core.database.dao.DownloadDao
import io.github.nimbice.fanos.core.database.dao.NovelDao
import io.github.nimbice.fanos.core.database.dao.ProgressDao
import io.github.nimbice.fanos.core.database.dao.ReadingListDao
import io.github.nimbice.fanos.core.database.dao.ReplacementDao
import io.github.nimbice.fanos.core.database.dao.SectionDao
import io.github.nimbice.fanos.core.database.entity.ChapterContentEntity
import io.github.nimbice.fanos.core.database.entity.ReadingTimeEntity
import io.github.nimbice.fanos.core.database.entity.ChapterEntity
import io.github.nimbice.fanos.core.database.entity.ChapterProgressEntity
import io.github.nimbice.fanos.core.database.entity.DownloadEntity
import io.github.nimbice.fanos.core.database.entity.NovelEntity
import io.github.nimbice.fanos.core.database.entity.NovelSectionEntity
import io.github.nimbice.fanos.core.database.entity.ReadingListEntryEntity
import io.github.nimbice.fanos.core.database.entity.ReplacementEntity
import io.github.nimbice.fanos.core.database.entity.SectionEntity
import io.github.nimbice.fanos.core.model.NovelStatus
import io.github.nimbice.fanos.core.database.dao.HighlightDao
import io.github.nimbice.fanos.core.database.entity.HighlightEntity

@Database(
    entities = [
        NovelEntity::class,
        ChapterEntity::class,
        ChapterProgressEntity::class,
        ChapterContentEntity::class,
        DownloadEntity::class,
        SectionEntity::class,
        NovelSectionEntity::class,
        ReadingListEntryEntity::class,
        BookmarkEntity::class,
        ReadingTimeEntity::class,
        ReplacementEntity::class,
        HighlightEntity::class,
    ],
    version = AppDatabase.VERSION,
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun novelDao(): NovelDao

    abstract fun chapterDao(): ChapterDao

    abstract fun progressDao(): ProgressDao

    abstract fun contentDao(): ContentDao

    abstract fun downloadDao(): DownloadDao

    abstract fun sectionDao(): SectionDao

    abstract fun readingListDao(): ReadingListDao

    abstract fun bookmarkDao(): BookmarkDao

    abstract fun statsDao(): StatsDao

    abstract fun replacementDao(): ReplacementDao

    abstract fun highlightDao(): HighlightDao

    companion object {
        const val VERSION = 17
        const val NAME = "library.db"
    }
}

/** Runs a block in one write transaction; DAO calls made inside it join the transaction. */
interface TransactionRunner {
    suspend fun <T> transaction(block: suspend () -> T): T
}

internal class RoomTransactionRunner(private val database: AppDatabase) : TransactionRunner {
    override suspend fun <T> transaction(block: suspend () -> T): T =
        database.useWriterConnection { connection -> connection.immediateTransaction { block() } }
}

internal class Converters {
    @TypeConverter
    fun fromGenres(genres: List<String>): String = genres.joinToString(SEPARATOR)

    @TypeConverter
    fun toGenres(value: String): List<String> = if (value.isEmpty()) emptyList() else value.split(SEPARATOR)

    @TypeConverter
    fun fromStatus(status: NovelStatus): String = status.name

    @TypeConverter
    fun toStatus(value: String): NovelStatus = NovelStatus.entries.firstOrNull { it.name == value } ?: NovelStatus.Unknown

    private companion object {
        const val SEPARATOR = "\u001F"
    }
}
