package com.danceanon.native.export

import android.content.Context
import com.danceanon.native.bridge.DanceProcessingEvents
import com.danceanon.native.bridge.ExportRequestDto
import com.danceanon.native.bridge.JobStatusDto
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

class ExportCoordinator private constructor(private val appContext: Context) {

    val jobStore = ExportJobStore(appContext)
    private val activeCancellations = ConcurrentHashMap<String, AtomicBoolean>()
    private val cachedRequests = ConcurrentHashMap<String, ExportRequestDto>()
    private var eventEmitter: DanceProcessingEvents? = null

    init {
        // Startup reconciliation: any jobs left in active state without a running service become interrupted
        jobStore.markActiveJobsAsInterrupted()
    }

    fun setEventEmitter(emitter: DanceProcessingEvents?) {
        this.eventEmitter = emitter
    }

    fun registerJobRequest(jobId: String, request: ExportRequestDto): ExportJobRecord {
        val record = ExportJobRecord.fromRequest(jobId, request, initialState = "preparing")
        jobStore.saveJob(record)
        cachedRequests[jobId] = request
        activeCancellations[jobId] = AtomicBoolean(false)
        return record
    }

    fun getRequest(jobId: String): ExportRequestDto? {
        return cachedRequests[jobId]
    }

    fun getCancellationFlag(jobId: String): AtomicBoolean {
        return activeCancellations.getOrPut(jobId) { AtomicBoolean(false) }
    }

    fun cancelJob(jobId: String) {
        val flag = activeCancellations[jobId]
        if (flag != null) {
            flag.set(true)
        }
        val record = jobStore.getJob(jobId)
        if (record != null && record.state != "completed" && record.state != "failed") {
            val cancelledStatus = record.toJobStatusDto().copy(state = "cancelled")
            jobStore.updateStatus(jobId, cancelledStatus)
            notifyProgress(cancelledStatus)
        }
    }

    fun cancelAllJobs() {
        for (jobId in activeCancellations.keys) {
            cancelJob(jobId)
        }
    }


    private val notifyScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main + kotlinx.coroutines.SupervisorJob())

    fun notifyProgress(status: JobStatusDto) {
        jobStore.updateStatus(status.jobId, status)
        notifyScope.launch {
            try {
                eventEmitter?.onProgressUpdate(status)
            } catch (_: Throwable) {}
        }
    }

    fun getJobStatus(jobId: String): JobStatusDto {
        val record = jobStore.getJob(jobId)
        return record?.toJobStatusDto() ?: JobStatusDto(
            jobId = jobId,
            state = "unknown",
            currentFrame = 0L,
            totalFrames = 0L,
            fps = 0.0,
            progress = 0.0,
            outputUri = null,
            errorCode = "JOB_NOT_FOUND",
            errorMessage = "Job  was not found in job store"
        )
    }

    fun onJobFinished(jobId: String) {
        activeCancellations.remove(jobId)
        cachedRequests.remove(jobId)
    }

    companion object {
        @Volatile
        private var instance: ExportCoordinator? = null

        fun getInstance(context: Context): ExportCoordinator {
            return instance ?: synchronized(this) {
                instance ?: ExportCoordinator(context.applicationContext).also { instance = it }
            }
        }
    }
}
