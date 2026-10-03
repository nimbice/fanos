package io.github.nimbice.fanos.feature.reader.listen

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.speech.tts.TextToSpeech
import android.speech.tts.Voice
import androidx.core.content.ContextCompat
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import coil3.SingletonImageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.toBitmap
import com.google.common.util.concurrent.ListenableFuture
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.nimbice.fanos.core.common.ApplicationScope
import io.github.nimbice.fanos.core.common.suspendRunCatching
import io.github.nimbice.fanos.core.data.download.DownloadRepository
import io.github.nimbice.fanos.core.data.repository.ContentRepository
import io.github.nimbice.fanos.core.data.repository.NovelRepository
import io.github.nimbice.fanos.core.data.repository.ReadingRepository
import io.github.nimbice.fanos.core.data.repository.SettingsRepository
import io.github.nimbice.fanos.core.data.userMessage
import io.github.nimbice.fanos.core.model.Chapter
import io.github.nimbice.fanos.core.model.ListeningSettings
import io.github.nimbice.fanos.core.model.ReadingPosition
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import io.github.nimbice.fanos.core.data.repository.StatsRepository
import io.github.nimbice.fanos.core.content.replaced
import io.github.nimbice.fanos.core.data.repository.ReplacementRepository

/** When listening stops by itself. */
enum class StopAfter(val minutes: Int = 0) { Off, Chapter, Quarter(15), HalfHour(30), ThreeQuarters(45), Hour(60) }

/** What is being read aloud: for the reader's highlight and controls, the mini player and the notification. */
data class ListeningState(
    val novelId: Long,
    val novelTitle: String,
    val chapterId: Long,
    val chapterTitle: String,
    /** The sentence being read, or to be read on play; null while the chapter loads. */
    val sentence: Sentence? = null,
    /** Its place among the chapter's sentences, from 0, and how many there are. */
    val index: Int = 0,
    val count: Int = 0,
    /** Where in the sentence's leaf the word being said starts, when the voice tells: the page follows it. */
    val spokenAt: Int? = null,
    val playing: Boolean = false,
    val loading: Boolean = false,
    val stopAfter: StopAfter = StopAfter.Off,
    /** When a timed stop comes, epoch milliseconds. */
    val stopsAt: Long? = null,
    /** Why it stopped when nobody asked it to: said aloud as well. */
    val problem: String? = null,
    /** The novel's cover has come, for the notification. */
    val hasArtwork: Boolean = false,
)

/** One of the phone's voices, for picking: its name, and what to show of it. */
data class VoiceOption(val name: String, val label: String, val detail: String)

/**
 * Reads chapters aloud with the phone's own text-to-speech or a natural voice (see [Speaker]), a sentence at a time, on
 * into the chapters after: the
 * reader's highlight, the mini player, the notification and a headset all drive it, through [ListeningService]'s
 * session, which it keeps connected while it has a novel. Its place is kept as the reader's, so reading or listening
 * picks up from the other. Everything here runs on the main thread.
 */
