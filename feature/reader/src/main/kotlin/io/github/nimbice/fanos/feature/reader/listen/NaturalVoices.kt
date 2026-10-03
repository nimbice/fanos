package io.github.nimbice.fanos.feature.reader.listen

import android.content.Context
import android.media.AudioFormat
import android.media.AudioTrack
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsKokoroModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.nimbice.fanos.core.common.ApplicationScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.Executors
import javax.inject.Inject
import javax.inject.Singleton
import io.github.nimbice.fanos.core.common.AppIdentity
import java.util.concurrent.TimeUnit

/** One of the natural voices: its speaker number in the pack, its name and accent. */
data class NaturalVoice(val sid: Int, val name: String, val accent: String) {
    /** How listening's settings name it. */
    val id: String get() = "$PREFIX$sid"

    val label: String get() = "$name · $accent"

    companion object {
        private const val PREFIX = "kokoro:"

        /** The speaker number a voice setting names, for a natural voice. */
        fun sidOf(voice: String?): Int? = voice?.takeIf { it.startsWith(PREFIX) }?.removePrefix(PREFIX)?.toIntOrNull()
    }
}

/** Where the natural voices are on the phone. */
sealed interface NaturalPack {
    data object Missing : NaturalPack

    /** Coming in: how much of it, or null while it's unpacked. */
    data class Downloading(val fraction: Float?) : NaturalPack

    data class Failed(val reason: String) : NaturalPack

    data object Ready : NaturalPack
}

/**
 * The natural voices: Kokoro's English voices (hexgrad's Kokoro-82M, Apache-2.0, packed for sherpa-onnx by k2-fsa with
 * eSpeak NG's data, GPL-3.0-or-later), read offline with sherpa-onnx. The pack is downloaded once,
 * on Wi-Fi, after asking, and checked against its published SHA-256 before it's unpacked. The voice model loads when
 * listening first uses it and is let go when listening stops.
 */
