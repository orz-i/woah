package art.gaoge.dance.engine.diagnostics

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import art.gaoge.dance.engine.BuildConfig
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

object DiagnosticBundleExporter {
    private const val TAG = "DiagnosticBundleExporter"

    fun createBundle(context: Context): Map<String, Any?> {
        val appCtx = context.applicationContext ?: context
        val sessionId = NativeDiagnostics.getCurrentSessionId()

        val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }.format(Date())

        val snapshotStagingDir = File(appCtx.cacheDir, "diag_snapshot_${sessionId}_${System.currentTimeMillis()}").apply { mkdirs() }

        // 1. Consistent snapshot barrier: flush & atomic copy in single-thread writer executor
        val stagedFiles = NativeDiagnostics.createConsistentSnapshot(snapshotStagingDir, timeoutMs = 5000L)
        if (stagedFiles == null) {
            try { snapshotStagingDir.deleteRecursively() } catch (_: Throwable) {}
            NativeDiagnostics.event(
                level = "ERROR",
                component = "DiagnosticBundleExporter",
                event = "DIAGNOSTIC_SNAPSHOT_TIMEOUT",
                fields = mapOf("session_id" to sessionId)
            )
            throw IllegalStateException("DIAGNOSTIC_SNAPSHOT_TIMEOUT: Failed to acquire consistent diagnostic snapshot barrier within 5000ms")
        }

        val exportDir = File(appCtx.cacheDir, "diagnostics/export").apply { mkdirs() }
        val zipFileName = "woah_diag_${timeStamp}_${sessionId}.zip"
        val tempZipFile = File(exportDir, zipFileName)

        // 2. Resolve the current lifecycle before selecting bundle contents. The diagnostics
        // directory intentionally retains some historical process/session state, but a bundle
        // must describe exactly one current session/export. Older per-job artifacts can be very
        // large (RGBA/tensor/YUV captures) and must not leak into later bundles.
        val lifecycleFile = stagedFiles.firstOrNull { it.name == "pipeline_lifecycle.json" }
        val lifecycleJson = lifecycleFile?.let {
            try { JSONObject(it.readText(Charsets.UTF_8)) } catch (_: Throwable) { null }
        }
        val lifecycleStage = lifecycleJson?.optString("stage")?.takeIf { it in setOf("PREVIEW", "PREPARING", "EXPORTING", "COMPLETED", "FAILED", "CANCELLED") } ?: "UNKNOWN"
        val lifecycleJobId = lifecycleJson?.optString("job_id")?.takeIf { DiagnosticTextPrivacy.validJob(it) }

        val filesToZip = stagedFiles.filter {
            it.isFile && it.canonicalFile == it.absoluteFile &&
                it.length() > 0L &&
                shouldIncludeSnapshotFile(it.name, sessionId, lifecycleJobId)
        }.toMutableList()
        val excludedSnapshotFileCount = stagedFiles.count {
            it.isFile && it.length() > 0L && !shouldIncludeSnapshotFile(it.name, sessionId, lifecycleJobId)
        }
        // Sanitize staging copies, including historic text written before this boundary.
        // Raw quality artifacts remain byte-identical and debug-only, NOT anonymous media.
        filesToZip.forEach(DiagnosticTextPrivacy::sanitizeStagedText)
        val perJobPipelineSummaries = filesToZip.filter {
            it.name.startsWith("pipeline_summary_") && it.name.endsWith(".json")
        }
        val matchingSummaryExists = if (lifecycleJobId != null) {
            perJobPipelineSummaries.any { it.name == "pipeline_summary_${lifecycleJobId}.json" }
        } else {
            false
        }
        val exportDiagnosticsComplete = lifecycleStage == "COMPLETED" && matchingSummaryExists
        val diagnosticScope = when (lifecycleStage) {
            "PREVIEW" -> "preview"
            "PREPARING", "EXPORTING" -> "export_in_progress"
            "COMPLETED" -> if (exportDiagnosticsComplete) "complete_export" else "completed_missing_summary"
            "FAILED", "CANCELLED" -> "terminal_incomplete_export"
            else -> "unknown_incomplete"
        }

