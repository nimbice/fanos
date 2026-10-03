package io.github.nimbice.fanos.core.updater

import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.Multibinds

/**
 * More to look for whenever the app looks for its own updates (daily, and as it opens after a while),
 * such as updates to extensions, which the updater itself knows nothing of. Bound into a set.
 */
interface BackgroundCheck {
    suspend fun check()
}

@Module
@InstallIn(SingletonComponent::class)
internal abstract class BackgroundCheckModule {
    // The set may be empty.
    @Multibinds
    abstract fun backgroundChecks(): Set<BackgroundCheck>
}