@Singleton
class NaturalVoices @Inject constructor(
    @ApplicationContext private val context: Context,
    private val client: OkHttpClient,
    private val identity: AppIdentity,
    @ApplicationScope private val scope: CoroutineScope,
) {
    private val root = File(context.filesDir, "voices")
    private val pack = File(root, PACK)
    private val _state = MutableStateFlow(if (File(pack, TOKENS).exists()) NaturalPack.Ready else NaturalPack.Missing)
    val state: StateFlow<NaturalPack> = _state.asStateFlow()
    private var download: Job? = null

    // The model isn't safe to use from two threads at once: it gets one of its own.
    private val synth: CoroutineDispatcher = Executors.newSingleThreadExecutor { Thread(it, "natural-voice") }.asCoroutineDispatcher()
    private var engine: OfflineTts? = null

    val voices: List<NaturalVoice> =
        listOf(
            NaturalVoice(0, "Default", "US"),
            NaturalVoice(1, "Bella", "US"),
            NaturalVoice(2, "Nicole", "US"),
            NaturalVoice(3, "Sarah", "US"),
            NaturalVoice(4, "Sky", "US"),
            NaturalVoice(5, "Adam", "US"),
            NaturalVoice(6, "Michael", "US"),
            NaturalVoice(7, "Emma", "UK"),
            NaturalVoice(8, "Isabella", "UK"),
            NaturalVoice(9, "George", "UK"),
            NaturalVoice(10, "Lewis", "UK"),
        )

    init {
        // Left by a download the app was closed during.
        root.listFiles()?.filter { it.name.endsWith(".part") }?.forEach { it.deleteRecursively() }
    }

    /** Whether the phone is on a network that isn't metered (Wi-Fi, say), the only kind the pack downloads over. */
    fun onWifi(): Boolean {
        val manager = context.getSystemService(ConnectivityManager::class.java)
        val capabilities = manager.getNetworkCapabilities(manager.activeNetwork) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
    }

    fun download() {
        if (download?.isActive == true) return
        download =
            scope.launch(Dispatchers.IO) {
                val archive = File(root, "$PACK.tar.bz2.part")
                val unpacking = File(root, "$PACK.part")
                try {
                    _state.value = NaturalPack.Downloading(0f)
                    root.mkdirs()
                    fetch(archive)
                    _state.value = NaturalPack.Downloading(null)
                    unpack(archive, unpacking)
                    // The pack unpacks into a folder of its own name.
                    val inner = unpacking.listFiles()?.singleOrNull { it.isDirectory } ?: unpacking
                    pack.deleteRecursively()
                    if (!inner.renameTo(pack)) throw IOException("Couldn't keep the voices")
                    _state.value = NaturalPack.Ready
                } catch (e: CancellationException) {
                    _state.value = NaturalPack.Missing
                    throw e
                } catch (e: Exception) {
                    _state.value = NaturalPack.Failed(e.message ?: "The download failed")
                } finally {
                    archive.delete()
                    unpacking.deleteRecursively()
                }
            }
    }

    fun cancel() {
        download?.cancel()
    }

    /** Deletes the pack; listening goes back to the phone's voices. */
    fun delete() {
        cancel()
        unload()
        scope.launch(Dispatchers.IO) {
            pack.deleteRecursively()
            _state.value = NaturalPack.Missing
        }
    }

    /** A speaker for voice [sid], the model loaded first if it isn't; null without the pack, or when it won't load. */
    internal suspend fun speaker(sid: Int, main: CoroutineScope): Speaker? {
        if (_state.value != NaturalPack.Ready) return null
        val tts = withContext(synth) { engine ?: runCatching { load() }.getOrNull()?.also { engine = it } } ?: return null
        return NaturalSpeaker(tts, sid.coerceIn(0, voices.lastIndex), synth, main)
    }

    /** Lets the model go, as listening stops. */
    fun unload() {
        scope.launch(synth) {
            engine?.release()
            engine = null
        }
    }

    private fun load(): OfflineTts {
        val model = pack.listFiles()?.firstOrNull { it.name.endsWith(".onnx") } ?: throw IOException("The voices are incomplete")
        val config =
            OfflineTtsConfig(
                model =
                    OfflineTtsModelConfig(
                        kokoro =
                            OfflineTtsKokoroModelConfig(
                                model = model.path,
                                voices = File(pack, "voices.bin").path,
                                tokens = File(pack, TOKENS).path,
                                dataDir = File(pack, "espeak-ng-data").path,
                            ),
                        numThreads = Runtime.getRuntime().availableProcessors().coerceIn(1, 4),
                        debug = false,
                        provider = "cpu",
                    ),
            )
        return OfflineTts(null, config)
    }

    private suspend fun fetch(archive: File) {
        // 103 MB: however long the connection takes, not the shared client's limit on a whole call.
        val response = client.newBuilder().callTimeout(0, TimeUnit.SECONDS).build().newCall(Request.Builder().url(URL).header("User-Agent", identity.userAgent).build()).execute()
        response.use {
            if (!response.isSuccessful) throw IOException("The download failed (HTTP ${response.code})")
            val body = response.body
            val total = body.contentLength().takeIf { it > 0 } ?: SIZE
            val digest = MessageDigest.getInstance("SHA-256")
            body.byteStream().use { input ->
                archive.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var done = 0L
                    while (true) {
                        currentCoroutineContextEnsureActive()
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        digest.update(buffer, 0, read)
                        done += read
                        _state.value = NaturalPack.Downloading(done.toFloat() / total)
                    }
                }
            }
            val sha = digest.digest().joinToString("") { "%02x".format(it) }
            if (sha != SHA256) throw IOException("The download wasn't the file expected")
        }
    }

    private suspend fun unpack(archive: File, into: File) {
        into.deleteRecursively()
        into.mkdirs()
        val base = into.canonicalPath + File.separator
        TarArchiveInputStream(BZip2CompressorInputStream(archive.inputStream().buffered())).use { tar ->
            var entry = tar.nextEntry
            while (entry != null) {
                currentCoroutineContextEnsureActive()
                val out = File(into, entry.name).canonicalFile
                // Nothing written outside the folder, whatever names the archive holds.
                if (!out.path.startsWith(base)) throw IOException("The download holds an unexpected file")
                if (entry.isDirectory) {
                    out.mkdirs()
                } else {
                    out.parentFile?.mkdirs()
                    out.outputStream().use { tar.copyTo(it) }
                }
                entry = tar.nextEntry
            }
        }
    }

    private suspend fun currentCoroutineContextEnsureActive() = kotlinx.coroutines.currentCoroutineContext().ensureActive()

    private companion object {
        const val PACK = "kokoro-int8-en-v0_19"
        const val URL = "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/kokoro-int8-en-v0_19.tar.bz2"
        const val SHA256 = "c9f0dd393615805b0bab050c340834d5e684e732aec91c0e860cd30e982c08bd"
        const val SIZE = 103_248_205L
        const val TOKENS = "tokens.txt"
    }
}

