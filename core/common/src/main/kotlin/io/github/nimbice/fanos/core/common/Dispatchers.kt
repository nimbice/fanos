package io.github.nimbice.fanos.core.common

import javax.inject.Qualifier

/** Which coroutine dispatcher to inject: `@Dispatcher(ReaderDispatchers.IO) dispatcher: CoroutineDispatcher`. */
@Qualifier
@Retention(AnnotationRetention.RUNTIME)
annotation class Dispatcher(val dispatcher: ReaderDispatchers)

enum class ReaderDispatchers { Default, IO }

/** The scope for work that must outlive a screen, such as saving progress as the reader closes. */
@Qualifier
@Retention(AnnotationRetention.RUNTIME)
annotation class ApplicationScope
