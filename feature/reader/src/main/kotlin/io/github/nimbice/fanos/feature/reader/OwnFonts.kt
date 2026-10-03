package io.github.nimbice.fanos.feature.reader

import android.content.Context
import android.graphics.Typeface
import android.net.Uri
import android.provider.OpenableColumns
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import io.github.nimbice.fanos.core.model.ReaderSettings
import io.github.nimbice.fanos.feature.reader.text.family
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.RandomAccessFile

/** A font the reader added: its name, as the settings know it, and its file. */
internal data class OwnFont(val name: String, val file: File)

/**
 * Fonts the reader added from files on the phone, copied into the app's own storage and named for the font
 * (from the font's own names, else the file's). They stay on this phone: backups keep a novel's choice of one
 * by name, and where the name finds no font, the font chosen before it reads.
 */
internal object OwnFonts {
    private fun folder(context: Context) = File(context.filesDir, "reader-fonts")

    /** The fonts added, by name. */
    fun list(context: Context): List<OwnFont> =
        folder(context).listFiles().orEmpty()
            .filter { it.isFile && it.extension in EXTENSIONS }
            .map { OwnFont(it.nameWithoutExtension, it) }
            .sortedBy { it.name.lowercase() }

    /**
     * Copies in the font at [uri]: its name, or null when the file isn't a font the phone can read (or is
     * too big to be one). A font of the same name already added is replaced.
     */
    suspend fun add(context: Context, uri: Uri): String? =
        withContext(Dispatchers.IO) {
            val resolver = context.contentResolver
            val fileName =
                runCatching {
                    resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { if (it.moveToFirst()) it.getString(0) else null }
                }.getOrNull()
            val folder = folder(context).apply { mkdirs() }
            val incoming = File(folder, "incoming.part")
            try {
                val copied =
                    resolver.openInputStream(uri)?.use { input ->
                        incoming.outputStream().use { output ->
                            val buffer = ByteArray(64 * 1024)
                            var total = 0L
                            while (true) {
                                val read = input.read(buffer)
                                if (read < 0) break
                                total += read
                                if (total > MAX_BYTES) return@use false
                                output.write(buffer, 0, read)
                            }
                            true
                        }
                    } ?: false
                if (!copied || Typeface.Builder(incoming).build() == null) return@withContext null
                val names = RandomAccessFile(incoming, "r").use { file -> FontNames.read { offset, length -> file.bytesAt(offset, length) } }
                val name = safeName(names?.label ?: fileName?.substringBeforeLast('.').orEmpty())
                val extension = fileName?.substringAfterLast('.', "")?.lowercase()?.takeIf { it in EXTENSIONS } ?: names?.extension ?: "ttf"
                list(context).filter { it.name == name }.forEach { it.file.delete() }
                if (!incoming.renameTo(File(folder, "$name.$extension"))) return@withContext null
                name
            } catch (e: Exception) {
                null
            } finally {
                incoming.delete()
            }
        }

    fun remove(context: Context, name: String) {
        list(context).filter { it.name == name }.forEach { it.file.delete() }
    }

    /** A name that's also a file name: no path or reserved characters, not too long, not empty. */
    private fun safeName(name: String): String =
        name.replace(Regex("[\\p{Cntrl}/\\\\:*?\"<>|]"), " ").replace(Regex("\\s+"), " ").trim().take(60).trim().ifEmpty { "Font" }

    private fun RandomAccessFile.bytesAt(offset: Long, length: Int): ByteArray? {
        if (offset < 0 || length < 0 || offset + length > length()) return null
        seek(offset)
        return ByteArray(length).also { readFully(it) }
    }

    private val EXTENSIONS = setOf("ttf", "otf", "ttc")

    /** Bigger than any font a novel needs: the largest, with every Chinese character, are around 20 MB. */
    private const val MAX_BYTES = 40L * 1024 * 1024
}

/** The family to read with: the reader's own font when they chose one that's here, otherwise [ReaderSettings.font]. */
internal fun readingFamily(context: Context, settings: ReaderSettings): FontFamily {
    val own = settings.ownFont?.let { name -> OwnFonts.list(context).firstOrNull { it.name == name } }
    return own?.let(::ownFontFamily) ?: settings.font.family(context.assets)
}