@Singleton
class Narrator @Inject constructor(
    @ApplicationContext private val context: Context,
    private val reading: ReadingRepository,
    private val contents: ContentRepository,
    private val novels: NovelRepository,
    private val downloads: DownloadRepository,
    private val settings: SettingsRepository,
    private val natural: NaturalVoices,
    private val stats: StatsRepository,
    private val replacements: ReplacementRepository,
    @ApplicationScope scope: CoroutineScope,
) {
    private val main = CoroutineScope(scope.coroutineContext + Dispatchers.Main.immediate)
    private val _state = MutableStateFlow<ListeningState?>(null)
    val state: StateFlow<ListeningState?> = _state.asStateFlow()

    /** The novel's cover for the notification and lock screen, as an image file's bytes. */
    var artwork: ByteArray? = null
        private set

    private var tts: TextToSpeech? = null
    private var ttsReady: CompletableDeferred<Boolean>? = null

    // The voice reading, and the setting it was made for: another voice chosen makes another.
    private var speaker: Speaker? = null
    private var speakerFor: String? = null
    private var listening = ListeningSettings()
    private var chapter: Chapter? = null
    private var sentences: List<Sentence> = emptyList()
    private var index = 0
    private var errors = 0
    private var job: Job? = null
    private var timer: Job? = null
    private var controller: ListenableFuture<MediaController>? = null
    private var resumeOnFocus = false
    private var noisyRegistered = false

    // The phone's voice's sound is the text-to-speech engine's, not the app's: without some of its own playing, Android
    // sends a headset's buttons to whichever app played last.
    private val quiet = QuietTrack(SPEECH)

    private val audio = context.getSystemService(AudioManager::class.java)
    private val focus =
        AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(SPEECH)
            .setOnAudioFocusChangeListener { change -> main.launch { onFocus(change) } }
            .build()
    private val noisy =
        object : BroadcastReceiver() {
            // Headphones pulled out: stop before the room hears it.
            override fun onReceive(context: Context, intent: Intent) {
                main.launch { pause() }
            }
        }

    init {
        // Time listening is time reading, for the stats.
        main.launch {
            while (true) {
                delay(TICK_MS)
                _state.value?.takeIf { it.playing }?.let { stats.addTime(it.novelId, (TICK_MS / 1000).toInt()) }
            }
        }
        main.launch {
            settings.listeningSettings.collect { new ->
                val old = listening
                listening = new
                // Heard at once: the sentence being read starts again as the new settings say it, in the new voice.
                if (_state.value?.playing == true && (new.speed != old.speed || new.voice != old.voice || new.pronunciations != old.pronunciations)) {
                    hush()
                    if (speaker() == null) fail(NO_VOICE) else speakFrom(index)
                }
            }
        }
    }

    /** Reads [chapterId] of [novelId] aloud from [from], or from where the reader left it. */
    fun start(novelId: Long, chapterId: Long, from: ReadingPosition? = null) {
        main.launch {
            connect()
            val current = _state.value
            if (current != null && current.novelId == novelId && current.chapterId == chapterId && sentences.isNotEmpty()) {
                index = Sentences.indexAt(sentences, from ?: reading.position(chapterId))
                play()
                return@launch
            }
            val novel = novels.observeNovel(novelId).first() ?: return@launch
            val target = reading.chapter(chapterId) ?: return@launch
            timer?.cancel()
            _state.value = ListeningState(novelId, novel.title, chapterId, target.title, loading = true, playing = true)
            artwork = null
            main.launch {
                artwork = cover(novel.coverUrl)
                if (artwork != null) _state.update { it?.copy(hasArtwork = true) }
            }
            job?.cancel()
            job = main.launch { load(target, from, play = true) }
        }
    }

    fun play() {
        val state = _state.value ?: return
        if (state.loading) return
        if (sentences.isEmpty()) {
            chapter?.let { target -> job = main.launch { load(target, null, play = true) } }
            return
        }
        main.launch {
            if (speaker() == null) return@launch fail(NO_VOICE)
            if (takeFocus()) speakFrom(index.coerceIn(0, sentences.lastIndex))
        }
    }

    fun pause() {
        val state = _state.value ?: return
        hush()
        quiet.stop()
        giveUpFocus()
        if (state.playing) _state.update { it?.copy(playing = false, spokenAt = null) }
    }

    fun toggle() = if (_state.value?.playing == true) pause() else play()

    /** Says [text] once in the listening voice, as a word looked up: listening, if it's reading, pauses first. */
    fun say(text: String) {
        main.launch {
            if (_state.value?.playing == true) pause()
            speaker()?.say(text)
        }
    }

    fun sentenceOn() = step(1)

    fun sentenceBack() = step(-1)

    fun chapterOn() = toChapter { reading.next(it) }

    fun chapterBack() = toChapter { reading.previous(it) }

    fun setStopAfter(choice: StopAfter) {
        timer?.cancel()
        speaker?.setVolume(1f)
        val stopsAt = if (choice.minutes > 0) System.currentTimeMillis() + choice.minutes * 60_000L else null
        _state.update { it?.copy(stopAfter = choice, stopsAt = stopsAt) }
        if (stopsAt != null) {
            timer =
                main.launch {
                    // The voice fades out over the last half minute, rather than stopping mid-word at full voice.
                    delay(stopsAt - FADE_MS - System.currentTimeMillis())
                    while (true) {
                        val left = stopsAt - System.currentTimeMillis()
                        if (left <= 0) break
                        speaker?.setVolume((left.toFloat() / FADE_MS).coerceIn(QUIETEST, 1f))
                        delay(FADE_STEP_MS)
                    }
                    pause()
                    speaker?.setVolume(1f)
                    _state.update { it?.copy(stopAfter = StopAfter.Off, stopsAt = null) }
                }
        }
    }

    /** Ends listening: the notification goes, and the voice is let go. */
    fun stop() {
        job?.cancel()
        timer?.cancel()
        pause()
        _state.value = null
        chapter = null
        sentences = emptyList()
        artwork = null
        speaker?.release()
        speaker = null
        speakerFor = null
        natural.unload()
        tts?.shutdown()
        tts = null
        ttsReady = null
        controller?.let(MediaController::releaseFuture)
        controller = null
    }

    /** The phone's voices for the language being read, installed ones first. */
    suspend fun voices(): List<VoiceOption> {
        val engine = engine() ?: return emptyList()
        val language = (engine.voice?.locale ?: Locale.getDefault()).language
        // A voice's name is the engine's code ("en-us-x-iob-local"): numbered instead, by accent, in a steady order.
        val numbers = HashMap<String, Int>()
        return engine.voices.orEmpty()
            .filter { it.locale.language == language }
            .sortedWith(compareBy<Voice>({ it.locale.displayName }, { it.name }))
            .map { voice ->
                val where =
                    when {
                        !voice.installed -> "Needs downloading in this device's settings"
                        voice.isNetworkConnectionRequired -> "Online"
                        else -> "On this device"
                    }
                val number = numbers.merge(voice.locale.displayName, 1, Int::plus)
                VoiceOption(voice.name, "${voice.locale.displayName} · Voice $number", where)
            }.sortedBy { it.detail != "On this device" }
    }

    private fun step(delta: Int) {
        val state = _state.value ?: return
        if (sentences.isEmpty() || state.loading) return
        val target = index + delta
        when {
            target > sentences.lastIndex -> chapterOn()
            target < 0 -> if (state.playing) speakFrom(0)
            state.playing -> speakFrom(target)
            else -> {
                index = target
                _state.update { it?.copy(sentence = sentences[target], index = target, spokenAt = null) }
                savePlace(sentences[target])
            }
        }
    }

    private fun toChapter(find: suspend (Long) -> Chapter?) {
        val from = chapter ?: return
        val playing = _state.value?.playing == true
        job?.cancel()
        job =
            main.launch {
                val target = find(from.id) ?: return@launch
                load(target, ReadingPosition(0, 0), play = playing)
            }
    }

    /** Loads [target] and reads it from [from] (its saved place for none) if [play]. */
    private suspend fun load(target: Chapter, from: ReadingPosition?, play: Boolean) {
        hush()
        chapter = target
        _state.update { it?.copy(chapterId = target.id, chapterTitle = target.title, sentence = null, index = 0, count = 0, spokenAt = null, loading = true, playing = play, problem = null) }
        val voice = speaker()
        if (voice == null) {
            fail(NO_VOICE)
            return
        }
        val content =
            suspendRunCatching { contents.content(target.id) }.getOrElse { error ->
                fail("Couldn't get ${target.title}: ${userMessage(error)}")
                return
            }
        // Read as shown: with the words the reader replaced.
        val novelId = _state.value?.novelId
        val shown = if (novelId == null) content else content.replaced(replacements.rulesFor(novelId))
        sentences = Sentences.of(shown, target.title, voice.locale)
        if (sentences.isEmpty()) {
            // Nothing to say (pictures only, say): on to the next.
            _state.update { it?.copy(loading = false) }
            if (play) chapterDone() else _state.update { it?.copy(playing = false) }
            return
        }
        index = Sentences.indexAt(sentences, from ?: reading.position(target.id))
        _state.update { it?.copy(sentence = sentences[index], index = index, count = sentences.size, loading = false) }
        if (play) {
            if (takeFocus()) speakFrom(index) else _state.update { it?.copy(playing = false) }
        }
    }

    private fun speakFrom(from: Int) {
        val voice = speaker ?: return
        hush()
        index = from
        errors = 0
        registerNoisy()
        if (!voice.ownsAudio) quiet.start()
        _state.update { it?.copy(sentence = sentences[from], index = from, spokenAt = null, playing = true, loading = false, problem = null) }
        voice.speak(sentences.map { Sentences.spoken(it.text, listening.pronunciations) }, from, listening.speed, events)
    }

    /** Drops whatever the voice is reading, and its word on it. */
    private fun hush() {
        speaker?.stop()
        unregisterNoisy()
    }

    private val events =
        object : SpeakerEvents {
            override fun onStart(index: Int) = onSentenceStart(index)

            override fun onWord(index: Int, start: Int) = this@Narrator.onWord(index, start)

            override fun onDone(index: Int) = onSentenceDone(index)

            override fun onError(index: Int) = onSentenceError(index)
        }

    private fun onSentenceStart(at: Int) {
        if (at !in sentences.indices) return
        index = at
        errors = 0
        _state.update { it?.copy(sentence = sentences[at], index = at, spokenAt = null) }
        savePlace(sentences[at])
    }

    private fun onSentenceDone(at: Int) {
        if (at == sentences.lastIndex) chapterDone()
    }

    private fun onWord(at: Int, start: Int) {
        val sentence = sentences.getOrNull(at) ?: return
        if (at != index) return
        // With words put in the reader's way, the voice's place is only roughly the text's.
        val said = Sentences.spoken(sentence.text, listening.pronunciations)
        val within = if (said.length == sentence.text.length) start else start * sentence.text.length / said.length.coerceAtLeast(1)
        _state.update { it?.copy(spokenAt = sentence.start + within) }
    }

    private fun onSentenceError(at: Int) {
        if (++errors >= 3) {
            fail("The voice stopped working.")
            return
        }
        onSentenceDone(at)
    }

    /** The chapter was read to its end: it's read, and listening goes on into the next, or stops there as asked. */
    private fun chapterDone() {
        val finished = chapter ?: return
        main.launch {
            reading.markRead(finished.id)
            suspendRunCatching { downloads.finishedReading(finished.id) }
        }
        val stopHere = _state.value?.stopAfter == StopAfter.Chapter
        if (stopHere) _state.update { it?.copy(stopAfter = StopAfter.Off) }
        job =
            main.launch {
                val next = reading.next(finished.id)
                if (next == null) {
                    fail("That was the last chapter there is.")
                    return@launch
                }
                if (listening.chime && !stopHere) Chime.play(SPEECH)
                load(next, ReadingPosition(0, 0), play = !stopHere)
                if (stopHere) giveUpFocus()
            }
    }

    /** Stops, saying why, aloud as well. */
    private fun fail(problem: String) {
        hush()
        quiet.stop()
        _state.update { it?.copy(playing = false, loading = false, problem = problem) }
        speaker?.say(problem)
        giveUpFocus()
    }

    private fun savePlace(sentence: Sentence) {
        val target = chapter ?: return
        main.launch { reading.savePosition(target.id, sentence.position) }
    }

    /**
     * The voice listening's settings choose, made the first time and again when another is chosen: a natural voice
     * while its pack is on the phone, else the phone's own. Null with neither.
     */
    private suspend fun speaker(): Speaker? {
        val wanted = listening.voice
        speaker?.let { current -> if (speakerFor == wanted) return current }
        speaker?.release()
        speaker = null
        val made = NaturalVoice.sidOf(wanted)?.let { sid -> natural.speaker(sid, main) } ?: engine()?.let { engine -> applyVoice(engine).let { PhoneSpeaker(engine, main) } }
        speaker = made
        speakerFor = wanted
        return made
    }

    /** The phone's text-to-speech, set up the first time: null when there's none. */
    private suspend fun engine(): TextToSpeech? {
        tts?.let { engine -> return if (ttsReady?.await() == true) engine else null }
        val ready = CompletableDeferred<Boolean>()
        ttsReady = ready
        val engine = TextToSpeech(context) { status -> ready.complete(status == TextToSpeech.SUCCESS) }
        tts = engine
        if (!ready.await()) return null
        engine.setAudioAttributes(SPEECH)
        engine.setSpeechRate(listening.speed)
        applyVoice(engine)
        return engine
    }

    private fun applyVoice(engine: TextToSpeech) {
        val name = listening.voice
        val voice = if (name == null || NaturalVoice.sidOf(name) != null) engine.defaultVoice else engine.voices?.firstOrNull { it.name == name }
        if (voice != null) engine.voice = voice
    }

    private fun takeFocus(): Boolean {
        val granted = audio.requestAudioFocus(focus) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        if (!granted) _state.update { it?.copy(playing = false) }
        return granted
    }

    private fun giveUpFocus() {
        audio.abandonAudioFocusRequest(focus)
    }

    private fun onFocus(change: Int) {
        when (change) {
            AudioManager.AUDIOFOCUS_LOSS -> {
                resumeOnFocus = false
                pause()
            }
            // A call, a navigation prompt: stopped for it, and back after.
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT, AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK ->
                if (_state.value?.playing == true) {
                    resumeOnFocus = true
                    hush()
                    quiet.stop()
                    _state.update { it?.copy(playing = false, spokenAt = null) }
                }
            AudioManager.AUDIOFOCUS_GAIN ->
                if (resumeOnFocus) {
                    resumeOnFocus = false
                    play()
                }
        }
    }

    private fun registerNoisy() {
        if (noisyRegistered) return
        ContextCompat.registerReceiver(context, noisy, IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY), ContextCompat.RECEIVER_NOT_EXPORTED)
        noisyRegistered = true
    }

    private fun unregisterNoisy() {
        if (!noisyRegistered) return
        context.unregisterReceiver(noisy)
        noisyRegistered = false
    }

    /** Keeps [ListeningService] running while there's a novel to listen to: it shows the notification and takes the headset's buttons. */
    private fun connect() {
        if (controller != null) return
        controller = MediaController.Builder(context, SessionToken(context, ComponentName(context, ListeningService::class.java))).buildAsync()
    }

    private suspend fun cover(url: String?): ByteArray? {
        if (url == null) return null
        val request = ImageRequest.Builder(context).data(url).size(COVER_PX).allowHardware(false).build()
        val bitmap = (SingletonImageLoader.get(context).execute(request) as? SuccessResult)?.image?.toBitmap() ?: return null
        return withContext(Dispatchers.Default) { ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }.toByteArray() }
    }

    private val Voice.installed: Boolean get() = TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED !in features.orEmpty()

    private companion object {
        const val COVER_PX = 512
        const val NO_VOICE = "There's no voice on this device to read with."
        const val TICK_MS = 15_000L
    }
}

/** How long the voice takes to fade out as a timer runs out, and how often it's turned down meanwhile. */
private const val FADE_MS = 30_000L
private const val FADE_STEP_MS = 250L

/** The voice's lowest in the fade: still heard to the end. */
private const val QUIETEST = 0.05f
