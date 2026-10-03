package io.github.nimbice.fanos.core.network

import okhttp3.Interceptor
import okhttp3.Response
import java.io.InterruptedIOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit

/**
 * Limits requests per host: at most [maxConcurrent] at once and at least [minIntervalMs] between
 * their starts. A slow or blocked site no longer holds up the others (the old app serialised all
 * content traffic behind one lock), and a library update or download does not hammer a site into
 * rate-limiting or challenging the app.
 */
class HostLimiter(
    private val maxConcurrent: Int = 2,
    private val minIntervalMs: Long = 250,
) : Interceptor {

    private class HostState(permits: Int) {
        val permits = Semaphore(permits, true)
        var nextStart = 0L
    }

    private val hosts = ConcurrentHashMap<String, HostState>()

    override fun intercept(chain: Interceptor.Chain): Response {
        val host = chain.request().url.host
        val state = hosts.getOrPut(host) { HostState(maxConcurrent) }
        if (!state.permits.tryAcquire(WAIT_LIMIT_SECONDS, TimeUnit.SECONDS)) {
            throw InterruptedIOException("Timed out waiting for a turn at $host")
        }
        try {
            val delay = synchronized(state) {
                val now = System.currentTimeMillis()
                val start = maxOf(now, state.nextStart)
                state.nextStart = start + minIntervalMs
                start - now
            }
            if (delay > 0) Thread.sleep(delay)
            return chain.proceed(chain.request())
        } finally {
            state.permits.release()
        }
    }

    private companion object {
        const val WAIT_LIMIT_SECONDS = 120L
    }
}
