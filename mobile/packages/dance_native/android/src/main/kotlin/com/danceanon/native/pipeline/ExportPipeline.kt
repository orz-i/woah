package com.danceanon.native.pipeline

import android.content.Context
import android.graphics.SurfaceTexture
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface
import com.danceanon.native.bridge.DanceNativeException
import com.danceanon.native.bridge.DanceProcessingEvents
import com.danceanon.native.bridge.ExportRequestDto
import com.danceanon.native.bridge.JobStatusDto
import com.danceanon.native.inference.RgbaColOrder
import com.danceanon.native.inference.RgbaRowOrder
import com.danceanon.native.inference.YoloLiteRtSegmenter
import com.danceanon.native.jobs.JobManager
import com.danceanon.native.media.AudioTrackCopier
import com.danceanon.native.media.CanonicalYuvInferenceDecoder
import com.danceanon.native.media.Mp4Muxer
import com.danceanon.native.media.VideoDecoder
import com.danceanon.native.media.VideoEncoder
import com.danceanon.native.media.VideoProbe
import com.danceanon.native.privacy.FaceOcclusionBridgePolicy
import com.danceanon.native.privacy.FacePixelMotionTracker
import com.danceanon.native.render.EglCore
import com.danceanon.native.render.GlRenderer
import com.danceanon.native.storage.CacheManager
import com.danceanon.native.tracking.HungarianSolver
import com.danceanon.native.tracking.TrackManager
import com.danceanon.native.tracking.TrackState
import com.danceanon.native.tracking.TrackedPerson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

