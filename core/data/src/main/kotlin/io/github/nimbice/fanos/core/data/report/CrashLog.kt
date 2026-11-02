package io.github.nimbice.fanos.core.data.report

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.nimbice.fanos.core.common.AppIdentity
import io.github.nimbice.fanos.core.common.Dispatcher
import io.github.nimbice.fanos.core.common.MemoryLedger
import io.github.nimbice.fanos.core.data.notification.DiagnosticsNotifier
import io.github.nimbice.fanos.core.common.ReaderDispatchers
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/** The last crash's report: when it happened, and the trace with the build and device it happened on. */
data class CrashReport(val at: Long, val text: String)

/**
 * Keeps the last crash, so there's something to report: nothing leaves the device by itself. The report is a file
 * under the app's own files, shown in Settings > About until the reader clears it or another crash replaces it.
 */
@Singleton
class CrashLog @Inject constructor(
    @ApplicationContext private val context: Context,
    private val identity: AppIdentity,
    private val memory: MemoryGuard,
    private val notifier: DiagnosticsNotifier,
    @Dispatcher(ReaderDispatchers.IO) private val io: CoroutineDispatcher,
) {
    private val file = File(context.filesDir, "crash/last-crash.txt")

    // The last time the app closed that's been looked at, so it isn't reported twice.
    private val seenExit = File(context.filesDir, "crash/seen-exit.txt")

    /**
     * Writes the report of any crash from now on, then lets the crash go on as it would have. A crash that couldn't be
     * written down as it happened, or the app stopped for not responding, is written down from what Android kept of it.
     */
    fun install() {
        val before = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            // Out of memory: what can go goes first, for room to write the report in.
            val outOfMemory = error is OutOfMemoryError
            if (outOfMemory) runCatching { MemoryLedger.shed() }
            runCatching {
                file.parentFile?.mkdirs()
                file.writeText(report(thread.name, error.stackTraceToString(), outOfMemory))
            }
            before?.uncaughtException(thread, error)
        }
        runCatching { lastExit() }
    }

    private fun report(thread: String, what: String, outOfMemory: Boolean, at: Long = System.currentTimeMillis()): String =
        buildString {
            append("Fanos ").append(identity.versionName)
            append(" · Android ").append(Build.VERSION.RELEASE).append(" (API ").append(Build.VERSION.SDK_INT).append(")")
            append(" · ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL).append('\n')
            append(SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z", Locale.US).format(Date(at))).append(" on thread ").append(thread).append("\n\n")
            append(what)
            if (outOfMemory) {
                append("\n\nMemory as it ran out:\n").append(MemoryLedger.report())
                memory.lastNote()?.let { append("\nWhen it first ran high: ").append(it) }
            }
        }

    /** How the app last closed, if it crashed without a report or stopped responding. */
    private fun lastExit() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        val manager = context.getSystemService(ActivityManager::class.java) ?: return
        val exit = manager.getHistoricalProcessExitReasons(null, 0, EXITS)
            // The app's own process, not the browser's page processes that are kept under its name too.
            .firstOrNull { it.processName == context.packageName } ?: return
        val seen = runCatching { seenExit.readText().trim().toLong() }.getOrDefault(0L)
        if (exit.timestamp <= seen) return
        seenExit.parentFile?.mkdirs()
        seenExit.writeText(exit.timestamp.toString())
        when (exit.reason) {
            // Written down as it happened, unless it couldn't be.
            ApplicationExitInfo.REASON_CRASH ->
                if (!file.exists() || file.lastModified() < exit.timestamp - SLACK_MS) {
                    val note = memory.lastNote()?.let { "\n\nWhen memory last ran high: $it" }.orEmpty()
                    file.writeText(report("?", "Fanos crashed, but the crash couldn't be written down as it happened.$note", outOfMemory = false, at = exit.timestamp))
                    file.setLastModified(exit.timestamp)
                    notifier.show(DiagnosticsNotifier.Kind.Closed, "Fanos crashed earlier", "It couldn't write down why as it happened; what Android kept is in Settings → About.")
                }
            ApplicationExitInfo.REASON_ANR -> {
                // Where every thread was, the main one first: the main one is what wasn't responding.
                val trace = exit.traceInputStream?.use { input -> String(ByteArray(TRACE_BYTES).let { it.copyOf(input.readInto(it)) }) } ?: "(no trace kept)"
                file.writeText(report("main", "Fanos stopped responding (${exit.description}).\n\n$trace", outOfMemory = false, at = exit.timestamp))
                file.setLastModified(exit.timestamp)
                notifier.show(DiagnosticsNotifier.Kind.Closed, "Fanos stopped responding earlier", "Android closed it. Where it was stuck is in Settings → About.")
            }
        }
    }

    suspend fun last(): CrashReport? = withContext(io) { if (file.exists()) CrashReport(file.lastModified(), file.readText()) else null }

    /** Reads into [buffer] until it's full or the stream ends; how much it read. */
    private fun java.io.InputStream.readInto(buffer: ByteArray): Int {
        var read = 0
        while (read < buffer.size) {
            val n = read(buffer, read, buffer.size - read)
            if (n < 0) break
            read += n
        }
        return read
    }

    private companion object {
        // How far apart the report's writing and Android's note of the crash may be and still be the same crash.
        const val SLACK_MS = 10_000L

        // As much of a not-responding trace as is kept: the main thread and the next few.
        const val TRACE_BYTES = 48 * 1024

        // How many of the app's last exits to look through for its own process's.
        const val EXITS = 10
    }

    suspend fun clear() {
        withContext(io) { file.delete() }
    }
}
