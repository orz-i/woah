package com.danceanon.native.jobs

import com.danceanon.native.bridge.JobStatusDto
import kotlinx.coroutines.Job
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

class ProcessingJob(
    val id: String,
    val coroutineJob: Job,
    val isCancelled: AtomicBoolean = AtomicBoolean(false),
    initialStatus: JobStatusDto? = null
) {
    @Volatile
    var currentStatus: JobStatusDto = initialStatus ?: JobStatusDto(
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

class JobManager(
    private val maxCompletedJobs: Int = 50
) {
    private val jobs = ConcurrentHashMap<String, ProcessingJob>()
    private val completedJobOrder = java.util.concurrent.ConcurrentLinkedQueue<String>()

    fun registerJob(job: ProcessingJob) {
        jobs[job.id] = job
    }

    fun getJob(jobId: String): ProcessingJob? = jobs[jobId]

    fun updateStatus(jobId: String, status: JobStatusDto) {
        val job = jobs[jobId] ?: return
        job.currentStatus = status
        if (status.state == "completed" || status.state == "failed" || status.state == "cancelled") {
            completedJobOrder.add(jobId)
            while (completedJobOrder.size > maxCompletedJobs) {
                val oldJobId = completedJobOrder.poll()
                if (oldJobId != null) {
                    jobs.remove(oldJobId)
                }
            }
        }
    }

    fun cancelJob(jobId: String) {
        jobs[jobId]?.let { job ->
            job.isCancelled.set(true)
            job.coroutineJob.cancel()
            updateStatus(jobId, job.currentStatus.copy(state = "cancelled"))
        }
    }

    fun removeJob(jobId: String) {
        jobs.remove(jobId)
    }
}