class ExportPipeline(
    private val context: Context,
    private val segmenter: YoloLiteRtSegmenter,
    private val eventEmitter: DanceProcessingEvents? = null
) {

    private fun emitProgress(st: JobStatusDto, onStatusChange: (JobStatusDto) -> Unit) {
        onStatusChange(st)
        kotlinx.coroutines.CoroutineScope(Dispatchers.Main).launch {
            try {
                eventEmitter?.onProgressUpdate(st)
            } catch (_: Throwable) {}
        }
    }

    suspend fun execute(
        jobId: String,
        sourceUri: String,
        request: ExportRequestDto,
        isCancelled: AtomicBoolean,
        onStatusChange: (JobStatusDto) -> Unit
    ) = withContext(Dispatchers.IO) {
        val startTime = System.currentTimeMillis()
        val videoInfo = VideoProbe.probe(context, sourceUri)

        var w = if (request.targetWidth > 0) request.targetWidth.toInt() else videoInfo.displayWidth.toInt()
        var h = if (request.targetHeight > 0) request.targetHeight.toInt() else videoInfo.displayHeight.toInt()

        val maxDim = kotlin.math.max(w, h)
        if (maxDim > 1920) {
            val scale = 1920f / maxDim
            w = (w * scale).toInt()
            h = (h * scale).toInt()
        }
        val targetWidth = ((w + 1) / 2) * 2
        val targetHeight = ((h + 1) / 2) * 2
        val targetFps = if (request.targetFps in 1.0..60.0) request.targetFps.toFloat() else 30.0f

        val finalOutFile = if (request.outputFilePath.startsWith("/tmp") || !request.outputFilePath.startsWith("/")) {
            val exportDir = File(context.cacheDir, "exports")
            exportDir.mkdirs()
            File(exportDir, "export_${System.currentTimeMillis()}.mp4")
        } else {
            val f = File(request.outputFilePath)
            f.parentFile?.mkdirs()
            f
        }
        val tempOutFile = File(finalOutFile.parentFile, "${finalOutFile.nameWithoutExtension}.tmp.mp4")

        val totalFrames = if (videoInfo.fps > 0) ((videoInfo.durationMs / 1000.0) * videoInfo.fps).toInt().coerceAtLeast(1) else 300
        val privacyModeByTrackId = com.danceanon.native.privacy.PersonPrivacyModeResolver.resolve(
            fullBodyPersonIds = request.selectedPersonIds.map { it.toInt() },
            faceOnlyPersonIds = request.faceOnlyPersonIds?.map { it.toInt() }
        )
        val fullBodyPersonIds = privacyModeByTrackId.asSequence()
            .filter { it.value == com.danceanon.native.privacy.PersonPrivacyMode.FULL_BODY }
            .map { it.key }
            .toSet()
        val faceOnlyPersonIds = privacyModeByTrackId.asSequence()
            .filter { it.value == com.danceanon.native.privacy.PersonPrivacyMode.FACE_ONLY }
            .map { it.key }
            .toSet()
        val allPrivacyTargetIds = privacyModeByTrackId.keys.toSet()

        var status = JobStatusDto(
            jobId = jobId,
            state = "preparing",
            currentFrame = 0L,
            totalFrames = totalFrames.toLong(),
            fps = 0.0,
            progress = 0.0,
            outputUri = null,
            errorCode = null,
            errorMessage = null
        )
        com.danceanon.native.diagnostics.NativeDiagnostics.recordPipelineLifecycle(
            stage = "PREPARING",
            jobId = jobId,
            fields = mapOf(
                "profile" to request.processingProfile,
                "selected_ids" to fullBodyPersonIds.sorted(),
                "face_only_ids" to faceOnlyPersonIds.sorted()
            )
        )
        onStatusChange(status)
        kotlinx.coroutines.CoroutineScope(Dispatchers.Main).launch {
            try { eventEmitter?.onProgressUpdate(status) } catch (_: Throwable) {}
        }

        segmenter.initialize()
        val yoloRuntimeInfo = segmenter.runtimeInfo
        val yoloEffectiveAccelerator = segmenter.effectiveAccelerator
        val yoloRequestedAccelerator = yoloRuntimeInfo?.requestedAccelerator?.name ?: "GPU"
        val yoloFallbackReason = yoloRuntimeInfo?.fallbackReason
        var cpuDeterminismProbeSegmenter: YoloLiteRtSegmenter? = null
        var cpuDeterminismProbeFallbackReason: String? = null
        var cpuMt2ProbeSegmenter: YoloLiteRtSegmenter? = null
        var cpuMt2ProbeFallbackReason: String? = null
        var cpuMt4ProbeSegmenter: YoloLiteRtSegmenter? = null
        var cpuMt4ProbeFallbackReason: String? = null
        if (com.danceanon.dance_native.BuildConfig.DEBUG) {
            try {
                cpuDeterminismProbeSegmenter = YoloLiteRtSegmenter(
                    context = context,
                    requestedAccelerator = com.danceanon.native.litert.LiteRtAccelerator.CPU
                ).also { it.initialize() }
                val cpuInfo = cpuDeterminismProbeSegmenter?.runtimeInfo
                com.danceanon.native.diagnostics.NativeDiagnostics.event(
                    level = "INFO",
                    component = "ExportPipeline",
                    event = "YOLO_CPU_DETERMINISM_PROBE_ACTIVE",
                    fields = mapOf(
                        "job_id" to jobId,
                        "effective_accelerator" to cpuDeterminismProbeSegmenter?.effectiveAccelerator?.name,
                        "compile_ms" to cpuInfo?.compileMs,
                        "warmup_ms" to cpuInfo?.warmupMs
                    )
                )
            } catch (t: Throwable) {
                cpuDeterminismProbeFallbackReason = "${t.javaClass.simpleName}:${t.message ?: "unknown"}"
                try { cpuDeterminismProbeSegmenter?.close() } catch (_: Throwable) {}
                cpuDeterminismProbeSegmenter = null
                com.danceanon.native.diagnostics.NativeDiagnostics.event(
                    level = "WARN",
                    component = "ExportPipeline",
                    event = "YOLO_CPU_DETERMINISM_PROBE_UNAVAILABLE",
                    fields = mapOf(
                        "job_id" to jobId,
                        "reason" to cpuDeterminismProbeFallbackReason
                    )
                )
            }

            try {
                cpuMt2ProbeSegmenter = YoloLiteRtSegmenter(
                    context = context,
                    requestedAccelerator = com.danceanon.native.litert.LiteRtAccelerator.CPU,
                    cpuNumThreads = CPU_MT2_PROBE_THREADS,
                    diagnosticArtifactMaxPtsUs = -1L,
                    diagnosticSignatureMaxPtsUs = CPU_SINGLE_THREAD_REFERENCE_MAX_PTS_US
                ).also { it.initialize() }
                val cpuMt2Info = cpuMt2ProbeSegmenter?.runtimeInfo
                com.danceanon.native.diagnostics.NativeDiagnostics.event(
                    level = "INFO",
                    component = "ExportPipeline",
                    event = "YOLO_CPU_MT2_PROBE_ACTIVE",
                    fields = mapOf(
                        "job_id" to jobId,
                        "threads" to CPU_MT2_PROBE_THREADS,
                        "signature_max_pts_us" to CPU_SINGLE_THREAD_REFERENCE_MAX_PTS_US,
                        "effective_accelerator" to cpuMt2ProbeSegmenter?.effectiveAccelerator?.name,
                        "compile_ms" to cpuMt2Info?.compileMs,
                        "warmup_ms" to cpuMt2Info?.warmupMs
                    )
                )
            } catch (t: Throwable) {
                cpuMt2ProbeFallbackReason = "${t.javaClass.simpleName}:${t.message ?: "unknown"}"
                try { cpuMt2ProbeSegmenter?.close() } catch (_: Throwable) {}
                cpuMt2ProbeSegmenter = null
                com.danceanon.native.diagnostics.NativeDiagnostics.event(
                    level = "WARN",
                    component = "ExportPipeline",
                    event = "YOLO_CPU_MT2_PROBE_UNAVAILABLE",
                    fields = mapOf(
                        "job_id" to jobId,
                        "threads" to CPU_MT2_PROBE_THREADS,
                        "reason" to cpuMt2ProbeFallbackReason
                    )
                )
            }

            try {
                cpuMt4ProbeSegmenter = YoloLiteRtSegmenter(
                    context = context,
                    requestedAccelerator = com.danceanon.native.litert.LiteRtAccelerator.CPU,
                    cpuNumThreads = CPU_MT_PROBE_THREADS,
                    diagnosticArtifactMaxPtsUs = CPU_SINGLE_THREAD_REFERENCE_MAX_PTS_US,
                    diagnosticSignatureMaxPtsUs = Long.MAX_VALUE
                ).also { it.initialize() }
                val cpuMtInfo = cpuMt4ProbeSegmenter?.runtimeInfo
                com.danceanon.native.diagnostics.NativeDiagnostics.event(
                    level = "INFO",
                    component = "ExportPipeline",
                    event = "YOLO_CPU_MT4_PROBE_ACTIVE",
                    fields = mapOf(
                        "job_id" to jobId,
                        "threads" to CPU_MT_PROBE_THREADS,
                        "signature_scope" to "FULL_EXPORT",
                        "effective_accelerator" to cpuMt4ProbeSegmenter?.effectiveAccelerator?.name,
                        "compile_ms" to cpuMtInfo?.compileMs,
                        "warmup_ms" to cpuMtInfo?.warmupMs
                    )
                )
            } catch (t: Throwable) {
                cpuMt4ProbeFallbackReason = "${t.javaClass.simpleName}:${t.message ?: "unknown"}"
                try { cpuMt4ProbeSegmenter?.close() } catch (_: Throwable) {}
                cpuMt4ProbeSegmenter = null
                com.danceanon.native.diagnostics.NativeDiagnostics.event(
                    level = "WARN",
                    component = "ExportPipeline",
                    event = "YOLO_CPU_MT4_PROBE_UNAVAILABLE",
                    fields = mapOf(
                        "job_id" to jobId,
                        "threads" to CPU_MT_PROBE_THREADS,
                        "reason" to cpuMt4ProbeFallbackReason
                    )
                )
            }
        }
        com.danceanon.native.diagnostics.NativeDiagnostics.recordPipelineLifecycle(
            stage = "PREPARING",
            jobId = jobId,
            fields = mapOf(
                "yolo_requested_accelerator" to yoloRequestedAccelerator,
                "yolo_effective_accelerator" to yoloEffectiveAccelerator.name,
                "yolo_gpu_fallback_reason" to yoloFallbackReason,
                "yolo_compile_ms" to yoloRuntimeInfo?.compileMs,
                "yolo_warmup_ms" to yoloRuntimeInfo?.warmupMs
            )
        )
        com.danceanon.native.diagnostics.NativeDiagnostics.event(
            level = if (yoloEffectiveAccelerator == com.danceanon.native.litert.LiteRtAccelerator.GPU) "INFO" else "WARN",
            component = "ExportPipeline",
            event = if (yoloEffectiveAccelerator == com.danceanon.native.litert.LiteRtAccelerator.GPU) {
                "YOLO_EXPORT_GPU_ACTIVE"
            } else {
                "YOLO_EXPORT_CPU_FALLBACK"
            },
            fields = mapOf(
                "job_id" to jobId,
                "requested_accelerator" to yoloRequestedAccelerator,
                "effective_accelerator" to yoloEffectiveAccelerator.name,
                "fallback_reason" to yoloFallbackReason,
                "compile_ms" to yoloRuntimeInfo?.compileMs,
                "warmup_ms" to yoloRuntimeInfo?.warmupMs
            )
        )

        // Dedicated GL thread for EGL context, rendering, and encoding
        val glThread = HandlerThread("ExportGlPipeline").apply { start() }
        val glHandler = Handler(glThread.looper)

        // Separate thread for SurfaceTexture frame callbacks so it is NEVER blocked by the GL render loop
        val frameThread = HandlerThread("FrameNotifier").apply { start() }
        val frameHandler = Handler(frameThread.looper)

        val pipelineLatch = CountDownLatch(1)
        var pipelineException: Throwable? = null

        glHandler.post {
            var eglCore: EglCore? = null
            var eglSurface: android.opengl.EGLSurface? = null
            var glRenderer: GlRenderer? = null
            var surfaceTexture: SurfaceTexture? = null
            var decoder: VideoDecoder? = null
            var canonicalInferenceDecoder: CanonicalYuvInferenceDecoder? = null
            var canonicalInferenceFallbackReason: String? = null
            var canonicalValidationWindowCompleted = false
            var encoder: VideoEncoder? = null
            var muxer: Mp4Muxer? = null
            var audioCopier: AudioTrackCopier? = null
            var frameReader: com.danceanon.native.render.InferenceFrameReader? = null
            var oesTextureId = 0
            var livePreviewFile: java.io.File? = null
            var decoderSurface: android.view.Surface? = null
            var previewScope: kotlinx.coroutines.CoroutineScope? = null
            var faceOnlyPrivacyProcessor: com.danceanon.native.privacy.FaceOnlyPrivacyFrameProcessor? = null

            try {
                audioCopier = AudioTrackCopier(context, sourceUri)
                val hasAudioTrack = audioCopier.prepare()
                val audioFmt = if (hasAudioTrack) audioCopier.audioFormat else null
                val actualHasAudio = hasAudioTrack && audioFmt != null

                muxer = Mp4Muxer(tempOutFile.absolutePath, expectedTracks = if (actualHasAudio) 2 else 1)
                if (actualHasAudio && audioFmt != null) {
                    muxer.addAudioTrack(audioFmt)
                }

                encoder = VideoEncoder(
                    width = targetWidth,
                    height = targetHeight,
                    bitrate = request.videoBitrate.toInt().coerceAtLeast(4_000_000),
                    fps = targetFps
                )

                val inputSurface = encoder.prepare()
                eglCore = EglCore()
                val surf = eglCore.createWindowSurface(inputSurface)
                eglSurface = surf
                eglCore.makeCurrent(surf)

                glRenderer = GlRenderer()
                glRenderer.initialize(targetWidth, targetHeight)

                val oesTextures = IntArray(1)
                android.opengl.GLES20.glGenTextures(1, oesTextures, 0)
                oesTextureId = oesTextures[0]
                android.opengl.GLES20.glBindTexture(android.opengl.GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTextureId)
                android.opengl.GLES20.glTexParameteri(android.opengl.GLES11Ext.GL_TEXTURE_EXTERNAL_OES, android.opengl.GLES20.GL_TEXTURE_MIN_FILTER, android.opengl.GLES20.GL_LINEAR)
                android.opengl.GLES20.glTexParameteri(android.opengl.GLES11Ext.GL_TEXTURE_EXTERNAL_OES, android.opengl.GLES20.GL_TEXTURE_MAG_FILTER, android.opengl.GLES20.GL_LINEAR)
                android.opengl.GLES20.glTexParameteri(android.opengl.GLES11Ext.GL_TEXTURE_EXTERNAL_OES, android.opengl.GLES20.GL_TEXTURE_WRAP_S, android.opengl.GLES20.GL_CLAMP_TO_EDGE)
                android.opengl.GLES20.glTexParameteri(android.opengl.GLES11Ext.GL_TEXTURE_EXTERNAL_OES, android.opengl.GLES20.GL_TEXTURE_WRAP_T, android.opengl.GLES20.GL_CLAMP_TO_EDGE)



                val inferenceFbo = com.danceanon.native.render.InferenceFbo(640)
                val inferenceRenderer = com.danceanon.native.render.InferenceRenderer()
                val mapper = com.danceanon.native.geometry.ModelCoordinateMapper(targetWidth, targetHeight, 640)
                val profiler = com.danceanon.native.profiler.PipelineProfiler()
                val inferencePixelDiagnostics = com.danceanon.native.diagnostics.InferencePixelDiagnostics(
                    jobId = jobId,
                    width = inferenceFbo.size,
                    height = inferenceFbo.size
                )

                val frameAvailableSequence = java.util.concurrent.atomic.AtomicLong(0L)
                val consumedFrameSequence = java.util.concurrent.atomic.AtomicLong(0L)
                val frameSync = Object()

                surfaceTexture = SurfaceTexture(oesTextureId).apply {
                    val bufW = if (videoInfo.codedWidth > 0) videoInfo.codedWidth.toInt() else targetWidth
                    val bufH = if (videoInfo.codedHeight > 0) videoInfo.codedHeight.toInt() else targetHeight
                    setDefaultBufferSize(bufW, bufH)
                    setOnFrameAvailableListener({
                        synchronized(frameSync) {
                            frameAvailableSequence.incrementAndGet()
                            frameSync.notifyAll()
                        }
                    }, frameHandler)
                }

                decoderSurface = android.view.Surface(surfaceTexture)
                decoder = VideoDecoder(
                    context = context,
                    sourceUri = sourceUri,
                    outputSurface = decoderSurface
                )
                decoder.prepare()

                var processedFrames = 0
                val targetFps = if (videoInfo.fps > 0) videoInfo.fps else 30.0
                val totalEstFrames = ((videoInfo.durationMs / 1000.0) * targetFps).toLong().coerceAtLeast(1L)
                val frameDurationNs = (1_000_000_000.0 / targetFps).toLong().coerceAtLeast(1_000_000L)
                val stMatrix = FloatArray(16).apply {
                    android.opengl.Matrix.setIdentityM(this, 0)
                }
                var basePtsUs = -1L
                var lastPresentationNs = -1L
                val trackManager = TrackManager()
                // Temporal fresh-class evidence has no exact person ID. It is safe
                // only for the historical FULL_BODY-only compositor. In mixed mode
                // it can label nearby FACE_ONLY detections as SELECTED and turn
                // several dancers into FULL_BODY masks, so keep the primary rooted
                // in the exact TrackManager-selected ID and render FACE_ONLY only as
                // the independent sticker overlay below.
                val allowFreshFullBodyClassPrimary = shouldUseFreshFullBodyClassPrimary(
                    fullBodyPersonIds = fullBodyPersonIds,
                    faceOnlyPersonIds = faceOnlyPersonIds
                )
                if (faceOnlyPersonIds.isEmpty()) {
                    // Preserve the exact legacy identity/privacy coupling when no
                    // FACE_ONLY policy was requested.
                    trackManager.setProtectedTrackIds(fullBodyPersonIds)
                } else {
                    trackManager.setIdentityProtectedTrackIds(allPrivacyTargetIds)
                    trackManager.setPrivacySelectedTrackIds(fullBodyPersonIds)
                    trackManager.setPrivacyOffscreenDormancyEnabled(fullBodyPersonIds.isNotEmpty())
                }
                val privacyClassTemporalTracker = com.danceanon.native.privacy.PrivacyClassTemporalTracker()
                val profile = ProcessingProfile.fromName(request.processingProfile)
                val frameStride = profile.inferenceStride
                var lastProgressEmitTime = 0L
                val isSam2Mode = profile.useSam2
                val crossDeviceTrackingDiagnostics = if (
                    com.danceanon.dance_native.BuildConfig.DEBUG && !isSam2Mode
                ) {
                    com.danceanon.native.diagnostics.CrossDeviceTrackingDiagnostics(
                        jobId = jobId,
                        fullBodyPersonIds = fullBodyPersonIds,
                        faceOnlyPersonIds = faceOnlyPersonIds
                    )
                } else {
                    null
                }

                // Cross-device validation path: keep rendering on the historical Surface decoder,
                // but feed YOLO from an independent CPU-readable YUV decoder. This bypasses the
                // device-specific SurfaceTexture/OES YUV->RGB conversion without touching tracking.
                if (com.danceanon.dance_native.BuildConfig.DEBUG && !isSam2Mode) {
                    try {
                        canonicalInferenceDecoder = CanonicalYuvInferenceDecoder(
                            context = context,
                            sourceUri = sourceUri,
                            rotationDegrees = videoInfo.rotation.toInt(),
                            modelInputSize = inferenceFbo.size
                        ).also { it.prepare() }
                        com.danceanon.native.diagnostics.NativeDiagnostics.event(
                            level = "INFO",
                            component = "ExportPipeline",
                            event = "CANONICAL_YUV_INFERENCE_ACTIVE",
                            fields = buildMap {
                                put("job_id", jobId)
                                put("codec_name", canonicalInferenceDecoder?.runtimeInfo?.codecName)
                                put("color_standard", canonicalInferenceDecoder?.runtimeInfo?.colorStandard)
                                put("color_range", canonicalInferenceDecoder?.runtimeInfo?.colorRange)
                                put("color_transfer", canonicalInferenceDecoder?.runtimeInfo?.colorTransfer)
                            }
                        )
                    } catch (t: Throwable) {
                        canonicalInferenceFallbackReason = "${t.javaClass.simpleName}:${t.message ?: "unknown"}"
                        try { canonicalInferenceDecoder?.close() } catch (_: Throwable) {}
                        canonicalInferenceDecoder = null
                        com.danceanon.native.diagnostics.NativeDiagnostics.event(
                            level = "WARN",
                            component = "ExportPipeline",
                            event = "CANONICAL_YUV_INFERENCE_FALLBACK",
                            fields = mapOf(
                                "job_id" to jobId,
                                "reason" to canonicalInferenceFallbackReason
                            )
                        )
                    }
                }
                var sam2Fbo: com.danceanon.native.sam2.Sam2InputFbo? = null
                var sam2Renderer: com.danceanon.native.sam2.Sam2InputRenderer? = null
                var sam2Tracker: com.danceanon.native.sam2.ISam2VideoTracker? = null

                android.util.Log.i(
                    "ExportPipeline",
                    "Pipeline Config: isSam2Mode=$isSam2Mode, profileName=${profile.name}, stride=$frameStride, inputSize=${profile.inputSize}, target=${targetWidth}x${targetHeight}"
                )

                if (faceOnlyPersonIds.isNotEmpty()) {
                    if (isSam2Mode) {
                        throw DanceNativeException(
                            DanceNativeException.INVALID_ARGUMENT,
                            "FACE_ONLY export is supported only on the stable YOLO pipeline."
                        )
                    }
                    faceOnlyPrivacyProcessor =
                        com.danceanon.native.privacy.FaceOnlyPrivacyFrameProcessor.create(context, mapper)
                }

                if (isSam2Mode) {
                    if (!com.danceanon.native.sam2.Sam2GpuCapabilityManager.isAvailable()) {
                        throw DanceNativeException(
                            DanceNativeException.SAM2_GPU_UNAVAILABLE,
                            "SAM2 requires a verified LiteRT GPU accelerator on this device."
                        )
                    }
                    sam2Fbo = com.danceanon.native.sam2.Sam2InputFbo(com.danceanon.native.sam2.Sam2TensorContract.IMAGE_SIZE)
                    sam2Renderer = com.danceanon.native.sam2.Sam2InputRenderer()
                    val bundle = com.danceanon.native.sam2.Sam2LiteRtModelBundle.loadFromAssets(context)
                    sam2Tracker = com.danceanon.native.sam2.Sam2LiteRtVideoTracker(bundle, encoderStride = frameStride)
                }

                var lastLivePreviewCaptureTime = 0L
                var previewFlip = 0
                val livePreviewDir = java.io.File(context.cacheDir, "export_live_preview").apply { mkdirs() }
                val scope = kotlinx.coroutines.CoroutineScope(Dispatchers.IO + kotlinx.coroutines.SupervisorJob())
                previewScope = scope
                val isPreviewSaving = java.util.concurrent.atomic.AtomicBoolean(false)
                var lastPreviewFilePath: String? = null
                var sam2Initialized = false

                var decodedFrameCount = 0L
                var latchedFrameCount = 0L
                var renderedFrameCount = 0L
                var encodedFrameCount = 0L
                var lastDecoderPtsUs = -1L
                var lastEncoderPtsUs = -1L
                var emptyFrameStreak = 0
                var faceDetectorCallCount = 0L
                var faceDetectorObservationCount = 0L
                var faceDetectorZeroObservationCallCount = 0L
                var faceDetectorRejectedCallCount = 0L
                var faceDetectedTrackFrameCount = 0L
                var facePredictedTrackFrameCount = 0L
                var faceFallbackTrackFrameCount = 0L
                var faceBodyMaskGuidedTrackFrameCount = 0L
                var facePositionClampedTrackFrameCount = 0L
                var faceBodyCompensatedTrackFrameCount = 0L
                var faceFreshBodyMotionTrackFrameCount = 0L
                var faceRecentBodyMotionBridgeTrackFrameCount = 0L
                var faceDormantReactivationProbeTrackFrameCount = 0L
                var faceDormantProbeMotionRejectedTrackFrameCount = 0L
                var faceDormantReactivatedEventCount = 0L
                var faceDormantExactReacquiredTrackFrameCount = 0L
                var faceDormantSuppressedTrackFrameCount = 0L
                var faceDormantPixelMotionBridgeTrackFrameCount = 0L
                var facePixelMotionTrackFrameCount = 0L
                var facePartialOcclusionPixelMotionTrackFrameCount = 0L
                var facePixelMotionRejectedTrackFrameCount = 0L
                var faceOcclusionHoldTrackFrameCount = 0L
                var faceOcclusionReacquireDetectorTrackFrameCount = 0L
                var faceAppearanceReacquireDetectorTrackFrameCount = 0L
                var faceEvidenceGapReacquireDetectorTrackFrameCount = 0L
                var faceEvidenceGapReacquireDetectorSuccessTrackFrameCount = 0L
                var faceEvidenceGapReacquireDetectorZeroObservationTrackFrameCount = 0L
                var faceEvidenceGapReacquireDetectorRejectedTrackFrameCount = 0L
                val faceDetectorCallsByTrackId = mutableMapOf<Int, Long>()
                val faceDetectorRejectedCallsByTrackId = mutableMapOf<Int, Long>()
                val faceDetectedFramesByTrackId = mutableMapOf<Int, Long>()
                val facePredictedFramesByTrackId = mutableMapOf<Int, Long>()
                val faceFallbackFramesByTrackId = mutableMapOf<Int, Long>()
                val faceBodyMaskGuidedFramesByTrackId = mutableMapOf<Int, Long>()
                val facePositionClampedFramesByTrackId = mutableMapOf<Int, Long>()
                val faceBodyCompensatedFramesByTrackId = mutableMapOf<Int, Long>()
                val faceFreshBodyMotionFramesByTrackId = mutableMapOf<Int, Long>()
                val faceRecentBodyMotionBridgeFramesByTrackId = mutableMapOf<Int, Long>()
                val faceDormantReactivationProbeFramesByTrackId = mutableMapOf<Int, Long>()
                val faceDormantProbeMotionRejectedFramesByTrackId = mutableMapOf<Int, Long>()
                val faceDormantReactivatedEventsByTrackId = mutableMapOf<Int, Long>()
                val faceDormantExactReacquiredFramesByTrackId = mutableMapOf<Int, Long>()
                val faceDormantSuppressedFramesByTrackId = mutableMapOf<Int, Long>()
                val faceDormantPixelMotionBridgeFramesByTrackId = mutableMapOf<Int, Long>()
                val facePixelMotionFramesByTrackId = mutableMapOf<Int, Long>()
                val facePartialOcclusionPixelMotionFramesByTrackId = mutableMapOf<Int, Long>()
                val facePixelMotionRejectedFramesByTrackId = mutableMapOf<Int, Long>()
                val faceOcclusionHoldFramesByTrackId = mutableMapOf<Int, Long>()
                val faceOcclusionReacquireDetectorFramesByTrackId = mutableMapOf<Int, Long>()
                val faceAppearanceReacquireDetectorFramesByTrackId = mutableMapOf<Int, Long>()
                val faceEvidenceGapReacquireDetectorFramesByTrackId = mutableMapOf<Int, Long>()
                val faceEvidenceGapReacquireDetectorSuccessFramesByTrackId = mutableMapOf<Int, Long>()
                val faceEvidenceGapReacquireDetectorZeroObservationFramesByTrackId = mutableMapOf<Int, Long>()
                val faceEvidenceGapReacquireDetectorRejectedFramesByTrackId = mutableMapOf<Int, Long>()
                val facePixelMotionRejectReasonCounts = mutableMapOf<String, Long>()
                val facePixelMotionRejectReasonsByTrackId = mutableMapOf<Int, MutableMap<String, Long>>()
                val faceDormantSuppressionReasonCounts = mutableMapOf<String, Long>()
                val faceDormantSuppressionReasonsByTrackId = mutableMapOf<Int, MutableMap<String, Long>>()
                val faceDormantReactivationStickerMaxWidthByTrackId = mutableMapOf<Int, Float>()
                val faceDormantReactivationStickerMaxHeightByTrackId = mutableMapOf<Int, Float>()
                val faceStickerMinWidthByTrackId = mutableMapOf<Int, Float>()
                val faceStickerMaxWidthByTrackId = mutableMapOf<Int, Float>()
                val faceStickerMinHeightByTrackId = mutableMapOf<Int, Float>()
                val faceStickerMaxHeightByTrackId = mutableMapOf<Int, Float>()
                val faceStickerLastCenterByTrackId = mutableMapOf<Int, Pair<Float, Float>>()
                val faceStickerLastPlacementFrameByTrackId = mutableMapOf<Int, Int>()
                val faceStickerMaxCenterStepByTrackId = mutableMapOf<Int, Float>()
                val faceStickerMaxConsecutiveCenterStepByTrackId = mutableMapOf<Int, Float>()
                val facePartialOcclusionMaxCenterStepByTrackId = mutableMapOf<Int, Float>()
                val facePartialOcclusionMaxConsecutiveCenterStepByTrackId = mutableMapOf<Int, Float>()

                // SurfaceTexture Timing & Diagnostics Metrics (PHASE E)
                var surfaceWaitTimeoutCount = 0L
                var duplicateSurfaceTimestampCount = 0L
                var nonMonotonicSurfaceTimestampCount = 0L
                var maxAbsSurfacePtsDeltaUs = 0L
                val surfacePtsDeltaSamples = mutableListOf<Long>()
                var lastSurfaceTimestampNs = -1L

                // Target Missing Streak Map (PHASE A & D)
                val missingTargetStreakMap = mutableMapOf<Int, Int>()

                com.danceanon.native.diagnostics.NativeDiagnostics.recordPipelineLifecycle(
                    stage = "EXPORTING",
                    jobId = jobId,
                    fields = mapOf(
                        "target_width" to targetWidth,
                        "target_height" to targetHeight,
                        "target_fps" to targetFps,
                        "yolo_requested_accelerator" to yoloRequestedAccelerator,
                        "yolo_effective_accelerator" to yoloEffectiveAccelerator.name,
                        "yolo_gpu_fallback_reason" to yoloFallbackReason
                    )
                )

                while (!isCancelled.get() && !decoder.isOutputEOS) {
                    while (!isCancelled.get() && !decoder.isInputEOS && decoder.feedInputBuffer(timeoutUs = 0L)) {}

                    val token = decoder.dequeueOutputBufferToken(timeoutUs = 10_000L)
                    if (token == null) {
                        if (decoder.isOutputEOS) {
                            break
                        }
                        emptyFrameStreak++
                        if (decoder.isInputEOS && emptyFrameStreak > 200) {
                            android.util.Log.w(
                                "ExportPipeline",
                                "[Decoder] Drain timed out after input EOS ($emptyFrameStreak empty iterations). Breaking loop."
                            )
                            break
                        }
                        continue
                    }
                    emptyFrameStreak = 0

                    if (token.isEOS || isCancelled.get()) {
                        decoder.releaseOutputBuffer(token.bufferIndex, false)
                        break
                    }

                    val ptsUs = token.presentationTimeUs
                    val targetSeq = frameAvailableSequence.get() + 1L

                    // Handshake: Release buffer to SurfaceTexture and wait for onFrameAvailable sequence increment
                    decoder.releaseOutputBuffer(token.bufferIndex, true)

                    var frameReceived = false
                    synchronized(frameSync) {
                        val deadline = System.currentTimeMillis() + 500L
                        while (frameAvailableSequence.get() < targetSeq && System.currentTimeMillis() < deadline) {
                            val waitMs = deadline - System.currentTimeMillis()
                            if (waitMs > 0) {
                                try {
                                    frameSync.wait(waitMs)
                                } catch (_: InterruptedException) {}
                            }
                        }
                        frameReceived = frameAvailableSequence.get() >= targetSeq
                    }

                    if (!frameReceived) {
                        surfaceWaitTimeoutCount++
                        android.util.Log.w(
                            "ExportPipeline",
                            "[Telemetry Warning] SurfaceTexture frame wait timeout (500ms) on frame #$processedFrames (pts=${ptsUs}us). Attempting 100ms retry."
                        )
                        synchronized(frameSync) {
                            val retryDeadline = System.currentTimeMillis() + 100L
                            while (frameAvailableSequence.get() < targetSeq && System.currentTimeMillis() < retryDeadline) {
                                val waitMs = retryDeadline - System.currentTimeMillis()
                                if (waitMs > 0) {
                                    try {
                                        frameSync.wait(waitMs)
                                    } catch (_: InterruptedException) {}
                                }
                            }
                            frameReceived = frameAvailableSequence.get() >= targetSeq
                        }
                        if (!frameReceived) {
                            throw DanceNativeException(
                                DanceNativeException.FRAME_DECODE_TIMEOUT,
                                "SurfaceTexture frame wait timeout exceeded after retry on frame #$processedFrames (pts=${ptsUs}us)"
                            )
                        }
                    }

                    consumedFrameSequence.set(frameAvailableSequence.get())
                    decodedFrameCount++
                    lastDecoderPtsUs = ptsUs
                    processedFrames++

                    val selectedIds = fullBodyPersonIds

                    // Ensure OES texture is active and bound before latching frame
                    android.opengl.GLES20.glActiveTexture(android.opengl.GLES20.GL_TEXTURE0)
                    android.opengl.GLES20.glBindTexture(android.opengl.GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTextureId)

                    // Latch and update OES texture with decoded video frame
                    try {
                        surfaceTexture?.updateTexImage()
                        surfaceTexture?.getTransformMatrix(stMatrix)
                        latchedFrameCount++

                        // SurfaceTexture Timing Diagnostics
                        val surfaceTimestampNs = surfaceTexture?.timestamp ?: 0L
                        if (lastSurfaceTimestampNs > 0L) {
                            if (surfaceTimestampNs == lastSurfaceTimestampNs) {
                                duplicateSurfaceTimestampCount++
                            } else if (surfaceTimestampNs < lastSurfaceTimestampNs) {
                                nonMonotonicSurfaceTimestampCount++
                            }
                        }
                        lastSurfaceTimestampNs = surfaceTimestampNs

                        val surfacePtsUs = surfaceTimestampNs / 1000L
                        val deltaUs = kotlin.math.abs(surfacePtsUs - ptsUs)
                        if (deltaUs > maxAbsSurfacePtsDeltaUs) {
                            maxAbsSurfacePtsDeltaUs = deltaUs
                        }
                        if (surfacePtsDeltaSamples.size < 5000) {
                            surfacePtsDeltaSamples.add(deltaUs)
                        }
                    } catch (e: Throwable) {
                        android.util.Log.w("ExportPipeline", "updateTexImage warning: ${e.message}")
                        continue
                    }

                    val rotation = videoInfo.rotation.toInt()
                    val finalTexMatrix = GlRenderer.computeTransformMatrix(stMatrix, rotation)

                    // 1. Prepare video texture for current frame
                    val renderTexId = oesTextureId
                    val renderTexType = com.danceanon.native.render.SourceTextureType.OES
                    val renderTexMatrix: FloatArray? = finalTexMatrix
                    var freshPrivacyClassEvidence = emptyList<com.danceanon.native.tracking.FreshPrivacyClassEvidence>()
                    var freshSelectedCoveredTrackIds = emptySet<Int>()
                    var suppressedSelectedPrivacyTrackIds = emptySet<Int>()
                    var preferFreshPrivacyClassPrimary = false

                        // 2. Perform Inference / Temporal Mask Tracking
                        val trackedList: List<com.danceanon.native.tracking.TrackedPerson> = if (isSam2Mode && sam2Fbo != null && sam2Renderer != null && sam2Tracker != null) {
                            if (!sam2Initialized) {
                                // YOLO anchor detection to register prompt boxes
                                val initialPersons = profiler.recordStage("yoloAnchor") {
                                    inferenceRenderer.renderToFbo(renderTexId, finalTexMatrix, mapper, inferenceFbo, renderTexType)
                                    val yoloRgbaBuffer = inferenceFbo.readRgbaPixels()
                                    val seg = segmenter.segmentGlReadbackRgbaSync(
                                        yoloRgbaBuffer,
                                        mapper,
                                        ptsUs,
                                        colOrder = RgbaColOrder.LEFT_TO_RIGHT,
                                        diagnosticJobId = jobId
                                    )
                                    seg.persons.sortedBy { it.bbox.centerX }
                                }

                                if (initialPersons.isNotEmpty()) {
                                    val sam2RgbaBuffer = profiler.recordStage("sam2Readback") {
                                        sam2Renderer.renderToFbo(renderTexId, finalTexMatrix, sam2Fbo, renderTexType)
                                        sam2Fbo.readRgbaPixels()
                                    }

                                    val cacheMgr = com.danceanon.native.storage.CacheManager(context)
                                    val metadata = if (request.analysisCacheId.isNotBlank()) cacheMgr.getAnalysisMetadata(request.analysisCacheId) else null
                                    val assignedIds: List<Int> = if (metadata != null && metadata.persons.isNotEmpty() && initialPersons.isNotEmpty()) {
                                        val cached = metadata.persons
                                        val costMatrix = Array(cached.size) { r ->
                                            val cPerson = cached[r]
                                            val cLeft = (cPerson.bbox.left * targetWidth).toFloat()
                                            val cTop = (cPerson.bbox.top * targetHeight).toFloat()
                                            val cRight = (cPerson.bbox.right * targetWidth).toFloat()
                                            val cBottom = (cPerson.bbox.bottom * targetHeight).toFloat()
                                            val cBox = com.danceanon.native.inference.FloatRect(cLeft, cTop, cRight, cBottom)

                                            FloatArray(initialPersons.size) { c ->
                                                val dBox = initialPersons[c].bbox
                                                val iou = com.danceanon.native.tracking.TrackManager.computeBBoxIoU(cBox, dBox)
                                                val refDim = maxOf(cBox.width, cBox.height, 1f)
                                                val dx = cBox.centerX - dBox.centerX
                                                val dy = cBox.centerY - dBox.centerY
                                                val dist = kotlin.math.sqrt(dx * dx + dy * dy)
                                                val distScore = (1.0f - (dist / (refDim * 1.5f))).coerceIn(0f, 1f)
                                                val score = 0.7f * iou + 0.3f * distScore
                                                (1.0f - score).coerceIn(0f, 1f)
                                            }
                                        }

                                        val matchResult = com.danceanon.native.tracking.HungarianSolver.match(costMatrix, maxCostThreshold = 0.85f)
                                        val ids = IntArray(initialPersons.size) { -1 }
                                        val usedIds = mutableSetOf<Int>()

                                        for (match in matchResult.matches) {
                                            val cIdx = match.first
                                            val dIdx = match.second
                                            if (dIdx < initialPersons.size && cIdx < cached.size) {
                                                val pId = cached[cIdx].id.toInt()
                                                ids[dIdx] = pId
                                                usedIds.add(pId)
                                            }
                                        }

                                        var nextId = 0
                                        for (i in ids.indices) {
                                            if (ids[i] == -1) {
                                                while (usedIds.contains(nextId)) {
                                                    nextId++
                                                }
                                                ids[i] = nextId
                                                usedIds.add(nextId)
                                                nextId++
                                            }
                                        }
                                        ids.toList()
                                    } else {
                                        initialPersons.indices.toList()
                                    }

                                    val targetPersons = initialPersons.mapIndexed { idx, det ->
                                        assignedIds[idx] to det
                                    }.filter { (personId, _) ->
                                        selectedIds.contains(personId)
                                    }

                                    val resultPersons = profiler.recordStage("sam2Init") {
                                        val maskSize = com.danceanon.native.sam2.Sam2TensorContract.MASK_OUTPUT_SIZE
                                        targetPersons.map { (personId, det) ->
                                            val initRes = sam2Tracker.initializeWithRgba(
                                                rgbaBuffer = sam2RgbaBuffer,
                                                width = targetWidth,
                                                height = targetHeight,
                                                objectId = personId,
                                                bbox = det.bbox
                                            )

                                            val maskBuffer = java.nio.ByteBuffer.allocateDirect(maskSize * maskSize)
                                            for (v in initRes.softMask) {
                                                maskBuffer.put((v * 255f).toInt().coerceIn(0, 255).toByte())
                                            }
                                            maskBuffer.rewind()

                                            val sam2Mask = com.danceanon.native.inference.NativeMask(
                                                width = maskSize,
                                                height = maskSize,
                                                buffer = maskBuffer,
                                                originalWidth = targetWidth,
                                                originalHeight = targetHeight,
                                                samplingRect = com.danceanon.native.inference.FloatRect(0f, 0f, 1f, 1f)
                                            )

                                            com.danceanon.native.tracking.TrackedPerson(
                                                id = personId,
                                                bbox = initRes.bbox,
                                                mask = sam2Mask,
                                                confidence = det.confidence,
                                                state = com.danceanon.native.tracking.TrackState.ACTIVE
                                            )
                                        }
                                    }
                                    sam2Initialized = true
                                    resultPersons
                                } else {
                                    emptyList()
                                }
                            } else {
                                // Frame 2+: SAM2 persistent temporal propagation with direct FBO RGBA and Stride Caching
                                val sam2RgbaBuffer = profiler.recordStage("sam2Readback") {
                                    sam2Renderer.renderToFbo(renderTexId, finalTexMatrix, sam2Fbo, renderTexType)
                                    sam2Fbo.readRgbaPixels()
                                }

                                val sam2Results = profiler.recordStage("sam2Tracking") {
                                    sam2Tracker.stepWithRgba(sam2RgbaBuffer, processedFrames)
                                }

                                profiler.recordStage("sam2MaskGen") {
                                    val maskSize = com.danceanon.native.sam2.Sam2TensorContract.MASK_OUTPUT_SIZE
                                    sam2Results.map { res ->
                                        val maskBuffer = java.nio.ByteBuffer.allocateDirect(maskSize * maskSize)
                                        for (v in res.softMask) {
                                            maskBuffer.put((v * 255f).toInt().coerceIn(0, 255).toByte())
                                        }
                                        maskBuffer.rewind()

                                        val sam2Mask = com.danceanon.native.inference.NativeMask(
                                            width = maskSize,
                                            height = maskSize,
                                            buffer = maskBuffer,
                                            originalWidth = targetWidth,
                                            originalHeight = targetHeight,
                                            samplingRect = com.danceanon.native.inference.FloatRect(0f, 0f, 1f, 1f)
                                        )

                                        com.danceanon.native.tracking.TrackedPerson(
                                            id = res.objectId,
                                            bbox = res.bbox,
                                            mask = sam2Mask,
                                            confidence = 1.0f,
                                            state = com.danceanon.native.tracking.TrackState.ACTIVE
                                        )
                                    }
                                }

                            }
                        } else {

                            // Standard YOLO pipeline
                            val shouldInfer = (processedFrames == 1) || (processedFrames % frameStride == 0)
                            var cpuMt4DetectionsForShadow: List<com.danceanon.native.inference.PersonDetection>? = null
                            val detections = if (shouldInfer) {
                                var usedCanonicalInput = false
                                val canonicalDecoder = canonicalInferenceDecoder
                                val canonicalRgba = if (canonicalDecoder != null) {
                                    try {
                                        profiler.recordStage("canonicalYuvToRgba") {
                                            canonicalDecoder.decodeRgbaAtPts(ptsUs, mapper)
                                        }.also {
                                            usedCanonicalInput = true
                                        }
                                    } catch (t: Throwable) {
                                        canonicalInferenceFallbackReason =
                                            "${t.javaClass.simpleName}:${t.message ?: "unknown"}"
                                        try { canonicalDecoder.close() } catch (_: Throwable) {}
                                        canonicalInferenceDecoder = null
                                        com.danceanon.native.diagnostics.NativeDiagnostics.event(
                                            level = "WARN",
                                            component = "ExportPipeline",
                                            event = "CANONICAL_YUV_INFERENCE_FALLBACK",
                                            fields = mapOf(
                                                "job_id" to jobId,
                                                "pts_us" to ptsUs,
                                                "reason" to canonicalInferenceFallbackReason
                                            )
                                        )
                                        null
                                    }
                                } else null
                                val rgbaBuffer = canonicalRgba ?: run {
                                    profiler.recordStage("fboLetterbox") {
                                        inferenceRenderer.renderToFbo(renderTexId, finalTexMatrix, mapper, inferenceFbo, renderTexType)
                                    }
                                    profiler.recordStage("readback640") {
                                        inferenceFbo.readRgbaPixels()
                                    }
                                }
                                inferencePixelDiagnostics.maybeCapture(
                                    rgbaBuffer = rgbaBuffer,
                                    ptsUs = ptsUs,
                                    surfaceTransform = finalTexMatrix,
                                    decoderFormatFields = decoder.videoFormat?.let { format ->
                                        buildMap<String, Any?> {
                                            put(
                                                "inference_input_path",
                                                if (usedCanonicalInput) "CANONICAL_YUV_CPU" else "SURFACE_OES_RGBA"
                                            )
                                            put("decoder_name", decoder.codecName)
                                            put("decoder_mime", format.getString(android.media.MediaFormat.KEY_MIME))
                                            put("gl_vendor", android.opengl.GLES20.glGetString(android.opengl.GLES20.GL_VENDOR))
                                            put("gl_renderer", android.opengl.GLES20.glGetString(android.opengl.GLES20.GL_RENDERER))
                                            put("gl_version", android.opengl.GLES20.glGetString(android.opengl.GLES20.GL_VERSION))
                                            if (format.containsKey(android.media.MediaFormat.KEY_WIDTH)) {
                                                put("decoder_width", format.getInteger(android.media.MediaFormat.KEY_WIDTH))
                                            }
                                            if (format.containsKey(android.media.MediaFormat.KEY_HEIGHT)) {
                                                put("decoder_height", format.getInteger(android.media.MediaFormat.KEY_HEIGHT))
                                            }
                                            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
                                                if (format.containsKey(android.media.MediaFormat.KEY_COLOR_STANDARD)) {
                                                    put("decoder_color_standard", format.getInteger(android.media.MediaFormat.KEY_COLOR_STANDARD))
                                                }
                                                if (format.containsKey(android.media.MediaFormat.KEY_COLOR_RANGE)) {
                                                    put("decoder_color_range", format.getInteger(android.media.MediaFormat.KEY_COLOR_RANGE))
                                                }
                                                if (format.containsKey(android.media.MediaFormat.KEY_COLOR_TRANSFER)) {
                                                    put("decoder_color_transfer", format.getInteger(android.media.MediaFormat.KEY_COLOR_TRANSFER))
                                                }
                                            }
                                        }
                                    }.orEmpty()
                                )
                                val seg = profiler.recordStage("yoloCpuInference") {
                                    segmenter.segmentGlReadbackRgbaSync(
                                        rgbaBuffer,
                                        mapper,
                                        ptsUs,
                                        colOrder = RgbaColOrder.LEFT_TO_RIGHT,
                                        diagnosticJobId = jobId
                                    )
                                }
                                if (ptsUs <= CPU_SINGLE_THREAD_REFERENCE_MAX_PTS_US) {
                                    val cpuProbe = cpuDeterminismProbeSegmenter
                                    if (cpuProbe != null) {
                                        try {
                                            val cpuProbeSeg = profiler.recordStage("yoloCpuDeterminismProbe") {
                                                cpuProbe.segmentGlReadbackRgbaSync(
                                                    rgbaBuffer,
                                                    mapper,
                                                    ptsUs,
                                                    colOrder = RgbaColOrder.LEFT_TO_RIGHT,
                                                    diagnosticJobId = "${jobId}_cpu_probe"
                                                )
                                            }
                                            for ((stage, elapsedMs) in cpuProbeSeg.stageTimingsMs) {
                                                profiler.recordSample("yoloCpuProbe_${stage}", elapsedMs)
                                            }
                                        } catch (t: Throwable) {
                                            cpuDeterminismProbeFallbackReason =
                                                "${t.javaClass.simpleName}:${t.message ?: "unknown"}"
                                            try { cpuProbe.close() } catch (_: Throwable) {}
                                            cpuDeterminismProbeSegmenter = null
                                            com.danceanon.native.diagnostics.NativeDiagnostics.event(
                                                level = "WARN",
                                                component = "ExportPipeline",
                                                event = "YOLO_CPU_DETERMINISM_PROBE_FAILED",
                                                fields = mapOf(
                                                    "job_id" to jobId,
                                                    "pts_us" to ptsUs,
                                                    "reason" to cpuDeterminismProbeFallbackReason
                                                )
                                            )
                                        }
                                    }

                                    val cpuMt2Probe = cpuMt2ProbeSegmenter
                                    if (cpuMt2Probe != null) {
                                        try {
                                            val cpuMt2Seg = profiler.recordStage("yoloCpuMt2Probe") {
                                                cpuMt2Probe.segmentGlReadbackRgbaSync(
                                                    rgbaBuffer,
                                                    mapper,
                                                    ptsUs,
                                                    colOrder = RgbaColOrder.LEFT_TO_RIGHT,
                                                    diagnosticJobId = "${jobId}_cpu_mt2_probe"
                                                )
                                            }
                                            for ((stage, elapsedMs) in cpuMt2Seg.stageTimingsMs) {
                                                profiler.recordSample("yoloCpuMt2Probe_${stage}", elapsedMs)
                                            }
                                        } catch (t: Throwable) {
                                            cpuMt2ProbeFallbackReason =
                                                "${t.javaClass.simpleName}:${t.message ?: "unknown"}"
                                            try { cpuMt2Probe.close() } catch (_: Throwable) {}
                                            cpuMt2ProbeSegmenter = null
                                            com.danceanon.native.diagnostics.NativeDiagnostics.event(
                                                level = "WARN",
                                                component = "ExportPipeline",
                                                event = "YOLO_CPU_MT2_PROBE_FAILED",
                                                fields = mapOf(
                                                    "job_id" to jobId,
                                                    "threads" to CPU_MT2_PROBE_THREADS,
                                                    "pts_us" to ptsUs,
                                                    "reason" to cpuMt2ProbeFallbackReason
                                                )
                                            )
                                        }
                                    }
                                }

                                val cpuMt4Probe = cpuMt4ProbeSegmenter
                                if (cpuMt4Probe != null) {
                                    try {
                                        val cpuMt4Seg = profiler.recordStage("yoloCpuMt4Probe") {
                                            cpuMt4Probe.segmentGlReadbackRgbaSync(
                                                rgbaBuffer,
                                                mapper,
                                                ptsUs,
                                                colOrder = RgbaColOrder.LEFT_TO_RIGHT,
                                                diagnosticJobId = "${jobId}_cpu_mt4_probe"
                                            )
                                        }
                                        cpuMt4DetectionsForShadow = cpuMt4Seg.persons
                                        for ((stage, elapsedMs) in cpuMt4Seg.stageTimingsMs) {
                                            profiler.recordSample("yoloCpuMt4Probe_${stage}", elapsedMs)
                                        }
                                    } catch (t: Throwable) {
                                        cpuMt4ProbeFallbackReason =
                                            "${t.javaClass.simpleName}:${t.message ?: "unknown"}"
                                        try { cpuMt4Probe.close() } catch (_: Throwable) {}
                                        cpuMt4ProbeSegmenter = null
                                        com.danceanon.native.diagnostics.NativeDiagnostics.event(
                                            level = "WARN",
                                            component = "ExportPipeline",
                                            event = "YOLO_CPU_MT4_PROBE_FAILED",
                                            fields = mapOf(
                                                "job_id" to jobId,
                                                "threads" to CPU_MT_PROBE_THREADS,
                                                "pts_us" to ptsUs,
                                                "reason" to cpuMt4ProbeFallbackReason
                                            )
                                        )
                                    }
                                }
                                // Historical compatibility: this metric name predates the
                                // LiteRT GPU path and is misleading. Preserve it unchanged
                                // for existing diagnostics, and expose a correctly named
                                // canonical alias from the segmenter's own whole-call timer.
                                profiler.recordSample("yoloPipelineTotal", seg.inferenceTimeMs)
                                for ((stage, elapsedMs) in seg.stageTimingsMs) {
                                    profiler.recordSample(stage, elapsedMs)
                                }
                                // Export QUALITY path: YOLO raw organic masks directly enter TrackManager without pre-dilation
                                seg.persons
                            } else {
                                emptyList()
                            }

                            // Tracking on detections
                            val tracked = profiler.recordStage("tracking") {
                                if (processedFrames == 1) {
                                    val cacheMgr = com.danceanon.native.storage.CacheManager(context)
                                    val metadata = if (request.analysisCacheId.isNotBlank()) cacheMgr.getAnalysisMetadata(request.analysisCacheId) else null
                                    if (metadata != null && metadata.persons.isNotEmpty() && detections.isNotEmpty()) {
                                        val cached = metadata.persons
                                        val costMatrix = Array(cached.size) { r ->
                                            val cPerson = cached[r]
                                            val cLeft = (cPerson.bbox.left * targetWidth).toFloat()
                                            val cTop = (cPerson.bbox.top * targetHeight).toFloat()
                                            val cRight = (cPerson.bbox.right * targetWidth).toFloat()
                                            val cBottom = (cPerson.bbox.bottom * targetHeight).toFloat()
                                            val cBox = com.danceanon.native.inference.FloatRect(cLeft, cTop, cRight, cBottom)

                                            FloatArray(detections.size) { c ->
                                                val dBox = detections[c].bbox
                                                val iou = com.danceanon.native.tracking.TrackManager.computeBBoxIoU(cBox, dBox)
                                                val refDim = maxOf(cBox.width, cBox.height, 1f)
                                                val dx = cBox.centerX - dBox.centerX
                                                val dy = cBox.centerY - dBox.centerY
                                                val dist = kotlin.math.sqrt(dx * dx + dy * dy)
                                                val distScore = (1.0f - (dist / (refDim * 1.5f))).coerceIn(0f, 1f)
                                                val score = 0.7f * iou + 0.3f * distScore
                                                (1.0f - score).coerceIn(0f, 1f)
                                            }
                                        }

                                        val matchResult = com.danceanon.native.tracking.HungarianSolver.match(costMatrix, maxCostThreshold = 0.85f)
                                        val assignedIds = IntArray(detections.size) { -1 }
                                        val usedIds = mutableSetOf<Int>()

                                        for (match in matchResult.matches) {
                                            val cIdx = match.first
                                            val dIdx = match.second
                                            if (dIdx < detections.size && cIdx < cached.size) {
                                                val pId = cached[cIdx].id.toInt()
                                                assignedIds[dIdx] = pId
                                                usedIds.add(pId)
                                            }
                                        }

                                        var nextId = 0
                                        for (i in assignedIds.indices) {
                                            if (assignedIds[i] == -1) {
                                                while (usedIds.contains(nextId)) {
                                                    nextId++
                                                }
                                                assignedIds[i] = nextId
                                                usedIds.add(nextId)
                                                nextId++
                                            }
                                        }
                                        trackManager.initializeWithAssignedIds(detections, assignedIds.toList())

                                    } else {
                                        trackManager.initialize(detections)
                                    }
                                } else if (!shouldInfer) {
                                    trackManager.predictWithoutObservation(ptsUs)
                                } else if (detections.isNotEmpty()) {
                                    trackManager.update(detections, ptsUs)
                                } else {
                                    trackManager.predict(ptsUs)
                                }
                            }
                            crossDeviceTrackingDiagnostics?.recordFrame(
                                ptsUs = ptsUs,
                                shouldInfer = shouldInfer,
                                productionDetections = if (processedFrames == 1) detections else null,
                                productionTracked = if (processedFrames == 1) tracked else null,
                                cpuMt4Detections = cpuMt4DetectionsForShadow
                            )
                            val temporalPrivacyEvidence = if (shouldInfer && allowFreshFullBodyClassPrimary) {
                                profiler.recordStage("privacyClassTracking") {
                                    privacyClassTemporalTracker.update(
                                        detections = detections,
                                        hardClassByDetectionIndex = if (processedFrames == 1) {
                                            trackManager.getHardPrivacyClassByDetectionIndex()
                                        } else {
                                            emptyMap()
                                        },
                                        ptsUs = ptsUs
                                    )
                                }
                            } else {
                                emptyList()
                            }
                            val trackManagerFreshPrivacyEvidence = if (shouldInfer && allowFreshFullBodyClassPrimary) {
                                trackManager.getFreshPrivacyClassEvidence()
                            } else {
                                emptyList()
                            }
                            val temporalByDetectionIndex = temporalPrivacyEvidence.associateBy { it.detectionIndex }
                            freshSelectedCoveredTrackIds = trackManagerFreshPrivacyEvidence.asSequence()
                                .filter {
                                    it.selectionClass == com.danceanon.native.tracking.PrivacySelectionClass.SELECTED &&
                                        it.residualTrackIds.size == 1
                                }
                                .filter { runtimeEvidence ->
                                    val temporal = temporalByDetectionIndex[runtimeEvidence.detectionIndex]
                                    temporal != null &&
                                        temporal.selectionClass == com.danceanon.native.tracking.PrivacySelectionClass.SELECTED &&
                                        !temporal.conservativeUnknown
                                }
                                .map { it.residualTrackIds.first() }
                                .filter { selectedIds.contains(it) }
                                .toSet()
                            if (allowFreshFullBodyClassPrimary) {
                                // Historical FULL_BODY-only QUALITY composition.
                                freshPrivacyClassEvidence = temporalPrivacyEvidence
                                suppressedSelectedPrivacyTrackIds = emptySet()
                                preferFreshPrivacyClassPrimary = shouldInfer && temporalPrivacyEvidence.isNotEmpty()
                            } else {
                                // Mixed / FACE_ONLY-only composition: never let
                                // non-identity temporal class evidence become the
                                // FULL_BODY primary.
                                freshPrivacyClassEvidence = emptyList()
                                freshSelectedCoveredTrackIds = emptySet()
                                suppressedSelectedPrivacyTrackIds = emptySet()
                                preferFreshPrivacyClassPrimary = false
                            }
                            tracked
                        }

                        val faceOnlyFrameResult = faceOnlyPrivacyProcessor?.let { processor ->
                            val protectedMotionEvidence = trackManager.getFreshProtectedTrackMotionEvidence()
                            profiler.recordStage("faceOnlyPrivacy") {
                                processor.resolveFrame(
                                    frameTexture = renderTexId,
                                    texMatrix = finalTexMatrix,
                                    textureType = renderTexType,
                                    persons = trackedList,
                                    faceOnlyTrackIds = faceOnlyPersonIds,
                                    fullBodyTrackIds = selectedIds,
                                    protectedMotionEvidence = protectedMotionEvidence,
                                    ptsUs = ptsUs
                                )
                            }
                        }
                        if (faceOnlyFrameResult != null) {
                            faceDetectorCallCount += faceOnlyFrameResult.detectorCallCount
                            faceDetectorObservationCount += faceOnlyFrameResult.detectorObservationCount
                            faceDetectorZeroObservationCallCount += faceOnlyFrameResult.detectorZeroObservationCallCount
                            faceDetectorRejectedCallCount += faceOnlyFrameResult.detectorRejectedCallCount
                            faceDetectedTrackFrameCount += faceOnlyFrameResult.detectedTrackIds.size
                            facePredictedTrackFrameCount += faceOnlyFrameResult.predictedTrackIds.size
                            faceFallbackTrackFrameCount += faceOnlyFrameResult.fallbackTrackIds.size
                            faceBodyMaskGuidedTrackFrameCount += faceOnlyFrameResult.bodyMaskGuidedTrackIds.size
                            facePositionClampedTrackFrameCount += faceOnlyFrameResult.positionClampedTrackIds.size
                            faceBodyCompensatedTrackFrameCount += faceOnlyFrameResult.bodyCompensatedTrackIds.size
                            faceFreshBodyMotionTrackFrameCount += faceOnlyFrameResult.freshBodyMotionTrackIds.size
                            faceRecentBodyMotionBridgeTrackFrameCount += faceOnlyFrameResult.recentBodyMotionBridgeTrackIds.size
                            faceDormantReactivationProbeTrackFrameCount +=
                                faceOnlyFrameResult.dormantReactivationProbeTrackIds.size
                            faceDormantProbeMotionRejectedTrackFrameCount +=
                                faceOnlyFrameResult.dormantProbeMotionRejectedTrackIds.size
                            faceDormantReactivatedEventCount += faceOnlyFrameResult.dormantReactivatedTrackIds.size
                            faceDormantExactReacquiredTrackFrameCount +=
                                faceOnlyFrameResult.dormantExactReacquiredTrackIds.size
                            faceDormantSuppressedTrackFrameCount += faceOnlyFrameResult.dormantSuppressedTrackIds.size
                            faceDormantPixelMotionBridgeTrackFrameCount +=
                                faceOnlyFrameResult.dormantPixelMotionBridgeTrackIds.size
                            facePixelMotionTrackFrameCount += faceOnlyFrameResult.pixelMotionTrackIds.size
                            facePartialOcclusionPixelMotionTrackFrameCount +=
                                faceOnlyFrameResult.partialOcclusionPixelMotionTrackIds.size
                            facePixelMotionRejectedTrackFrameCount += faceOnlyFrameResult.pixelMotionRejectedTrackIds.size
                            faceOcclusionHoldTrackFrameCount += faceOnlyFrameResult.occlusionHoldTrackIds.size
                            faceOcclusionReacquireDetectorTrackFrameCount +=
                                faceOnlyFrameResult.occlusionReacquireDetectorTrackIds.size
                            faceAppearanceReacquireDetectorTrackFrameCount +=
                                faceOnlyFrameResult.appearanceReacquireDetectorTrackIds.size
                            faceEvidenceGapReacquireDetectorTrackFrameCount +=
                                faceOnlyFrameResult.evidenceGapReacquireDetectorTrackIds.size
                            faceEvidenceGapReacquireDetectorSuccessTrackFrameCount +=
                                faceOnlyFrameResult.evidenceGapReacquireDetectorSuccessTrackIds.size
                            faceEvidenceGapReacquireDetectorZeroObservationTrackFrameCount +=
                                faceOnlyFrameResult.evidenceGapReacquireDetectorZeroObservationTrackIds.size
                            faceEvidenceGapReacquireDetectorRejectedTrackFrameCount +=
                                faceOnlyFrameResult.evidenceGapReacquireDetectorRejectedTrackIds.size
                            faceOnlyFrameResult.detectorCalledTrackIds.forEach { trackId ->
                                faceDetectorCallsByTrackId[trackId] = faceDetectorCallsByTrackId.getOrDefault(trackId, 0L) + 1L
                            }
                            faceOnlyFrameResult.detectorRejectedTrackIds.forEach { trackId ->
                                faceDetectorRejectedCallsByTrackId[trackId] =
                                    faceDetectorRejectedCallsByTrackId.getOrDefault(trackId, 0L) + 1L
                            }
                            faceOnlyFrameResult.detectedTrackIds.forEach { trackId ->
                                faceDetectedFramesByTrackId[trackId] = faceDetectedFramesByTrackId.getOrDefault(trackId, 0L) + 1L
                            }
                            faceOnlyFrameResult.predictedTrackIds.forEach { trackId ->
                                facePredictedFramesByTrackId[trackId] = facePredictedFramesByTrackId.getOrDefault(trackId, 0L) + 1L
                            }
                            faceOnlyFrameResult.fallbackTrackIds.forEach { trackId ->
                                faceFallbackFramesByTrackId[trackId] = faceFallbackFramesByTrackId.getOrDefault(trackId, 0L) + 1L
                            }
                            faceOnlyFrameResult.bodyMaskGuidedTrackIds.forEach { trackId ->
                                faceBodyMaskGuidedFramesByTrackId[trackId] =
                                    faceBodyMaskGuidedFramesByTrackId.getOrDefault(trackId, 0L) + 1L
                            }
                            faceOnlyFrameResult.positionClampedTrackIds.forEach { trackId ->
                                facePositionClampedFramesByTrackId[trackId] =
                                    facePositionClampedFramesByTrackId.getOrDefault(trackId, 0L) + 1L
                            }
                            faceOnlyFrameResult.bodyCompensatedTrackIds.forEach { trackId ->
                                faceBodyCompensatedFramesByTrackId[trackId] =
                                    faceBodyCompensatedFramesByTrackId.getOrDefault(trackId, 0L) + 1L
                            }
                            faceOnlyFrameResult.freshBodyMotionTrackIds.forEach { trackId ->
                                faceFreshBodyMotionFramesByTrackId[trackId] =
                                    faceFreshBodyMotionFramesByTrackId.getOrDefault(trackId, 0L) + 1L
                            }
                            faceOnlyFrameResult.recentBodyMotionBridgeTrackIds.forEach { trackId ->
                                faceRecentBodyMotionBridgeFramesByTrackId[trackId] =
                                    faceRecentBodyMotionBridgeFramesByTrackId.getOrDefault(trackId, 0L) + 1L
                            }
                            faceOnlyFrameResult.dormantReactivationProbeTrackIds.forEach { trackId ->
                                faceDormantReactivationProbeFramesByTrackId[trackId] =
                                    faceDormantReactivationProbeFramesByTrackId.getOrDefault(trackId, 0L) + 1L
                            }
                            faceOnlyFrameResult.dormantProbeMotionRejectedTrackIds.forEach { trackId ->
                                faceDormantProbeMotionRejectedFramesByTrackId[trackId] =
                                    faceDormantProbeMotionRejectedFramesByTrackId.getOrDefault(trackId, 0L) + 1L
                            }
                            faceOnlyFrameResult.dormantReactivatedTrackIds.forEach { trackId ->
                                faceDormantReactivatedEventsByTrackId[trackId] =
                                    faceDormantReactivatedEventsByTrackId.getOrDefault(trackId, 0L) + 1L
                            }
                            faceOnlyFrameResult.dormantExactReacquiredTrackIds.forEach { trackId ->
                                faceDormantExactReacquiredFramesByTrackId[trackId] =
                                    faceDormantExactReacquiredFramesByTrackId.getOrDefault(trackId, 0L) + 1L
                            }
                            faceOnlyFrameResult.dormantSuppressedTrackIds.forEach { trackId ->
                                faceDormantSuppressedFramesByTrackId[trackId] =
                                    faceDormantSuppressedFramesByTrackId.getOrDefault(trackId, 0L) + 1L
                            }
                            faceOnlyFrameResult.dormantPixelMotionBridgeTrackIds.forEach { trackId ->
                                faceDormantPixelMotionBridgeFramesByTrackId[trackId] =
                                    faceDormantPixelMotionBridgeFramesByTrackId.getOrDefault(trackId, 0L) + 1L
                            }
                            faceOnlyFrameResult.pixelMotionTrackIds.forEach { trackId ->
                                facePixelMotionFramesByTrackId[trackId] =
                                    facePixelMotionFramesByTrackId.getOrDefault(trackId, 0L) + 1L
                            }
                            faceOnlyFrameResult.partialOcclusionPixelMotionTrackIds.forEach { trackId ->
                                facePartialOcclusionPixelMotionFramesByTrackId[trackId] =
                                    facePartialOcclusionPixelMotionFramesByTrackId.getOrDefault(trackId, 0L) + 1L
                            }
                            faceOnlyFrameResult.pixelMotionRejectedTrackIds.forEach { trackId ->
                                facePixelMotionRejectedFramesByTrackId[trackId] =
                                    facePixelMotionRejectedFramesByTrackId.getOrDefault(trackId, 0L) + 1L
                            }
                            faceOnlyFrameResult.occlusionHoldTrackIds.forEach { trackId ->
                                faceOcclusionHoldFramesByTrackId[trackId] =
                                    faceOcclusionHoldFramesByTrackId.getOrDefault(trackId, 0L) + 1L
                            }
                            faceOnlyFrameResult.occlusionReacquireDetectorTrackIds.forEach { trackId ->
                                faceOcclusionReacquireDetectorFramesByTrackId[trackId] =
                                    faceOcclusionReacquireDetectorFramesByTrackId.getOrDefault(trackId, 0L) + 1L
                            }
                            faceOnlyFrameResult.appearanceReacquireDetectorTrackIds.forEach { trackId ->
                                faceAppearanceReacquireDetectorFramesByTrackId[trackId] =
                                    faceAppearanceReacquireDetectorFramesByTrackId.getOrDefault(trackId, 0L) + 1L
                            }
                            faceOnlyFrameResult.evidenceGapReacquireDetectorTrackIds.forEach { trackId ->
                                faceEvidenceGapReacquireDetectorFramesByTrackId[trackId] =
                                    faceEvidenceGapReacquireDetectorFramesByTrackId.getOrDefault(trackId, 0L) + 1L
                            }
                            faceOnlyFrameResult.evidenceGapReacquireDetectorSuccessTrackIds.forEach { trackId ->
                                faceEvidenceGapReacquireDetectorSuccessFramesByTrackId[trackId] =
                                    faceEvidenceGapReacquireDetectorSuccessFramesByTrackId.getOrDefault(trackId, 0L) + 1L
                            }
                            faceOnlyFrameResult.evidenceGapReacquireDetectorZeroObservationTrackIds.forEach { trackId ->
                                faceEvidenceGapReacquireDetectorZeroObservationFramesByTrackId[trackId] =
                                    faceEvidenceGapReacquireDetectorZeroObservationFramesByTrackId.getOrDefault(trackId, 0L) + 1L
                            }
                            faceOnlyFrameResult.evidenceGapReacquireDetectorRejectedTrackIds.forEach { trackId ->
                                faceEvidenceGapReacquireDetectorRejectedFramesByTrackId[trackId] =
                                    faceEvidenceGapReacquireDetectorRejectedFramesByTrackId.getOrDefault(trackId, 0L) + 1L
                            }
                            faceOnlyFrameResult.pixelMotionRejectReasonByTrackId.forEach { (trackId, reason) ->
                                facePixelMotionRejectReasonCounts[reason] =
                                    facePixelMotionRejectReasonCounts.getOrDefault(reason, 0L) + 1L
                                val perTrack = facePixelMotionRejectReasonsByTrackId.getOrPut(trackId) { mutableMapOf() }
                                perTrack[reason] = perTrack.getOrDefault(reason, 0L) + 1L
                            }
                            faceOnlyFrameResult.dormantSuppressionReasonByTrackId.forEach { (trackId, reason) ->
                                faceDormantSuppressionReasonCounts[reason] =
                                    faceDormantSuppressionReasonCounts.getOrDefault(reason, 0L) + 1L
                                val perTrack = faceDormantSuppressionReasonsByTrackId.getOrPut(trackId) { mutableMapOf() }
                                perTrack[reason] = perTrack.getOrDefault(reason, 0L) + 1L
                            }
                            faceOnlyFrameResult.stickerPlacements.forEach { placement ->
                                val trackId = placement.trackId
                                val width = placement.sourceRect.width
                                val height = placement.sourceRect.height
                                val centerX = placement.sourceRect.centerX
                                val centerY = placement.sourceRect.centerY
                                faceStickerMinWidthByTrackId[trackId] = minOf(faceStickerMinWidthByTrackId[trackId] ?: width, width)
                                faceStickerMaxWidthByTrackId[trackId] = maxOf(faceStickerMaxWidthByTrackId[trackId] ?: width, width)
                                faceStickerMinHeightByTrackId[trackId] = minOf(faceStickerMinHeightByTrackId[trackId] ?: height, height)
                                faceStickerMaxHeightByTrackId[trackId] = maxOf(faceStickerMaxHeightByTrackId[trackId] ?: height, height)
                                if (faceOnlyFrameResult.dormantReactivatedTrackIds.contains(trackId)) {
                                    faceDormantReactivationStickerMaxWidthByTrackId[trackId] = maxOf(
                                        faceDormantReactivationStickerMaxWidthByTrackId[trackId] ?: width,
                                        width
                                    )
                                    faceDormantReactivationStickerMaxHeightByTrackId[trackId] = maxOf(
                                        faceDormantReactivationStickerMaxHeightByTrackId[trackId] ?: height,
                                        height
                                    )
                                }
                                faceStickerLastCenterByTrackId[trackId]?.let { previous ->
                                    val dx = centerX - previous.first
                                    val dy = centerY - previous.second
                                    val step = sqrt(dx * dx + dy * dy)
                                    faceStickerMaxCenterStepByTrackId[trackId] = maxOf(
                                        faceStickerMaxCenterStepByTrackId[trackId] ?: 0f,
                                        step
                                    )
                                    if (faceOnlyFrameResult.partialOcclusionPixelMotionTrackIds.contains(trackId)) {
                                        facePartialOcclusionMaxCenterStepByTrackId[trackId] = maxOf(
                                            facePartialOcclusionMaxCenterStepByTrackId[trackId] ?: 0f,
                                            step
                                        )
                                    }
                                    if (faceStickerLastPlacementFrameByTrackId[trackId] == processedFrames - 1) {
                                        faceStickerMaxConsecutiveCenterStepByTrackId[trackId] = maxOf(
                                            faceStickerMaxConsecutiveCenterStepByTrackId[trackId] ?: 0f,
                                            step
                                        )
                                        if (faceOnlyFrameResult.partialOcclusionPixelMotionTrackIds.contains(trackId)) {
                                            facePartialOcclusionMaxConsecutiveCenterStepByTrackId[trackId] = maxOf(
                                                facePartialOcclusionMaxConsecutiveCenterStepByTrackId[trackId] ?: 0f,
                                                step
                                            )
                                        }
                                    }
                                }
                                faceStickerLastCenterByTrackId[trackId] = centerX to centerY
                                faceStickerLastPlacementFrameByTrackId[trackId] = processedFrames
                            }
                            if (faceOnlyFrameResult.faceInferenceMs > 0.0) {
                                profiler.recordSample(
                                    "faceDetectorCpu",
                                    faceOnlyFrameResult.faceInferenceMs.toLong().coerceAtLeast(0L)
                                )
                            }
                            if (faceOnlyFrameResult.pixelMotionMs > 0.0) {
                                profiler.recordSample(
                                    "facePixelMotionCpu",
                                    faceOnlyFrameResult.pixelMotionMs.toLong().coerceAtLeast(0L)
                                )
                            }
                            if (faceOnlyFrameResult.roiReadbackMs > 0.0) {
                                profiler.recordSample(
                                    "faceRoiReadback",
                                    faceOnlyFrameResult.roiReadbackMs.toLong().coerceAtLeast(0L)
                                )
                            }
                            if (faceOnlyFrameResult.maskBuildMs > 0.0) {
                                profiler.recordSample(
                                    "faceMaskBuild",
                                    faceOnlyFrameResult.maskBuildMs.toLong().coerceAtLeast(0L)
                                )
                            }
                            if (faceOnlyFrameResult.privacyResolveMs > 0.0) {
                                profiler.recordSample(
                                    "facePrivacyResolve",
                                    faceOnlyFrameResult.privacyResolveMs.toLong().coerceAtLeast(0L)
                                )
                            }
                            if (!faceOnlyFrameResult.readyForRender) {
                                com.danceanon.native.diagnostics.NativeDiagnostics.event(
                                    level = "CRITICAL",
                                    component = "ExportPipeline",
                                    event = "FACE_PRIVACY_UNRESOLVED",
                                    fields = mapOf(
                                        "job_id" to jobId,
                                        "frame" to processedFrames,
                                        "pts_us" to ptsUs,
                                        "face_only_ids" to faceOnlyPersonIds.sorted(),
                                        "unresolved_ids" to faceOnlyFrameResult.unresolvedTrackIds.sorted(),
                                        "fallback_ids" to faceOnlyFrameResult.fallbackTrackIds.sorted(),
                                        "escalated_full_body_ids" to faceOnlyFrameResult.escalatedFullBodyTrackIds.sorted()
                                    )
                                )
                                throw DanceNativeException(
                                    DanceNativeException.EXPORT_FAILED,
                                    "FACE_ONLY privacy unresolved for track(s) ${faceOnlyFrameResult.unresolvedTrackIds.sorted()}"
                                )
                            }
                        }

                        // Validate selected target survival with rate-limited telemetry (PHASE A & D)
                        val trackedIds = trackedList.map { it.id }.toSet()
                        for (sId in allPrivacyTargetIds) {
                            if (!trackedIds.contains(sId)) {
                                val streak = missingTargetStreakMap.getOrDefault(sId, 0) + 1
                                missingTargetStreakMap[sId] = streak

                                if (streak == 1) {
                                    com.danceanon.native.diagnostics.NativeDiagnostics.event(
                                        level = "CRITICAL",
                                        component = "ExportPipeline",
                                        event = "SELECTED_TARGET_MISSING",
                                        fields = mapOf(
                                            "job_id" to jobId,
                                            "selected_id" to sId,
                                            "frame" to processedFrames,
                                            "pts_us" to ptsUs,
                                            "missing_streak" to streak,
                                            "tracked_ids" to trackedIds.toList(),
                                            "tracked_states" to trackedList.map { "${it.id}:${it.state.name}" }
                                        )
                                    )
                                } else if (streak % 30 == 0) {
                                    com.danceanon.native.diagnostics.NativeDiagnostics.event(
                                        level = "WARN",
                                        component = "ExportPipeline",
                                        event = "SELECTED_TARGET_MISSING_SAMPLED",
                                        fields = mapOf(
                                            "job_id" to jobId,
                                            "selected_id" to sId,
                                            "frame" to processedFrames,
                                            "pts_us" to ptsUs,
                                            "missing_streak" to streak,
                                            "tracked_ids" to trackedIds.toList(),
                                            "tracked_states" to trackedList.map { "${it.id}:${it.state.name}" }
                                        )
                                    )
                                }
                            } else {
                                val previousMissing = missingTargetStreakMap.remove(sId)
                                if (previousMissing != null && previousMissing > 0) {
                                    com.danceanon.native.diagnostics.NativeDiagnostics.event(
                                        level = "INFO",
                                        component = "ExportPipeline",
                                        event = "SELECTED_TARGET_RECOVERED",
                                        fields = mapOf(
                                            "job_id" to jobId,
                                            "selected_id" to sId,
                                            "frame" to processedFrames,
                                            "pts_us" to ptsUs,
                                            "missing_duration_frames" to previousMissing
                                        )
                                    )
                                }
                            }
                        }

                        // 4. Render final anonymized frame to EGL surface (encoder input)
                        profiler.recordStage("renderEffects") {
                            glRenderer.render(
                                frameTexture = renderTexId,
                                texMatrix = renderTexMatrix,
                                persons = trackedList,
                                selectedPersonIds = selectedIds,
                                effects = request.effects,
                                follow = request.follow,
                                presentationTimeUs = ptsUs,
                                textureType = renderTexType,
                                freshPrivacyClassEvidence = freshPrivacyClassEvidence,
                                freshSelectedCoveredTrackIds = freshSelectedCoveredTrackIds,
                                suppressedSelectedPrivacyTrackIds = suppressedSelectedPrivacyTrackIds,
                                preferFreshPrivacyClassPrimary = preferFreshPrivacyClassPrimary,
                                expectedSelectedPrivacyCount = selectedIds.size,
                                maxFallbackObservationAgeFrames = trackManager.getMaxMissedFrames(),
                                additionalResolvedPrivacy = faceOnlyFrameResult?.resolvedPrivacy,
                                faceStickerPlacements = faceOnlyFrameResult?.stickerPlacements.orEmpty()
                            )
                            renderedFrameCount++
                        }


                    // Optional live preview capture when enabled (async background IO)
                    val now = System.currentTimeMillis()
                    if (request.enableLivePreview && (now - lastLivePreviewCaptureTime > 350 || processedFrames == 1)) {
                        lastLivePreviewCaptureTime = now
                        if (isPreviewSaving.compareAndSet(false, true)) {
                            val capturedBmp = glRenderer.captureRenderedFrame()
                            if (capturedBmp != null) {
                                previewFlip = 1 - previewFlip
                                val flipIndex = previewFlip
                                previewScope?.launch {
                                    try {
                                        val scale = minOf(1.0f, 480f / maxOf(capturedBmp.width, capturedBmp.height))
                                        val previewBmp = if (scale < 1.0f) {
                                            android.graphics.Bitmap.createScaledBitmap(
                                                capturedBmp,
                                                (capturedBmp.width * scale).toInt().coerceAtLeast(1),
                                                (capturedBmp.height * scale).toInt().coerceAtLeast(1),
                                                true
                                            )
                                        } else {
                                            capturedBmp
                                        }
                                        val targetPreviewFile = java.io.File(livePreviewDir, "preview_${jobId}_$flipIndex.jpg")
                                        val tempPreview = java.io.File(livePreviewDir, "preview_${jobId}_tmp_$flipIndex.jpg")
                                        java.io.FileOutputStream(tempPreview).use { out ->
                                            previewBmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 75, out)
                                        }
                                        if (previewBmp !== capturedBmp) {
                                            previewBmp.recycle()
                                        }
                                        capturedBmp.recycle()
                                        if (tempPreview.exists()) {
                                            if (targetPreviewFile.exists()) targetPreviewFile.delete()
                                            tempPreview.renameTo(targetPreviewFile)
                                            lastPreviewFilePath = targetPreviewFile.absolutePath
                                        }
                                    } catch (e: Throwable) {
                                        try { capturedBmp.recycle() } catch (_: Throwable) {}
                                        android.util.Log.w("ExportPipeline", "Live preview capture async warning: ${e.message}")
                                    } finally {
                                        isPreviewSaving.set(false)
                                    }
                                }
                            } else {
                                isPreviewSaving.set(false)
                            }
                        }
                    }

                    // 2. Swap buffers to push rendered frame to hardware encoder with smooth monotonic PTS
                    if (eglSurface != null) {
                        if (basePtsUs < 0L) {
                            basePtsUs = ptsUs
                        }
                        val relPtsNs = (ptsUs - basePtsUs).coerceAtLeast(0L) * 1000L
                        val presentationNs = if (relPtsNs > lastPresentationNs) {
                            relPtsNs
                        } else {
                            if (lastPresentationNs >= 0L) lastPresentationNs + frameDurationNs else 0L
                        }
                        lastPresentationNs = presentationNs
                        eglCore.setPresentationTime(eglSurface, presentationNs)
                        val swapSuccess = eglCore.swapBuffers(eglSurface)
                        if (!swapSuccess) {
                            android.util.Log.e(
                                "ExportPipeline",
                                "[Stage 2 Error] eglSwapBuffers returned false on frame #$processedFrames (pts=${ptsUs}us). Encoder surface handoff failed!"
                            )
                        } else {
                            encodedFrameCount++
                            lastEncoderPtsUs = presentationNs / 1000L
                        }
                    }

                    // 3. Drain encoder output to MP4 muxer (Stage 3 packet writing)
                    profiler.recordStage("drainEncoder") {
                        encoder.drainEncoder(muxer, endOfStream = false)
                    }

                    // 4. Emit progress based on presentation timestamp
                    if (now - lastProgressEmitTime > 200 || processedFrames % 5 == 0) {
                        lastProgressEmitTime = now
                        val elapsedSec = (now - startTime) / 1000.0
                        val currentFps = if (elapsedSec > 0) processedFrames / elapsedSec else 0.0
                        val durationUs = videoInfo.durationMs * 1000L
                        val progress = if (durationUs > 0) {
                            (ptsUs.toDouble() / durationUs).coerceIn(0.0, 0.99)
                        } else {
                            (processedFrames.toDouble() / totalEstFrames).coerceIn(0.0, 0.99)
                        }

                        status = status.copy(
                            state = "processing",
                            currentFrame = processedFrames.toLong(),
                            fps = currentFps,
                            progress = progress,
                            outputUri = null,
                            currentPreviewPath = lastPreviewFilePath ?: status.currentPreviewPath
                        )
                        emitProgress(status, onStatusChange)
                    }
                }

                android.util.Log.i(
                    "ExportPipeline",
                    "[Pipeline Telemetry] decoded=$decodedFrameCount, latched=$latchedFrameCount, rendered=$renderedFrameCount, encoded=$encodedFrameCount, lastDecPts=${lastDecoderPtsUs}us, lastEncPts=${lastEncoderPtsUs}us"
                )
                if (faceOnlyPersonIds.isNotEmpty()) {
                    android.util.Log.i(
                        "ExportPipeline",
                        "[FaceOnly Telemetry] detectorCalls=$faceDetectorCallCount detectedTrackFrames=$faceDetectedTrackFrameCount " +
                            "predictedTrackFrames=$facePredictedTrackFrameCount fallbackTrackFrames=$faceFallbackTrackFrameCount " +
                            "observations=$faceDetectorObservationCount zeroObservationCalls=$faceDetectorZeroObservationCallCount " +
                            "rejectedCalls=$faceDetectorRejectedCallCount callsByTrack=$faceDetectorCallsByTrackId " +
                            "rejectedByTrack=$faceDetectorRejectedCallsByTrackId " +
                            "detectedByTrack=$faceDetectedFramesByTrackId predictedByTrack=$facePredictedFramesByTrackId " +
                            "fallbackByTrack=$faceFallbackFramesByTrackId"
                    )
                }



                if (isCancelled.get()) {
                    tempOutFile.delete()
                    inferenceFbo.close()
                    inferenceRenderer.close()
                    status = status.copy(state = "cancelled")
                    emitProgress(status, onStatusChange)
                    return@post
                }

                // Drain remaining encoder output
                encoder.drainEncoder(muxer, endOfStream = true)

                if (isCancelled.get()) {
                    tempOutFile.delete()
                    inferenceFbo.close()
                    inferenceRenderer.close()
                    status = status.copy(state = "cancelled")
                    emitProgress(status, onStatusChange)
                    return@post
                }

                // Copy audio
                if (hasAudioTrack && audioCopier.audioTrackIndexInSource >= 0) {
                    audioCopier.copyToMuxer(muxer)
                }

                // Close pipeline resources
                sam2Fbo?.close()
                sam2Renderer?.close()
                sam2Tracker?.close()
                inferenceFbo.close()
                inferenceRenderer.close()
                profiler.printSummary(jobId)


                muxer.close()
                decoder.close()
                audioCopier.close()
                encoder.close()
                eglCore.releaseSurface(eglSurface)
                eglCore.close()

                if (isCancelled.get()) {
                    tempOutFile.delete()
                    status = status.copy(state = "cancelled")
                    emitProgress(status, onStatusChange)
                    com.danceanon.native.diagnostics.NativeDiagnostics.recordPipelineLifecycle(
                        stage = "CANCELLED",
                        jobId = jobId,
                        fields = mapOf("rendered_frames" to renderedFrameCount)
                    )
                    return@post
                }

                // Atomically finalize output file
                if (tempOutFile.exists()) {
                    if (finalOutFile.exists()) finalOutFile.delete()
                    tempOutFile.renameTo(finalOutFile)
                }


                status = status.copy(
                    state = "completed",
                    progress = 1.0,
                    currentFrame = totalFrames.toLong(),
                    outputUri = finalOutFile.absolutePath
                )
                emitProgress(status, onStatusChange)

                try {
                    val p95DeltaUs = if (surfacePtsDeltaSamples.isNotEmpty()) {
                        val sorted = surfacePtsDeltaSamples.sorted()
                        sorted[(sorted.size * 0.95).toInt().coerceAtMost(sorted.size - 1)]
                    } else 0L

                    com.danceanon.native.diagnostics.NativeDiagnostics.recordPipelineSummary(
                        jobId = jobId,
                        summary = mapOf(
                            "job_id" to jobId,
                            "profile" to request.processingProfile,
                            "source_width" to videoInfo.displayWidth,
                            "source_height" to videoInfo.displayHeight,
                            "source_fps" to videoInfo.fps,
                            "target_width" to targetWidth,
                            "target_height" to targetHeight,
                            "target_fps" to targetFps,
                            "selected_ids" to fullBodyPersonIds.sorted(),
                            "face_only_ids" to faceOnlyPersonIds.sorted(),
                            "decoded_frames" to decodedFrameCount,
                            "latched_frames" to latchedFrameCount,
                            "rendered_frames" to renderedFrameCount,
                            "encoded_frames" to encodedFrameCount,
                            "face_detector_call_count" to faceDetectorCallCount,
                            "face_detector_observation_count" to faceDetectorObservationCount,
                            "face_detector_zero_observation_call_count" to faceDetectorZeroObservationCallCount,
                            "face_detector_rejected_call_count" to faceDetectorRejectedCallCount,
                            "face_detected_track_frames" to faceDetectedTrackFrameCount,
                            "face_predicted_track_frames" to facePredictedTrackFrameCount,
                            "face_fallback_track_frames" to faceFallbackTrackFrameCount,
                            "face_body_mask_guided_track_frames" to faceBodyMaskGuidedTrackFrameCount,
                            "face_position_clamped_track_frames" to facePositionClampedTrackFrameCount,
                            "face_body_compensated_track_frames" to faceBodyCompensatedTrackFrameCount,
                            "face_fresh_body_motion_track_frames" to faceFreshBodyMotionTrackFrameCount,
                            "face_recent_body_motion_bridge_track_frames" to faceRecentBodyMotionBridgeTrackFrameCount,
                            "face_dormant_reactivation_probe_track_frames" to faceDormantReactivationProbeTrackFrameCount,
                            "face_dormant_probe_motion_rejected_track_frames" to faceDormantProbeMotionRejectedTrackFrameCount,
                            "face_dormant_reactivated_by_face_detection_events" to faceDormantReactivatedEventCount,
                            "face_dormant_exact_reacquired_track_frames" to faceDormantExactReacquiredTrackFrameCount,
                            "face_dormant_suppressed_track_frames" to faceDormantSuppressedTrackFrameCount,
                            "face_dormant_pixel_motion_bridge_track_frames" to faceDormantPixelMotionBridgeTrackFrameCount,
                            "face_pixel_motion_track_frames" to facePixelMotionTrackFrameCount,
                            "face_partial_occlusion_pixel_motion_track_frames" to facePartialOcclusionPixelMotionTrackFrameCount,
                            "face_pixel_motion_rejected_track_frames" to facePixelMotionRejectedTrackFrameCount,
                            "face_occlusion_hold_track_frames" to faceOcclusionHoldTrackFrameCount,
                            "face_occlusion_reacquire_detector_track_frames" to faceOcclusionReacquireDetectorTrackFrameCount,
                            "face_appearance_reacquire_detector_track_frames" to faceAppearanceReacquireDetectorTrackFrameCount,
                            "face_evidence_gap_reacquire_detector_track_frames" to faceEvidenceGapReacquireDetectorTrackFrameCount,
                            "face_evidence_gap_reacquire_detector_success_track_frames" to faceEvidenceGapReacquireDetectorSuccessTrackFrameCount,
                            "face_evidence_gap_reacquire_detector_zero_observation_track_frames" to faceEvidenceGapReacquireDetectorZeroObservationTrackFrameCount,
                            "face_evidence_gap_reacquire_detector_rejected_track_frames" to faceEvidenceGapReacquireDetectorRejectedTrackFrameCount,
                            "face_occlusion_hold_max_age_us" to FaceOcclusionBridgePolicy.MAX_HOLD_AGE_US,
                            "face_pixel_motion_backend" to "SOURCE_ROI_256",
                            "face_pixel_motion_evidence_gap_us" to FacePixelMotionTracker.ROI_MAX_EVIDENCE_GAP_US,
                            "face_pixel_motion_detector_seed_max_age_us" to FacePixelMotionTracker.ROI_MAX_DETECTOR_SEED_AGE_US,
                            "face_detector_calls_by_track_id" to faceDetectorCallsByTrackId.toSortedMap().mapKeys { it.key.toString() },
                            "face_detector_rejected_calls_by_track_id" to faceDetectorRejectedCallsByTrackId.toSortedMap().mapKeys { it.key.toString() },
                            "face_detected_frames_by_track_id" to faceDetectedFramesByTrackId.toSortedMap().mapKeys { it.key.toString() },
                            "face_predicted_frames_by_track_id" to facePredictedFramesByTrackId.toSortedMap().mapKeys { it.key.toString() },
                            "face_fallback_frames_by_track_id" to faceFallbackFramesByTrackId.toSortedMap().mapKeys { it.key.toString() },
                            "face_body_mask_guided_frames_by_track_id" to faceBodyMaskGuidedFramesByTrackId.toSortedMap().mapKeys { it.key.toString() },
                            "face_position_clamped_frames_by_track_id" to facePositionClampedFramesByTrackId.toSortedMap().mapKeys { it.key.toString() },
                            "face_body_compensated_frames_by_track_id" to faceBodyCompensatedFramesByTrackId.toSortedMap().mapKeys { it.key.toString() },
                            "face_fresh_body_motion_frames_by_track_id" to faceFreshBodyMotionFramesByTrackId.toSortedMap().mapKeys { it.key.toString() },
                            "face_recent_body_motion_bridge_frames_by_track_id" to faceRecentBodyMotionBridgeFramesByTrackId.toSortedMap().mapKeys { it.key.toString() },
                            "face_dormant_reactivation_probe_frames_by_track_id" to faceDormantReactivationProbeFramesByTrackId.toSortedMap().mapKeys { it.key.toString() },
                            "face_dormant_probe_motion_rejected_frames_by_track_id" to faceDormantProbeMotionRejectedFramesByTrackId.toSortedMap().mapKeys { it.key.toString() },
                            "face_dormant_reactivated_by_face_detection_events_by_track_id" to faceDormantReactivatedEventsByTrackId.toSortedMap().mapKeys { it.key.toString() },
                            "face_dormant_exact_reacquired_frames_by_track_id" to faceDormantExactReacquiredFramesByTrackId.toSortedMap().mapKeys { it.key.toString() },
                            "face_dormant_suppressed_frames_by_track_id" to faceDormantSuppressedFramesByTrackId.toSortedMap().mapKeys { it.key.toString() },
                            "face_dormant_pixel_motion_bridge_frames_by_track_id" to faceDormantPixelMotionBridgeFramesByTrackId.toSortedMap().mapKeys { it.key.toString() },
                            "face_pixel_motion_frames_by_track_id" to facePixelMotionFramesByTrackId.toSortedMap().mapKeys { it.key.toString() },
                            "face_partial_occlusion_pixel_motion_frames_by_track_id" to facePartialOcclusionPixelMotionFramesByTrackId.toSortedMap().mapKeys { it.key.toString() },
                            "face_pixel_motion_rejected_frames_by_track_id" to facePixelMotionRejectedFramesByTrackId.toSortedMap().mapKeys { it.key.toString() },
                            "face_occlusion_hold_frames_by_track_id" to faceOcclusionHoldFramesByTrackId.toSortedMap().mapKeys { it.key.toString() },
                            "face_occlusion_reacquire_detector_frames_by_track_id" to faceOcclusionReacquireDetectorFramesByTrackId.toSortedMap().mapKeys { it.key.toString() },
                            "face_appearance_reacquire_detector_frames_by_track_id" to faceAppearanceReacquireDetectorFramesByTrackId.toSortedMap().mapKeys { it.key.toString() },
                            "face_evidence_gap_reacquire_detector_frames_by_track_id" to faceEvidenceGapReacquireDetectorFramesByTrackId.toSortedMap().mapKeys { it.key.toString() },
                            "face_evidence_gap_reacquire_detector_success_frames_by_track_id" to faceEvidenceGapReacquireDetectorSuccessFramesByTrackId.toSortedMap().mapKeys { it.key.toString() },
                            "face_evidence_gap_reacquire_detector_zero_observation_frames_by_track_id" to faceEvidenceGapReacquireDetectorZeroObservationFramesByTrackId.toSortedMap().mapKeys { it.key.toString() },
                            "face_evidence_gap_reacquire_detector_rejected_frames_by_track_id" to faceEvidenceGapReacquireDetectorRejectedFramesByTrackId.toSortedMap().mapKeys { it.key.toString() },
                            "face_pixel_motion_reject_reasons" to facePixelMotionRejectReasonCounts.toSortedMap(),
                            "face_pixel_motion_reject_reasons_by_track_id" to facePixelMotionRejectReasonsByTrackId
                                .toSortedMap()
                                .mapKeys { it.key.toString() }
                                .mapValues { (_, reasons) -> reasons.toSortedMap() },
                            "face_dormant_suppression_reasons" to faceDormantSuppressionReasonCounts.toSortedMap(),
                            "face_dormant_suppression_reasons_by_track_id" to faceDormantSuppressionReasonsByTrackId
                                .toSortedMap()
                                .mapKeys { it.key.toString() }
                                .mapValues { (_, reasons) -> reasons.toSortedMap() },
                            "face_dormant_reactivation_sticker_max_width_by_track_id" to faceDormantReactivationStickerMaxWidthByTrackId.toSortedMap().mapKeys { it.key.toString() },
                            "face_dormant_reactivation_sticker_max_height_by_track_id" to faceDormantReactivationStickerMaxHeightByTrackId.toSortedMap().mapKeys { it.key.toString() },
                            "face_sticker_min_width_by_track_id" to faceStickerMinWidthByTrackId.toSortedMap().mapKeys { it.key.toString() },
                            "face_sticker_max_width_by_track_id" to faceStickerMaxWidthByTrackId.toSortedMap().mapKeys { it.key.toString() },
                            "face_sticker_min_height_by_track_id" to faceStickerMinHeightByTrackId.toSortedMap().mapKeys { it.key.toString() },
                            "face_sticker_max_height_by_track_id" to faceStickerMaxHeightByTrackId.toSortedMap().mapKeys { it.key.toString() },
                            "face_sticker_max_center_step_by_track_id" to faceStickerMaxCenterStepByTrackId.toSortedMap().mapKeys { it.key.toString() },
                            "face_sticker_max_consecutive_center_step_by_track_id" to faceStickerMaxConsecutiveCenterStepByTrackId.toSortedMap().mapKeys { it.key.toString() },
                            "face_partial_occlusion_max_center_step_by_track_id" to facePartialOcclusionMaxCenterStepByTrackId.toSortedMap().mapKeys { it.key.toString() },
                            "face_partial_occlusion_max_consecutive_center_step_by_track_id" to facePartialOcclusionMaxConsecutiveCenterStepByTrackId.toSortedMap().mapKeys { it.key.toString() },
                            "fresh_full_body_class_primary_enabled" to allowFreshFullBodyClassPrimary,
                            "conservative_mixed_full_body_occluder_policy_enabled" to false,
                            "surface_wait_timeout_count" to surfaceWaitTimeoutCount,
                            "duplicate_surface_timestamp_count" to duplicateSurfaceTimestampCount,
                            "non_monotonic_surface_timestamp_count" to nonMonotonicSurfaceTimestampCount,
                            "max_surface_pts_delta_us" to maxAbsSurfacePtsDeltaUs,
                            "p95_surface_pts_delta_us" to p95DeltaUs,
                            "stage_timings" to profiler.snapshotSummary(),
                            "yolo_requested_accelerator" to yoloRequestedAccelerator,
                            "yolo_effective_accelerator" to yoloEffectiveAccelerator.name,
                            "yolo_gpu_fallback_reason" to yoloFallbackReason,
                            "yolo_inference_input_path" to if (canonicalValidationWindowCompleted) {
                                "CANONICAL_YUV_CPU_THEN_SURFACE_OES_RGBA"
                            } else if (canonicalInferenceDecoder != null) {
                                "CANONICAL_YUV_CPU"
                            } else {
                                "SURFACE_OES_RGBA"
                            },
                            "canonical_yuv_validation_scope" to "FULL_EXPORT_DEBUG",
                            "canonical_yuv_validation_window_us" to -1L,
                            "canonical_yuv_validation_window_completed" to canonicalValidationWindowCompleted,
                            "canonical_yuv_fallback_reason" to canonicalInferenceFallbackReason,
                            "cpu_single_thread_reference_window_us" to CPU_SINGLE_THREAD_REFERENCE_MAX_PTS_US,
                            "cpu_determinism_probe_fallback_reason" to cpuDeterminismProbeFallbackReason,
                            "cpu_mt2_probe_threads" to CPU_MT2_PROBE_THREADS,
                            "cpu_mt2_probe_fallback_reason" to cpuMt2ProbeFallbackReason,
                            "cpu_mt4_probe_threads" to CPU_MT_PROBE_THREADS,
                            "cpu_mt4_signature_scope" to "FULL_EXPORT",
                            "cpu_mt4_shadow_bbox_grid_px" to com.danceanon.native.diagnostics.CrossDeviceTrackingDiagnostics.DEFAULT_BBOX_GRID_PX,
                            "cpu_mt4_probe_fallback_reason" to cpuMt4ProbeFallbackReason,
                            "state" to "completed"
                        )
                    )
                    com.danceanon.native.diagnostics.NativeDiagnostics.recordPipelineLifecycle(
                        stage = "COMPLETED",
                        jobId = jobId,
                        fields = mapOf(
                            "decoded_frames" to decodedFrameCount,
                            "rendered_frames" to renderedFrameCount,
                            "encoded_frames" to encodedFrameCount,
                            "yolo_requested_accelerator" to yoloRequestedAccelerator,
                            "yolo_effective_accelerator" to yoloEffectiveAccelerator.name,
                            "yolo_gpu_fallback_reason" to yoloFallbackReason
                        )
                    )
                } catch (_: Throwable) {}

            } catch (e: Throwable) {
                tempOutFile.delete()
                val stackTraceStr = android.util.Log.getStackTraceString(e)
                android.util.Log.e("ExportPipeline", "Export failed: $stackTraceStr", e)
                status = status.copy(
                    state = "failed",
                    errorCode = "EXPORT_FAILED",
                    errorMessage = "${e.javaClass.simpleName}: ${e.message}\n${e.stackTrace.take(8).joinToString("\n")}"
                )
                emitProgress(status, onStatusChange)
                com.danceanon.native.diagnostics.NativeDiagnostics.recordPipelineLifecycle(
                    stage = "FAILED",
                    jobId = jobId,
                    fields = mapOf(
                        "error_type" to e.javaClass.simpleName
                    )
                )
                pipelineException = e
            } finally {
                try { previewScope?.cancel() } catch (_: Throwable) {}
                try { cpuDeterminismProbeSegmenter?.close() } catch (_: Throwable) {}
                try { cpuMt2ProbeSegmenter?.close() } catch (_: Throwable) {}
                try { cpuMt4ProbeSegmenter?.close() } catch (_: Throwable) {}
                try { canonicalInferenceDecoder?.close() } catch (_: Throwable) {}
                try { decoder?.close() } catch (_: Throwable) {}
                // Run the independent CPU-readable decoder probe only after the production
                // decoder is fully closed. This keeps the diagnostic from warming, occupying,
                // or otherwise perturbing the decoder/OES path whose behavior we are measuring.
                if (
                    com.danceanon.dance_native.BuildConfig.DEBUG &&
                    pipelineException == null &&
                    !isCancelled.get()
                ) {
                    com.danceanon.native.diagnostics.VideoYuvDiagnosticSampler.capture(
                        context = context,
                        sourceUri = sourceUri,
                        jobId = jobId
                    )
                }
                try { decoderSurface?.release() } catch (_: Throwable) {}
                try { frameReader?.close() } catch (_: Throwable) {}
                try { surfaceTexture?.release() } catch (_: Throwable) {}
                if (oesTextureId != 0) {
                    try { GLES20.glDeleteTextures(1, intArrayOf(oesTextureId), 0) } catch (_: Throwable) {}
                }
                try { muxer?.close() } catch (_: Throwable) {}
                try { audioCopier?.close() } catch (_: Throwable) {}
                try { encoder?.close() } catch (_: Throwable) {}
                try { faceOnlyPrivacyProcessor?.close() } catch (_: Throwable) {}
                try { glRenderer?.close() } catch (_: Throwable) {}
                try { eglCore?.close() } catch (_: Throwable) {}
                try {
                    val livePreviewDir = java.io.File(context.cacheDir, "export_live_preview")
                    java.io.File(livePreviewDir, "preview_${jobId}_0.jpg").delete()
                    java.io.File(livePreviewDir, "preview_${jobId}_1.jpg").delete()
                    java.io.File(livePreviewDir, "preview_${jobId}_tmp.jpg").delete()
                } catch (_: Throwable) {}
                if (isCancelled.get()) {
                    try { tempOutFile.delete() } catch (_: Throwable) {}
                }
                pipelineLatch.countDown()


            }

        }

        pipelineLatch.await()
        frameThread.quitSafely()
        glThread.quitSafely()
    }

    private fun isFrameBlack(bitmap: android.graphics.Bitmap?): Boolean {
        if (bitmap == null) return true
        val w = bitmap.width
        val h = bitmap.height
        var nonBlackPixels = 0
        for (gx in 1..5) {
            for (gy in 1..5) {
                val px = (w * gx / 6).coerceIn(0, w - 1)
                val py = (h * gy / 6).coerceIn(0, h - 1)
                val pixel = bitmap.getPixel(px, py)
                val r = (pixel shr 16) and 0xFF
                val g = (pixel shr 8) and 0xFF
                val b = pixel and 0xFF
                if (r > 8 || g > 8 || b > 8) {
                    nonBlackPixels++
                    if (nonBlackPixels >= 2) return false
                }
            }
        }
        return nonBlackPixels == 0
    }

    companion object {
        private const val CPU_MT2_PROBE_THREADS = 2
        private const val CPU_MT_PROBE_THREADS = 4
        private const val CPU_SINGLE_THREAD_REFERENCE_MAX_PTS_US = 450_000L

        internal fun shouldUseFreshFullBodyClassPrimary(
            fullBodyPersonIds: Set<Int>,
            faceOnlyPersonIds: Set<Int>
        ): Boolean = fullBodyPersonIds.isNotEmpty() && faceOnlyPersonIds.isEmpty()
    }
}


