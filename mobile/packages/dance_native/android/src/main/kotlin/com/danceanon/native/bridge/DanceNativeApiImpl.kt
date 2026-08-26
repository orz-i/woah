package com.danceanon.native.bridge

import android.content.Context
import com.danceanon.native.device.DeviceCapabilities
import com.danceanon.native.jobs.JobManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class DanceNativeApiImpl(
    private val context: Context,
    private val eventEmitter: DanceProcessingEvents? = null
) : DanceNativeApi {

    private val jobManager = JobManager()

    override suspend fun getCapabilities(): NativeCapabilitiesDto = withContext(Dispatchers.Default) {
        val caps = DeviceCapabilities()
        NativeCapabilitiesDto(
            androidApi = caps.androidApi.toLong(),
            gpuSupported = caps.gpuSupported,
            h264Encoder = caps.h264Encoder,
            hevcEncoder = caps.hevcEncoder,
            maxEncodeWidth = caps.maxEncodeWidth.toLong(),
            maxEncodeHeight = caps.maxEncodeHeight.toLong(),
            cpuCores = caps.cpuCores.toLong(),
            recommendedProfile = caps.recommendedProfile
        )
    }

    override suspend fun probeVideo(uri: String): VideoInfoDto = withContext(Dispatchers.IO) {
        if (uri.isBlank()) {
            throw DanceNativeException(
                DanceNativeException.INVALID_ARGUMENT,
                "Video URI is empty"
            )
        }
        try {
            com.danceanon.native.media.VideoProbe.probe(context, uri)
        } catch (e: DanceNativeException) {
            throw e
        } catch (e: Throwable) {
            android.util.Log.e("DanceNativeApiImpl", "Failed to probe video: $uri", e)
            throw DanceNativeException(
                DanceNativeException.VIDEO_OPEN_FAILED,
                "Failed to probe video $uri: ${e.message}",
                e
            )
        }
    }

    private val segmenter = com.danceanon.native.inference.YoloOnnxSegmenter(context)
    private val cacheManager = com.danceanon.native.storage.CacheManager(context)
    private val analyzePipeline = com.danceanon.native.pipeline.AnalyzePipeline(context, segmenter, cacheManager)

    override suspend fun analyzeVideo(request: AnalyzeRequestDto): AnalyzeResultDto = withContext(Dispatchers.Default) {
        if (request.videoUri.isBlank()) {
            throw DanceNativeException(
                DanceNativeException.INVALID_ARGUMENT,
                "videoUri cannot be empty"
            )
        }
        try {
            analyzePipeline.analyze(request)
        } catch (e: DanceNativeException) {
            throw e
        } catch (e: Throwable) {
            android.util.Log.e("DanceNativeApiImpl", "Failed to analyze video: ${request.videoUri}", e)
            throw DanceNativeException(
                DanceNativeException.ANALYSIS_FAILED,
                "Failed to analyze video ${request.videoUri}: ${e.message}",
                e
            )
        }
    }

    override suspend fun getPreviewFrame(request: PreviewRequestDto): PreviewFrameDto = withContext(Dispatchers.Default) {
        PreviewFrameDto(
            thumbnailPath = "",
            timestampMs = request.timestampMs,
            renderTimeMs = 0.0
        )
    }

    private val exportPipeline = com.danceanon.native.pipeline.ExportPipeline(context, segmenter, eventEmitter)
    private val exportScope = kotlinx.coroutines.CoroutineScope(Dispatchers.Default + kotlinx.coroutines.SupervisorJob())

    override suspend fun startExport(request: ExportRequestDto): String = withContext(Dispatchers.Default) {
        val sourceUri = request.sourceUri
        if (sourceUri.isBlank()) {
            throw DanceNativeException(
                DanceNativeException.INVALID_ARGUMENT,
                "sourceUri is empty"
            )
        }
        if (request.outputFilePath.isBlank()) {
            throw DanceNativeException(
                DanceNativeException.INVALID_ARGUMENT,
                "outputFilePath is empty"
            )
        }

        val jobId = "job_${System.currentTimeMillis()}"
        val isCancelled = java.util.concurrent.atomic.AtomicBoolean(false)

        val initialStatus = JobStatusDto(
            jobId = jobId,
            state = "preparing",
            currentFrame = 0L,
            totalFrames = 0L,
            fps = 0.0,
            progress = 0.0,
            outputUri = null,
            errorCode = null,
            errorMessage = null
        )

        val coroutineJob = exportScope.launch(start = kotlinx.coroutines.CoroutineStart.LAZY) {
            try {
                exportPipeline.execute(
                    jobId = jobId,
                    sourceUri = sourceUri,
                    request = request,
                    isCancelled = isCancelled,
                    onStatusChange = { status ->
                        jobManager.updateStatus(jobId, status)
                    }
                )
            } catch (e: Throwable) {
                val stackTraceStr = android.util.Log.getStackTraceString(e)
                android.util.Log.e("DanceNativeApiImpl", "Export failed: $stackTraceStr", e)
                val errorCode = if (e is DanceNativeException) e.code else DanceNativeException.EXPORT_FAILED
                val failedStatus = JobStatusDto(
                    jobId = jobId,
                    state = "failed",
                    currentFrame = 0L,
                    totalFrames = 0L,
                    fps = 0.0,
                    progress = 0.0,
                    outputUri = null,
                    errorCode = errorCode,
                    errorMessage = "${e.javaClass.simpleName}: ${e.message}\n${e.stackTrace.take(8).joinToString("\n")}"
                )
                jobManager.updateStatus(jobId, failedStatus)
                kotlinx.coroutines.CoroutineScope(Dispatchers.Main).launch {
                    try { eventEmitter?.onProgressUpdate(failedStatus) } catch (_: Throwable) {}
                }
            }
        }

        val processingJob = com.danceanon.native.jobs.ProcessingJob(
            id = jobId,
            coroutineJob = coroutineJob,
            isCancelled = isCancelled,
            initialStatus = initialStatus
        )
        jobManager.registerJob(processingJob)
        coroutineJob.start()

        jobId
    }

    override suspend fun cancelJob(jobId: String) = withContext(Dispatchers.Default) {
        jobManager.cancelJob(jobId)
    }

    override suspend fun getJobStatus(jobId: String): JobStatusDto = withContext(Dispatchers.Default) {
        jobManager.getJob(jobId)?.currentStatus ?: JobStatusDto(
            jobId = jobId,
            state = "unknown",
            currentFrame = 0L,
            totalFrames = 0L,
            fps = 0.0,
            progress = 0.0,
            outputUri = null,
            errorCode = "JOB_NOT_FOUND",
            errorMessage = "Job $jobId was not found"
        )
    }

    override suspend fun releaseProject(projectId: String) = withContext(Dispatchers.IO) {
        if (projectId.isNotBlank()) {
            cacheManager.clearAnalysisCache(projectId)
        }
    }

    fun close() {
        try {
            exportScope.cancel()
        } catch (_: Throwable) {}
        try {
            segmenter.close()
        } catch (_: Throwable) {}
    }
}
