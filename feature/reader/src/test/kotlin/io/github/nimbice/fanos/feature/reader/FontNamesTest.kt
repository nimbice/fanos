package io.github.nimbice.fanos.feature.reader

import org.junit.Test
import java.io.ByteArrayOutputStream
import kotlin.test.assertEquals
import kotlin.test.assertNull

class FontNamesTest {

    @Test
    fun `a font is named for its family, and its style unless that's the regular one`() {
        val regular = font(listOf(Record(3, 1, 0x409, 1, "Palatino Linotype"), Record(3, 1, 0x409, 2, "Regular")))
        val bold = font(listOf(Record(3, 1, 0x409, 1, "Palatino Linotype"), Record(3, 1, 0x409, 2, "Bold Italic")))

        assertEquals("Palatino Linotype", names(regular)?.label)
        assertEquals("Palatino Linotype Bold Italic", names(bold)?.label)
        assertEquals("ttf", names(regular)?.extension)
    }

    @Test
    fun `the typographic family comes first, and Windows English names before the others`() {
        val records =
            listOf(
                Record(1, 0, 0, 1, "Mac Name"),
                Record(3, 1, 0x40C, 1, "Nom"),
                Record(3, 1, 0x409, 1, "Literata 12pt"),
                Record(3, 1, 0x409, 2, "Regular"),
                Record(3, 1, 0x409, 16, "Literata"),
                Record(3, 1, 0x409, 17, "Light"),
            )

        assertEquals("Literata Light", names(font(records, otf = true))?.label)
        assertEquals("otf", names(font(records, otf = true))?.extension)
        assertEquals("Mac Name", names(font(listOf(Record(1, 0, 0, 1, "Mac Name"))))?.label)
    }

    @Test
    fun `a file that isn't a font has no names`() {
        assertNull(names("not a font at all, just some text".toByteArray()))
        assertNull(names(ByteArray(4)))
    }

    private fun names(bytes: ByteArray): FontNames? =
        FontNames.read { offset, length -> if (offset < 0 || offset + length > bytes.size) null else bytes.copyOfRange(offset.toInt(), offset.toInt() + length) }

    private data class Record(val platform: Int, val encoding: Int, val language: Int, val id: Int, val text: String)

    /** A font file with nothing in it but a name table. */
    private fun font(records: List<Record>, otf: Boolean = false): ByteArray {
        val strings = ByteArrayOutputStream()
        val table = ByteArrayOutputStream()
        val encoded = records.map { if (it.platform == 1) it.text.toByteArray(Charsets.ISO_8859_1) else it.text.toByteArray(Charsets.UTF_16BE) }
        table.u16(0)
        table.u16(records.size)
        table.u16(6 + records.size * 12)
        records.forEachIndexed { i, record ->
            table.u16(record.platform)
            table.u16(record.encoding)
            table.u16(record.language)
            table.u16(record.id)
            table.u16(encoded[i].size)
            table.u16(strings.size())
            strings.write(encoded[i])
        }
        table.write(strings.toByteArray())
        val name = table.toByteArray()
        val out = ByteArrayOutputStream()
        out.u32(if (otf) 0x4F54544F else 0x00010000)
        out.u16(1)
        out.u16(16)
        out.u16(0)
        out.u16(0)
        out.write("name".toByteArray())
        out.u32(0)
        out.u32(12 + 16)
        out.u32(name.size)
        out.write(name)
        return out.toByteArray()
    }

    private fun ByteArrayOutputStream.u16(value: Int) {
        write(value shr 8 and 0xFF)
        write(value and 0xFF)
    }

    private fun ByteArrayOutputStream.u32(value: Int) {
        u16(value ushr 16)
        u16(value and 0xFFFF)
    }
}
