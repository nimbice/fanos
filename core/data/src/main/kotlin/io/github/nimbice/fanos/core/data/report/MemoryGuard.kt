package io.github.nimbice.fanos.core.data.report

import android.content.Context
import android.os.Debug
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.nimbice.fanos.core.common.AppIdentity
import io.github.nimbice.fanos.core.common.MemoryLedger
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.concurrent.thread

/**
 * Watches the app's memory, which runs at a few tens of MB, and notices when it goes well past that: it writes down what
 * the [MemoryLedger] says is holding memory and under way, for a crash report should it run out, and past half of all
 * there is it asks what holds memory to let go of what it can. Test builds also save the heap, once, for adb to pull.
 */
@Singleton
class MemoryGuard @Inject constructor(
    @ApplicationContext context: Context,
    identity: AppIdentity,
) {
    private val note = File(context.filesDir, "crash/memory.txt")
    private val heap = context.getExternalFilesDir(null)?.let { File(it, "high-memory.hprof") }
    private val testBuild = "-test" in identity.versionName

    fun start() {
        thread(isDaemon = true, name = "memory-guard") { watch() }
    }

    private fun watch() {
        val runtime = Runtime.getRuntime()
        val max = runtime.maxMemory()
        fun used() = runtime.totalMemory() - runtime.freeMemory()
        var noticed = false
        var shed = false
        var dumped = false
        var quietUntil = 0L
        var lastLine = 0L
        while (true) {
            Thread.sleep(CHECK_MS)
            val now = System.currentTimeMillis()
            var used = used()
            if (used < max / 8) {
                noticed = false
                shed = false
            }
            if (!noticed && used > max / 4 && now >= quietUntil) {
                // Some of it may be garbage not yet collected: only what's left after a collection counts.
                Runtime.getRuntime().gc()
                used = used()
                if (used <= max / 4) {
                    quietUntil = now + QUIET_MS
                    continue
                }
                noticed = true
                write("Memory high", MemoryLedger.report())
                if (testBuild && !dumped) {
                    dumped = true
                    heap?.let { runCatching { Debug.dumpHprofData(it.path) } }
                }
            }
            if (!shed && used > max / 2) {
                shed = true
                val names = MemoryLedger.shed()
                write("Memory over half, let go: ${names.ifEmpty { listOf("nothing") }.joinToString()}", MemoryLedger.report())
            }
            // While it's high, a line every few seconds: how it goes, in the log.
            if (noticed && now - lastLine >= LINE_MS) {
                lastLine = now
                Log.i(TAG, "${used shr 20} MB of ${max shr 20} MB")
            }
        }
    }

    private fun write(title: String, report: String) {
        Log.w(TAG, "$title\n$report")
        runCatching {
            note.parentFile?.mkdirs()
            note.writeText("${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())} $title\n$report")
        }
    }

    /** What was written down the last time memory ran high: when, and what held it. */
    fun lastNote(): String? = note.takeIf { it.exists() }?.readText()

    private companion object {
        const val TAG = "FanosMemory"
        const val CHECK_MS = 1_000L
        const val QUIET_MS = 30_000L
        const val LINE_MS = 5_000L
    }
}
