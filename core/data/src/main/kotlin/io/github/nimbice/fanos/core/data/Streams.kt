package io.github.nimbice.fanos.core.data

import java.io.InputStream
import java.io.OutputStream

/** Copies up to [limit] bytes, and throws what [tooLong] makes when the stream holds more: then it isn't the file it claims to be. */
internal fun InputStream.copyAtMost(out: OutputStream, limit: Long, tooLong: () -> Exception) {
    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
    var copied = 0L
    while (true) {
        val read = read(buffer)
        if (read < 0) return
        copied += read
        if (copied > limit) throw tooLong()
        out.write(buffer, 0, read)
    }
}
