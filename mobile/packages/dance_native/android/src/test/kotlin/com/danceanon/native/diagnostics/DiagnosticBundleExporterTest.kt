package com.danceanon.native.diagnostics

import com.danceanon.dance_native.BuildConfig
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.nio.file.Files
import java.util.zip.ZipInputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DiagnosticBundleExporterTest {

    @Test
    fun bundleScopeExcludesHistoricalSessionsAndJobs() {
        val session = "20260902_094235_current"
        val job = "job_123"

        assertTrue(DiagnosticBundleExporter.shouldIncludeSnapshotFile("session_${session}_000.jsonl", session, job))
        assertTrue(DiagnosticBundleExporter.shouldIncludeSnapshotFile("session_${session}_001.jsonl", session, job))
        assertFalse(DiagnosticBundleExporter.shouldIncludeSnapshotFile("session_20260901_old_000.jsonl", session, job))

        assertTrue(DiagnosticBundleExporter.shouldIncludeSnapshotFile("pipeline_summary_${job}.json", session, job))
        assertFalse(DiagnosticBundleExporter.shouldIncludeSnapshotFile("pipeline_summary_job_old.json", session, job))

        assertTrue(DiagnosticBundleExporter.shouldIncludeSnapshotFile("yolo_tensor_${job}_0_input.f32s", session, job))
        assertTrue(DiagnosticBundleExporter.shouldIncludeSnapshotFile("yolo_tensor_${job}_cpu_probe_0_output0.f32s", session, job))
        assertTrue(DiagnosticBundleExporter.shouldIncludeSnapshotFile("yolo_tensor_${job}_cpu_mt4_probe_0_output1.f32s", session, job))
        assertFalse(DiagnosticBundleExporter.shouldIncludeSnapshotFile("yolo_tensor_job_old_0_input.f32s", session, job))

        assertTrue(DiagnosticBundleExporter.shouldIncludeSnapshotFile("inference_rgba_${job}_1_0_640x640.rgba", session, job))
        assertFalse(DiagnosticBundleExporter.shouldIncludeSnapshotFile("inference_rgba_job_old_1_0_640x640.rgba", session, job))
        assertTrue(DiagnosticBundleExporter.shouldIncludeSnapshotFile("decoder_yuv_${job}_1_0_64x64_32x32.yuvs", session, job))
        assertFalse(DiagnosticBundleExporter.shouldIncludeSnapshotFile("decoder_yuv_job_old_1_0_64x64_32x32.yuvs", session, job))

        assertTrue(DiagnosticBundleExporter.shouldIncludeSnapshotFile("device.json", session, job))
        assertTrue(DiagnosticBundleExporter.shouldIncludeSnapshotFile("pipeline_lifecycle.json", session, job))
        assertFalse(DiagnosticBundleExporter.shouldIncludeSnapshotFile("unscoped_historical_blob.bin", session, job))
    }

    @Test
    fun previewBundleRejectsAllPerJobArtifactsWhenNoLifecycleJobExists() {
        val session = "20260902_preview_current"
        assertTrue(DiagnosticBundleExporter.shouldIncludeSnapshotFile("session_${session}_000.jsonl", session, null))
        assertTrue(DiagnosticBundleExporter.shouldIncludeSnapshotFile("device.json", session, null))
        assertFalse(DiagnosticBundleExporter.shouldIncludeSnapshotFile("pipeline_summary_job_old.json", session, null))
        assertFalse(DiagnosticBundleExporter.shouldIncludeSnapshotFile("yolo_tensor_job_old_0_input.f32s", session, null))
    }

    @Test
    fun testBundleCreatesValidZipWithRequiredEntries() {
        val tempDir = Files.createTempDirectory("exporter_test").toFile()
        val mockContext = object : android.content.ContextWrapper(null) {
            override fun getFilesDir(): File = File(tempDir, "files").apply { mkdirs() }
            override fun getCacheDir(): File = File(tempDir, "cache").apply { mkdirs() }
            override fun getApplicationContext(): android.content.Context = this
            override fun getPackageName(): String = "com.danceanon.woah"
        }

        NativeDiagnostics.initialize(mockContext)

        NativeDiagnostics.event(
            level = "INFO",
            component = "TestExporter",
            event = "EXPORT_START"
        )
        NativeDiagnostics.recordPipelineLifecycle(
            stage = "PREVIEW",
            fields = mapOf("timestamp_ms" to 0L)
        )
        NativeDiagnostics.breadcrumb("Test", "STAGE_1")
        NativeDiagnostics.flushCriticalNow(1000L)

        val result = DiagnosticBundleExporter.createBundle(mockContext)
        assertNotNull(result)

        val filePath = result["filePath"] as String?
        assertNotNull(filePath)
        val zipFile = File(filePath)
        assertTrue(zipFile.exists(), "Exported ZIP file must exist")
        assertTrue(zipFile.length() > 0L, "ZIP file must be non-empty")

        // Inspect ZIP entries
        val entryNames = mutableSetOf<String>()
        var manifestText: String? = null
        ZipInputStream(FileInputStream(zipFile)).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                entryNames.add(entry.name)
                if (entry.name == "manifest.json") {
                    manifestText = zis.readBytes().toString(Charsets.UTF_8)
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }

        assertTrue(entryNames.contains("manifest.json"), "ZIP must contain manifest.json")
        assertTrue(entryNames.any { it.startsWith("session_") && it.endsWith(".jsonl") }, "ZIP must contain session jsonl log")
        assertTrue(entryNames.contains("last_breadcrumb.json"), "ZIP must contain last_breadcrumb.json")
        assertTrue(entryNames.contains("device.json"), "ZIP must contain device.json")
        assertTrue(entryNames.contains("pipeline_lifecycle.json"), "ZIP must contain pipeline lifecycle state")

        val manifest = JSONObject(assertNotNull(manifestText))
        assertEquals(BuildConfig.GIT_COMMIT_SHA, manifest.getString("git_commit_sha"))
        assertEquals(BuildConfig.BUILD_TIMESTAMP, manifest.getString("build_timestamp"))
        assertTrue(manifest.has("version_name"))
        assertTrue(manifest.has("version_code"))
        assertEquals(false, manifest.getBoolean("export_diagnostics_complete"))
        assertEquals("preview", manifest.getString("diagnostic_scope"))
        assertEquals("PREVIEW", manifest.getString("pipeline_lifecycle_stage"))
        assertTrue(manifest.isNull("pipeline_lifecycle_job_id"))
        assertEquals(0, manifest.getJSONArray("pipeline_summary_files").length())
        assertTrue(manifest.has("snapshot_files_excluded_as_historical"))
    }
}
