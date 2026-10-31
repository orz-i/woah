package art.gaoge.dance.engine.media

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GallerySaveTransactionTest {
    private class Backend : GallerySaveBackend {
        val events = mutableListOf<String>()
        val output = ByteArrayOutputStream()
        var insertAction: () -> Unit = {}
        var openAction: () -> OutputStream = { output }
        var publishAction: () -> Unit = {}
        var rollbackAction: () -> Unit = {}
        override fun insert(): String { events.add("insert"); insertAction(); return "owned:new" }
        override fun openOutput(uri: String): OutputStream { assertEquals("owned:new", uri); events.add("open"); return openAction() }
        override fun publish(uri: String) { assertEquals("owned:new", uri); events.add("publish"); publishAction() }
        override fun rollback(uri: String) { assertEquals("owned:new", uri); events.add("rollback"); rollbackAction() }
    }
    private val bytes = ByteArray(150_000) { (it % 251).toByte() }
    private fun failure(backend: Backend, source: () -> InputStream): GallerySaveFailure =
        assertFailsWith { GallerySaveTransaction(backend).save(source, GallerySaveControl()) }

    @Test fun closesBothStreamsBeforePublishingAndPreservesBytes() {
        val backend = Backend()
        var inputClosed = false
        var outputClosed = false
        backend.openAction = { object : OutputStream() {
            override fun write(value: Int) { backend.output.write(value) }
            override fun close() { outputClosed = true }
        } }
        backend.publishAction = { assertTrue(inputClosed); assertTrue(outputClosed) }
        val result = GallerySaveTransaction(backend).save({ object : ByteArrayInputStream(bytes) {
            override fun close() { inputClosed = true; super.close() }
        } }, GallerySaveControl())
        assertEquals("owned:new", result)
        assertContentEquals(bytes, backend.output.toByteArray())
        assertEquals(listOf("insert", "open", "publish"), backend.events)
    }

    @Test fun sourceOpenFailureDoesNotCreateDestination() {
        val backend = Backend()
        assertEquals("SAVE_FAILED", failure(backend) { throw IOException("source unavailable") }.code)
        assertTrue(backend.events.isEmpty())
    }

    @Test fun insertFailureDoesNotDeleteAnyExistingMedia() {
        val backend = Backend().apply { insertAction = { throw SecurityException("denied") } }
        assertEquals("SAVE_FAILED", failure(backend) { ByteArrayInputStream(bytes) }.code)
        assertEquals(listOf("insert"), backend.events)
    }

    @Test fun outputOpenFailureRollsBackOnlyOwnedUri() {
        val backend = Backend().apply { openAction = { throw IOException("output unavailable") } }
        failure(backend) { ByteArrayInputStream(bytes) }
        assertEquals(listOf("insert", "open", "rollback"), backend.events)
    }

    @Test fun readFailureRollsBackOnlyOwnedUri() {
        val backend = Backend()
        failure(backend) { object : InputStream() { override fun read(): Int = throw IOException("read failure") } }
        assertEquals(listOf("insert", "open", "rollback"), backend.events)
    }

    @Test fun fullDiskWriteFailureRollsBackOnlyOwnedUri() {
        val backend = Backend().apply { openAction = {
            object : OutputStream() { override fun write(value: Int) { throw IOException("ENOSPC") } }
        } }
        failure(backend) { ByteArrayInputStream(bytes) }
        assertEquals(listOf("insert", "open", "rollback"), backend.events)
    }

    @Test fun emptySourceNeverPublishesEmptyMedia() {
        val backend = Backend()
        assertEquals("SAVE_FAILED", failure(backend) { ByteArrayInputStream(byteArrayOf()) }.code)
        assertEquals(listOf("insert", "open", "rollback"), backend.events)
    }

    @Test fun flushFailureRollsBackBeforePublish() {
        val backend = Backend().apply { openAction = {
            object : ByteArrayOutputStream() { override fun flush() { throw IOException("flush failed") } }
        } }
        failure(backend) { ByteArrayInputStream(bytes) }
        assertEquals(listOf("insert", "open", "rollback"), backend.events)
    }

    @Test fun outputCloseFailureDoesNotPublish() {
        val backend = Backend().apply { openAction = {
            object : ByteArrayOutputStream() { override fun close() { throw IOException("close output") } }
        } }
        failure(backend) { ByteArrayInputStream(bytes) }
        assertEquals(listOf("insert", "open", "rollback"), backend.events)
    }

    @Test fun inputCloseFailureDoesNotPublish() {
        val backend = Backend()
        failure(backend) { object : ByteArrayInputStream(bytes) { override fun close() { throw IOException("close input") } } }
        assertEquals(listOf("insert", "open", "rollback"), backend.events)
    }

    @Test fun publishFailureRollsBack() {
        val backend = Backend().apply { publishAction = { throw IOException("update returned zero") } }
        failure(backend) { ByteArrayInputStream(bytes) }
        assertEquals(listOf("insert", "open", "publish", "rollback"), backend.events)
    }

    @Test fun preCancelledOperationNeverOpensSource() {
        val backend = Backend()
        val control = GallerySaveControl().also { it.cancel() }
        val error = assertFailsWith<GallerySaveFailure> {
            GallerySaveTransaction(backend).save({ error("must not open") }, control)
        }
        assertEquals("SAVE_CANCELLED", error.code)
        assertTrue(backend.events.isEmpty())
    }

    @Test fun cancellationAfterInsertRollsBackBeforeOpeningOutput() {
        val control = GallerySaveControl()
        val backend = Backend().apply { insertAction = { control.cancel() } }
        val error = assertFailsWith<GallerySaveFailure> {
            GallerySaveTransaction(backend).save({ ByteArrayInputStream(bytes) }, control)
        }
        assertEquals("SAVE_CANCELLED", error.code)
        assertEquals(listOf("insert", "rollback"), backend.events)
    }

    @Test fun cancellationAfterCopyButBeforeCommitRollsBack() {
        val backend = Backend()
        val control = GallerySaveControl()
        val error = assertFailsWith<GallerySaveFailure> {
            GallerySaveTransaction(backend).save({ object : ByteArrayInputStream(bytes) {
                override fun close() { control.cancel() }
            } }, control)
        }
        assertEquals("SAVE_CANCELLED", error.code)
        assertEquals(listOf("insert", "open", "rollback"), backend.events)
    }

    @Test fun cancellationAfterCommitStartsCannotDeletePublishedAsset() {
        val backend = Backend()
        val control = GallerySaveControl()
        backend.publishAction = { assertFalse(control.cancel()) }
        assertEquals("owned:new", GallerySaveTransaction(backend).save({ ByteArrayInputStream(bytes) }, control))
        assertFalse(backend.events.contains("rollback"))
    }

    @Test fun rollbackFailureIsExplicitAndRetainsBothCauses() {
        val backend = Backend().apply {
            openAction = { throw IOException("write cause") }
            rollbackAction = { throw SecurityException("delete rejected") }
        }
        val error = failure(backend) { ByteArrayInputStream(bytes) }
        assertEquals("SAVE_ROLLBACK_FAILED", error.code)
        assertTrue(error.cause is IOException)
        assertTrue(error.suppressed.single() is SecurityException)
        assertEquals(1, backend.events.count { it == "rollback" })
    }

    @Test fun failedSaveNeverChangesSyntheticSourceFile() {
        val source = Files.createTempFile("woah-g2-b-source-", ".mp4").toFile()
        try {
            source.writeBytes(bytes)
            val backend = Backend().apply { publishAction = { throw IOException("publish failed") } }
            failure(backend) { source.inputStream() }
            assertTrue(source.exists())
            assertContentEquals(bytes, source.readBytes())
        } finally { source.delete() }
    }
}
