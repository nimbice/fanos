package io.github.nimbice.fanos.feature.reader.listen

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin

/** A soft two-note chime between one chapter and the next, made here rather than shipped as a sound. */
internal object Chime {

    suspend fun play(attributes: AudioAttributes) {
        val samples = withContext(Dispatchers.Default) { tone() }
        val track =
            AudioTrack.Builder()
                .setAudioAttributes(attributes)
                .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(RATE).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                .setBufferSizeInBytes(samples.size * 2)
                .setTransferMode(AudioTrack.MODE_STATIC)
                .build()
        try {
            track.write(samples, 0, samples.size)
            track.play()
            delay(LENGTH_MS + 60L)
        } finally {
            track.release()
        }
    }

    /** A bell-like E then B, each a sine with a quieter fifth above, fading out. */
    private fun tone(): ShortArray {
        val count = RATE * LENGTH_MS / 1000
        val second = count * 2 / 5
        return ShortArray(count) { i ->
            fun note(frequency: Double, from: Int): Double {
                if (i < from) return 0.0
                val t = (i - from).toDouble() / RATE
                val sound = sin(2 * PI * frequency * t) + 0.3 * sin(2 * PI * frequency * 1.5 * t)
                return sound * exp(-t * 5)
            }
            val value = (note(659.25, 0) * 0.6 + note(987.77, second) * 0.5) * VOLUME * Short.MAX_VALUE
            value.coerceIn(Short.MIN_VALUE.toDouble(), Short.MAX_VALUE.toDouble()).toInt().toShort()
        }
    }

    private const val RATE = 22_050
    private const val LENGTH_MS = 900
    private const val VOLUME = 0.28
}
