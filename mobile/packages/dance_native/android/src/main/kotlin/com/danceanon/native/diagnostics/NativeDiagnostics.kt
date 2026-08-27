package com.danceanon.native.diagnostics

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import android.os.Process
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedWriter
import java.io.File
import java.io.FileOutputStream
import java.io.FileWriter
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Production native diagnostics infrastructure.
 * Enforces:
 * 1. Async JSONL file writes via dedicated single-thread executor (no hot-path blocking).
 * 2. Synchronous atomic breadcrumb before high-risk native calls (survives SIGSEGV / GPU driver crash).
 * 3. Strict log rotation (8 MiB / session, keep 4 sessions, total <= 32 MiB).
 * 4. ApplicationExitInfo extraction (Android API >= 30).
 * 5. Device & LiteRT environment telemetry with privacy sanitization.
 */
object NativeDiagnostics {
    private const val TAG = "NativeDiagnostics"
    private const val MAX_SESSION_BYTES = 8L * 1024L * 1024L // 8 MiB
    private const val MAX_RETAINED_SESSIONS = 4
    private const val MAX_TRACE_BYTES = 2L * 1024L * 1024L // 2 MiB

    private val initialized = AtomicBoolean(false)
    private var appContext: Context? = null
    private var diagnosticsDir: File? = null

    private val sessionId: String = generateSessionId()
    private val sessionStartTimeMs = System.currentTimeMillis()
    private val bytesWritten = AtomicLong(0L)

