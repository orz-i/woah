package art.gaoge.dance.engine.comparison

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException

class ComparisonSelectedSourceCopyTest {
    @TempDir lateinit var temporary: File

    @Test fun copiesExactBytesAndDigestThenClosesInput() {
        val bytes = "abc".toByteArray()
        var closed = false
        val input = object : ByteArrayInputStream(bytes) {
            override fun close() { closed = true; super.close() }
        }
        val destination = File(temporary, "teacher.media")
        val result = ComparisonSelectedSourceCopy.copy(input, destination) { true }
        assertArrayEquals(bytes, destination.readBytes())
        assertEquals(3L, result.bytes)
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", result.sha256)
        assertTrue(closed)
        assertFalse(File(temporary, "teacher.media.part").exists())
    }

    @Test fun cancellationNeverPublishesPartialMedia() {
        val destination = File(temporary, "learner.media")
        var checks = 0
        try {
            ComparisonSelectedSourceCopy.copy(ByteArrayInputStream(ByteArray(150000)), destination) { ++checks < 3 }
            fail("Cancelled copy should fail")
        } catch (_: IllegalStateException) { }
        assertFalse(destination.exists())
        assertFalse(File(temporary, "learner.media.part").exists())
    }

    @Test fun brokenProviderNeverPublishesOrKeepsPartialMedia() {
        val destination = File(temporary, "teacher.media")
        var closed = false
        val input = object : ByteArrayInputStream(ByteArray(150000)) {
            var reads = 0
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                if (++reads == 2) throw IOException("Provider interrupted")
                return super.read(buffer, offset, length)
            }
            override fun close() { closed = true; super.close() }
        }
        try {
            ComparisonSelectedSourceCopy.copy(input, destination) { true }
            fail("Broken provider should fail")
        } catch (_: IOException) { }
        assertTrue(closed)
        assertFalse(destination.exists())
        assertFalse(File(temporary, "teacher.media.part").exists())
    }

    @Test fun anExistingDestinationIsUntouchedAndTheNewInputIsClosed() {
        val destination = File(temporary, "existing.media").apply { writeText("old") }
        val oldPart = File(temporary, "existing.media.part").apply { writeText("other") }
        var closed = false
        val input = object : ByteArrayInputStream("new".toByteArray()) {
            override fun close() { closed = true; super.close() }
        }
        try {
            ComparisonSelectedSourceCopy.copy(input, destination) { true }
            fail("Existing evidence should not be overwritten")
        } catch (_: IllegalStateException) { }
        assertTrue(closed)
        assertEquals("old", destination.readText())
        assertEquals("other", oldPart.readText())
    }

}
