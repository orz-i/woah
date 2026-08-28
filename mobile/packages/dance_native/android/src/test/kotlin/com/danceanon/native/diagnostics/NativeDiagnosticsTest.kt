package com.danceanon.native.diagnostics

import org.json.JSONObject
import java.io.File
import java.nio.file.Files
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class NativeDiagnosticsTest {

    @Test
    fun testEventWritesValidJsonLine() {
        val tempDir = Files.createTempDirectory("diag_test").toFile()
        val mockContext = createMockContext(tempDir)

        NativeDiagnostics.initialize(mockContext)

        NativeDiagnostics.event(
            level = "INFO",
            component = "TestComponent",
            event = "TEST_EVENT",
            fields = mapOf(
                "key1" to "value1",
                "count" to 42,
                "uri" to "content://media/external/video/media/12345"
            )
        )

        NativeDiagnostics.flushCriticalNow(1000L)

        val logFile = NativeDiagnostics.getCurrentLogFile()
        assertNotNull(logFile)
        assertTrue(logFile.exists())

        val lines = logFile.readLines().filter { it.isNotBlank() }
        assertTrue(lines.isNotEmpty())

        val lastLine = lines.last()
        val json = JSONObject(lastLine)
        assertEquals("INFO", json.getString("level"))
        assertEquals("TestComponent", json.getString("component"))
        assertEquals("TEST_EVENT", json.getString("event"))

        val fields = json.getJSONObject("fields")
        assertEquals("value1", fields.getString("key1"))
        assertEquals(42, fields.getInt("count"))

        // Ensure URI sanitization (no raw content://media/external path)
        val sanitizedUri = fields.getString("uri")
        assertFalse(sanitizedUri.contains("/external/video/media/12345"))
        assertTrue(sanitizedUri.contains("12345"))
    }

    @Test
    fun testPipelineLifecycleWritesLatestStageAtomically() {
        val tempDir = Files.createTempDirectory("native_diag_lifecycle").toFile()
        val mockContext = object : android.content.ContextWrapper(null) {
            override fun getFilesDir(): File = File(tempDir, "files").apply { mkdirs() }
            override fun getCacheDir(): File = File(tempDir, "cache").apply { mkdirs() }
            override fun getApplicationContext(): android.content.Context = this
            override fun getPackageName(): String = "com.danceanon.woah"
        }

        NativeDiagnostics.initialize(mockContext)
        NativeDiagnostics.recordPipelineLifecycle(
            stage = "EXPORTING",
            jobId = "job-123",
            fields = mapOf("rendered_frames" to 42)
        )
        NativeDiagnostics.flushCriticalNow(1000L)

        val diagDir = NativeDiagnostics.getDiagnosticsDir()
        assertNotNull(diagDir)
        val lifecycle = JSONObject(File(diagDir, "pipeline_lifecycle.json").readText())
        assertEquals("EXPORTING", lifecycle.getString("stage"))
        assertEquals("job-123", lifecycle.getString("job_id"))
        assertEquals(42, lifecycle.getJSONObject("details").getInt("rendered_frames"))
    }

    @Test
    fun testConcurrentEventCallsDoNotCorruptFile() {
        val tempDir = Files.createTempDirectory("diag_concurrent_test").toFile()
        val mockContext = createMockContext(tempDir)

        NativeDiagnostics.initialize(mockContext)

        val executor = Executors.newFixedThreadPool(4)
        val totalEvents = 100

        for (i in 0 until totalEvents) {
            executor.submit {
                NativeDiagnostics.event(
                    level = "DEBUG",
                    component = "Concurrent",
                    event = "EVENT_$i",
                    fields = mapOf("index" to i)
                )
            }
        }

        executor.shutdown()
        executor.awaitTermination(2, TimeUnit.SECONDS)
        NativeDiagnostics.flushCriticalNow(1000L)

        val logFile = NativeDiagnostics.getCurrentLogFile()
        assertNotNull(logFile)
        val lines = logFile.readLines().filter { it.isNotBlank() }

        // Every line must be a parseable valid JSON object
        for (line in lines) {
            val obj = JSONObject(line)
            assertTrue(obj.has("session_id"))
            assertTrue(obj.has("wall_time"))
        }
    }

    @Test
    fun testBreadcrumbAtomicReplace() {
        val tempDir = Files.createTempDirectory("diag_breadcrumb_test").toFile()
        val mockContext = createMockContext(tempDir)

        NativeDiagnostics.initialize(mockContext)

        NativeDiagnostics.breadcrumb(
            component = "SAM2",
            stage = "SAM_GPU_COMPILE",
            fields = mapOf("model" to "sam2_image_features.tflite")
        )

        val diagDir = NativeDiagnostics.getDiagnosticsDir()
        assertNotNull(diagDir)

        val breadcrumbFile = File(diagDir, "last_breadcrumb.json")
        assertTrue(breadcrumbFile.exists())

        val json = JSONObject(breadcrumbFile.readText())
        assertEquals("SAM2", json.getString("component"))
        assertEquals("SAM_GPU_COMPILE", json.getString("stage"))
        assertEquals("sam2_image_features.tflite", json.getJSONObject("fields").getString("model"))
    }

    @Test
    fun testRootCauseUnwrap() {
        val root = IllegalArgumentException("Root cause error")
        val wrapper1 = java.util.concurrent.ExecutionException("Wrapper 1", root)
        val wrapper2 = RuntimeException("Wrapper 2", wrapper1)

        val unwrapped = NativeDiagnostics.rootCause(wrapper2)
        assertEquals(root, unwrapped)
        assertEquals("Root cause error", unwrapped.message)

        val chain = NativeDiagnostics.buildCauseChain(wrapper2)
        assertTrue(chain.contains("RuntimeException"))
        assertTrue(chain.contains("ExecutionException"))
        assertTrue(chain.contains("IllegalArgumentException"))
    }

    private fun createMockContext(filesDir: File): android.content.Context {
        return object : android.content.ContextWrapper(null) {
            override fun getFilesDir(): File = filesDir
            override fun getCacheDir(): File = File(filesDir, "cache").apply { mkdirs() }
            override fun getApplicationContext(): android.content.Context = this
            override fun getPackageName(): String = "com.danceanon.woah"
        }
    }
}