    private val writerExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "NativeDiagWriter").apply { isDaemon = true }
    }

    private var currentLogFile: File? = null
    private var currentWriter: BufferedWriter? = null
    private val lock = Any()

    private val isoFormat: SimpleDateFormat
        get() = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }

    fun initialize(context: Context) {
        if (initialized.getAndSet(true)) {
            return
        }

        val appCtx = context.applicationContext ?: context
        appContext = appCtx

        val diagDir = File(appCtx.filesDir, "diagnostics").apply { mkdirs() }
        diagnosticsDir = diagDir

        synchronized(lock) {
            val fileName = "session_${sessionId}.jsonl"
            val logFile = File(diagDir, fileName)
            currentLogFile = logFile
            try {
                currentWriter = BufferedWriter(FileWriter(logFile, true))
            } catch (e: Throwable) {
                Log.e(TAG, "Failed to initialize diagnostic writer: ${e.message}", e)
            }
        }

        // 1. Initial diagnostic event
        event(
            level = "INFO",
            component = "NativeDiagnostics",
            event = "DIAGNOSTICS_INITIALIZED",
            fields = mapOf(
                "session_id" to sessionId,
                "api_level" to Build.VERSION.SDK_INT,
                "model" to Build.MODEL,
                "manufacturer" to Build.MANUFACTURER
            )
        )

        // 2. Extract historical process exits if API >= 30
        captureProcessExitInfo(appCtx, diagDir)

        // 3. Persist initial device info & clean old sessions
        writerExecutor.submit {
            try {
                persistDeviceInfo(appCtx, diagDir)
                rotateLogs(diagDir)
            } catch (t: Throwable) {
                Log.w(TAG, "Background diagnostics maintenance error: ${t.message}")
            }
        }
    }

    fun getCurrentSessionId(): String = sessionId

    fun getDiagnosticsDir(): File? = diagnosticsDir

    fun getCurrentLogFile(): File? = currentLogFile

    /**
     * Non-blocking structured JSONL log event.
     */
    fun event(
        level: String,
        component: String,
        event: String,
        fields: Map<String, Any?> = emptyMap(),
        throwable: Throwable? = null
    ) {
        val now = System.currentTimeMillis()
        val wallTimeStr = isoFormat.format(Date(now))
        val elapsedMs = now - sessionStartTimeMs
        val threadName = Thread.currentThread().name
        val pid = Process.myPid()

        val json = JSONObject()
        try {
            json.put("wall_time", wallTimeStr)
            json.put("elapsed_ms", elapsedMs)
            json.put("session_id", sessionId)
            json.put("pid", pid)
            json.put("thread", threadName)
            json.put("level", level.uppercase(Locale.US))
            json.put("component", component)
            json.put("event", event)

            val fieldsObj = JSONObject()
            for ((k, v) in fields) {
                fieldsObj.put(k, sanitizeValue(v))
            }
            json.put("fields", fieldsObj)

            if (throwable != null) {
                val root = rootCause(throwable)
                json.put("exception_class", throwable.javaClass.name)
                json.put("exception_message", throwable.message ?: "")
                json.put("root_cause_class", root.javaClass.name)
                json.put("root_cause_message", root.message ?: "")
                json.put("cause_chain", buildCauseChain(throwable))

                val sw = StringWriter()
                throwable.printStackTrace(PrintWriter(sw))
                json.put("stack_trace", sw.toString().take(4000))
            }
        } catch (_: Throwable) {
            return
        }

        val line = json.toString()

        writerExecutor.submit {
            writeLogLine(line)
        }

        if (level.equals("CRITICAL", ignoreCase = true)) {
            flushBlocking(300L)
        }
    }

    /**
     * Synchronous atomic breadcrumb written to filesDir/diagnostics/last_breadcrumb.json.
     * Guaranteed to persist before risky native or GPU calls.
     */
    fun breadcrumb(
        component: String,
        stage: String,
        fields: Map<String, Any?> = emptyMap()
    ) {
        val diagDir = diagnosticsDir ?: return
        val now = System.currentTimeMillis()

        try {
            val json = JSONObject().apply {
                put("session_id", sessionId)
                put("wall_time", isoFormat.format(Date(now)))
                put("component", component)
                put("stage", stage)
                val fObj = JSONObject()
                for ((k, v) in fields) {
                    fObj.put(k, sanitizeValue(v))
                }
                put("fields", fObj)
            }

            val targetFile = File(diagDir, "last_breadcrumb.json")
            val tempFile = File(diagDir, "last_breadcrumb.json.tmp.${now}")

            FileOutputStream(tempFile).use { fos ->
                fos.write(json.toString(2).toByteArray(Charsets.UTF_8))
                fos.flush()
            }

            if (tempFile.exists()) {
                if (targetFile.exists()) targetFile.delete()
                if (!tempFile.renameTo(targetFile)) {
                    tempFile.copyTo(targetFile, overwrite = true)
                    tempFile.delete()
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to write breadcrumb: ${t.message}")
        }
    }

    fun flush() {
        writerExecutor.submit {
            synchronized(lock) {
                try {
                    currentWriter?.flush()
                } catch (_: Throwable) {}
            }
        }
    }

    fun flushBlocking(timeoutMs: Long = 500L) {
        val latch = CountDownLatch(1)
        writerExecutor.submit {
            synchronized(lock) {
                try {
                    currentWriter?.flush()
                } catch (_: Throwable) {}
            }
            latch.countDown()
        }
        try {
            latch.await(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (_: Throwable) {}
    }

    fun recordCapabilities(caps: Map<String, Any?>) {
        val diagDir = diagnosticsDir ?: return
        writerExecutor.submit {
            try {
                val json = JSONObject()
                for ((k, v) in caps) {
                    json.put(k, sanitizeValue(v))
                }
                json.put("updated_at", isoFormat.format(Date()))
                json.put("session_id", sessionId)

                val targetFile = File(diagDir, "capabilities.json")
                FileOutputStream(targetFile).use { fos ->
                    fos.write(json.toString(2).toByteArray(Charsets.UTF_8))
                    fos.flush()
                }
            } catch (t: Throwable) {
                Log.w(TAG, "Failed to write capabilities.json: ${t.message}")
            }
        }
    }

    fun recordPipelineSummary(jobId: String, summary: Map<String, Any?>) {
        val diagDir = diagnosticsDir ?: return
        writerExecutor.submit {
            try {
                val json = JSONObject()
                json.put("job_id", jobId)
                json.put("session_id", sessionId)
                json.put("created_at", isoFormat.format(Date()))
                val sObj = JSONObject()
                for ((k, v) in summary) {
                    sObj.put(k, sanitizeValue(v))
                }
                json.put("summary", sObj)

                val targetFile = File(diagDir, "pipeline_summary.json")
                FileOutputStream(targetFile).use { fos ->
                    fos.write(json.toString(2).toByteArray(Charsets.UTF_8))
                    fos.flush()
                }
            } catch (t: Throwable) {
                Log.w(TAG, "Failed to write pipeline_summary.json: ${t.message}")
            }
        }
    }

    fun clearOldDiagnostics() {
        val diagDir = diagnosticsDir ?: return
        writerExecutor.submit {
            try {
                val currentFile = currentLogFile
                val files = diagDir.listFiles() ?: return@submit
                for (f in files) {
                    if (f.name.endsWith(".jsonl") && f != currentFile) {
                        f.delete()
                    }
                }
            } catch (t: Throwable) {
                Log.w(TAG, "Failed to clear old diagnostics: ${t.message}")
            }
        }
    }

    /**
     * Unwraps nested exception wrapper chains to find root cause.
     */
    fun rootCause(t: Throwable): Throwable {
        var curr: Throwable = t
        val seen = mutableSetOf<Throwable>()
        while (curr.cause != null && curr.cause !== curr && !seen.contains(curr.cause)) {
            seen.add(curr)
            curr = curr.cause!!
        }
        return curr
    }

    fun buildCauseChain(t: Throwable): String {
        val sb = StringBuilder()
        var curr: Throwable? = t
        var idx = 0
        val seen = mutableSetOf<Throwable>()
        while (curr != null && !seen.contains(curr)) {
            seen.add(curr)
            if (idx > 0) sb.append(" -> ")
            sb.append("${curr.javaClass.simpleName}: ${curr.message}")
            curr = curr.cause
            idx++
        }
        return sb.toString()
    }

    private fun writeLogLine(line: String) {
        synchronized(lock) {
            try {
                val writer = currentWriter ?: return
                writer.write(line)
                writer.newLine()
                val lineBytes = line.toByteArray(Charsets.UTF_8).size + 1
                bytesWritten.addAndGet(lineBytes.toLong())

                if (bytesWritten.get() > MAX_SESSION_BYTES) {
                    writer.flush()
                }
            } catch (e: Throwable) {
                Log.e(TAG, "Error writing log line: ${e.message}")
            }
        }
    }

    private fun captureProcessExitInfo(context: Context, diagDir: File) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
                val reasons = am?.getHistoricalProcessExitReasons(context.packageName, 0, 5)
                if (reasons != null && reasons.isNotEmpty()) {
                    val rootJson = JSONObject()
                    val array = JSONArray()
                    var traceSaved = false

                    for (info in reasons) {
                        val obj = JSONObject().apply {
                            put("timestamp", info.timestamp)
                            put("pid", info.pid)
                            put("reason", info.reason)
                            put("reason_name", processExitReasonName(info.reason))
                            put("status", info.status)
                            put("importance", info.importance)
                            put("description", info.description ?: "")
                            put("pss_kb", info.pss)
                            put("rss_kb", info.rss)
                        }

                        val traceStream = info.traceInputStream
                        if (!traceSaved && traceStream != null) {
                            try {
                                val traceFile = File(diagDir, "process_exit_trace.txt")
                                traceStream.use { input ->
                                    FileOutputStream(traceFile).use { output ->
                                        val buf = ByteArray(8192)
                                        var total = 0L
                                        var r: Int
                                        while (input.read(buf).also { r = it } != -1 && total < MAX_TRACE_BYTES) {
                                            output.write(buf, 0, r)
                                            total += r
                                        }
                                    }
                                }
                                obj.put("trace_available", true)
                                obj.put("trace_file", "process_exit_trace.txt")
                                traceSaved = true
                            } catch (_: Throwable) {
                                obj.put("trace_available", false)
                            }
                        } else {
                            obj.put("trace_available", false)
                        }

                        array.put(obj)
                    }

                    rootJson.put("process_exits", array)
                    val exitFile = File(diagDir, "process_exit.json")
                    FileOutputStream(exitFile).use { fos ->
                        fos.write(rootJson.toString(2).toByteArray(Charsets.UTF_8))
                    }
                }
            } catch (t: Throwable) {
                Log.w(TAG, "Failed to extract ApplicationExitInfo: ${t.message}")
            }
        }
    }

    private fun processExitReasonName(reason: Int): String {
        return when (reason) {
            ApplicationExitInfo.REASON_ANR -> "REASON_ANR"
            ApplicationExitInfo.REASON_CRASH -> "REASON_CRASH"
            ApplicationExitInfo.REASON_CRASH_NATIVE -> "REASON_CRASH_NATIVE"
            ApplicationExitInfo.REASON_DEPENDENCY_DIED -> "REASON_DEPENDENCY_DIED"
            ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "REASON_EXCESSIVE_RESOURCE_USAGE"
            ApplicationExitInfo.REASON_EXIT_SELF -> "REASON_EXIT_SELF"
            ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "REASON_INITIALIZATION_FAILURE"
            ApplicationExitInfo.REASON_LOW_MEMORY -> "REASON_LOW_MEMORY"
            ApplicationExitInfo.REASON_OTHER -> "REASON_OTHER"
            ApplicationExitInfo.REASON_PERMISSION_CHANGE -> "REASON_PERMISSION_CHANGE"
            ApplicationExitInfo.REASON_SIGNALED -> "REASON_SIGNALED"
            ApplicationExitInfo.REASON_USER_REQUESTED -> "REASON_USER_REQUESTED"
            else -> "REASON_UNKNOWN($reason)"
        }
    }

    private fun persistDeviceInfo(context: Context, diagDir: File) {
        try {
            val json = generateDeviceJson(context)
            val devFile = File(diagDir, "device.json")
            FileOutputStream(devFile).use { fos ->
                fos.write(json.toString(2).toByteArray(Charsets.UTF_8))
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to persist device.json: ${t.message}")
            try {
                val fallback = JSONObject().apply {
                    put("SDK_INT", Build.VERSION.SDK_INT)
                    put("MODEL", Build.MODEL)
                }
                File(diagDir, "device.json").writeText(fallback.toString(2))
            } catch (_: Throwable) {}
        }
    }

    fun generateDeviceJson(context: Context): JSONObject {
        val json = JSONObject()
        val build = JSONObject().apply {
            put("MANUFACTURER", Build.MANUFACTURER)
            put("BRAND", Build.BRAND)
            put("MODEL", Build.MODEL)
            put("DEVICE", Build.DEVICE)
            put("HARDWARE", Build.HARDWARE)
            put("PRODUCT", Build.PRODUCT)
            put("SDK_INT", Build.VERSION.SDK_INT)
            put("RELEASE", Build.VERSION.RELEASE)
            put("FINGERPRINT", Build.FINGERPRINT)
            put("SUPPORTED_ABIS", JSONArray(Build.SUPPORTED_ABIS.toList()))
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                put("SOC_MANUFACTURER", Build.SOC_MANUFACTURER)
                put("SOC_MODEL", Build.SOC_MODEL)
            }
        }
        json.put("build", build)

        try {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            val memory = JSONObject().apply {
                put("availableProcessors", Runtime.getRuntime().availableProcessors())
                put("maxMemoryMb", Runtime.getRuntime().maxMemory() / (1024 * 1024))
                put("totalMemoryMb", Runtime.getRuntime().totalMemory() / (1024 * 1024))
                put("freeMemoryMb", Runtime.getRuntime().freeMemory() / (1024 * 1024))
                if (am != null) {
                    val mi = ActivityManager.MemoryInfo()
                    am.getMemoryInfo(mi)
                    put("availMemMb", mi.availMem / (1024 * 1024))
                    put("totalMemMb", mi.totalMem / (1024 * 1024))
                    put("lowMemory", mi.lowMemory)
                    put("thresholdMb", mi.threshold / (1024 * 1024))
                    put("memoryClass", am.memoryClass)
                    put("largeMemoryClass", am.largeMemoryClass)
                    put("isLowRamDevice", am.isLowRamDevice)
                }
            }
            json.put("memory", memory)
        } catch (_: Throwable) {}

        val app = JSONObject().apply {
            put("packageName", context.packageName)
            try {
                val pm = context.packageManager
                if (pm != null) {
                    val pInfo = pm.getPackageInfo(context.packageName, 0)
                    put("versionName", pInfo.versionName ?: "")
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                        put("versionCode", pInfo.longVersionCode)
                    } else {
                        @Suppress("DEPRECATION")
                        put("versionCode", pInfo.versionCode)
                    }
                }
            } catch (_: Throwable) {}
        }
        json.put("app", app)

        val ml = JSONObject().apply {
            put("litert_version", "2.1.5")
            val modelsDir = File(context.filesDir, "litert_models")
            val sam2ImgFile = File(modelsDir, "sam2_image_features.tflite")
            val sam2Info = JSONObject().apply {
                put("asset_path", "models/litert/sam2_image_features.tflite")
                put("extracted_path", sam2ImgFile.absolutePath)
                put("exists", sam2ImgFile.exists())
                put("length", if (sam2ImgFile.exists()) sam2ImgFile.length() else 0L)
                put("readable", sam2ImgFile.canRead())
                put("last_modified", sam2ImgFile.lastModified())
            }
            put("sam2_image_features", sam2Info)
        }
        json.put("ml_environment", ml)

        return json
    }

    private fun rotateLogs(diagDir: File) {
        val sessionFiles = diagDir.listFiles { _, name ->
            name.startsWith("session_") && name.endsWith(".jsonl")
        }?.sortedByDescending { it.lastModified() } ?: return

        val current = currentLogFile
        var count = 0
        for (file in sessionFiles) {
            if (file == current) continue
            count++
            if (count >= MAX_RETAINED_SESSIONS) {
                file.delete()
            }
        }
    }

    private fun sanitizeValue(value: Any?): Any? {
        if (value == null) return null
        return when (value) {
            is Number, is Boolean, is String -> {
                val str = value.toString()
                if (str.startsWith("content://") || str.startsWith("file://")) {
                    sanitizeUri(str)
                } else {
                    value
                }
            }
            is Map<*, *> -> {
                val obj = JSONObject()
                for ((k, v) in value) {
                    if (k != null) obj.put(k.toString(), sanitizeValue(v))
                }
                obj
            }
            is Collection<*> -> {
                val arr = JSONArray()
                for (item in value) {
                    arr.put(sanitizeValue(item))
                }
                arr
            }
            is Array<*> -> {
                val arr = JSONArray()
                for (item in value) {
                    arr.put(sanitizeValue(item))
                }
                arr
            }
            else -> value.toString()
        }
    }

    fun sanitizeUri(uri: String): String {
        return try {
            val idx = uri.lastIndexOf('/')
            val basename = if (idx >= 0 && idx < uri.length - 1) uri.substring(idx + 1) else "media"
            val scheme = if (uri.contains("://")) uri.substringBefore("://") else "uri"
            val hash = Integer.toHexString(uri.hashCode())
            "${scheme}://[redacted_${hash}]/${basename}"
        } catch (_: Throwable) {
            "uri://[redacted]"
        }
    }

    private fun generateSessionId(): String {
        val df = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }
        val rand = UUID.randomUUID().toString().take(8)
        return "${df.format(Date())}_${rand}"
    }
}
