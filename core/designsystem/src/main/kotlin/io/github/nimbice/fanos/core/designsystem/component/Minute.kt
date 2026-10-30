package io.github.nimbice.fanos.core.designsystem.component

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import kotlinx.coroutines.delay
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit

/** The time now, to the minute, moving on as each minute turns: for text that counts down ("about 5 hours from now"). */
@Composable
fun rememberMinute(): ZonedDateTime {
    val now by produceState(minuteNow()) {
        while (true) {
            delay(MINUTE_MS - System.currentTimeMillis() % MINUTE_MS)
            value = minuteNow()
        }
    }
    return now
}

private fun minuteNow(): ZonedDateTime = ZonedDateTime.now().truncatedTo(ChronoUnit.MINUTES)

private const val MINUTE_MS = 60_000L
