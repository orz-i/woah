package art.gaoge.dance.engine.export

import android.content.Context
import art.gaoge.dance.engine.bridge.JobStatusDto
import org.json.JSONArray
import java.io.File

class ExportJobStore(
    private val filesDir: File,
    val diskPersistIntervalMs: Long = 3000L
) {
    constructor(context: Context, diskPersistIntervalMs: Long = 3000L) : this(context.filesDir, diskPersistIntervalMs)

    private val lock = Any()
    private val memoryCache = mutableMapOf<String, ExportJobRecord>()
    private var lastDiskPersistTime = 0L

    var diskWriteCount = 0
        private set

    private val storeFile: File
        get() = File(filesDir, "export_jobs.json")


    init {
        loadFromDisk()
    }

    private fun loadFromDisk() {
        synchronized(lock) {
            try {
                if (storeFile.exists()) {
                    val text = storeFile.readText()
                    if (text.isNotBlank()) {
                        val arr = JSONArray(text)
                        for (i in 0 until arr.length()) {
                            val record = ExportJobRecord.fromJson(arr.getJSONObject(i))
                            memoryCache[record.jobId] = record
                        }
                    }
                }
            } catch (_: Throwable) {
                android.util.Log.w("ExportJobStore", "Failed to load jobs from disk")
            }
        }
    }

    private fun persistToDiskLocked() {
        try {
            val arr = JSONArray()
            memoryCache.values.sortedByDescending { it.createdAt }.take(100).forEach {
                arr.put(it.toJson())
            }
            val tempFile = File(filesDir, "export_jobs.json.tmp")

            tempFile.writeText(arr.toString(2))
            if (tempFile.exists()) {
                if (storeFile.exists()) storeFile.delete()
                tempFile.renameTo(storeFile)
            }
            diskWriteCount++
        } catch (e: Throwable) {
            android.util.Log.e("ExportJobStore", "JOB_PERSIST_FAILED")
        }
    }

    fun saveJob(record: ExportJobRecord) {
        synchronized(lock) {
            val now = System.currentTimeMillis()
            memoryCache[record.jobId] = record.copy(updatedAt = now)
            lastDiskPersistTime = now
            persistToDiskLocked()
        }
    }

    fun getJob(jobId: String): ExportJobRecord? = synchronized(lock) {
        memoryCache[jobId]?.copy()
    }

    fun updateStatus(jobId: String, status: JobStatusDto): Boolean = synchronized(lock) {
        val record = memoryCache[jobId] ?: return@synchronized false
        // Cancellation can publish while the worker is draining an already queued frame.
        // Once observed, a terminal result must survive that worker's late callbacks.
        if (isTerminal(record.state)) return@synchronized false

        record.state = status.state
        record.currentFrame = status.currentFrame
        record.totalFrames = status.totalFrames
        record.fps = status.fps
        record.progress = status.progress
        record.outputUri = status.outputUri ?: record.outputUri
        if (status.errorCode != null || status.errorMessage != null) {
            record.errorCode = art.gaoge.dance.engine.diagnostics.NativeDiagnosticPrivacy.safeCode(status.errorCode)
            record.errorMessage = art.gaoge.dance.engine.diagnostics.NativeDiagnosticPrivacy.message(record.errorCode, "export")
        }
        val now = System.currentTimeMillis()
        record.updatedAt = now
        if (isTerminal(status.state) || (now - lastDiskPersistTime) >= diskPersistIntervalMs) {
            lastDiskPersistTime = now
            persistToDiskLocked()
        }
        true
    }

    private fun isTerminal(state: String): Boolean = when (state) {
        "completed", "failed", "cancelled", "interrupted" -> true
        else -> false
    }

    fun flush() {
        synchronized(lock) {
            lastDiskPersistTime = System.currentTimeMillis()
            persistToDiskLocked()
        }
    }

    fun markActiveJobsAsInterrupted() {
        synchronized(lock) {
            var changed = false
            for (record in memoryCache.values) {
                if (record.state == "processing" || record.state == "preparing" || record.state == "muxing" || record.state == "queued") {
                    record.state = "interrupted"
                    record.errorCode = "EXPORT_INTERRUPTED"
                    record.errorMessage = "Process was terminated while export was running"
                    record.updatedAt = System.currentTimeMillis()
                    changed = true
                }
            }
            if (changed) {
                lastDiskPersistTime = System.currentTimeMillis()
                persistToDiskLocked()
            }
        }
    }

    fun getAllJobs(): List<ExportJobRecord> = synchronized(lock) {
        memoryCache.values.sortedByDescending { it.createdAt }.map { it.copy() }
    }
}
