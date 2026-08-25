package com.danceanon.native.jobs

import com.danceanon.native.bridge.JobStatusDto
import kotlinx.coroutines.Job
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

class ProcessingJob(
    val id: String,
    val coroutineJob: Job,
    val isCancelled: AtomicBoolean = AtomicBoolean(false)
) {
    @Volatile
    var currentStatus: JobStatusDto = JobStatusDto(
        jobId = id,
        state = "queued",
        currentFrame = 0,
        totalFrames = 0,
        fps = 0.0,
        progress = 0.0,
        outputUri = null,
        errorCode = null,
        errorMessage = null
    )
}

class JobManager {
    private val jobs = ConcurrentHashMap<String, ProcessingJob>()

    fun registerJob(job: ProcessingJob) {
        jobs[job.id] = job
    }

    fun getJob(jobId: String): ProcessingJob? = jobs[jobId]

    fun updateStatus(jobId: String, status: JobStatusDto) {
        jobs[jobId]?.currentStatus = status
    }

    fun cancelJob(jobId: String) {
        jobs[jobId]?.let { job ->
            job.isCancelled.set(true)
            job.coroutineJob.cancel()
            job.currentStatus = job.currentStatus.copy(state = "cancelled")
        }
    }

    fun removeJob(jobId: String) {
        jobs.remove(jobId)
    }
}
