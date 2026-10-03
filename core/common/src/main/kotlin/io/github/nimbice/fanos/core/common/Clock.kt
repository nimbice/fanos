package io.github.nimbice.fanos.core.common

/** The current time in epoch milliseconds; injected so tests can control it. */
fun interface Clock {
    fun now(): Long

    companion object {
        val System = Clock { java.lang.System.currentTimeMillis() }
    }
}
