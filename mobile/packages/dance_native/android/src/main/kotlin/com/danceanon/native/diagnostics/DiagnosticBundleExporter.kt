package com.danceanon.native.diagnostics

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
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
        NativeDiagnostics.flushBlocking(500L)

        val diagDir = File(appCtx.filesDir, "diagnostics")
        val exportDir = File(appCtx.cacheDir, "diagnostics/export").apply { mkdirs() }
        val sessionId = NativeDiagnostics.getCurrentSessionId()

        val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }.format(Date())

        val zipFileName = "woah_diag_${timeStamp}_${sessionId}.zip"
        val tempZipFile = File(exportDir, zipFileName)

        // 1. Gather all diagnostic files
        val filesToZip = mutableListOf<File>()
        if (diagDir.exists() && diagDir.isDirectory) {
            val diagFiles = diagDir.listFiles() ?: emptyArray()
            for (f in diagFiles) {
                if (f.isFile && !f.name.endsWith(".tmp")) {
                    filesToZip.add(f)
                }
            }
        }

        // 2. Generate manifest.json
        val manifestJson = JSONObject().apply {
            put("session_id", sessionId)
            put("created_at", SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
                timeZone = TimeZone.getTimeZone("UTC")
            }.format(Date()))
            put("android_api", Build.VERSION.SDK_INT)
            try {
                val pInfo = appCtx.packageManager.getPackageInfo(appCtx.packageName, 0)
                put("app_version", pInfo.versionName ?: "")
            } catch (_: Throwable) {
                put("app_version", "unknown")
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

        val manifestFile = File(exportDir, "manifest.json")
        FileOutputStream(manifestFile).use { fos ->
            fos.write(manifestJson.toString(2).toByteArray(Charsets.UTF_8))
        }
        filesToZip.add(0, manifestFile)

        // 3. Compress files into ZIP
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

        try { manifestFile.delete() } catch (_: Throwable) {}

        // 4. For Android 10+ (API >= 29): publish to MediaStore.Downloads (Downloads/DanceAnon/Diagnostics/)
        var publicUriStr: String? = null
        var finalFilePath = tempZipFile.absolutePath

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                val resolver = try { appCtx.contentResolver } catch (_: Throwable) { null }
                if (resolver != null) {
                    val values = ContentValues().apply {
                        put(MediaStore.Downloads.DISPLAY_NAME, zipFileName)
                        put(MediaStore.Downloads.MIME_TYPE, "application/zip")
                        put(MediaStore.Downloads.RELATIVE_PATH, "Download/DanceAnon/Diagnostics")
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
                Log.w(TAG, "Failed to publish diagnostic bundle to MediaStore: ${t.message}")
            }
        } else {
            // Android < 29
            try {
                val extDir = appCtx.getExternalFilesDir("diagnostics")
                if (extDir != null) {
                    val destFile = File(extDir, zipFileName)
                    tempZipFile.copyTo(destFile, overwrite = true)
                    finalFilePath = destFile.absolutePath
                    publicUriStr = Uri.fromFile(destFile).toString()
                }
            } catch (t: Throwable) {
                Log.w(TAG, "Failed to save diagnostic bundle to external files: ${t.message}")
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

    fun shareBundle(
        context: Context,
        filePath: String?,
        publicUriStr: String?
    ): Map<String, Any?> {
        val appCtx = context.applicationContext ?: context

        val uri = if (!publicUriStr.isNullOrBlank()) {
            Uri.parse(publicUriStr)
        } else if (!filePath.isNullOrBlank()) {
            Uri.fromFile(File(filePath))
        } else {
            null
        }

        if (uri == null) {
            return mapOf("success" to false, "error" to "No valid URI or file path provided")
        }

        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = "application/zip"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        val chooser = Intent.createChooser(shareIntent, "分享诊断包").apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        try {
            appCtx.startActivity(chooser)
            NativeDiagnostics.event(
                level = "INFO",
                component = "DiagnosticBundleExporter",
                event = "DIAGNOSTIC_BUNDLE_SHARED",
                fields = mapOf("uri" to uri.toString())
            )
            return mapOf(
                "success" to true,
                "uri" to uri.toString()
            )
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to start share chooser: ${t.message}")
            return mapOf(
                "success" to false,
                "error" to (t.message ?: "Failed to open share activity"),
                "uri" to uri.toString()
            )
        }
    }

    fun clearLogs(context: Context) {
        NativeDiagnostics.clearOldDiagnostics()
    }
}
