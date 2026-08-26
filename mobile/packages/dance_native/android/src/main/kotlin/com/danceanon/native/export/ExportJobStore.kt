package com.danceanon.native.export

import android.content.Context
import com.danceanon.native.bridge.JobStatusDto
import org.json.JSONArray
import java.io.File
import java.util.concurrent.ConcurrentHashMap

class ExportJobStore(
    private val filesDir: File,
    val diskPersistIntervalMs: Long = 3000L
) {
    constructor(context: Context, diskPersistIntervalMs: Long = 3000L) : this(context.filesDir, diskPersistIntervalMs)

    private val lock = Any()
    private val memoryCache = ConcurrentHashMap<String, ExportJobRecord>()
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
            android.util.Log.e("ExportJobStore", "Failed to persist jobs to disk: ${e.message}")
        }
    }

    fun saveJob(record: ExportJobRecord) {
        synchronized(lock) {
            record.updatedAt = System.currentTimeMillis()
            memoryCache[record.jobId] = record
            lastDiskPersistTime = System.currentTimeMillis()
            persistToDiskLocked()
        }
    }

    fun getJob(jobId: String): ExportJobRecord? {
        return memoryCache[jobId]
    }

    fun updateStatus(jobId: String, status: JobStatusDto) {
        synchronized(lock) {
            val record = memoryCache[jobId] ?: return
            record.state = status.state
            record.currentFrame = status.currentFrame
            record.totalFrames = status.totalFrames
            record.fps = status.fps
            record.progress = status.progress
            record.outputUri = status.outputUri ?: record.outputUri
            record.errorCode = status.errorCode ?: record.errorCode
            record.errorMessage = status.errorMessage ?: record.errorMessage
            val now = System.currentTimeMillis()
            record.updatedAt = now

            val isTerminal = when (status.state.lowercase()) {
                "completed", "failed", "cancelled", "interrupted" -> true
                else -> false
            }

            if (isTerminal || (now - lastDiskPersistTime) >= diskPersistIntervalMs) {
                lastDiskPersistTime = now
                persistToDiskLocked()
            }
        }
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

    fun getAllJobs(): List<ExportJobRecord> {
        return memoryCache.values.sortedByDescending { it.createdAt }
    }
}