/** An added font as a family: its one file, with bold and italic made from it where it has none. */
@OptIn(ExperimentalTextApi::class)
internal fun ownFontFamily(font: OwnFont): FontFamily = FontFamily(Font(font.file))

/** A font's family name, and its style when it isn't the regular one, as its own name table has them. */
internal data class FontNames(val family: String, val style: String?, val extension: String) {
    val label: String get() = if (style == null) family else "$family $style"

    companion object {
        /**
         * Reads the names from a TrueType or OpenType font (the first, in a collection) through [bytesAt]; null
         * when there aren't any to read.
         */
        fun read(bytesAt: (offset: Long, length: Int) -> ByteArray?): FontNames? {
            val head = bytesAt(0, 12) ?: return null
            val tag = head.u32(0)
            val start = if (tag == TTCF) (bytesAt(12, 4) ?: return null).u32(0) else 0L
            val offsets = bytesAt(start, 12) ?: return null
            val extension = if (offsets.u32(0) == OTTO) "otf" else if (tag == TTCF) "ttc" else "ttf"
            val tables = offsets.u16(4)
            val directory = bytesAt(start + 12, tables * 16) ?: return null
            val record = (0 until tables).map { it * 16 }.firstOrNull { directory.u32(it) == NAME } ?: return null
            val tableOffset = directory.u32(record + 8)
            val tableLength = directory.u32(record + 12).toInt()
            val table = bytesAt(tableOffset, tableLength) ?: return null
            if (table.size < 6) return null
            val count = table.u16(2)
            val strings = table.u16(4)
            val names =
                (0 until count).mapNotNull { i ->
                    val at = 6 + i * 12
                    if (at + 12 > table.size) return@mapNotNull null
                    val platform = table.u16(at)
                    val encoding = table.u16(at + 2)
                    val language = table.u16(at + 4)
                    val id = table.u16(at + 6)
                    val length = table.u16(at + 8)
                    val from = strings + table.u16(at + 10)
                    if (from + length > table.size) return@mapNotNull null
                    val bytes = table.copyOfRange(from, from + length)
                    val text =
                        when {
                            platform == 3 || platform == 0 -> String(bytes, Charsets.UTF_16BE)
                            platform == 1 && encoding == 0 -> String(bytes, Charsets.ISO_8859_1)
                            else -> return@mapNotNull null
                        }.trim()
                    if (text.isEmpty()) null else Name(id, platform, language, text)
                }
            fun best(id: Int): String? =
                names.filter { it.id == id }.minByOrNull { name ->
                    when {
                        name.platform == 3 && name.language == ENGLISH -> 0
                        name.platform == 3 || name.platform == 0 -> 1
                        else -> 2
                    }
                }?.text
            val typographic = best(TYPOGRAPHIC_FAMILY)
            val family = typographic ?: best(FAMILY) ?: return null
            val style = (if (typographic != null) best(TYPOGRAPHIC_STYLE) ?: best(STYLE) else best(STYLE))?.takeUnless { it.lowercase() in REGULAR }
            return FontNames(family, style, extension)
        }

        private data class Name(val id: Int, val platform: Int, val language: Int, val text: String)

        private fun ByteArray.u16(at: Int): Int = ((this[at].toInt() and 0xFF) shl 8) or (this[at + 1].toInt() and 0xFF)

        private fun ByteArray.u32(at: Int): Long = (u16(at).toLong() shl 16) or u16(at + 2).toLong()

        private const val TTCF = 0x74746366L
        private const val OTTO = 0x4F54544FL
        private const val NAME = 0x6E616D65L
        private const val FAMILY = 1
        private const val STYLE = 2
        private const val TYPOGRAPHIC_FAMILY = 16
        private const val TYPOGRAPHIC_STYLE = 17
        private const val ENGLISH = 0x409
        private val REGULAR = setOf("regular", "normal", "roman", "book", "plain", "upright")
    }
}
