package art.gaoge.dance.engine.diagnostics

import android.content.Context
import android.content.ContextWrapper
import art.gaoge.dance.engine.BuildConfig
import art.gaoge.dance.engine.bridge.*
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.lang.reflect.Proxy
import java.nio.file.Files
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlin.test.*

class DiagnosticPrivacyTest {
    private val secrets = listOf("/Users/测试 用户/私密 舞蹈.mp4",
        "content://vendor/primary%3ADCIM%2F私密%20舞蹈.mp4", "file:///tmp/私密%20舞蹈.mp4",
        "C:\\Users\\私密 视频.mp4", "\\\\server\\share\\私密.mp4", "私密 舞蹈.mp4")

    @Test fun exceptionsAndNonGeneratedChannelRejectArbitraryDescriptions() = runBlocking {
        for (secret in secrets) {
            val original = FlutterError("EXPORT_BUSY", secret, mapOf(secret to listOf(secret)))
            val projected = NativeDiagnosticPrivacy.pigeon(original, "export")
            assertEquals("EXPORT_BUSY", projected.code)
            assertNull(projected.details)
            assertFalse(projected.message.orEmpty().contains(secret))
            assertEquals("NATIVE_OPERATION_FAILED", NativeDiagnosticPrivacy.safeCode(secret))
            val delegate = Proxy.newProxyInstance(DanceNativeApi::class.java.classLoader,
                arrayOf(DanceNativeApi::class.java)) { _, _, _ ->
                throw java.io.IOException(secret, IllegalStateException(secret))
            } as DanceNativeApi
            val error = assertFailsWith<FlutterError> { PrivateDanceNativeApi(delegate).probeVideo(secret) }
            assertEquals("NATIVE_OPERATION_FAILED", error.code)
            assertEquals("stage=probe; code=NATIVE_OPERATION_FAILED", error.message)
            assertNull(error.details)
        }
    }

    @Test fun legacyJobErrorsAreProjectedOnNewWritesWithoutChangingMediaFields() {
        val secret = secrets.first()
        val legacy = JSONObject().put("jobId", "job_123").put("state", "failed")
            .put("sourceUri", secret).put("outputPath", secret).put("outputUri", secret)
            .put("errorCode", secret).put("errorMessage", secret)
        val record = art.gaoge.dance.engine.export.ExportJobRecord.fromJson(legacy)
        val persisted = record.toJson()
        assertEquals(secret, persisted.getString("sourceUri"))
        assertEquals(secret, persisted.getString("outputPath"))
        assertEquals(secret, persisted.getString("outputUri"))
        assertEquals("NATIVE_OPERATION_FAILED", persisted.getString("errorCode"))
        assertFalse(persisted.getString("errorMessage").contains(secret))
        assertFalse(record.toJobStatusDto().errorMessage.orEmpty().contains(secret))
        assertEquals(secret, record.errorMessage) // Projection does not mutate the loaded record.
    }

    @Test fun recursiveFieldsAreBoundedAndNeverStringifyUnknownObjects() {
        val cycle = mutableMapOf<String, Any?>()
        cycle["fields"] = cycle
        val snapshot = NativeDiagnostics.snapshotFields(cycle)
        assertTrue(DiagnosticTextPrivacy.value(snapshot).toString().contains("[redacted]"))
        val bomb = object { override fun toString(): String = error("must not stringify") }
        assertEquals("[redacted]", NativeDiagnostics.snapshotFields(mapOf("value" to bomb))["value"])
        for (secret in secrets) {
            val input = JSONObject().put("fields", JSONObject().put("source_uri", secret)
                .put("description", secret).put("error", JSONObject().put("message", secret))
                .put(secret, secret)).put("event", secret).put("job_id", secret)
            val output = DiagnosticTextPrivacy.json(input).toString()
            assertFalse(output.contains(secret))
        }
        assertEquals("[redacted]", DiagnosticTextPrivacy.text("CPU", "file_path"))
        assertEquals("CRITICAL", DiagnosticTextPrivacy.text("CRITICAL", "level"))
        assertEquals("IO", DiagnosticTextPrivacy.text("IO", "exception_class"))
    }

