package io.github.nimbice.fanos.core.data.report

import android.content.Context
import android.os.Build
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.nimbice.fanos.core.common.AppIdentity
import io.github.nimbice.fanos.core.common.Dispatcher
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
    @ApplicationContext context: Context,
    private val identity: AppIdentity,
    @Dispatcher(ReaderDispatchers.IO) private val io: CoroutineDispatcher,
) {
    private val file = File(context.filesDir, "crash/last-crash.txt")

    /** Writes the report of any crash from now on, then lets the crash go on as it would have. */
    fun install() {
        val before = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching {
                file.parentFile?.mkdirs()
                file.writeText(report(thread, error))
            }
            before?.uncaughtException(thread, error)
        }
    }

    private fun report(thread: Thread, error: Throwable): String =
        buildString {
            append("Fanos ").append(identity.versionName)
            append(" · Android ").append(Build.VERSION.RELEASE).append(" (API ").append(Build.VERSION.SDK_INT).append(")")
            append(" · ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL).append('\n')
            append(SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z", Locale.US).format(Date())).append(" on thread ").append(thread.name).append("\n\n")
            append(error.stackTraceToString())
        }

    suspend fun last(): CrashReport? = withContext(io) { if (file.exists()) CrashReport(file.lastModified(), file.readText()) else null }

    suspend fun clear() {
        withContext(io) { file.delete() }
    }
}
