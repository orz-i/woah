package com.danceanon.native.diagnostics

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import org.json.JSONObject
import org.mockito.Mockito.`when`
import org.mockito.Mockito.mock
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class NativeDiagnosticsSnapshotIntegrityTest {

    private lateinit var tempDir: File
    private lateinit var mockContext: Context

    @BeforeTest
    fun setUp() {
        tempDir = File(System.getProperty("java.io.tmpdir"), "diag_integrity_test_${System.currentTimeMillis()}").apply { mkdirs() }
        mockContext = mock(Context::class.java)
        `when`(mockContext.filesDir).thenReturn(tempDir)
        `when`(mockContext.cacheDir).thenReturn(tempDir)
        `when`(mockContext.applicationContext).thenReturn(mockContext)
        `when`(mockContext.packageName).thenReturn("com.danceanon.app")

        val mockPm = mock(PackageManager::class.java)
        val pInfo = PackageInfo().apply { versionName = "1.0.0" }
        `when`(mockContext.packageManager).thenReturn(mockPm)
        `when`(mockPm.getPackageInfo("com.danceanon.app", 0)).thenReturn(pInfo)

        NativeDiagnostics.initialize(mockContext)
    }

    @AfterTest
    fun tearDown() {
        tempDir.deleteRecursively()
    }

    @Test
    fun testSnapshotIntegrityWithHeavyConcurrentEvents() {
        // Write 600 heavy structured events with stack traces and deep fields
        for (i in 0 until 600) {
            val dummyException = RuntimeException("Synthetic error $i at iteration").apply {
                initCause(IllegalStateException("Root cause error $i"))
            }
            NativeDiagnostics.event(
                level = if (i % 50 == 0) "CRITICAL" else if (i % 10 == 0) "WARN" else "INFO",
                component = "StressTester",
                event = "STRESS_EVENT_$i",
                fields = mapOf(
                    "iteration" to i,
                    "large_payload" to "x".repeat(300),
                    "nested_map" to mapOf("k1" to "v1", "k2" to i * 100),
                    "list_data" to listOf("a", "b", "c", i)
                ),
                throwable = if (i % 20 == 0) dummyException else null
            )
        }

        val stagingDir = File(tempDir, "snapshot_stage").apply { mkdirs() }
        val snapshotFiles = NativeDiagnostics.createConsistentSnapshot(stagingDir, timeoutMs = 5000L)

        assertNotNull(snapshotFiles, "Snapshot barrier must return non-null file list")
        assertTrue(snapshotFiles.isNotEmpty(), "Snapshot files must not be empty")

        var totalJsonLines = 0
        for (file in snapshotFiles) {
            if (file.name.endsWith(".jsonl")) {
                file.forEachLine { line ->
                    if (line.isNotBlank()) {
                        // CRITICAL ASSERTION: Every single line must be 100% valid JSON without truncation or partial boundary
                        val json = JSONObject(line)
                        assertTrue(json.has("wall_time"), "Missing wall_time in line: $line")
                        assertTrue(json.has("event"), "Missing event in line: $line")
                        assertTrue(json.has("session_id"), "Missing session_id in line: $line")
                        totalJsonLines++
                    }
                }
            } else if (file.name.endsWith(".json")) {
                val content = file.readText()
                val json = JSONObject(content)
                assertTrue(json.length() > 0, "JSON file ${file.name} must be valid non-empty JSON")
            }
        }

        assertTrue(totalJsonLines >= 600, "Must contain at least 600 logged JSON lines, found $totalJsonLines")
    }
}
