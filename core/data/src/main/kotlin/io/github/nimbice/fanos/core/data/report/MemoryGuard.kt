package io.github.nimbice.fanos.core.data.report

import android.content.Context
import android.os.Debug
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.nimbice.fanos.core.common.AppIdentity
import io.github.nimbice.fanos.core.common.Dispatcher
import io.github.nimbice.fanos.core.common.MemoryLedger
import io.github.nimbice.fanos.core.common.ReaderDispatchers
import io.github.nimbice.fanos.core.data.notification.DiagnosticsNotifier
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.concurrent.thread

/** A time memory ran high: when, and the note of it (what held memory, what was under way, how it went). */
data class MemoryIncident(val at: Long, val text: String)

/**
 * Watches the app's memory, which runs at a few tens of MB, and notices when it goes well past that: it writes down what
 * the [MemoryLedger] says is holding memory and under way, and past half of all there is it asks what holds memory to let
 * go of what it can. The last few such times are kept, for About to share and for a crash report should it run out. Test
 * builds also save the heap and say so in a notification, so the phone can be connected for a look.
 */
@Singleton
class MemoryGuard @Inject constructor(
    @ApplicationContext context: Context,
    identity: AppIdentity,
    private val notifier: DiagnosticsNotifier,
    @Dispatcher(ReaderDispatchers.IO) private val io: CoroutineDispatcher,
) {
    private val notes = File(context.filesDir, "crash/memory")
    private val heaps = context.getExternalFilesDir(null)

    // The notes again where adb can read them (Android/data/<app>/files/memory), as it can't the app's own files.
    private val shared = context.getExternalFilesDir("memory")
    private val testBuild = "-test" in identity.versionName
    private val version = identity.versionName

    fun start() {
        thread(isDaemon = true, name = "memory-guard") { watch() }
    }

    /** A time memory ran high, as it goes: written down again as each thing happens. */
    private inner class Incident(val at: Long) {
        private val name = STAMP.format(Date(at))
        private val file = File(notes, "$name.txt")
        private val parts = StringBuilder()
        var peak = 0L
        var shed = false

        fun add(title: String, report: String? = null) {
            Log.w(TAG, if (report == null) title else "$title\n$report")
            parts.append(TIME.format(Date())).append(' ').append(title).append('\n')
            if (report != null) parts.append(report)
            runCatching {
                notes.mkdirs()
                val text = "${WHEN.format(Date(at))} · Fanos $version\nHighest: ${peak shr 20} MB of ${Runtime.getRuntime().maxMemory() shr 20} MB\n\n$parts"
                for (dir in listOfNotNull(notes, shared)) {
                    File(dir, file.name).apply { writeText(text); setLastModified(at) }
                    prune(dir.listFiles { f -> f.name.endsWith(".txt") }, KEEP_NOTES)
                }
            }
        }

        fun saveHeap() {
            val dir = heaps ?: return
            runCatching {
                Debug.dumpHprofData(File(dir, "high-memory-$name.hprof").path)
                prune(dir.listFiles { f -> f.name.startsWith("high-memory-") && f.name.endsWith(".hprof") }, KEEP_HEAPS)
            }
        }
    }

    private fun watch() {
        val runtime = Runtime.getRuntime()
        val max = runtime.maxMemory()
        fun used() = runtime.totalMemory() - runtime.freeMemory()
        var incident: Incident? = null
        var quietUntil = 0L
        var lastLine = 0L
        while (true) {
            Thread.sleep(CHECK_MS)
            val now = System.currentTimeMillis()
            var used = used()
            val current = incident
            if (current == null) {
                if (used <= max / 4 || now < quietUntil) continue
                // Some of it may be garbage not yet collected: only what's left after a collection counts.
                runtime.gc()
                used = used()
                if (used <= max / 4) {
                    quietUntil = now + QUIET_MS
                    continue
                }
                incident =
                    Incident(now).apply {
                        peak = used
                        add("Memory ran high: ${used shr 20} MB", MemoryLedger.report())
                        if (testBuild) saveHeap()
                    }
                notifier.show(
                    DiagnosticsNotifier.Kind.Memory,
                    "Fanos used far more memory than usual",
                    "${used shr 20} MB of ${max shr 20} MB. What held it is saved in Settings → About.",
                )
                continue
            }
            current.peak = maxOf(current.peak, used)
            if (!current.shed && used > max / 2) {
                current.shed = true
                val names = MemoryLedger.shed()
                current.add("Over half: ${used shr 20} MB. Let go: ${names.ifEmpty { listOf("nothing") }.joinToString()}", MemoryLedger.report())
                notifier.show(
                    DiagnosticsNotifier.Kind.Memory,
                    "Fanos used far more memory than usual",
                    "Over half of all it may use (${used shr 20} MB of ${max shr 20} MB), so it let go of what it could. What held it is saved in Settings → About.",
                )
            }
            if (used < max / 8) {
                // Over: back to about what it should be.
                current.add("Back down to ${used shr 20} MB after ${(now - current.at) / 1000} s; at most ${current.peak shr 20} MB")
                notifier.show(
                    DiagnosticsNotifier.Kind.Memory,
                    "Fanos used far more memory than usual",
                    "Up to ${current.peak shr 20} MB of ${max shr 20} MB for ${(now - current.at) / 1000} s, then it came back down and kept going. What held it is saved in Settings → About.",
                )
                incident = null
                continue
            }
            // While it's high, a line every few seconds: how it goes, in the log.
            if (now - lastLine >= LINE_MS) {
                lastLine = now
                Log.i(TAG, "${used shr 20} MB of ${max shr 20} MB")
            }
        }
    }

    /** Deletes all but the newest [keep] of [files]. */
    private fun prune(files: Array<File>?, keep: Int) {
        files.orEmpty().sortedByDescending { it.name }.drop(keep).forEach { it.delete() }
    }

    /** The newest note, for a crash report. */
    fun lastNote(): String? = newest().firstOrNull()?.readText()

    private fun newest(): List<File> = notes.listFiles { f -> f.name.endsWith(".txt") }.orEmpty().sortedByDescending { it.name }

    /** The times memory ran high that are kept, newest first. */
    suspend fun incidents(): List<MemoryIncident> = withContext(io) { newest().map { MemoryIncident(it.lastModified(), it.readText()) } }

    /** Forgets them, and any heaps saved with them. */
    suspend fun clear() {
        withContext(io) {
            notes.listFiles().orEmpty().forEach { it.delete() }
            shared?.listFiles().orEmpty().forEach { it.delete() }
            heaps?.listFiles { f -> f.name.startsWith("high-memory-") }.orEmpty().forEach { it.delete() }
        }
    }

    private companion object {
        const val TAG = "FanosMemory"
        const val CHECK_MS = 1_000L
        const val QUIET_MS = 30_000L
        const val LINE_MS = 5_000L
        const val KEEP_NOTES = 3
        const val KEEP_HEAPS = 2
        val STAMP = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US)
        val WHEN = SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z", Locale.US)
        val TIME = SimpleDateFormat("HH:mm:ss", Locale.US)
    }
}