/**
 * A natural voice: each sentence made into sound on the model's thread, a sentence or so ahead of the one playing, and
 * played as it comes. It tells of a sentence as the sound of it starts and ends, not word by word.
 */
internal class NaturalSpeaker(
    private val tts: OfflineTts,
    private val sid: Int,
    private val synth: CoroutineDispatcher,
    private val main: CoroutineScope,
) : Speaker {
    private var job: Job? = null
    private var track: AudioTrack? = null
    private var volume = 1f

    override val locale: Locale = Locale.US
    override val ownsAudio = true

    override fun speak(texts: List<String>, from: Int, speed: Float, events: SpeakerEvents) {
        stop()
        val rate = tts.sampleRate()
        val audio =
            AudioTrack.Builder()
                .setAudioAttributes(SPEECH)
                .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_FLOAT).setSampleRate(rate).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setBufferSizeInBytes(maxOf(AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_FLOAT), rate * 4 / 2))
                .build()
        audio.setVolume(volume)
        track = audio
        job =
            main.launch {
                val made = Channel<Pair<Int, FloatArray>>(capacity = 1)
                val spans = Channel<Span>(Channel.UNLIMITED)
                launch(synth) {
                    for (i in from until texts.size) {
                        val samples = if (texts[i].isBlank()) FloatArray(0) else tts.generate(texts[i], sid, speed).samples
                        made.send(i to samples)
                    }
                    made.close()
                }
                launch(Dispatchers.IO) {
                    audio.play()
                    var written = 0L
                    for ((i, samples) in made) {
                        val start = written
                        var offset = 0
                        while (offset < samples.size && isActive) {
                            val n = audio.write(samples, offset, samples.size - offset, AudioTrack.WRITE_BLOCKING)
                            if (n <= 0) break
                            offset += n
                        }
                        written += samples.size
                        spans.send(Span(i, start, written))
                    }
                    spans.close()
                }
                // Each sentence is told of as the sound of it starts and ends.
                var started = -1
                for (span in spans) {
                    while (isActive) {
                        val head = audio.playbackHeadPosition.toLong() and 0xFFFFFFFFL
                        if (started != span.index && head >= span.start) {
                            started = span.index
                            events.onStart(span.index)
                        }
                        if (head >= span.end) break
                        delay(POLL_MS)
                    }
                    events.onDone(span.index)
                }
            }
    }

    override fun setVolume(volume: Float) {
        this.volume = volume.coerceIn(0f, 1f)
        track?.setVolume(this.volume)
    }

    override fun say(text: String) =
        speak(
            listOf(text),
            0,
            1f,
            object : SpeakerEvents {
                override fun onStart(index: Int) = Unit

                override fun onWord(index: Int, start: Int) = Unit

                override fun onDone(index: Int) = Unit

                override fun onError(index: Int) = Unit
            },
        )

    override fun stop() {
        job?.cancel()
        job = null
        track?.let {
            runCatching {
                it.pause()
                it.flush()
            }
            it.release()
        }
        track = null
    }

    override fun release() = stop()

    /** A sentence's sound in the track: from frame [start] to [end]. */
    private data class Span(val index: Int, val start: Long, val end: Long)

    private companion object {
        const val POLL_MS = 25L
    }
}
