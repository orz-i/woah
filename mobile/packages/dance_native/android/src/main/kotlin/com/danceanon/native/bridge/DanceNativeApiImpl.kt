package com.danceanon.native.bridge

import android.content.Context
import com.danceanon.native.device.DeviceCapabilities
import com.danceanon.native.jobs.JobManager
import kotlinx.coroutines.Dispatchers
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
        try {
            com.danceanon.native.media.VideoProbe.probe(context, uri)
        } catch (e: Exception) {
            android.util.Log.e("DanceNativeApiImpl", "Failed to probe video: $uri", e)
            VideoInfoDto(
                codedWidth = 1920L,
                codedHeight = 1080L,
                displayWidth = 1920L,
                displayHeight = 1080L,
                fps = 30.0,
                durationMs = 0L,
                rotation = 0L,
                videoCodec = "video/avc",
                audioCodec = null,
                hasAudio = false
            )
        }
    }

    private val segmenter = com.danceanon.native.inference.YoloLiteRtSegmenter(context)
    private val cacheManager = com.danceanon.native.storage.CacheManager(context)
    private val analyzePipeline = com.danceanon.native.pipeline.AnalyzePipeline(context, segmenter, cacheManager)

    override suspend fun analyzeVideo(request: AnalyzeRequestDto): AnalyzeResultDto = withContext(Dispatchers.Default) {
        try {
            analyzePipeline.analyze(request)
        } catch (e: Exception) {
            android.util.Log.e("DanceNativeApiImpl", "Failed to analyze video: ${request.videoUri}", e)
            val cacheId = "analysis_${System.currentTimeMillis()}"
            AnalyzeResultDto(
                analysisCacheId = cacheId,
                videoInfo = probeVideo(request.videoUri),
                persons = emptyList()
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
        val jobId = "job_${System.currentTimeMillis()}"
        val isCancelled = java.util.concurrent.atomic.AtomicBoolean(false)

        val coroutineJob = exportScope.launch {
            exportPipeline.execute(
                jobId = jobId,
                sourceUri = request.outputFilePath, // will use input video or cache
                request = request,
                isCancelled = isCancelled,
                onStatusChange = { status ->
                    jobManager.updateStatus(jobId, status)
                }
            )
        }

        val processingJob = com.danceanon.native.jobs.ProcessingJob(
            id = jobId,
            coroutineJob = coroutineJob,
            isCancelled = isCancelled
        )
        jobManager.registerJob(processingJob)
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
        // Cleanup project caches in Phase 1
    }
}
