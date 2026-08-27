package com.danceanon.native.diagnostics

import java.io.File
import java.io.FileInputStream
import java.nio.file.Files
import java.util.zip.ZipInputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class DiagnosticBundleExporterTest {

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
        ZipInputStream(FileInputStream(zipFile)).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                entryNames.add(entry.name)
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }

        assertTrue(entryNames.contains("manifest.json"), "ZIP must contain manifest.json")
        assertTrue(entryNames.any { it.startsWith("session_") && it.endsWith(".jsonl") }, "ZIP must contain session jsonl log")
        assertTrue(entryNames.contains("last_breadcrumb.json"), "ZIP must contain last_breadcrumb.json")
        assertTrue(entryNames.contains("device.json"), "ZIP must contain device.json")
    }
}