    @Test fun actualZipRedactsHistoricTextPreservesQualityAndRejectsSourceLinks() {
        val root = Files.createTempDirectory("woah_privacy_fixture").toFile()
        val context = object : ContextWrapper(null) {
            override fun getFilesDir() = File(root, "files").apply { mkdirs() }
            override fun getCacheDir() = File(root, "cache").apply { mkdirs() }
            override fun getApplicationContext(): Context = this
            override fun getPackageName() = "art.gaoge.dance"
        }
        NativeDiagnostics.initialize(context)
        NativeDiagnostics.flushCriticalNow(1000)
        val dir = assertNotNull(NativeDiagnostics.getDiagnosticsDir()).canonicalFile
        val prior = linkedMapOf<String, ByteArray?>()
        fun remember(name: String) {
            if (!prior.containsKey(name)) prior[name] = File(dir, name).takeIf { it.isFile }?.readBytes()
        }
        fun write(name: String, bytes: ByteArray) {
            remember(name); File(dir, name).writeBytes(bytes)
        }
        try {
        val session = NativeDiagnostics.getCurrentSessionId()
        val job = "job_${UUID.randomUUID()}"
        val fixture = JSONObject(javaClass.getResourceAsStream("/privacy/quality_fixture.json")!!
            .bufferedReader().readText().replace("__JOB__", job).replace("__SESSION__", session))
        val events = fixture.getJSONArray("events")
        val eventText = (0 until events.length()).joinToString("\n") {
            events.getJSONObject(it).put("wall_time", "2026-10-31T00:00:00.000Z").toString()
        } + "\n"
        val eventName = "session_${session}_999.jsonl"
        write(eventName, eventText.toByteArray())
        val summaryName = "pipeline_summary_${job}.json"
        val summaryText = JSONObject().put("summary", fixture.getJSONObject("summary")).toString()
        write(summaryName, summaryText.toByteArray())
        remember("pipeline_lifecycle.json")
        NativeDiagnostics.recordPipelineLifecycle("COMPLETED", job)
        NativeDiagnostics.flushCriticalNow(1000)
        val binary = linkedMapOf<String, ByteArray>()
        for (suffix in listOf("", "_cpu_probe", "_cpu_mt4_probe")) {
            binary["yolo_tensor_${job}${suffix}_0_input.f32s"] = byteArrayOf(0, 0, -128, 63)
        }
        binary["inference_rgba_${job}_1_0_2x2.rgba"] = ByteArray(16) { it.toByte() }
        binary["decoder_yuv_${job}_1_0_64x64_32x32.yuvs"] = ByteArray(12) { (it + 3).toByte() }
        binary.forEach { (name, bytes) -> write(name, bytes) }

        val manifest = JSONObject().put("session_id", session)
            .put("pipeline_lifecycle_job_id", job).put("git_commit_sha", BuildConfig.GIT_COMMIT_SHA)
        val before = File(root, "before.zip")
        ZipOutputStream(before.outputStream()).use { zip ->
            fun put(name: String, bytes: ByteArray) {
                zip.putNextEntry(ZipEntry(name)); zip.write(bytes); zip.closeEntry()
            }
            put("manifest.json", manifest.toString().toByteArray())
            put("device.json", File(dir, "device.json").readBytes())
            put(eventName, eventText.toByteArray())
            put(summaryName, summaryText.toByteArray())
            binary.forEach { (name, bytes) -> put(name, bytes) }
        }

        // Only self-created synthetic history. Never touch real application/user diagnostics.
        val secret = secrets.joinToString("|")
        write("capabilities.json", JSONObject().put("description", secret)
            .put("fields", JSONObject().put("error", JSONObject().put("message", secret))).toString().toByteArray())
        write("sam_gpu_native_log.txt", secret.toByteArray())
        write("session_${session}_998.jsonl", (
            JSONObject().put("event", secret).put("fields", JSONObject().put("uri", secret)).toString() +
                "\nBROKEN JSON $secret\n").toByteArray())
        val malicious = "inference_rgba_${job}_private_私密.mp4.rgba"
        write(malicious, secret.toByteArray())
        val outside = File(root, "outside-private.bin").apply { writeText(secret) }
        val link = File(dir, "inference_rgba_${job}_2_0_2x2.rgba")
        remember(link.name)
        Files.createSymbolicLink(link.toPath(), outside.toPath())
        val result = DiagnosticBundleExporter.createBundle(context)
        val after = File(result["filePath"] as String)
        val entries = linkedMapOf<String, ByteArray>()
        ZipInputStream(after.inputStream()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                entries[entry.name] = zip.readBytes()
                zip.closeEntry()
            }
        }
        assertFalse(entries.containsKey(malicious))
        assertFalse(entries.containsKey(link.name))
        binary.forEach { (name, bytes) -> assertContentEquals(bytes, entries[name], name) }
        for ((name, bytes) in entries) {
            assertFalse(name.contains("私密"))
            if (name.endsWith(".json") || name.endsWith(".jsonl") || name.endsWith(".txt")) {
                assertFalse(bytes.toString(Charsets.UTF_8).contains("私密"), name)
            }
        }
        val afterManifest = JSONObject(entries.getValue("manifest.json").toString(Charsets.UTF_8))
        assertTrue(afterManifest.getBoolean("contains_raw_media_evidence"))
        assertEquals("text_redacted_debug_quality_evidence", afterManifest.getString("privacy_scope"))
        val cleanEvents = entries.getValue(eventName).toString(Charsets.UTF_8)
        assertTrue(cleanEvents.contains("${job}_cpu_mt4_probe"))
        assertTrue(cleanEvents.contains("${job}_cpu_probe"))
        val cleanSummary = JSONObject(entries.getValue(summaryName).toString(Charsets.UTF_8)).getJSONObject("summary")
        assertEquals("FULL_EXPORT", cleanSummary.getString("cpu_mt4_signature_scope"))
        assertEquals(1, cleanSummary.getJSONObject("face_detector_calls_by_track_id").getInt("1"))
        assertEquals(2.0, cleanSummary.getJSONObject("stage_timings")
            .getJSONObject("yoloCpuMt4Probe_yoloLiteRtRun").getDouble("avg_ms"))
        assertEquals(secret, outside.readText())
        assertEquals(secret, File(dir, "sam_gpu_native_log.txt").readText())
        System.getenv("WOAH_PRIVACY_FIXTURE_DIR")?.let { target ->
            val output = File(target).apply { mkdirs() }
            before.copyTo(File(output, "before.zip"), overwrite = true)
            after.copyTo(File(output, "after.zip"), overwrite = true)
        }
        // This fixture owns these exact entries; keep native writer's session untouched.
        } finally {
            NativeDiagnostics.flushCriticalNow(1000)
            for ((name, bytes) in prior) {
                val file = File(dir, name)
                Files.deleteIfExists(file.toPath())
                if (bytes != null) file.writeBytes(bytes)
            }
            // The process-wide diagnostics writer may own this first test context.
            if (!dir.toPath().startsWith(root.canonicalFile.toPath())) root.deleteRecursively()
        }
    }
}
