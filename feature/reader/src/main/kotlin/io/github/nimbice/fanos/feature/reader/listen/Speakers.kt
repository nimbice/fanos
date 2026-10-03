package io.github.nimbice.fanos.feature.reader.listen

import android.media.AudioAttributes
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.util.Locale
import android.os.Bundle

/** What a [Speaker] says of its reading, on the main thread. */
internal interface SpeakerEvents {
    fun onStart(index: Int)

    /** The word being said starts at [start] in the sentence's text, for a voice that tells. */
    fun onWord(index: Int, start: Int)

    fun onDone(index: Int)

    fun onError(index: Int)
}

/** A voice reading sentences aloud: the phone's own text-to-speech, or a natural voice. */
internal interface Speaker {
    /** The language its sentences are cut for. */
    val locale: Locale

    /** It plays the sound itself, so Android sends the headset's buttons to the app without a [QuietTrack]. */
    val ownsAudio: Boolean

    /** Reads [texts] from [from] on, telling [events] of each sentence as it starts and is done. Whatever it was reading stops. */
    fun speak(texts: List<String>, from: Int, speed: Float, events: SpeakerEvents)

    /** Says [text] once, telling nothing of it: why listening stopped. */
    fun say(text: String)

    /** Stops at once. */
    fun stop()

    /** How loud it reads, 0 to 1: lowered as a timer runs out. */
    fun setVolume(volume: Float)

    fun release()
}

/** The phone's own text-to-speech: a few sentences queued ahead, so there are no gaps, and word by word as it says them. */
internal class PhoneSpeaker(private val tts: TextToSpeech, private val main: CoroutineScope) : Speaker {
    private var generation = 0
    private var texts: List<String> = emptyList()
    private var queuedUpTo = -1
    private var events: SpeakerEvents? = null
    private var volume = 1f

    override val locale: Locale get() = tts.voice?.locale ?: Locale.getDefault()
    override val ownsAudio = false

    init {
        tts.setOnUtteranceProgressListener(Progress())
    }

    override fun speak(texts: List<String>, from: Int, speed: Float, events: SpeakerEvents) {
        stop()
        this.texts = texts
        this.events = events
        queuedUpTo = from - 1
        tts.setSpeechRate(speed)
        queueAhead(from)
    }

    override fun say(text: String) {
        stop()
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "$generation:said")
    }

    override fun stop() {
        generation++
        tts.stop()
    }

    // The engine is the narrator's, which lets it go.
    override fun release() = stop()

    // Sentences queued from now on are at this volume: a sentence at a time, the engine's way.
    override fun setVolume(volume: Float) {
        this.volume = volume.coerceIn(0f, 1f)
    }

    private fun queueAhead(at: Int) {
        while (queuedUpTo < minOf(at + AHEAD, texts.lastIndex)) {
            queuedUpTo++
            val params = if (volume < 1f) Bundle().apply { putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, volume) } else null
            tts.speak(texts[queuedUpTo], TextToSpeech.QUEUE_ADD, params, "$generation:$queuedUpTo")
        }
    }

    /** What the voice says it's doing, brought over to the main thread; word of anything dropped since is let be. */
    private inner class Progress : UtteranceProgressListener() {
        override fun onStart(utteranceId: String) =
            on(utteranceId) {
                queueAhead(it)
                events?.onStart(it)
            }

        override fun onDone(utteranceId: String) = on(utteranceId) { events?.onDone(it) }

        @Deprecated("Deprecated in Java")
        override fun onError(utteranceId: String) = on(utteranceId) { events?.onError(it) }

        override fun onError(utteranceId: String, errorCode: Int) = on(utteranceId) { events?.onError(it) }

        override fun onRangeStart(utteranceId: String, start: Int, end: Int, frame: Int) = on(utteranceId) { events?.onWord(it, start) }

        private fun on(utteranceId: String, action: (Int) -> Unit) {
            val (gen, at) = utteranceId.split(':', limit = 2).let { (it.getOrNull(0)?.toIntOrNull() ?: -1) to it.getOrNull(1)?.toIntOrNull() }
            main.launch { if (gen == generation && at != null) action(at) }
        }
    }

    private companion object {
        /** Sentences queued ahead of the one being said. */
        const val AHEAD = 2
    }
}

/** How listening sounds to Android: speech, played as media. */
internal val SPEECH: AudioAttributes =
    AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
