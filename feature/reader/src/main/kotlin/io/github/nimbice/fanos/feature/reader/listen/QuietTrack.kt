package io.github.nimbice.fanos.feature.reader.listen

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack

/**
 * Silence, played over and over while listening. The voice is the text-to-speech engine's sound, so to Android the app
 * plays nothing, and a headset's buttons go to whichever app last did (a music player, say). With this playing they come
 * here.
 */
internal class QuietTrack(private val attributes: AudioAttributes) {
    private var track: AudioTrack? = null

    fun start() {
        if (track != null) return
        track =
            runCatching {
                AudioTrack.Builder()
                    .setAudioAttributes(attributes)
                    .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(RATE).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                    .setBufferSizeInBytes(FRAMES * 2)
                    .setTransferMode(AudioTrack.MODE_STATIC)
                    .build()
                    .apply {
                        write(ShortArray(FRAMES), 0, FRAMES)
                        setLoopPoints(0, FRAMES, -1)
                        play()
                    }
            }.getOrNull()
    }

    fun stop() {
        track?.run {
            runCatching { stop() }
            release()
        }
        track = null
    }

    private companion object {
        const val RATE = 8_000
        const val FRAMES = RATE / 2
    }
}
