package io.github.nimbice.fanos.core.common

import kotlinx.coroutines.CancellationException

/**
 * Like [runCatching], but lets cancellation through: catching CancellationException keeps a
 * cancelled coroutine running and was a common source of work leaking past a closed screen.
 */
inline fun <T> suspendRunCatching(block: () -> T): Result<T> =
    try {
        Result.success(block())
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        Result.failure(e)
    }
