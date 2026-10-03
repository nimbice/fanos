package io.github.nimbice.fanos.core.data.repository

import android.content.Context
import android.content.res.AssetFileDescriptor
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.nimbice.fanos.core.model.Meaning
import io.github.nimbice.fanos.core.model.Sense
import java.io.EOFException
import java.io.File
import java.io.FileInputStream
import java.io.FileNotFoundException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.util.zip.Inflater
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The dictionary built into the app: Open English WordNet's words and phrases (CC BY 4.0), as made by
 * tools/dictionary/make_dictionary.py, which describes the file. It's read where it lies in the app, a block at a time.
 */
@Singleton
class OfflineDictionary @Inject constructor(@ApplicationContext private val context: Context) {
    private val file by lazy { DictionaryFile(reader()) }

    /** The entry for exactly [key], lower case; null when there's none. */
    internal fun entry(key: String): OfflineEntry? = file.entry(key)

    /** The file's bytes, from the app itself when it's stored uncompressed there, or else from a copy made once. */
    private fun reader(): (Long, Int) -> ByteArray {
        val asset: AssetFileDescriptor? =
            try {
                context.assets.openFd(ASSET)
            } catch (e: FileNotFoundException) {
                null
            }
        if (asset != null) return AssetBytes(asset)
        val copy = File(context.noBackupFilesDir, ASSET)
        if (!copy.exists()) {
            copy.parentFile?.mkdirs()
            val partial = File(copy.path + ".part")
            context.assets.open(ASSET).use { input -> partial.outputStream().use { input.copyTo(it) } }
            partial.renameTo(copy)
        }
        val random = RandomAccessFile(copy, "r")
        return { position, length ->
            ByteArray(length).also {
                random.seek(position)
                random.readFully(it)
            }
        }
    }

    /** Bytes of an asset stored uncompressed, read from the app's own file; holding [asset] keeps that open. */
    private class AssetBytes(private val asset: AssetFileDescriptor) : (Long, Int) -> ByteArray {
        private val channel = FileInputStream(asset.fileDescriptor).channel

        override fun invoke(position: Long, length: Int): ByteArray {
            val buffer = ByteBuffer.allocate(length)
            while (buffer.hasRemaining()) if (channel.read(buffer, asset.startOffset + position + buffer.position()) < 0) throw EOFException()
            return buffer.array()
        }
    }

    companion object {
        const val NAME = "Open English WordNet"
        const val LICENCE = "CC BY 4.0"
        const val URL = "https://en-word.net/"
        private const val ASSET = "dictionary/oewn-2025.dict"
    }
}

/** A word in the built-in dictionary: how it's said, its senses by part of speech, and the words it's a form of ("went": go, a verb). */
internal class OfflineEntry(val word: String, val gb: String?, val us: String?, val meanings: List<Meaning>, val formOf: List<Pair<String, String>>)

/** The built-in dictionary's file, its bytes got by [read] (where, how many). */
internal class DictionaryFile(private val read: (Long, Int) -> ByteArray) {
    private val firstKeys: Array<String>
    private val offsets: IntArray
    private val packed: IntArray
    private val sizes: IntArray
    private val dataStart: Long
    private val blocks =
        object : LinkedHashMap<Int, List<String>>(8, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, List<String>>) = size > KEPT_BLOCKS
        }

    init {
        val head = ByteBuffer.wrap(read(0, HEAD))
        val magic = ByteArray(MAGIC.length).also(head::get)
        require(String(magic, Charsets.US_ASCII) == MAGIC) { "Not a Fanos dictionary" }
        require(head.int == VERSION) { "A Fanos dictionary of another version" }
        val count = head.int
        val directory = ByteBuffer.wrap(read(HEAD.toLong(), head.int))
        firstKeys = Array(count) { "" }
        offsets = IntArray(count)
        packed = IntArray(count)
        sizes = IntArray(count)
        for (i in 0 until count) {
            val key = ByteArray(directory.short.toInt() and 0xffff).also(directory::get)
            firstKeys[i] = String(key, Charsets.UTF_8)
            offsets[i] = directory.int
            packed[i] = directory.int
            sizes[i] = directory.int
        }
        dataStart = HEAD.toLong() + directory.capacity()
    }

    fun entry(key: String): OfflineEntry? {
        val fields = line(key)?.split('\t') ?: return null
        if (fields.size < 4) return null
        val said = fields[1].split(UNIT)
        val meanings =
            fields[2].split(RECORD).filter { it.isNotEmpty() }.map { part ->
                val bits = part.split(UNIT)
                Meaning(bits[0], bits.drop(1).map { sense -> sense.split(GROUP).let { Sense(it[0], it.getOrNull(1)?.ifEmpty { null }) } })
            }
        val forms = fields[3].split(UNIT).filter { it.isNotEmpty() }.map { form -> form.split(GROUP).let { it[0] to it.getOrElse(1) { "" } } }
        return OfflineEntry(key, said.getOrNull(0)?.ifEmpty { null }, said.getOrNull(1)?.ifEmpty { null }, meanings, forms)
    }

    /** [key]'s line, from the block it would be in. */
    @Synchronized
    private fun line(key: String): String? {
        val found = firstKeys.binarySearch(key)
        val block = if (found >= 0) found else -found - 2
        if (block < 0) return null
        val prefix = key + "\t"
        return blocks.getOrPut(block) { lines(block) }.firstOrNull { it.startsWith(prefix) }
    }

    private fun lines(block: Int): List<String> {
        val out = ByteArray(sizes[block])
        val inflater = Inflater()
        try {
            inflater.setInput(read(dataStart + offsets[block], packed[block]))
            var done = 0
            while (done < out.size) {
                val n = inflater.inflate(out, done, out.size - done)
                if (n == 0 && (inflater.finished() || inflater.needsInput())) break
                done += n
            }
        } finally {
            inflater.end()
        }
        return String(out, Charsets.UTF_8).split('\n')
    }

    private companion object {
        const val MAGIC = "FANOSDIC"
        const val VERSION = 1
        const val HEAD = 20
        const val KEPT_BLOCKS = 6
        const val RECORD = '\u001e'
        const val UNIT = '\u001f'
        const val GROUP = '\u001d'
    }
}
