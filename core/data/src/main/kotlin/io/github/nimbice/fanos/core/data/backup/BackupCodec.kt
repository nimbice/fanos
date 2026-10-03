package io.github.nimbice.fanos.core.data.backup

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.decodeFromStream
import kotlinx.serialization.json.put
import java.io.BufferedInputStream
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/**
 * Backup files: [Backup] as JSON, gzipped. Reading also takes the JSON as it is, for a file that
 * something unpacked on the way (browsers and cloud drives sometimes do).
 */
internal object BackupCodec {
    const val FORMAT = "fanos-backup"
    const val VERSION = 1

    // Nulls are left out; everything else is written, so a changed default can't change what an old
    // backup says.
    @OptIn(ExperimentalSerializationApi::class)
    private val json =
        Json {
            ignoreUnknownKeys = true
            explicitNulls = false
            encodeDefaults = true
        }

    /** Writes a backup a novel at a time, so a large library is never in memory all at once. */
    class Writer(
        out: OutputStream,
        createdAt: Long,
        app: String?,
        settings: JsonObject?,
        sections: List<BackupSection> = emptyList(),
        replacements: List<BackupReplacement> = emptyList(),
    ) : Closeable {
        private val gzip = GZIPOutputStream(out, BUFFER)
        private val text = gzip.bufferedWriter()
        private var novels = 0

        init {
            val head =
                buildJsonObject {
                    put("format", FORMAT)
                    put("version", VERSION)
                    put("createdAt", createdAt)
                    app?.let { put("app", it) }
                    settings?.let { put("settings", it) }
                    if (sections.isNotEmpty()) put("sections", json.encodeToJsonElement(ListSerializer(BackupSection.serializer()), sections))
                    if (replacements.isNotEmpty()) put("replacements", json.encodeToJsonElement(ListSerializer(BackupReplacement.serializer()), replacements))
                }
            text.write(json.encodeToString(JsonObject.serializer(), head).removeSuffix("}"))
            text.write(""","novels":[""")
        }

        fun add(novel: BackupNovel) {
            if (novels++ > 0) text.write(",")
            text.write(json.encodeToString(BackupNovel.serializer(), novel))
        }

        /** Completes the file; without this it is left unreadable, as a backup cut short should be. */
        fun finish() {
            text.write("]}")
            text.flush()
            gzip.finish()
        }

        override fun close() = text.close()
    }

    /** Reads the backup [open] gives, opening it a second time to explain a file it can't read. */
    fun read(open: () -> InputStream): Backup {
        val backup =
            try {
                open().use { decode(it, Backup.serializer()) }
            } catch (e: IOException) {
                throw explain(open, e)
            } catch (e: IllegalArgumentException) {
                // Also the serialization errors: missing fields, wrong types, broken JSON.
                throw explain(open, e)
            }
        return when {
            backup.format != FORMAT -> throw BackupException.NotABackup()
            backup.version > VERSION -> throw BackupException.TooNew()
            else -> backup
        }
    }

    @Serializable
    private data class Header(val format: String? = null, val version: Int = 0)

    /** Why [error] came up: a newer backup than this app reads, a damaged one, or no backup at all. */
    private fun explain(open: () -> InputStream, error: Exception): BackupException {
        val header = runCatching { open().use { decode(it, Header.serializer()) } }.getOrNull()
        return when {
            header == null -> BackupException.Damaged(error)
            header.format != FORMAT -> BackupException.NotABackup()
            header.version > VERSION -> BackupException.TooNew()
            else -> BackupException.Damaged(error)
        }
    }

    @OptIn(ExperimentalSerializationApi::class)
    private fun <T> decode(input: InputStream, serializer: KSerializer<T>): T {
        val stream = BufferedInputStream(input, BUFFER)
        stream.mark(1)
        val first = stream.read()
        stream.reset()
        if (!startsLikeBackup(first)) throw BackupException.NotABackup()
        val content = if (first == GZIP_FIRST_BYTE) GZIPInputStream(stream, BUFFER) else stream
        return json.decodeFromStream(serializer, content)
    }

    /** Whether a file starting with the byte [first] could be a backup: gzip, or the JSON as it is. */
    fun startsLikeBackup(first: Int): Boolean =
        first == GZIP_FIRST_BYTE || first == '{'.code || first == ' '.code || first == '\n'.code || first == '\r'.code || first == '\t'.code

    /** Larger than any backup could be: one a hundredth this size holds tens of thousands of chapters. */
    const val MAX_FILE_BYTES = 64L * 1024 * 1024

    private const val BUFFER = 64 * 1024
    private const val GZIP_FIRST_BYTE = 0x1f
}