        val manifestJson = JSONObject().apply {
            put("session_id", sessionId)
            put("privacy_scope", "text_redacted_debug_quality_evidence")
            put("contains_raw_media_evidence", filesToZip.any { it.extension in setOf("rgba", "yuvs", "f32s") })
            put("privacy_notice", "Debug bundle may contain identifiable frame/tensor evidence; not an anonymous sharing package.")
            put("created_at", SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
                timeZone = TimeZone.getTimeZone("UTC")
            }.format(Date()))
            put("android_api", Build.VERSION.SDK_INT)
            put("git_commit_sha", BuildConfig.GIT_COMMIT_SHA)
            put("build_timestamp", BuildConfig.BUILD_TIMESTAMP)
            put("export_diagnostics_complete", exportDiagnosticsComplete)
            put("diagnostic_scope", diagnosticScope)
            put("pipeline_lifecycle_stage", lifecycleStage)
            put("pipeline_lifecycle_job_id", lifecycleJobId ?: JSONObject.NULL)
            put("snapshot_files_excluded_as_historical", excludedSnapshotFileCount)
            put(
                "pipeline_summary_files",
                JSONArray().apply { perJobPipelineSummaries.forEach { put(it.name) } }
            )
            try {
                val pInfo = appCtx.packageManager.getPackageInfo(appCtx.packageName, 0)
                val versionName = pInfo.versionName ?: ""
                val versionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    pInfo.longVersionCode
                } else {
                    @Suppress("DEPRECATION")
                    pInfo.versionCode.toLong()
                }
                put("app_version", versionName)
                put("version_name", versionName)
                put("version_code", versionCode)
            } catch (_: Throwable) {
                put("app_version", "unknown")
                put("version_name", "unknown")
                put("version_code", -1L)
            }

            val filesArray = JSONArray()
            for (f in filesToZip) {
                val fObj = JSONObject().apply {
                    put("name", f.name)
                    put("size_bytes", f.length())
                }
                filesArray.put(fObj)
            }
            put("file_list", filesArray)
        }

        val manifestFile = File(snapshotStagingDir, "manifest.json")
        FileOutputStream(manifestFile).use { fos ->
            fos.write(manifestJson.toString(2).toByteArray(Charsets.UTF_8))
        }
        filesToZip.add(0, manifestFile)

        // 3. Compress snapshot files into ZIP
        FileOutputStream(tempZipFile).use { fos ->
            ZipOutputStream(fos).use { zos ->
                val buffer = ByteArray(16 * 1024)
                for (file in filesToZip) {
                    if (!file.exists() || file.length() == 0L) continue
                    val entry = ZipEntry(file.name)
                    zos.putNextEntry(entry)
                    FileInputStream(file).use { fis ->
                        var len: Int
                        while (fis.read(buffer).also { len = it } != -1) {
                            zos.write(buffer, 0, len)
                        }
                    }
                    zos.closeEntry()
                }
            }
        }

        // Clean up snapshot staging directory
        try { snapshotStagingDir.deleteRecursively() } catch (_: Throwable) {}

        // 4. For Android 10+ (API >= 29): publish to MediaStore.Downloads (Downloads/Woah/Diagnostics/)
        var publicUriStr: String? = null
        var finalFilePath = tempZipFile.absolutePath

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                val resolver = try { appCtx.contentResolver } catch (_: Throwable) { null }
                if (resolver != null) {
                    val values = ContentValues().apply {
                        put(MediaStore.Downloads.DISPLAY_NAME, zipFileName)
                        put(MediaStore.Downloads.MIME_TYPE, "application/zip")
                        put(MediaStore.Downloads.RELATIVE_PATH, "Download/Woah/Diagnostics")
                        put(MediaStore.Downloads.IS_PENDING, 1)
                    }

                    val collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                    val itemUri = resolver.insert(collection, values)

                    if (itemUri != null) {
                        resolver.openOutputStream(itemUri).use { outStream ->
                            if (outStream != null) {
                                FileInputStream(tempZipFile).use { inStream ->
                                    inStream.copyTo(outStream)
                                }
                            }
                        }

                        values.clear()
                        values.put(MediaStore.Downloads.IS_PENDING, 0)
                        resolver.update(itemUri, values, null, null)

                        publicUriStr = itemUri.toString()
                    }
                }
            } catch (t: Throwable) {
                Log.w(TAG, "DIAGNOSTIC_PUBLISH_FAILED")
            }
        } else {
            // Android < 29
            try {
                val extDir = appCtx.getExternalFilesDir("diagnostics")
                if (extDir != null) {
                    val destFile = File(extDir, zipFileName)
                    tempZipFile.copyTo(destFile, overwrite = true)
                    finalFilePath = destFile.absolutePath
                    publicUriStr = try { Uri.fromFile(destFile)?.toString() } catch (_: Throwable) { null }
                }
            } catch (t: Throwable) {
                Log.w(TAG, "DIAGNOSTIC_PUBLISH_FAILED")
            }
        }

        NativeDiagnostics.event(
            level = "INFO",
            component = "DiagnosticBundleExporter",
            event = "DIAGNOSTIC_BUNDLE_CREATED",
            fields = mapOf(
                "file_name" to zipFileName,
                "file_path" to finalFilePath,
                "size_bytes" to tempZipFile.length(),
                "public_uri" to (publicUriStr ?: "")
            )
        )

        val fallbackUri = try {
            Uri.fromFile(tempZipFile)?.toString()
        } catch (_: Throwable) {
            null
        } ?: "file://${tempZipFile.absolutePath}"

        return mapOf(
            "filePath" to finalFilePath,
            "fileName" to zipFileName,
            "sizeBytes" to tempZipFile.length(),
            "sessionId" to sessionId,
            "publicUri" to (publicUriStr ?: fallbackUri)
        )
    }

    internal fun shouldIncludeSnapshotFile(
        fileName: String,
        sessionId: String,
        lifecycleJobId: String?
    ): Boolean {
        if (fileName in SHARED_SNAPSHOT_FILES) return true
        if (!DiagnosticTextPrivacy.validSession(sessionId)) return false
        if (Regex("session_${Regex.escape(sessionId)}_[0-9]{3,}\\.jsonl").matches(fileName)) return true
        if (!DiagnosticTextPrivacy.validJob(lifecycleJobId)) return false
        val id = Regex.escape(requireNotNull(lifecycleJobId))
        if (fileName == "pipeline_summary_${lifecycleJobId}.json") return true
        return DiagnosticTextPrivacy.validArtifact(fileName) &&
            Regex("(?:yolo_tensor|inference_rgba|decoder_yuv)_${id}_.*").matches(fileName)
    }

    private val SHARED_SNAPSHOT_FILES = setOf(
        "process_exit.json",
        "device.json",
        "sam_gpu_native_log.txt",
        "capabilities.json",
        "last_breadcrumb.json",
        "pipeline_summary.json",
        "pipeline_lifecycle.json"
    )

    fun shareBundle(
        context: Context,
        filePath: String?,
        publicUriStr: String?
    ): Map<String, Any?> {
        val appCtx = context.applicationContext ?: context

        val uri = if (!publicUriStr.isNullOrBlank()) {
            try { Uri.parse(publicUriStr) } catch (_: Throwable) { null }
        } else if (!filePath.isNullOrBlank()) {
            try { Uri.fromFile(File(filePath)) } catch (_: Throwable) { null }
        } else {
            null
        }

        if (uri == null) {
            return mapOf("success" to false, "error" to "No valid URI or file path provided")
        }

        try {
            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "application/zip"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }

            val chooser = Intent.createChooser(shareIntent, "分享诊断包 / Share Diagnostic Bundle").apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            appCtx.startActivity(chooser)

            return mapOf("success" to true)
        } catch (t: Throwable) {
            Log.e(TAG, "DIAGNOSTIC_SHARE_FAILED")
            return mapOf("success" to false, "error" to (t.message ?: "Unknown error"))
        }
    }

    fun clearLogs(context: Context) {
        NativeDiagnostics.clearOldDiagnostics()
    }
}
