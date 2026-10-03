package io.github.nimbice.fanos.core.data.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import io.github.nimbice.fanos.core.common.ApplicationScope
import io.github.nimbice.fanos.core.common.Clock
import io.github.nimbice.fanos.core.common.Dispatcher
import io.github.nimbice.fanos.core.common.ReaderDispatchers
import io.github.nimbice.fanos.core.content.ChapterExtractor
import io.github.nimbice.fanos.core.content.DefaultChapterExtractor
import io.github.nimbice.fanos.core.data.download.ChapterSaver
import io.github.nimbice.fanos.core.data.download.ChapterSaving
import io.github.nimbice.fanos.core.data.extension.ExtensionUpdates
import io.github.nimbice.fanos.core.updater.BackgroundCheck
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.serialization.json.Json
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DataModule {

    @Provides
    @Singleton
    fun json(): Json = Json { ignoreUnknownKeys = true }

    @Provides
    fun clock(): Clock = Clock.System

    /** Extension updates are looked for along with the app's own. */
    @Provides
    @IntoSet
    fun extensionUpdateCheck(updates: ExtensionUpdates): BackgroundCheck = updates

    @Provides
    fun chapterSaving(saver: ChapterSaver): ChapterSaving = saver

    @Provides
    @Singleton
    fun chapterExtractor(): ChapterExtractor = DefaultChapterExtractor()

    @Provides
    @Dispatcher(ReaderDispatchers.IO)
    fun ioDispatcher(): CoroutineDispatcher = Dispatchers.IO

    @Provides
    @Dispatcher(ReaderDispatchers.Default)
    fun defaultDispatcher(): CoroutineDispatcher = Dispatchers.Default

    @Provides
    @Singleton
    @ApplicationScope
    fun applicationScope(): CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
}
