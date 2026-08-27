package com.danceanon.native.diagnostics

import android.content.Context
import org.mockito.Mockito.`when`
import org.mockito.Mockito.mock
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertTrue

class NativeDiagnosticsNoHotPathBlockingTest {

    private lateinit var tempDir: File
    private lateinit var mockContext: Context

    @BeforeTest
    fun setUp() {
        tempDir = File(System.getProperty("java.io.tmpdir"), "diag_noblock_test_${System.currentTimeMillis()}").apply { mkdirs() }
        mockContext = mock(Context::class.java)
        `when`(mockContext.filesDir).thenReturn(tempDir)
        `when`(mockContext.cacheDir).thenReturn(tempDir)
        `when`(mockContext.applicationContext).thenReturn(mockContext)
        `when`(mockContext.packageName).thenReturn("com.danceanon.app")

        NativeDiagnostics.initialize(mockContext)
    }

    @AfterTest
    fun tearDown() {
        tempDir.deleteRecursively()
    }

    @Test
    fun testCriticalEventDoesNotBlockCallingThread() {
        val t0 = System.currentTimeMillis()
        for (i in 0 until 50) {
            NativeDiagnostics.event(
                level = "CRITICAL",
                component = "HotPathModule",
                event = "SELECTED_TARGET_MISSING",
                fields = mapOf("target_id" to 4, "frame" to i)
            )
        }
        val elapsedMs = System.currentTimeMillis() - t0

        // 50 calls must execute well under 100ms since none should block for flush
        assertTrue(elapsedMs < 100L, "50 CRITICAL events took ${elapsedMs}ms, must be non-blocking (<100ms)")
    }
}
