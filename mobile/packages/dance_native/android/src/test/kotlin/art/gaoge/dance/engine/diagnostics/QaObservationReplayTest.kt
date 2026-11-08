package art.gaoge.dance.engine.diagnostics

import art.gaoge.dance.engine.inference.FloatRect
import art.gaoge.dance.engine.inference.NativeMask
import art.gaoge.dance.engine.inference.PersonDetection
import kotlin.test.*
import java.nio.ByteBuffer
import java.nio.file.Files
import java.security.MessageDigest

class QaObservationReplayTest {
    @Test fun roundTripPreservesFloatBitsMasksAndBufferOwnership() {
        val pixels=byteArrayOf(0,96,(-1).toByte(),7)
        val original=PersonDetection(FloatRect(-0.0f,1.25f,17.5f,38f),0.75f,
            NativeMask(2,2,ByteBuffer.allocateDirect(4).apply {put(pixels);flip()},640,480,
                roiInProto=FloatRect(0f,0f,2f,2f)),37.125f)
        val bytes=QaObservationCodec.encode(listOf(original))
        val first=QaObservationCodec.decode(bytes).single()
        val second=QaObservationCodec.decode(bytes).single()
        assertContentEquals(bytes,QaObservationCodec.encode(listOf(first)))
        assertEquals(original.bbox.left.toRawBits(),first.bbox.left.toRawBits())
        first.mask!!.buffer.put(1,0)
        assertEquals(96,second.mask!!.buffer.get(1).toInt())
        assertEquals(96,original.mask!!.buffer.get(1).toInt())
    }

    @Test fun pendingCorruptAndConflictingPayloadsCannotReplay() {
        val directory=Files.createTempDirectory("qa-observation-synthetic-").toFile()
        try {
            val cache=QaObservationReplay(directory,"a".repeat(64))
            val key=cache.key("synthetic","2,2|cpu",floatArrayOf(0f,1f))
            val bytes=byteArrayOf(1,2,3)
            cache.record(key,bytes)
            assertNull(cache.read(key))
            val hash=MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") {"%02x".format(it)}
            directory.resolve("$key.approved").writeText(hash+"\n")
            assertContentEquals(bytes,cache.read(key))
            directory.resolve("$key.bin").writeBytes(byteArrayOf(7))
            assertNull(cache.read(key))
            directory.resolve("$key.bin").writeBytes(bytes)
            cache.record(key,byteArrayOf(4,5))
            directory.resolve("$key.approved").writeText(hash)
            assertNull(cache.read(key))
            assertTrue(directory.resolve("$key.conflict").isFile)
        } finally {directory.deleteRecursively()}
    }

    @Test fun incompatibleInputsAndMalformedDtosAreRejected() {
        val directory=Files.createTempDirectory("qa-observation-key-").toFile()
        try {
            val a=QaObservationReplay(directory,"a".repeat(64))
            val b=QaObservationReplay(directory,"b".repeat(64))
            val key=a.key("face","64,64|cpu",floatArrayOf(0f))
            assertNotEquals(key,a.key("face","64,64|cpu",floatArrayOf(-0.0f)))
            assertNotEquals(key,a.key("face","64,64|gpu",floatArrayOf(0f)))
            assertNotEquals(key,b.key("face","64,64|cpu",floatArrayOf(0f)))
            for(bytes in listOf(byteArrayOf(),byteArrayOf(1,2,3),ByteArray(12){(-1).toByte()})) {
                assertTrue(runCatching {QaObservationCodec.decode(bytes)}.isFailure)
                assertTrue(runCatching {QaObservationCodec.floats(bytes)}.isFailure)
            }
        } finally {directory.deleteRecursively()}
    }
}
