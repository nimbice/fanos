package io.github.nimbice.fanos.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import io.github.nimbice.fanos.BuildConfig
import io.github.nimbice.fanos.core.common.AppIdentity
import javax.inject.Singleton

/** What only the app module knows: which build this is. */
@Module
@InstallIn(SingletonComponent::class)
object AppModule {
    @Provides
    @Singleton
    fun appIdentity(): AppIdentity = AppIdentity(BuildConfig.VERSION_NAME)
}
