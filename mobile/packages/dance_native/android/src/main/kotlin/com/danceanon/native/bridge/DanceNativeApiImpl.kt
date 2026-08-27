package com.danceanon.native.bridge

import android.content.Context
import com.danceanon.native.device.DeviceCapabilities
import com.danceanon.native.export.ExportCoordinator
import com.danceanon.native.export.ExportServiceController
import com.danceanon.native.sam2.Sam2GpuCapabilityManager
import com.danceanon.native.sam2.Sam2GpuState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class DanceNativeApiImpl(
    private val context: Context,
    private val eventEmitter: DanceProcessingEvents? = null
) : DanceNativeApi {

    private val coordinator = ExportCoordinator.getInstance(context)

    init {
        coordinator.setEventEmitter(eventEmitter)
    }

    override suspend fun getCapabilities(): NativeCapabilitiesDto = withContext(Dispatchers.Default) {
        val caps = DeviceCapabilities.detect(context)

        val sam2State = try {
            Sam2GpuCapabilityManager.probe(context)
        } catch (e: Throwable) {
            android.util.Log.w("DanceNativeApiImpl", "SAM2 capability probe failed: ${e.message}")
            Sam2GpuState.UNAVAILABLE
        }

        val supportedProfiles = mutableListOf("quality", "balanced", "speed")
        if (sam2State == Sam2GpuState.AVAILABLE) {
            supportedProfiles.add("sam2")
        }

        val backends = mutableListOf<String>()
        if (caps.gpuSupported) {
            backends.add("litert_gpu")
        }
        backends.add("litert_cpu")

        NativeCapabilitiesDto(
            platform = "android",
            osVersion = caps.androidApi.toString(),
            gpuSupported = caps.gpuSupported,
            h264Encoder = caps.h264Encoder,
            hevcEncoder = caps.hevcEncoder,
            maxEncodeWidth = caps.maxEncodeWidth.toLong(),
            maxEncodeHeight = caps.maxEncodeHeight.toLong(),
            cpuCores = caps.cpuCores.toLong(),
            recommendedProfile = caps.recommendedProfile,
            supportedProfiles = supportedProfiles,
            inferenceBackends = backends
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

    private val segmenter = com.danceanon.native.inference.YoloLiteRtSegmenter(context)
    private val cacheManager = com.danceanon.native.storage.CacheManager(context)
    private val analyzePipeline = com.danceanon.native.pipeline.AnalyzePipeline(context, segmenter, cacheManager)
    private val previewPipeline = com.danceanon.native.pipeline.PreviewPipeline(context, segmenter, cacheManager)

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
        if (request.analysisCacheId.isBlank()) {
            throw DanceNativeException(
                DanceNativeException.INVALID_ARGUMENT,
                "analysisCacheId cannot be empty"
            )
        }
        try {
            previewPipeline.renderPreview(request)
        } catch (e: DanceNativeException) {
            throw e
        } catch (e: Throwable) {
            android.util.Log.e("DanceNativeApiImpl", "Failed to render preview frame: ${e.message}", e)
            throw DanceNativeException(
                DanceNativeException.RENDER_FAILED,
                "Failed to render preview frame: ${e.message}",
                e
            )
        }
    }

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

        // Native Hard Gate: if requested profile is sam2, verify SAM2 GPU capability is AVAILABLE
        if (request.processingProfile.equals("sam2", ignoreCase = true)) {
            if (!Sam2GpuCapabilityManager.isAvailable()) {
                throw DanceNativeException(
                    DanceNativeException.SAM2_GPU_UNAVAILABLE,
                    "SAM2 requires a verified LiteRT GPU accelerator on this device."
                )
            }
        }

        val jobId = "job_${System.currentTimeMillis()}"

        // 1. Persist initial request and state into ExportJobStore
        coordinator.registerJobRequest(jobId, request)

        try {
            // 2. Trigger ForegroundService to take ownership of execution
            ExportServiceController.startExportService(context, jobId)
        } catch (e: Throwable) {
            android.util.Log.e("DanceNativeApiImpl", "Failed to start export foreground service: ${e.message}", e)
            val failedStatus = JobStatusDto(
                jobId = jobId,
                state = "failed",
                currentFrame = 0L,
                totalFrames = 0L,
                fps = 0.0,
                progress = 0.0,
                outputUri = null,
                errorCode = DanceNativeException.EXPORT_FAILED,
                errorMessage = "Failed to start export service: ${e.message}"
            )
            coordinator.jobStore.updateStatus(jobId, failedStatus)
            coordinator.onJobFinished(jobId)
            throw DanceNativeException(
                DanceNativeException.EXPORT_FAILED,
                "Could not start export foreground service: ${e.message}",
                e
            )
        }

        // 3. Immediately return jobId to Flutter caller
        jobId
    }


    override suspend fun cancelJob(jobId: String) = withContext(Dispatchers.Default) {
        coordinator.cancelJob(jobId)
    }

    override suspend fun getJobStatus(jobId: String): JobStatusDto = withContext(Dispatchers.Default) {
        coordinator.getJobStatus(jobId)
    }

    override suspend fun releaseProject(projectId: String) = withContext(Dispatchers.IO) {
        if (projectId.isNotBlank()) {
            cacheManager.clearAnalysisCache(projectId)
            previewPipeline.clearForAnalysis(projectId)
        }
    }

    fun close() {
        coordinator.setEventEmitter(null)
        try {
            previewPipeline.clear()
        } catch (_: Throwable) {}
        try {
            segmenter.close()
        } catch (_: Throwable) {}
    }
}


