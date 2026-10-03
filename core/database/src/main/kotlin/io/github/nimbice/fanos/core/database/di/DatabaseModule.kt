package io.github.nimbice.fanos.core.database.di

import android.content.Context
import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import io.github.nimbice.fanos.core.database.AppDatabase
import io.github.nimbice.fanos.core.database.DefaultSection
import io.github.nimbice.fanos.core.database.MIGRATIONS
import io.github.nimbice.fanos.core.database.RoomTransactionRunner
import io.github.nimbice.fanos.core.database.TransactionRunner
import io.github.nimbice.fanos.core.database.dao.BookmarkDao
import io.github.nimbice.fanos.core.database.dao.StatsDao
import io.github.nimbice.fanos.core.database.dao.ChapterDao
import io.github.nimbice.fanos.core.database.dao.ContentDao
import io.github.nimbice.fanos.core.database.dao.DownloadDao
import io.github.nimbice.fanos.core.database.dao.NovelDao
import io.github.nimbice.fanos.core.database.dao.ProgressDao
import io.github.nimbice.fanos.core.database.dao.ReadingListDao
import io.github.nimbice.fanos.core.database.dao.ReplacementDao
import io.github.nimbice.fanos.core.database.dao.SectionDao
import kotlinx.coroutines.Dispatchers
import javax.inject.Singleton
import io.github.nimbice.fanos.core.database.dao.HighlightDao

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    // The bundled SQLite driver gives every device the same, current SQLite, and lets the
    // database be tested on the JVM.
    @Provides
    @Singleton
    fun database(@ApplicationContext context: Context): AppDatabase =
        Room.databaseBuilder(context, AppDatabase::class.java, AppDatabase.NAME)
            .setDriver(BundledSQLiteDriver())
            .setQueryCoroutineContext(Dispatchers.IO)
            .addMigrations(*MIGRATIONS)
            .addCallback(DefaultSection)
            .build()

    @Provides
    fun transactionRunner(database: AppDatabase): TransactionRunner = RoomTransactionRunner(database)

    @Provides
    fun novelDao(database: AppDatabase): NovelDao = database.novelDao()

    @Provides
    fun chapterDao(database: AppDatabase): ChapterDao = database.chapterDao()

    @Provides
    fun progressDao(database: AppDatabase): ProgressDao = database.progressDao()

    @Provides
    fun contentDao(database: AppDatabase): ContentDao = database.contentDao()

    @Provides
    fun downloadDao(database: AppDatabase): DownloadDao = database.downloadDao()

    @Provides
    fun sectionDao(database: AppDatabase): SectionDao = database.sectionDao()

    @Provides
    fun readingListDao(database: AppDatabase): ReadingListDao = database.readingListDao()

    @Provides
    fun bookmarkDao(database: AppDatabase): BookmarkDao = database.bookmarkDao()

    @Provides
    fun statsDao(database: AppDatabase): StatsDao = database.statsDao()

    @Provides
    fun replacementDao(database: AppDatabase): ReplacementDao = database.replacementDao()

    @Provides
    fun highlightDao(database: AppDatabase): HighlightDao = database.highlightDao()
}
