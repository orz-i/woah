package art.gaoge.dance.engine.export

import android.content.Context
import art.gaoge.dance.engine.bridge.DanceProcessingEvents
import art.gaoge.dance.engine.bridge.ExportRequestDto
import art.gaoge.dance.engine.bridge.JobStatusDto
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

class ExportCoordinator private constructor(private val appContext: Context) {

    val jobStore = ExportJobStore(appContext)
    val resourceGate = PreviewExportResourceGate()
    val cacheRegistry = art.gaoge.dance.engine.storage.RegenerableCacheRegistry(appContext.cacheDir)
    private val cacheLeases = ConcurrentHashMap<String, art.gaoge.dance.engine.storage.RegenerableCacheRegistry.Lease>()
    private val activeCancellations = ConcurrentHashMap<String, AtomicBoolean>()
    private val activeLivePreviews = ConcurrentHashMap<String, AtomicBoolean>()
    private val lifecycles = ConcurrentHashMap<String, art.gaoge.dance.engine.core.FrameKernel.WorkLifecycle>()
    private val pendingTerminals = ConcurrentHashMap<String, JobStatusDto>()
    private val cachedRequests = ConcurrentHashMap<String, ExportRequestDto>()
    private var eventEmitter: DanceProcessingEvents? = null

    init {
        // Startup reconciliation: any jobs left in active state without a running service become interrupted
        jobStore.markActiveJobsAsInterrupted()
        cacheRegistry.pruneInactive()
    }

    fun setEventEmitter(emitter: DanceProcessingEvents?) {
        this.eventEmitter = emitter
    }

    @Synchronized fun registerJobRequest(jobId: String, request: ExportRequestDto): ExportJobRecord {
        val lease = request.analysisCacheId.takeIf { it.isNotBlank() }?.let(cacheRegistry::acquire)
        try {
            val record = ExportJobRecord.fromRequest(jobId, request, initialState = "preparing")
            jobStore.saveJob(record)
            if (lease != null) check(cacheLeases.putIfAbsent(jobId, lease) == null) { "Duplicate export job" }
            cachedRequests[jobId] = request
            lifecycles[jobId] = art.gaoge.dance.engine.core.FrameKernel.WorkLifecycle()
            activeCancellations[jobId] = AtomicBoolean(false)
            activeLivePreviews[jobId] = AtomicBoolean(request.enableLivePreview)
            return record
        } catch (error: Throwable) {
            lease?.close()
            throw error
        }
    }

    fun getRequest(jobId: String): ExportRequestDto? {
        return cachedRequests[jobId]
    }

    fun getCancellationFlag(jobId: String): AtomicBoolean {
        return activeCancellations.getOrPut(jobId) { AtomicBoolean(false) }
    }

    fun getLivePreviewFlag(jobId: String): AtomicBoolean {
        return activeLivePreviews.getOrPut(jobId) { AtomicBoolean(false) }
    }

    fun setLivePreviewEnabled(jobId: String, enabled: Boolean) {
        getLivePreviewFlag(jobId).set(enabled)
    }

    @Synchronized fun cancelJob(jobId: String) {
        val lifecycle = lifecycles[jobId] ?: return
        if (lifecycle.requestCancel()) activeCancellations[jobId]?.set(true)
    }

    fun cancelAllJobs() {
        for (jobId in activeCancellations.keys) {
            cancelJob(jobId)
        }
    }


    private val notifyScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main + kotlinx.coroutines.SupervisorJob())

    @Synchronized fun notifyProgress(status: JobStatusDto): Boolean {
        val lifecycle = lifecycles[status.jobId] ?: return false
        if (lifecycle.observe(status.state)) {
            pendingTerminals.putIfAbsent(status.jobId, status)
            return false
        }
        if (!lifecycle.acceptingProgress) return false
        return publish(status)
    }

    private fun publish(status: JobStatusDto): Boolean {
        if (!jobStore.updateStatus(status.jobId, status)) return false
        notifyScope.launch {
            // A previously accepted progress event may already be queued on Main.
            if (jobStore.getJob(status.jobId)?.state != status.state) return@launch
            try {
                eventEmitter?.onProgressUpdate(status)
            } catch (_: Throwable) {}
        }
        return true
    }

    @Synchronized fun hasActiveRuntime(jobId: String): Boolean = lifecycles.containsKey(jobId)

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

    @Synchronized fun onJobFinished(jobId: String) {
        val lifecycle = lifecycles[jobId] ?: return
        val pending = pendingTerminals[jobId] ?: getJobStatus(jobId)
        val outcome = lifecycle.outcomeAfterRelease()
        if (outcome == "cancelled") {
            check(ExportTerminalStatus.discardCancelledCompletion(pending, outcome)) {
                "Cancelled publication cleanup failed"
            }
        }
        cacheLeases[jobId]?.close()
        cacheLeases.remove(jobId)
        resourceGate.finishExport(jobId)
        val terminal = pending.copy(
            state = lifecycle.release(),
            outputUri = if (outcome == "completed") pending.outputUri else null,
            currentPreviewPath = null,
            errorCode = if (outcome == "failed") pending.errorCode ?: "EXPORT_FAILED" else null,
            errorMessage = if (outcome == "failed") pending.errorMessage ?: "Export worker failed" else null
        )
        activeCancellations.remove(jobId)
        activeLivePreviews.remove(jobId)
        cachedRequests.remove(jobId)
        pendingTerminals.remove(jobId)
        lifecycles.remove(jobId)
        publish(terminal)
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
