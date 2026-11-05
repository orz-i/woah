package art.gaoge.dance.engine.media

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class Mp4FastStartTest {
    private fun bytes(block: DataOutputStream.() -> Unit): ByteArray = ByteArrayOutputStream().also {
        DataOutputStream(it).use(block)
    }.toByteArray()

    private fun box(type: String, payload: ByteArray): ByteArray = bytes {
        writeInt(payload.size + 8)
        writeBytes(type)
        write(payload)
    }

    private fun index(offsets: List<Long>, wide: Boolean = false): ByteArray {
        val table = box(if (wide) "co64" else "stco", bytes {
            writeInt(0)
            writeInt(offsets.size)
            offsets.forEach { if (wide) writeLong(it) else writeInt(it.toInt()) }
        })
        return box("moov", box("trak", box("mdia", box("minf", box("stbl", table)))))
    }

    private fun offsets(bytes: ByteArray, wide: Boolean): List<Long> {
        val at = bytes.toString(Charsets.ISO_8859_1).indexOf(if (wide) "co64" else "stco")
        val buffer = ByteBuffer.wrap(bytes)
        val count = buffer.getInt(at + 8)
        return List(count) { i ->
            if (wide) buffer.getLong(at + 12 + i * 8) else buffer.getInt(at + 12 + i * 4).toLong() and 0xffffffffL
        }
    }

    private fun inDirectory(block: (File) -> Unit) {
        val directory = Files.createTempDirectory("woah-faststart-test").toFile()
        try { block(directory) } finally { directory.deleteRecursively() }
    }

    @Test fun `tail index relocation preserves all sample bytes and updates stco`() = inDirectory { directory ->
        val ftyp = box("ftyp", ByteArray(8))
        val free = box("free", ByteArray(12))
        val samples = ByteArray(32) { (it + 1).toByte() }
        val mdat = box("mdat", samples)
        val start = (ftyp.size + free.size + 8).toLong()
        val moov = index(listOf(start, start + 8, start + 16))
        val original = ftyp + free + mdat + moov
        val source = File(directory, "source.mp4").apply { writeBytes(original) }
        val result = assertNotNull(Mp4FastStart.prepare(source))
        assertTrue(result != source)
        val actual = result.readBytes()
        assertContentEquals(original, source.readBytes())
        assertEquals(original.size, actual.size)
        assertEquals(listOf(start + moov.size, start + 8 + moov.size, start + 16 + moov.size), offsets(actual, false))
        val mediaAt = actual.toString(Charsets.ISO_8859_1).indexOf("mdat") + 4
        assertContentEquals(samples, actual.copyOfRange(mediaAt, mediaAt + samples.size))
        assertEquals("moov", actual.copyOfRange(ftyp.size + 4, ftyp.size + 8).toString(Charsets.ISO_8859_1))
    }

    @Test fun `co64 offsets across multiple media boxes use each actual relocation`() = inDirectory { directory ->
        val ftyp = box("ftyp", ByteArray(8))
        val first = box("mdat", ByteArray(32) { 1 })
        val second = box("mdat", ByteArray(32) { 2 })
        val placeholder = index(listOf(0, 0), wide = true)
        val firstStart = ftyp.size + 8L
        val secondStart = ftyp.size + first.size + placeholder.size + 8L
        val moov = index(listOf(firstStart, secondStart), wide = true)
        val source = File(directory, "source.mp4").apply { writeBytes(ftyp + first + moov + second) }
        val result = assertNotNull(Mp4FastStart.prepare(source))
        assertEquals(listOf(firstStart + moov.size, secondStart), offsets(result.readBytes(), true))
    }

    @Test fun `existing fast start file is returned unchanged without another copy`() = inDirectory { directory ->
        val ftyp = box("ftyp", ByteArray(8))
        val placeholder = index(listOf(0))
        val moov = index(listOf(ftyp.size + placeholder.size + 8L))
        val source = File(directory, "source.mp4").apply { writeBytes(ftyp + moov + box("mdat", ByteArray(32))) }
        val before = source.readBytes()
        assertSame(source, Mp4FastStart.prepare(source))
        assertContentEquals(before, source.readBytes())
        assertEquals(1, directory.listFiles()!!.size)
    }

    @Test fun `cancellation removes only its own copy and preserves the muxed input`() = inDirectory { directory ->
        val source = File(directory, "source.mp4").apply {
            writeBytes(box("ftyp", ByteArray(8)) + box("mdat", ByteArray(32)) + index(listOf(24)))
        }
        val before = source.readBytes()
        var checks = 0
        assertNull(Mp4FastStart.prepare(source) { ++checks >= 3 })
        assertContentEquals(before, source.readBytes())
        assertEquals(listOf("source.mp4"), directory.listFiles()!!.map { it.name })
    }

    @Test fun `invalid chunk reference cannot publish a damaged copy or alter the original`() = inDirectory { directory ->
        val source = File(directory, "source.mp4").apply {
            writeBytes(box("ftyp", ByteArray(8)) + box("mdat", ByteArray(32)) + index(listOf(1)))
        }
        val before = source.readBytes()
        assertFailsWith<IOException> { Mp4FastStart.prepare(source) }
        assertContentEquals(before, source.readBytes())
        assertEquals(1, directory.listFiles()!!.size)
    }
}
