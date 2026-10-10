package art.gaoge.dance.engine.pipeline

import android.content.Context
import android.graphics.SurfaceTexture
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface
import art.gaoge.dance.engine.camera.FollowCameraIdentityHistory
import art.gaoge.dance.engine.camera.FollowCameraTemporalRecovery
import art.gaoge.dance.engine.bridge.DanceNativeException
import art.gaoge.dance.engine.bridge.DanceProcessingEvents
import art.gaoge.dance.engine.bridge.ExportRequestDto
import art.gaoge.dance.engine.bridge.JobStatusDto
import art.gaoge.dance.engine.clarity.CropClarityAbCapture
import art.gaoge.dance.engine.clarity.CropClaritySceneSampler
import art.gaoge.dance.engine.clarity.CropClarityCropPrivacyGeometry
import art.gaoge.dance.engine.export.ExportCoordinator
import art.gaoge.dance.engine.inference.FloatRect
import art.gaoge.dance.engine.inference.RgbaColOrder
import art.gaoge.dance.engine.inference.RgbaRowOrder
import art.gaoge.dance.engine.inference.YoloLiteRtSegmenter
import art.gaoge.dance.engine.jobs.JobManager
import art.gaoge.dance.engine.media.AudioTrackCopier
import art.gaoge.dance.engine.media.CanonicalYuvInferenceDecoder
import art.gaoge.dance.engine.media.Mp4Muxer
import art.gaoge.dance.engine.media.VideoDecoder
import art.gaoge.dance.engine.media.VideoEncoder
import art.gaoge.dance.engine.media.VideoProbe
import art.gaoge.dance.engine.privacy.FaceOcclusionBridgePolicy
import art.gaoge.dance.engine.privacy.FacePixelMotionTracker
import art.gaoge.dance.engine.privacy.FaceReferenceGeometryCanonicalizer
import art.gaoge.dance.engine.render.EglCore
import art.gaoge.dance.engine.render.GlRenderer
import art.gaoge.dance.engine.storage.CacheManager
import art.gaoge.dance.engine.tracking.HungarianSolver
import art.gaoge.dance.engine.tracking.ProtectedTrackMotionEvidence
import art.gaoge.dance.engine.tracking.TrackManager
import art.gaoge.dance.engine.tracking.TrackState
import art.gaoge.dance.engine.tracking.TrackedPerson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

class ExportPipeline(
    private val context: Context,
    private val segmenter: YoloLiteRtSegmenter,
    private val eventEmitter: DanceProcessingEvents? = null
) {

    private enum class FollowCameraProxyMode {
        OCCLUSION,
        ID_HANDOFF
    }

    private fun canonicalizeCpuReferenceForFace(
        cpuReferenceTracks: List<TrackedPerson>
    ): List<TrackedPerson> = cpuReferenceTracks.map { cpuTrack ->
        cpuTrack.copy(
            bbox = FaceReferenceGeometryCanonicalizer.rect(cpuTrack.bbox),
            footY = cpuTrack.footY?.let(FaceReferenceGeometryCanonicalizer::coordinate)
        )
    }

    private fun canonicalizeCpuReferenceMotionEvidence(
        evidence: List<ProtectedTrackMotionEvidence>
    ): List<ProtectedTrackMotionEvidence> = evidence.map { item ->
        item.copy(
            detection = item.detection.copy(
                bbox = FaceReferenceGeometryCanonicalizer.rect(item.detection.bbox),
                footY = FaceReferenceGeometryCanonicalizer.coordinate(item.detection.footY)
            )
        )
    }

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
        val perfStartNs = android.os.SystemClock.elapsedRealtimeNanos()
        val startTime = System.currentTimeMillis()
        val diagnosticJobId = if (art.gaoge.dance.engine.diagnostics.DiagnosticsBuild.ENABLED) jobId else null
        val videoInfo = VideoProbe.probe(context, sourceUri)

        val requestedWidth = if (request.targetWidth > 0) request.targetWidth.toInt() else videoInfo.displayWidth.toInt()
        val requestedHeight = if (request.targetHeight > 0) request.targetHeight.toInt() else videoInfo.displayHeight.toInt()
        // Flutter's shared ExportPlan owns capability fallback. Native export
        // only normalizes to encoder-even dimensions and never applies a hidden
        // 1080p/1920-long-edge downgrade.
        val targetWidth = (requestedWidth - (requestedWidth and 1)).coerceAtLeast(2)
        val targetHeight = (requestedHeight - (requestedHeight and 1)).coerceAtLeast(2)
        val nominalOutputFps = when {
            request.targetFps.isFinite() && request.targetFps > 0.0 -> request.targetFps
            videoInfo.fps.isFinite() && videoInfo.fps > 0.0 -> videoInfo.fps
            else -> 30.0
        }

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

        val trimStartMs = request.trimStartMs.coerceIn(0L, videoInfo.durationMs)
        val trimEndMs = (request.trimEndMs ?: videoInfo.durationMs).coerceIn(trimStartMs, videoInfo.durationMs)
        val trimStartUs = trimStartMs * 1000L
        val trimEndUs = trimEndMs * 1000L
        val trimmedDurationMs = (trimEndMs - trimStartMs).coerceAtLeast(1L)
        val totalFrames = ((trimmedDurationMs / 1000.0) * nominalOutputFps).toInt().coerceAtLeast(1)
        val privacyModeByTrackId = art.gaoge.dance.engine.privacy.PersonPrivacyModeResolver.resolve(
            fullBodyPersonIds = request.selectedPersonIds.map { it.toInt() },
            faceOnlyPersonIds = request.faceOnlyPersonIds?.map { it.toInt() }
        )
        val fullBodyPersonIds = privacyModeByTrackId.asSequence()
            .filter { it.value == art.gaoge.dance.engine.privacy.PersonPrivacyMode.FULL_BODY }
            .map { it.key }
            .toSet()
        val faceOnlyPersonIds = privacyModeByTrackId.asSequence()
            .filter { it.value == art.gaoge.dance.engine.privacy.PersonPrivacyMode.FACE_ONLY }
            .map { it.key }
            .toSet()
        val allPrivacyTargetIds = privacyModeByTrackId.keys.toSet()
        val analysisMetadata = if (request.analysisCacheId.isNotBlank()) {
            CacheManager(context).getAnalysisMetadata(request.analysisCacheId)
        } else {
            null
        }
        val faceOnlyIdentityProtectedIds = if (faceOnlyPersonIds.isNotEmpty()) {
            resolveFaceOnlyIdentityProtectedIds(
                metadata = analysisMetadata,
                privacyTargetIds = allPrivacyTargetIds
            )
        } else {
            emptySet()
        }

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
        art.gaoge.dance.engine.diagnostics.NativeDiagnostics.recordPipelineLifecycle(
            stage = "PREPARING",
            jobId = jobId,
            fields = mapOf(
                "profile" to request.processingProfile,
                "crop_clarity_scale" to (request.cropClarityScale ?: 1.0),
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
        val yoloEffectiveCpuNumThreads = if (
            yoloEffectiveAccelerator == art.gaoge.dance.engine.litert.LiteRtAccelerator.CPU
        ) {
            yoloRuntimeInfo?.cpuNumThreads
        } else {
            null
        }
        var cpuMt4ProbeSegmenter: YoloLiteRtSegmenter? = null
        var cpuMt4ProbeFallbackReason: String? = null
        if (art.gaoge.dance.engine.BuildConfig.DEBUG) {
            try {
                cpuMt4ProbeSegmenter = YoloLiteRtSegmenter(
                    context = context,
                    requestedAccelerator = art.gaoge.dance.engine.litert.LiteRtAccelerator.CPU,
                    cpuNumThreads = CPU_MT_PROBE_THREADS,
                    diagnosticArtifactMaxPtsUs = CPU_MT4_ARTIFACT_MAX_PTS_US,
                    diagnosticSignatureMaxPtsUs = Long.MAX_VALUE
                ).also { it.initialize() }
                val cpuMtInfo = cpuMt4ProbeSegmenter?.runtimeInfo
                art.gaoge.dance.engine.diagnostics.NativeDiagnostics.event(
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
                art.gaoge.dance.engine.diagnostics.NativeDiagnostics.event(
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
        art.gaoge.dance.engine.diagnostics.NativeDiagnostics.recordPipelineLifecycle(
            stage = "PREPARING",
            jobId = jobId,
            fields = mapOf(
                "yolo_requested_accelerator" to yoloRequestedAccelerator,
                "yolo_effective_accelerator" to yoloEffectiveAccelerator.name,
                "yolo_gpu_fallback_reason" to yoloFallbackReason,
                "yolo_effective_cpu_num_threads" to yoloEffectiveCpuNumThreads,
                "yolo_compile_ms" to yoloRuntimeInfo?.compileMs,
                "yolo_warmup_ms" to yoloRuntimeInfo?.warmupMs
            )
        )
        art.gaoge.dance.engine.diagnostics.NativeDiagnostics.event(
            level = if (yoloEffectiveAccelerator == art.gaoge.dance.engine.litert.LiteRtAccelerator.GPU) "INFO" else "WARN",
            component = "ExportPipeline",
            event = if (yoloEffectiveAccelerator == art.gaoge.dance.engine.litert.LiteRtAccelerator.GPU) {
                "YOLO_EXPORT_GPU_ACTIVE"
            } else {
                "YOLO_EXPORT_CPU_FALLBACK"
            },
            fields = mapOf(
                "job_id" to jobId,
                "requested_accelerator" to yoloRequestedAccelerator,
                "effective_accelerator" to yoloEffectiveAccelerator.name,
                "fallback_reason" to yoloFallbackReason,
                "effective_cpu_num_threads" to yoloEffectiveCpuNumThreads,
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
            var privacyRenderer: GlRenderer? = null
            var privacyRenderTarget: art.gaoge.dance.engine.render.TextureRenderTarget? = null
            var clarityAbBaselineTarget: art.gaoge.dance.engine.render.TextureRenderTarget? = null
            var clarityAbCapture: CropClarityAbCapture? = null
            var surfaceTexture: SurfaceTexture? = null
            var decoder: VideoDecoder? = null
            var canonicalInferenceDecoder: CanonicalYuvInferenceDecoder? = null
            var canonicalInferenceFallbackReason: String? = null
            var canonicalValidationWindowCompleted = false
            var encoder: VideoEncoder? = null
            var muxer: Mp4Muxer? = null
            var audioCopier: AudioTrackCopier? = null
            var frameReader: art.gaoge.dance.engine.render.InferenceFrameReader? = null
            var oesTextureId = 0
            var livePreviewFile: java.io.File? = null
            var decoderSurface: android.view.Surface? = null
            var previewScope: kotlinx.coroutines.CoroutineScope? = null
            var faceOnlyPrivacyProcessor: art.gaoge.dance.engine.privacy.FaceOnlyPrivacyFrameProcessor? = null

            try {
                audioCopier = AudioTrackCopier(
                    context = context,
                    sourceUri = sourceUri,
                    startUs = trimStartUs,
                    endUs = trimEndUs
                )
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
                    bitrate = request.videoBitrate.coerceIn(2_000_000L, 80_000_000L).toInt(),
                    fps = nominalOutputFps.toFloat()
                )

                val inputSurface = encoder.prepare()
                eglCore = EglCore()
                val surf = eglCore.createWindowSurface(inputSurface)
                eglSurface = surf
                eglCore.makeCurrent(surf)

                val trackingWidth = if (request.follow.enabled) videoInfo.displayWidth.toInt() else targetWidth
                val trackingHeight = if (request.follow.enabled) videoInfo.displayHeight.toInt() else targetHeight
                val postCropEnabled = request.follow.enabled &&
                    request.follow.outputAspectRatio?.let { it.isFinite() && it > 0.0 } == true

                glRenderer = GlRenderer()
                glRenderer.initialize(targetWidth, targetHeight)

                if (postCropEnabled) {
                    val compositionSize = art.gaoge.dance.engine.camera.ReframeGeometry.postCropCompositionSize(
                        sourceWidth = trackingWidth,
                        sourceHeight = trackingHeight,
                        targetWidth = targetWidth,
                        targetHeight = targetHeight
                    ) ?: throw IllegalArgumentException("Unable to compute post-crop composition size")
                    privacyRenderer = GlRenderer().also {
                        it.initialize(compositionSize.first, compositionSize.second)
                    }
                    privacyRenderTarget = art.gaoge.dance.engine.render.TextureRenderTarget(
                        compositionSize.first,
                        compositionSize.second
                    )
                    clarityAbCapture = CropClarityAbCapture.beginIfRequested(
                        context = context,
                        jobId = jobId,
                        nominalFrames = totalFrames,
                        requestedScale = request.cropClarityScale ?: 1.0,
                        postCrop = postCropEnabled,
                        hasPrivacyTargets = allPrivacyTargetIds.isNotEmpty(),
                        outputWidth = targetWidth,
                        outputHeight = targetHeight
                    )
                    if (clarityAbCapture != null) {
                        // The QA allocation must never prevent a normal export.
                        try {
                            clarityAbBaselineTarget = art.gaoge.dance.engine.render.TextureRenderTarget(
                                compositionSize.first, compositionSize.second
                            )
                            art.gaoge.dance.engine.diagnostics.NativeDiagnostics.event(
                                level = "INFO", component = "ExportPipeline",
                                event = "CROP_CLARITY_AB_ARMED",
                                fields = mapOf(
                                    "job_id" to jobId,
                                    "scale" to (request.cropClarityScale ?: 1.0),
                                    "sampling_policy" to CropClaritySceneSampler.SELECTION_POLICY,
                                    "max_pairs" to CropClaritySceneSampler.MAX_PAIRS,
                                    "temporal_pairs_reserved" to CropClaritySceneSampler.TEMPORAL_FRAMES,
                                    "target_width" to targetWidth,
                                    "target_height" to targetHeight
                                )
                            )
                        } catch (error: Throwable) {
                            try { clarityAbCapture?.close() } catch (_: Throwable) {}
                            clarityAbCapture = null
                            art.gaoge.dance.engine.diagnostics.NativeDiagnostics.event(
                                level = "WARN", component = "ExportPipeline",
                                event = "CROP_CLARITY_AB_TARGET_UNAVAILABLE",
                                fields = mapOf("job_id" to jobId, "error" to error.javaClass.simpleName)
                            )
                        }
                    }
                    art.gaoge.dance.engine.diagnostics.NativeDiagnostics.event(
                        level = "INFO",
                        component = "ExportPipeline",
                        event = "POST_CROP_PRIVACY_COMPOSITION_ENABLED",
                        fields = mapOf(
                            "source_width" to trackingWidth,
                            "source_height" to trackingHeight,
                            "composition_width" to compositionSize.first,
                            "composition_height" to compositionSize.second,
                            "target_width" to targetWidth,
                            "target_height" to targetHeight
                        )
                    )
                }

                val oesTextures = IntArray(1)
                android.opengl.GLES20.glGenTextures(1, oesTextures, 0)
                oesTextureId = oesTextures[0]
                android.opengl.GLES20.glBindTexture(android.opengl.GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTextureId)
                android.opengl.GLES20.glTexParameteri(android.opengl.GLES11Ext.GL_TEXTURE_EXTERNAL_OES, android.opengl.GLES20.GL_TEXTURE_MIN_FILTER, android.opengl.GLES20.GL_LINEAR)
                android.opengl.GLES20.glTexParameteri(android.opengl.GLES11Ext.GL_TEXTURE_EXTERNAL_OES, android.opengl.GLES20.GL_TEXTURE_MAG_FILTER, android.opengl.GLES20.GL_LINEAR)
                android.opengl.GLES20.glTexParameteri(android.opengl.GLES11Ext.GL_TEXTURE_EXTERNAL_OES, android.opengl.GLES20.GL_TEXTURE_WRAP_S, android.opengl.GLES20.GL_CLAMP_TO_EDGE)
                android.opengl.GLES20.glTexParameteri(android.opengl.GLES11Ext.GL_TEXTURE_EXTERNAL_OES, android.opengl.GLES20.GL_TEXTURE_WRAP_T, android.opengl.GLES20.GL_CLAMP_TO_EDGE)



                val inferenceFbo = art.gaoge.dance.engine.render.InferenceFbo(640)
                val inferenceRenderer = art.gaoge.dance.engine.render.InferenceRenderer()
                // Reframing changes only final composition. Inference and identity
                // stay in the full visual source space, never in portrait output.
                val mapper = art.gaoge.dance.engine.geometry.ModelCoordinateMapper(trackingWidth, trackingHeight, 640)
                val profiler = art.gaoge.dance.engine.profiler.PipelineProfiler()
                val inferencePixelDiagnostics = if (art.gaoge.dance.engine.diagnostics.DiagnosticsBuild.ENABLED) {
                    art.gaoge.dance.engine.diagnostics.InferencePixelDiagnostics(
                        jobId = jobId,
                        width = inferenceFbo.size,
                        height = inferenceFbo.size
                    )
                } else {
                    null
                }

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
                    outputSurface = decoderSurface,
                    startUs = trimStartUs,
                    endUs = trimEndUs
                )
                decoder.prepare()

                var processedFrames = 0
                var qualityLuma: CropClaritySceneSampler.LumaEvidence? = null
                var qualityLumaFrame = -1
                var previousProtagonistPosition: Pair<Float, Float>? = null
                var previousProtagonistFrame = -1
                val totalEstFrames = ((trimmedDurationMs / 1000.0) * nominalOutputFps).toLong().coerceAtLeast(1L)
                // Source PTS remains authoritative. This duration is only a
                // deterministic monotonic fallback for duplicate/broken PTS.
                val frameDurationNs = (1_000_000_000.0 / nominalOutputFps).toLong().coerceAtLeast(1_000L)
                val stMatrix = FloatArray(16).apply {
                    android.opengl.Matrix.setIdentityM(this, 0)
                }
                var lastPresentationNs = -1L
                val trackManager = TrackManager()
                val reframeFollower = art.gaoge.dance.engine.camera.SmoothFollower()
                val reframeOcclusionProxyStabilizer =
                    art.gaoge.dance.engine.camera.OcclusionProxyStabilizer()
                // Follow camera ONLY; never alters TrackManager privacy IDs.
                val reframeTemporalRecovery = FollowCameraTemporalRecovery()
                // Keep co-observed identities excluded across the whole export.
                val reframeIdentityHistory = FollowCameraIdentityHistory()
                var reframeRecoveryExpiredLogged = false
                var reframeInitialized = false
                var reframeIdentityTrackId = request.follow.targetPersonId?.toInt()
                var reframeOcclusionProxyTrackId: Int? = null
                var lastLoggedReframeProxyTrackId: Int? = null
                var lastLoggedReframeProxyMode: FollowCameraProxyMode? = null
                var reframeLastIdentityTrackBox: FloatRect? = null
                var reframeLastIdentityPtsUs: Long? = null
                var reframeLastHandoffIou: Float? = null
                var reframeLastHandoffAgeUs: Long? = null
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
                    // FACE_ONLY identity durability must not depend on which people the user
                    // chose to anonymize. Otherwise an unselected but credible neighbor can use
                    // weaker association/recovery rules and destabilize a selected identity.
                    // Keep privacy selection separate: these extra roots never receive privacy.
                    trackManager.setIdentityProtectedTrackIds(faceOnlyIdentityProtectedIds)
                    trackManager.setPrivacySelectedTrackIds(fullBodyPersonIds)
                    trackManager.setPrivacyOffscreenDormancyEnabled(fullBodyPersonIds.isNotEmpty())
                    if (art.gaoge.dance.engine.BuildConfig.DEBUG) {
                        art.gaoge.dance.engine.diagnostics.NativeDiagnostics.event(
                            level = "INFO",
                            component = "ExportPipeline",
                            event = "FACE_ONLY_IDENTITY_ROOTS_RESOLVED",
                            fields = mapOf(
                                "job_id" to jobId,
                                "face_only_person_ids" to faceOnlyPersonIds.sorted(),
                                "identity_protected_track_ids" to faceOnlyIdentityProtectedIds.sorted(),
                                "analysis_candidate_ids_ge_0_60" to analysisMetadata
                                    ?.persons
                                    ?.filter { it.confidence >= SELECTION_IDENTITY_ROOT_MIN_CONFIDENCE }
                                    ?.map { it.id }
                                    ?.sorted()
                                    .orEmpty()
                            )
                        )
                    }
                }
                val privacyClassTemporalTracker = art.gaoge.dance.engine.privacy.PrivacyClassTemporalTracker()
                val followSeed = if (request.follow.enabled) {
                    val targetId = request.follow.targetPersonId
                        ?: throw IllegalArgumentException("Follow requires an explicit target person")
                    val root = analysisMetadata?.persons?.firstOrNull { it.id.toLong() == targetId }
                        ?: throw IllegalArgumentException("Follow target is missing from the analysis cache")
                    val protectedIds = if (faceOnlyPersonIds.isEmpty()) fullBodyPersonIds else faceOnlyIdentityProtectedIds
                    // Camera following is not a privacy guarantee. Do not promote the
                    // protagonist into the privacy-grade strict identity lane: ambiguous
                    // crossings may intentionally refuse a plausible reassociation and
                    // freeze the camera for the rest of the clip. Privacy roots remain
                    // strict; the protagonist uses normal tracking plus a short prediction
                    // grace below.
                    trackManager.setIdentityProtectedTrackIds(protectedIds)
                    // Selecting a camera subject must never add a privacy mask.
                    trackManager.setPrivacySelectedTrackIds(fullBodyPersonIds)
                    art.gaoge.dance.engine.inference.FloatRect(
                        root.bbox.left.toFloat(), root.bbox.top.toFloat(),
                        root.bbox.right.toFloat(), root.bbox.bottom.toFloat()
                    )
                } else null
                val profile = ProcessingProfile.fromName(request.processingProfile)
                val frameStride = profile.inferenceStride
                var lastProgressEmitTime = 0L
                val preferDebugFaceDeterministicCpuPrimary = shouldPreferDebugFaceDeterministicCpuPrimary(
                    isDebugBuild = art.gaoge.dance.engine.BuildConfig.DEBUG,
                    fullBodyPersonIds = fullBodyPersonIds,
                    faceOnlyPersonIds = faceOnlyPersonIds
                )
                val reuseProductionCpuFallbackForCpuMt4Reference =
                    shouldReuseProductionCpuFallbackForCpuMt4Reference(
                        isDebugBuild = art.gaoge.dance.engine.BuildConfig.DEBUG,
                        fullBodyPersonIds = fullBodyPersonIds,
                        faceOnlyPersonIds = faceOnlyPersonIds,
                        effectiveAccelerator = yoloEffectiveAccelerator,
                        effectiveCpuNumThreads = yoloEffectiveCpuNumThreads
                    )
                var faceDeterministicCpuPrimaryInferenceFrames = 0L
                var faceDeterministicCpuPrimaryFallbackFrames = 0L
                var cpuMt4ProbeInferenceFrames = 0L
                var cpuMt4ProductionReuseFrames = 0L
                var cpuMt4ProductionReuseParityFrames = 0L
                var cpuMt4ProductionReuseParityExact = true
                val crossDeviceTrackingDiagnostics = if (
                    art.gaoge.dance.engine.BuildConfig.DEBUG
                ) {
                    art.gaoge.dance.engine.diagnostics.CrossDeviceTrackingDiagnostics(
                        jobId = jobId,
                        fullBodyPersonIds = fullBodyPersonIds,
                        faceOnlyPersonIds = faceOnlyPersonIds,
                        identityProtectedTrackIds = if (faceOnlyPersonIds.isNotEmpty()) {
                            faceOnlyIdentityProtectedIds
                        } else {
                            fullBodyPersonIds
                        },
                        // Five adaptive TrackManagers belong to an older scheduler experiment.
                        // They never feed production identity, privacy, or rendering, while the
                        // CPU-full tracker below is the cross-device identity reference for both
                        // Face and Full Body. Keep the adaptive scheduler implementation available
                        // for explicit tests/experiments, but do not run the matrix in standard
                        // debug exports.
                        enableAdaptiveShadowMatrix = false
                    )
                } else {
                    null
                }

                // Cross-device validation path: keep rendering on the historical Surface decoder,
                // but feed YOLO from an independent CPU-readable YUV decoder. This bypasses the
                // device-specific SurfaceTexture/OES YUV->RGB conversion without touching tracking.
                if (art.gaoge.dance.engine.BuildConfig.DEBUG) {
                    try {
                        canonicalInferenceDecoder = CanonicalYuvInferenceDecoder(
                            context = context,
                            sourceUri = sourceUri,
                            rotationDegrees = videoInfo.rotation.toInt(),
                            modelInputSize = inferenceFbo.size
                        ).also { it.prepare() }
                        art.gaoge.dance.engine.diagnostics.NativeDiagnostics.event(
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
                        art.gaoge.dance.engine.diagnostics.NativeDiagnostics.event(
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
                android.util.Log.i(
                    "ExportPipeline",
                    "Pipeline Config: profileName=${profile.name}, stride=$frameStride, inputSize=${profile.inputSize}, target=${targetWidth}x${targetHeight}"
                )

                if (faceOnlyPersonIds.isNotEmpty()) {
                    faceOnlyPrivacyProcessor =
                        art.gaoge.dance.engine.privacy.FaceOnlyPrivacyFrameProcessor.create(
                            context = context,
                            mapper = mapper,
                            diagnosticJobId = diagnosticJobId
                        )
                }

                var lastLivePreviewCaptureTime = 0L
                var previewSequence = 0L
                val livePreviewDir = java.io.File(context.cacheDir, "export_live_preview").apply { mkdirs() }
                val scope = kotlinx.coroutines.CoroutineScope(Dispatchers.IO + kotlinx.coroutines.SupervisorJob())
                previewScope = scope
                val isPreviewSaving = java.util.concurrent.atomic.AtomicBoolean(false)
                val livePreviewEnabled = ExportCoordinator.getInstance(context).getLivePreviewFlag(jobId)
                val lastPreviewFilePath = java.util.concurrent.atomic.AtomicReference<String?>(null)
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
                var faceRoiReadCount = 0L
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

                art.gaoge.dance.engine.diagnostics.NativeDiagnostics.recordPipelineLifecycle(
                    stage = "EXPORTING",
                    jobId = jobId,
                    fields = mapOf(
                        "target_width" to targetWidth,
                        "target_height" to targetHeight,
                        "target_fps" to nominalOutputFps,
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
                    if (ptsUs < trimStartUs) {
                        decoder.releaseOutputBuffer(token.bufferIndex, false)
                        continue
                    }
                    if (ptsUs >= trimEndUs) {
                        decoder.releaseOutputBuffer(token.bufferIndex, false)
                        break
                    }
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
                    val renderTexType = art.gaoge.dance.engine.render.SourceTextureType.OES
                    val renderTexMatrix: FloatArray? = finalTexMatrix
                    var freshPrivacyClassEvidence = emptyList<art.gaoge.dance.engine.tracking.FreshPrivacyClassEvidence>()
                    var freshSelectedCoveredTrackIds = emptySet<Int>()
                    var suppressedSelectedPrivacyTrackIds = emptySet<Int>()
                    var preferFreshPrivacyClassPrimary = false
                    var cpuReferenceTrackedForFace: List<TrackedPerson>? = null
                    var canonicalModelRgbaForFace: java.nio.ByteBuffer? = null

                        // 2. Perform Inference / Temporal Mask Tracking
                        val trackedList: List<art.gaoge.dance.engine.tracking.TrackedPerson> = run {

                            // Standard YOLO pipeline
                            val shouldInfer = (processedFrames == 1) || (processedFrames % frameStride == 0)
                            var cpuMt4DetectionsForShadow: List<art.gaoge.dance.engine.inference.PersonDetection>? = null
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
                                        art.gaoge.dance.engine.diagnostics.NativeDiagnostics.event(
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
                                canonicalModelRgbaForFace = canonicalRgba?.duplicate()?.apply { rewind() }
                                val rgbaBuffer = canonicalRgba ?: run {
                                    profiler.recordStage("fboLetterbox") {
                                        inferenceRenderer.renderToFbo(renderTexId, finalTexMatrix, mapper, inferenceFbo, renderTexType)
                                    }
                                    profiler.recordStage("readback640") {
                                        inferenceFbo.readRgbaPixels()
                                    }
                                }
                                inferencePixelDiagnostics?.maybeCapture(
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
                                // Reuse the 640px model input: sparse luma stats only.
                                // No extra GPU readback, no stored unprotected image.
                                if (clarityAbCapture != null && (
                                        processedFrames == 1 ||
                                            processedFrames - qualityLumaFrame >=
                                                CropClaritySceneSampler.LUMA_PROBE_STRIDE
                                    )) {
                                    qualityLuma = CropClaritySceneSampler.sampleLuma(rgbaBuffer)
                                    qualityLumaFrame = processedFrames
                                }
                                var cpuMt4PrimaryInferenceTimeMs: Long? = null
                                val cpuMt4Probe = cpuMt4ProbeSegmenter
                                val canReuseProductionForCpuMt4Reference =
                                    reuseProductionCpuFallbackForCpuMt4Reference &&
                                        cpuMt4ProductionReuseParityExact &&
                                        cpuMt4ProductionReuseParityFrames > 0L &&
                                        cpuMt4ProbeFallbackReason == null
                                val shouldRunIndependentCpuMt4Probe =
                                    !canReuseProductionForCpuMt4Reference ||
                                        ptsUs <= CPU_MT4_ARTIFACT_MAX_PTS_US ||
                                        processedFrames % CPU_MT4_PRODUCTION_REUSE_PARITY_INTERVAL_FRAMES == 0
                                if (cpuMt4Probe != null && shouldRunIndependentCpuMt4Probe) {
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
                                        cpuMt4PrimaryInferenceTimeMs = cpuMt4Seg.inferenceTimeMs
                                        cpuMt4ProbeInferenceFrames++
                                        for ((stage, elapsedMs) in cpuMt4Seg.stageTimingsMs) {
                                            profiler.recordSample("yoloCpuMt4Probe_${stage}", elapsedMs)
                                        }
                                    } catch (t: Throwable) {
                                        cpuMt4ProbeFallbackReason =
                                            "${t.javaClass.simpleName}:${t.message ?: "unknown"}"
                                        try { cpuMt4Probe.close() } catch (_: Throwable) {}
                                        cpuMt4ProbeSegmenter = null
                                        art.gaoge.dance.engine.diagnostics.NativeDiagnostics.event(
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
                                val deterministicPrimaryDetections = if (
                                    preferDebugFaceDeterministicCpuPrimary
                                ) {
                                    cpuMt4DetectionsForShadow
                                } else {
                                    null
                                }
                                if (deterministicPrimaryDetections != null) {
                                    // FACE_ONLY debug validation has already established that
                                    // final geometry/masks/class evidence come from this same
                                    // deterministic CPU4T measurement. Do not infer the identical
                                    // canonical frame a second time. Reuse the CPU4T detections for
                                    // the deterministic tracking primary below. A missing/failed CPU
                                    // probe still falls through to the historical production
                                    // segmenter in the same frame, and those fallback detections are
                                    // consumed by that same tracking primary.
                                    faceDeterministicCpuPrimaryInferenceFrames++
                                    cpuMt4PrimaryInferenceTimeMs?.let {
                                        profiler.recordSample("yoloPipelineTotal", it)
                                    }
                                    deterministicPrimaryDetections
                                } else {
                                    if (preferDebugFaceDeterministicCpuPrimary) {
                                        faceDeterministicCpuPrimaryFallbackFrames++
                                    }
                                    val seg = profiler.recordStage("yoloCpuInference") {
                                        segmenter.segmentGlReadbackRgbaSync(
                                            rgbaBuffer,
                                            mapper,
                                            ptsUs,
                                            colOrder = RgbaColOrder.LEFT_TO_RIGHT,
                                            diagnosticJobId = diagnosticJobId
                                        )
                                    }
                                    // Historical compatibility: this metric name predates the
                                    // LiteRT GPU path and is misleading. Preserve it unchanged
                                    // for existing diagnostics, and expose a correctly named
                                    // canonical alias from the segmenter's own whole-call timer.
                                    profiler.recordSample("yoloPipelineTotal", seg.inferenceTimeMs)
                                    for ((stage, elapsedMs) in seg.stageTimingsMs) {
                                        profiler.recordSample(stage, elapsedMs)
                                    }
                                    if (
                                        reuseProductionCpuFallbackForCpuMt4Reference &&
                                        cpuMt4DetectionsForShadow != null
                                    ) {
                                        val productionSignature =
                                            art.gaoge.dance.engine.diagnostics.YoloTensorDiagnostics
                                                .detectionSignature(seg.persons)
                                        val cpuMt4Signature =
                                            art.gaoge.dance.engine.diagnostics.YoloTensorDiagnostics
                                                .detectionSignature(requireNotNull(cpuMt4DetectionsForShadow))
                                        if (productionSignature == cpuMt4Signature) {
                                            cpuMt4ProductionReuseParityFrames++
                                        } else {
                                            cpuMt4ProductionReuseParityExact = false
                                            art.gaoge.dance.engine.diagnostics.NativeDiagnostics.event(
                                                level = "WARN",
                                                component = "ExportPipeline",
                                                event = "YOLO_CPU_MT4_PRODUCTION_REUSE_PARITY_MISMATCH",
                                                fields = mapOf(
                                                    "job_id" to jobId,
                                                    "pts_us" to ptsUs,
                                                    "confirmed_parity_frames" to cpuMt4ProductionReuseParityFrames
                                                )
                                            )
                                        }
                                    }
                                    if (
                                        reuseProductionCpuFallbackForCpuMt4Reference &&
                                        cpuMt4ProductionReuseParityExact &&
                                        cpuMt4ProductionReuseParityFrames > 0L &&
                                        cpuMt4ProbeFallbackReason == null &&
                                        ptsUs > CPU_MT4_ARTIFACT_MAX_PTS_US &&
                                        cpuMt4DetectionsForShadow == null
                                    ) {
                                        cpuMt4DetectionsForShadow = seg.persons
                                        cpuMt4ProductionReuseFrames++
                                        art.gaoge.dance.engine.diagnostics.YoloTensorDiagnostics
                                            .recordGeometrySignature(
                                                jobId = "${jobId}_cpu_mt4_probe",
                                                ptsUs = ptsUs,
                                                detections = seg.persons
                                            )
                                    }
                                    // Export QUALITY path: YOLO raw organic masks directly enter TrackManager without pre-dilation
                                    seg.persons
                                }
                            } else {
                                emptyList()
                            }

                            val deterministicTrackingPrimary = if (preferDebugFaceDeterministicCpuPrimary) {
                                val diagnostics = checkNotNull(crossDeviceTrackingDiagnostics) {
                                    "Deterministic Face tracking diagnostics unavailable"
                                }
                                profiler.recordStage("faceDeterministicCpuTracking") {
                                    checkNotNull(
                                        diagnostics.recordFrame(
                                            ptsUs = ptsUs,
                                            shouldInfer = shouldInfer,
                                            productionDetections = if (shouldInfer) detections else null,
                                            productionTracked = null,
                                            cpuMt4Detections = cpuMt4DetectionsForShadow,
                                            initialAssignedIds = if (processedFrames == 1) {
                                                resolveInitialTrackIdsFromAnalysis(
                                                    metadata = analysisMetadata,
                                                    detections = detections,
                                                    targetWidth = trackingWidth,
                                                    targetHeight = trackingHeight
                                                )
                                            } else {
                                                null
                                            },
                                            allowProductionFallbackForCpuFull = true
                                        )
                                    ) {
                                        "Deterministic Face tracking primary unavailable at pts_us=$ptsUs"
                                    }
                                }
                            } else {
                                null
                            }

                            // Once deterministic CPU4T detections are the Face-only primary,
                            // the CPU full tracker is the production bookkeeping tracker too.
                            // Do not run a second TrackManager over the same measurements merely
                            // to produce equivalent IDs/state/geometry. Release and FULL_BODY still
                            // execute the historical production tracker below.
                            val tracked = deterministicTrackingPrimary ?: profiler.recordStage("tracking") {
                                if (processedFrames == 1) {
                                    val metadata = analysisMetadata
                                    if (metadata != null && metadata.persons.isNotEmpty() && detections.isNotEmpty()) {
                                        val cached = metadata.persons
                                        val costMatrix = Array(cached.size) { r ->
                                            val cPerson = cached[r]
                                            val cLeft = (cPerson.bbox.left * trackingWidth).toFloat()
                                            val cTop = (cPerson.bbox.top * trackingHeight).toFloat()
                                            val cRight = (cPerson.bbox.right * trackingWidth).toFloat()
                                            val cBottom = (cPerson.bbox.bottom * trackingHeight).toFloat()
                                            val cBox = art.gaoge.dance.engine.inference.FloatRect(cLeft, cTop, cRight, cBottom)

                                            FloatArray(detections.size) { c ->
                                                val dBox = detections[c].bbox
                                                val iou = art.gaoge.dance.engine.tracking.TrackManager.computeBBoxIoU(cBox, dBox)
                                                val refDim = maxOf(cBox.width, cBox.height, 1f)
                                                val dx = cBox.centerX - dBox.centerX
                                                val dy = cBox.centerY - dBox.centerY
                                                val dist = kotlin.math.sqrt(dx * dx + dy * dy)
                                                val distScore = (1.0f - (dist / (refDim * 1.5f))).coerceIn(0f, 1f)
                                                val score = 0.7f * iou + 0.3f * distScore
                                                (1.0f - score).coerceIn(0f, 1f)
                                            }
                                        }

                                        val matchResult = art.gaoge.dance.engine.tracking.HungarianSolver.match(costMatrix, maxCostThreshold = 0.85f)
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
                            cpuReferenceTrackedForFace = if (deterministicTrackingPrimary != null) {
                                deterministicTrackingPrimary
                            } else {
                                crossDeviceTrackingDiagnostics?.recordFrame(
                                    ptsUs = ptsUs,
                                    shouldInfer = shouldInfer,
                                    productionDetections = if (shouldInfer) detections else null,
                                    productionTracked = if (processedFrames == 1) tracked else null,
                                    cpuMt4Detections = cpuMt4DetectionsForShadow
                                )
                            }
                            if (
                                art.gaoge.dance.engine.BuildConfig.DEBUG &&
                                faceOnlyPersonIds.isNotEmpty() &&
                                deterministicTrackingPrimary == null
                            ) {
                                art.gaoge.dance.engine.diagnostics.NativeDiagnostics.event(
                                    level = "INFO",
                                    component = "ExportPipeline",
                                    event = "FACE_ONLY_PRODUCTION_TRACK_SIGNATURE",
                                    fields = mapOf(
                                        "job_id" to jobId,
                                        "pts_us" to ptsUs,
                                        "should_infer" to shouldInfer,
                                        "face_only_person_ids" to faceOnlyPersonIds.sorted(),
                                        "identity_protected_track_ids" to faceOnlyIdentityProtectedIds.sorted(),
                                        "tracks" to art.gaoge.dance.engine.diagnostics.CrossDeviceTrackingDiagnostics
                                            .trackSignature(
                                                tracked.filter { faceOnlyIdentityProtectedIds.contains(it.id) }
                                            )
                                    )
                                )
                            }
                            if (
                                art.gaoge.dance.engine.BuildConfig.DEBUG &&
                                fullBodyPersonIds.isNotEmpty() &&
                                faceOnlyPersonIds.isEmpty()
                            ) {
                                art.gaoge.dance.engine.diagnostics.NativeDiagnostics.event(
                                    level = "INFO",
                                    component = "ExportPipeline",
                                    event = "FULL_BODY_PRODUCTION_TRACK_SIGNATURE",
                                    fields = mapOf(
                                        "job_id" to jobId,
                                        "pts_us" to ptsUs,
                                        "should_infer" to shouldInfer,
                                        "full_body_person_ids" to fullBodyPersonIds.sorted(),
                                        "tracks" to art.gaoge.dance.engine.diagnostics.CrossDeviceTrackingDiagnostics
                                            .trackSignature(tracked)
                                    )
                                )
                            }
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
                                    it.selectionClass == art.gaoge.dance.engine.tracking.PrivacySelectionClass.SELECTED &&
                                        it.residualTrackIds.size == 1
                                }
                                .filter { runtimeEvidence ->
                                    val temporal = temporalByDetectionIndex[runtimeEvidence.detectionIndex]
                                    temporal != null &&
                                        temporal.selectionClass == art.gaoge.dance.engine.tracking.PrivacySelectionClass.SELECTED &&
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
                            if (
                                art.gaoge.dance.engine.BuildConfig.DEBUG &&
                                fullBodyPersonIds.isNotEmpty() &&
                                faceOnlyPersonIds.isEmpty()
                            ) {
                                art.gaoge.dance.engine.diagnostics.NativeDiagnostics.event(
                                    level = "INFO",
                                    component = "ExportPipeline",
                                    event = "FULL_BODY_PRIVACY_INPUT_SIGNATURE",
                                    fields = mapOf(
                                        "job_id" to jobId,
                                        "pts_us" to ptsUs,
                                        "full_body_person_ids" to fullBodyPersonIds.sorted(),
                                        "fresh_selected_covered_track_ids" to freshSelectedCoveredTrackIds.sorted(),
                                        "prefer_fresh_class_primary" to preferFreshPrivacyClassPrimary,
                                        "evidence" to freshPrivacyClassEvidence
                                            .sortedBy { it.detectionIndex }
                                            .map { evidence ->
                                                val bbox = evidence.detection.bbox
                                                mapOf(
                                                    "detection_index" to evidence.detectionIndex,
                                                    "selection_class" to evidence.selectionClass.name,
                                                    "conservative_unknown" to evidence.conservativeUnknown,
                                                    "residual_track_ids" to evidence.residualTrackIds.sorted(),
                                                    "bbox_q0_0625px" to listOf(
                                                        (bbox.left * 16f).roundToInt(),
                                                        (bbox.top * 16f).roundToInt(),
                                                        (bbox.right * 16f).roundToInt(),
                                                        (bbox.bottom * 16f).roundToInt()
                                                    )
                                                )
                                            }
                                    )
                                )
                            }
                            tracked
                        }

                        val faceOnlyFrameResult = faceOnlyPrivacyProcessor?.let { processor ->
                            val useCpuReferenceGeometry =
                                art.gaoge.dance.engine.BuildConfig.DEBUG && cpuReferenceTrackedForFace != null
                            val faceGeometryPersons = if (useCpuReferenceGeometry) {
                                // Keep the complete deterministic CPU reference for FACE_ONLY:
                                // identity/state, geometry and body-mask evidence. Mixing the
                                // production GPU mask back into this path reintroduces a second
                                // device-specific geometry source through head refinement.
                                canonicalizeCpuReferenceForFace(
                                    cpuReferenceTracks = requireNotNull(cpuReferenceTrackedForFace)
                                )
                            } else {
                                trackedList
                            }
                            val protectedMotionEvidence = if (useCpuReferenceGeometry) {
                                canonicalizeCpuReferenceMotionEvidence(
                                    crossDeviceTrackingDiagnostics
                                        ?.getCpuFullProtectedTrackMotionEvidence()
                                        .orEmpty()
                                )
                            } else {
                                trackManager.getFreshProtectedTrackMotionEvidence()
                            }
                            val freshFacePrivacyClassEvidence = if (useCpuReferenceGeometry) {
                                val diagnostics = crossDeviceTrackingDiagnostics
                                buildList {
                                    addAll(diagnostics?.getCpuFullFreshFacePrivacyClassEvidence().orEmpty())
                                    addAll(diagnostics?.getCpuFullTemporalFacePrivacyClassEvidence().orEmpty())
                                }.distinctBy { it.detectionIndex }
                            } else {
                                // Keep release/runtime behavior unchanged until the
                                // deterministic candidate is validated on devices.
                                emptyList()
                            }
                            profiler.recordStage("faceOnlyPrivacy") {
                                processor.resolveFrame(
                                    frameTexture = renderTexId,
                                    texMatrix = finalTexMatrix,
                                    textureType = renderTexType,
                                    persons = faceGeometryPersons,
                                    faceOnlyTrackIds = faceOnlyPersonIds,
                                    fullBodyTrackIds = selectedIds,
                                    protectedMotionEvidence = protectedMotionEvidence,
                                    freshPrivacyClassEvidence = freshFacePrivacyClassEvidence,
                                    canonicalModelRgbaBottomUp = if (useCpuReferenceGeometry) {
                                        canonicalModelRgbaForFace
                                    } else {
                                        null
                                    },
                                    ptsUs = ptsUs
                                )
                            }
                        }
                        if (faceOnlyFrameResult != null) {
                            if (art.gaoge.dance.engine.BuildConfig.DEBUG) {
                                art.gaoge.dance.engine.diagnostics.NativeDiagnostics.event(
                                    level = "INFO",
                                    component = "ExportPipeline",
                                    event = "FACE_ONLY_STICKER_PLACEMENT_SIGNATURE",
                                    fields = mapOf(
                                        "job_id" to jobId,
                                        "pts_us" to ptsUs,
                                        "geometry_source" to if (cpuReferenceTrackedForFace != null) {
                                            "CPU_MT4_REFERENCE"
                                        } else {
                                            "PRODUCTION_TRACKS"
                                        },
                                        "mask_source" to if (cpuReferenceTrackedForFace != null) {
                                            "CPU_MT4_REFERENCE"
                                        } else {
                                            "PRODUCTION_TRACKS"
                                        },
                                        "placements" to faceOnlyFrameResult.stickerPlacements
                                            .sortedBy { it.trackId }
                                            .map { placement ->
                                                mapOf(
                                                    "track_id" to placement.trackId,
                                                    "source" to placement.source.name,
                                                    "source_rect_q0_0625px" to listOf(
                                                        (placement.sourceRect.left * 16f).roundToInt(),
                                                        (placement.sourceRect.top * 16f).roundToInt(),
                                                        (placement.sourceRect.right * 16f).roundToInt(),
                                                        (placement.sourceRect.bottom * 16f).roundToInt()
                                                    )
                                                )
                                            }
                                    )
                                )
                            }
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
                            faceRoiReadCount += faceOnlyFrameResult.roiReadCount
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
                            if (faceOnlyFrameResult.faceDetectorWallMs > 0.0) {
                                profiler.recordSample(
                                    "faceDetectorWall",
                                    faceOnlyFrameResult.faceDetectorWallMs.toLong().coerceAtLeast(0L)
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
                                art.gaoge.dance.engine.diagnostics.NativeDiagnostics.event(
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
                                    art.gaoge.dance.engine.diagnostics.NativeDiagnostics.event(
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
                                    art.gaoge.dance.engine.diagnostics.NativeDiagnostics.event(
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
                                    art.gaoge.dance.engine.diagnostics.NativeDiagnostics.event(
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

                        // 4. Render privacy in full-frame source aspect first. Auto-reframe
                        // is a second GPU pass over the already-protected RGBA frame so
                        // crop geometry can never alter mask/sticker coordinates.
                        profiler.recordStage("renderEffects") {
                            if (postCropEnabled) {
                                val target = requireNotNull(privacyRenderTarget)
                                val compositor = requireNotNull(privacyRenderer)
                                // Selection happens after the *single* camera crop has been
                                // determined. Production renders only once unconditionally.

                                // Both variants use identical tracked persons, privacy classes,
                                // masks, effects and source texture. The only difference is
                                // cropClarityScale. No extra render occurs without debug opt-in.
                                val renderProtected = { scale: Double ->
                                    compositor.render(
                                        frameTexture = renderTexId,
                                        texMatrix = renderTexMatrix,
                                        persons = trackedList,
                                        selectedPersonIds = selectedIds,
                                        effects = request.effects,
                                        follow = request.follow.copy(enabled = false),
                                        presentationTimeUs = ptsUs,
                                        textureType = renderTexType,
                                        freshPrivacyClassEvidence = freshPrivacyClassEvidence,
                                        freshSelectedCoveredTrackIds = freshSelectedCoveredTrackIds,
                                        suppressedSelectedPrivacyTrackIds = suppressedSelectedPrivacyTrackIds,
                                        preferFreshPrivacyClassPrimary = preferFreshPrivacyClassPrimary,
                                        expectedSelectedPrivacyCount = selectedIds.size,
                                        maxFallbackObservationAgeFrames = trackManager.getMaxMissedFrames(),
                                        additionalResolvedPrivacy = faceOnlyFrameResult?.resolvedPrivacy,
                                        faceStickerPlacements = faceOnlyFrameResult?.stickerPlacements.orEmpty(),
                                        tightMask = shouldUseTightFullBodyMaskForExport(selectedIds),
                                        sourceWidth = trackingWidth,
                                        sourceHeight = trackingHeight,
                                        legStretchTargetPersonId = if (request.follow.enabled) {
                                            request.follow.targetPersonId?.toInt()
                                        } else {
                                            null
                                        },
                                        cropClarityScale = scale,
                                        cropClarityJobId = jobId
                                    )
                                }
                                val previousFramebuffer = target.bind()
                                try {
                                    renderProtected(request.cropClarityScale ?: 1.0)
                                } finally {
                                    target.restore(previousFramebuffer)
                                }

                                val followTargetId = requireNotNull(request.follow.targetPersonId).toInt()
                                val rootFollowTrack = trackedList.firstOrNull { it.id == followTargetId }
                                val rootFollowObservation = resolveFollowCameraObservation(
                                    track = rootFollowTrack,
                                    trackingWidth = trackingWidth,
                                    trackingHeight = trackingHeight
                                )
                                if (rootFollowObservation != null && rootFollowTrack != null) {
                                    reframeIdentityTrackId = followTargetId
                                    reframeOcclusionProxyTrackId = null
                                    reframeTemporalRecovery.reset()
                                    reframeRecoveryExpiredLogged = false
                                    if (rootFollowTrack.observedThisFrame) {
                                        reframeLastIdentityTrackBox = rootFollowTrack.bbox
                                        reframeLastIdentityPtsUs = ptsUs
                                        reframeLastHandoffIou = null
                                        reframeLastHandoffAgeUs = null
                                        // If ID 2 and ID 8 were ever co-observed, an
                                        // ID 2 -> 8 handoff is identity-ambiguous, even
                                        // when later geometry looks similar. HOLD instead.
                                        reframeIdentityHistory.recordSelectedObservation(
                                            selectedId = followTargetId,
                                            selectedObserved = true,
                                            tracks = trackedList
                                        )
                                    }
                                }

                                var identityTrackId = reframeIdentityTrackId ?: followTargetId
                                reframeIdentityTrackId = identityTrackId
                                var identityTrack = trackedList.firstOrNull { it.id == identityTrackId }
                                var identityObservation = if (rootFollowObservation != null) {
                                    rootFollowObservation
                                } else {
                                    resolveFollowCameraObservation(
                                        track = identityTrack,
                                        trackingWidth = trackingWidth,
                                        trackingHeight = trackingHeight
                                    )
                                }
                                if (
                                    rootFollowObservation == null &&
                                    identityObservation != null &&
                                    identityTrack != null
                                ) {
                                    if (identityTrack.observedThisFrame) {
                                        reframeLastIdentityTrackBox = identityTrack.bbox
                                        reframeLastIdentityPtsUs = ptsUs
                                    }
                                    reframeOcclusionProxyTrackId = null
                                }

                                if (
                                    identityObservation == null &&
                                    (
                                        identityTrack == null ||
                                            (
                                                identityTrack.state != TrackState.OCCLUDED &&
                                                    identityTrack.state != TrackState.REACQUIRING
                                            )
                                    )
                                ) {
                                    reframeOcclusionProxyTrackId = null
                                }

                                // The occlusion-proxy lane must obey the same camera
                                // negative-identity evidence as direct/temporal handoff.
                                // Otherwise a historically separate dancer could still
                                // pull the crop through a cached proxy ID.
                                val excludedCameraProxyIds = allPrivacyTargetIds +
                                    reframeIdentityHistory.excludedIds()
                                if (reframeOcclusionProxyTrackId?.let { it in excludedCameraProxyIds } == true) {
                                    reframeOcclusionProxyTrackId = null
                                    reframeOcclusionProxyStabilizer.reset()
                                }
                                var followOcclusionProxy = if (identityObservation == null) {
                                    reframeOcclusionProxyTrackId?.let { proxyId ->
                                        trackedList.firstOrNull { track -> track.id == proxyId }
                                    }
                                } else {
                                    null
                                }
                                var followOcclusionProxyObservation = followOcclusionProxy?.let { proxy ->
                                    resolveFollowCameraObservation(
                                        track = proxy,
                                        trackingWidth = trackingWidth,
                                        trackingHeight = trackingHeight
                                    )
                                }
                                if (
                                    identityObservation == null &&
                                    reframeOcclusionProxyTrackId == null
                                ) {
                                    val freshOcclusionProxy = resolveFollowCameraOcclusionProxy(
                                        target = identityTrack,
                                        tracks = trackedList,
                                        excludedTrackIds = excludedCameraProxyIds
                                    )
                                    if (freshOcclusionProxy != null) {
                                        reframeOcclusionProxyTrackId = freshOcclusionProxy.id
                                        followOcclusionProxy = freshOcclusionProxy
                                        followOcclusionProxyObservation = resolveFollowCameraObservation(
                                            track = freshOcclusionProxy,
                                            trackingWidth = trackingWidth,
                                            trackingHeight = trackingHeight
                                        )
                                    }
                                }

                                val handoffAgeBeforeUs = reframeLastIdentityPtsUs?.let { lastIdentityPtsUs ->
                                    (ptsUs - lastIdentityPtsUs).coerceAtLeast(0L)
                                }
                                if (
                                    identityObservation == null &&
                                    (identityTrack == null || identityTrack.state == TrackState.LOST) &&
                                    handoffAgeBeforeUs != null &&
                                    handoffAgeBeforeUs <= FOLLOW_CAMERA_ID_HANDOFF_WINDOW_US
                                ) {
                                    val handoffAnchor = reframeLastIdentityTrackBox
                                    val handoffTrack = resolveFollowCameraLostHandoffProxy(
                                        anchor = handoffAnchor,
                                        targetId = identityTrackId,
                                        tracks = trackedList,
                                        handoffAgeUs = handoffAgeBeforeUs,
                                        excludedTrackIds = allPrivacyTargetIds +
                                            reframeIdentityHistory.excludedIds()
                                    )
                                    if (handoffTrack != null) {
                                        val previousIdentityTrackId = identityTrackId
                                        val handoffIou = handoffAnchor?.let { anchor ->
                                            TrackManager.computeBBoxIoU(anchor, handoffTrack.bbox)
                                        }
                                        identityTrackId = handoffTrack.id
                                        reframeIdentityTrackId = handoffTrack.id
                                        identityTrack = handoffTrack
                                        identityObservation = resolveFollowCameraObservation(
                                            track = handoffTrack,
                                            trackingWidth = trackingWidth,
                                            trackingHeight = trackingHeight
                                        )
                                        reframeOcclusionProxyTrackId = null
                                        followOcclusionProxy = null
                                        followOcclusionProxyObservation = null
                                        if (identityObservation != null) {
                                            reframeLastIdentityTrackBox = handoffTrack.bbox
                                            reframeLastIdentityPtsUs = ptsUs
                                        }
                                        reframeLastHandoffIou = handoffIou
                                        reframeLastHandoffAgeUs = handoffAgeBeforeUs
                                        reframeTemporalRecovery.reset()
                                        if (art.gaoge.dance.engine.diagnostics.DiagnosticsBuild.ENABLED) {
                                            art.gaoge.dance.engine.diagnostics.NativeDiagnostics.event(
                                                level = "INFO",
                                                component = "ExportPipeline",
                                                event = "AUTO_REFRAME_ID_HANDOFF",
                                                fields = mapOf(
                                                    "job_id" to jobId,
                                                    "frame" to processedFrames,
                                                    "pts_us" to ptsUs,
                                                    "target_person_id" to followTargetId,
                                                    "previous_identity_track_id" to previousIdentityTrackId,
                                                    "current_identity_track_id" to handoffTrack.id,
                                                    "handoff_age_us" to handoffAgeBeforeUs,
                                                    "handoff_iou" to handoffIou,
                                                    "late_strong_handoff" to (
                                                        handoffAgeBeforeUs > FOLLOW_CAMERA_ID_HANDOFF_RELAXED_WINDOW_US
                                                    )
                                                )
                                            )
                                        }
                                    }
                                }

                                // A lost root can reappear after the strict 1.1 s single-
                                // frame geometric window. Require repeated, unique real
                                // observations of the SAME camera-only candidate, and
                                // stop after 2.6 s. This must never reassign privacy IDs.
                                val eligibleForTemporalRecovery =
                                    identityObservation == null &&
                                        (identityTrack == null || identityTrack.state == TrackState.LOST) &&
                                        handoffAgeBeforeUs != null &&
                                        handoffAgeBeforeUs in
                                            FollowCameraTemporalRecovery.MIN_AGE_US..
                                            FollowCameraTemporalRecovery.MAX_AGE_US
                                if (eligibleForTemporalRecovery) {
                                    val previousPendingId = reframeTemporalRecovery.pendingTrackId
                                    val recovery = reframeTemporalRecovery.observe(
                                        anchor = reframeLastIdentityTrackBox,
                                        rootTrackId = identityTrackId,
                                        lastTargetPtsUs = reframeLastIdentityPtsUs,
                                        ptsUs = ptsUs,
                                        tracks = trackedList,
                                        excludedTrackIds = allPrivacyTargetIds +
                                            reframeIdentityHistory.excludedIds()
                                    )
                                    val currentPendingId = reframeTemporalRecovery.pendingTrackId
                                    if (
                                        art.gaoge.dance.engine.diagnostics.DiagnosticsBuild.ENABLED &&
                                        currentPendingId != null && currentPendingId != previousPendingId
                                    ) {
                                        art.gaoge.dance.engine.diagnostics.NativeDiagnostics.event(
                                            level = "INFO", component = "ExportPipeline",
                                            event = "AUTO_REFRAME_TEMPORAL_CANDIDATE",
                                            fields = mapOf(
                                                "job_id" to jobId, "pts_us" to ptsUs,
                                                "candidate_track_id" to currentPendingId,
                                                "age_us" to handoffAgeBeforeUs,
                                                "privacy_identity_unchanged" to true
                                            )
                                        )
                                    }
                                    if (recovery != null) {
                                        val previousIdentityTrackId = identityTrackId
                                        identityTrackId = recovery.track.id
                                        identityTrack = recovery.track
                                        identityObservation = resolveFollowCameraObservation(
                                            track = recovery.track,
                                            trackingWidth = trackingWidth,
                                            trackingHeight = trackingHeight
                                        )
                                        if (identityObservation != null) {
                                            reframeIdentityTrackId = recovery.track.id
                                            reframeLastIdentityTrackBox = recovery.track.bbox
                                            reframeLastIdentityPtsUs = ptsUs
                                            reframeLastHandoffIou = recovery.anchorIou
                                            reframeLastHandoffAgeUs = recovery.ageUs
                                            reframeOcclusionProxyTrackId = null
                                            followOcclusionProxy = null
                                            followOcclusionProxyObservation = null
                                            reframeRecoveryExpiredLogged = false
                                            art.gaoge.dance.engine.diagnostics.NativeDiagnostics.event(
                                                level = "INFO", component = "ExportPipeline",
                                                event = "AUTO_REFRAME_ID_HANDOFF",
                                                fields = mapOf(
                                                    "job_id" to jobId, "frame" to processedFrames,
                                                    "pts_us" to ptsUs,
                                                    "target_person_id" to followTargetId,
                                                    "previous_identity_track_id" to previousIdentityTrackId,
                                                    "current_identity_track_id" to recovery.track.id,
                                                    "handoff_mode" to "TEMPORAL_CONFIRMED",
                                                    "handoff_age_us" to recovery.ageUs,
                                                    "handoff_iou" to recovery.anchorIou,
                                                    "confirmed_observations" to recovery.observations,
                                                    "observation_span_us" to recovery.observationSpanUs,
                                                    "privacy_identity_unchanged" to true
                                                )
                                            )
                                        }
                                    }
                                } else {
                                    reframeTemporalRecovery.reset()
                                    if (
                                        identityObservation == null &&
                                        handoffAgeBeforeUs != null &&
                                        handoffAgeBeforeUs > FollowCameraTemporalRecovery.MAX_AGE_US &&
                                        !reframeRecoveryExpiredLogged
                                    ) {
                                        reframeRecoveryExpiredLogged = true
                                        art.gaoge.dance.engine.diagnostics.NativeDiagnostics.event(
                                            level = "INFO", component = "ExportPipeline",
                                            event = "AUTO_REFRAME_RECOVERY_EXPIRED",
                                            fields = mapOf(
                                                "job_id" to jobId, "pts_us" to ptsUs,
                                                "age_us" to handoffAgeBeforeUs,
                                                "identity_track_id" to identityTrackId,
                                                "camera_stays_held" to true,
                                                "historical_co_observed_excluded_ids" to
                                                    reframeIdentityHistory.excludedIds().sorted()
                                            )
                                        )
                                    }
                                }

                                val currentIdentityTrackId = reframeIdentityTrackId ?: followTargetId
                                val identityIsHandoff = currentIdentityTrackId != followTargetId
                                val rawOcclusionProxyObservation = followOcclusionProxyObservation
                                val stabilizedOcclusionProxyObservation = if (
                                    identityObservation == null &&
                                    reframeOcclusionProxyTrackId != null
                                ) {
                                    rawOcclusionProxyObservation?.let { rawProxy ->
                                        reframeOcclusionProxyStabilizer.stabilize(
                                            target = rawProxy,
                                            presentationTimeUs = ptsUs
                                        )
                                    }
                                } else {
                                    reframeOcclusionProxyStabilizer.reset()
                                    null
                                }
                                val followObservation =
                                    identityObservation ?: stabilizedOcclusionProxyObservation
                                val targetSource = when {
                                    identityObservation != null && !identityIsHandoff &&
                                        identityTrack?.observedThisFrame == true -> "OBSERVED"
                                    identityObservation != null && !identityIsHandoff -> "PREDICTED"
                                    identityObservation != null -> "ID_HANDOFF_PROXY"
                                    stabilizedOcclusionProxyObservation != null -> "OCCLUSION_PROXY"
                                    else -> "HELD"
                                }
                                val effectiveProxyTrackId = when {
                                    reframeOcclusionProxyTrackId != null -> reframeOcclusionProxyTrackId
                                    identityIsHandoff -> currentIdentityTrackId
                                    else -> null
                                }
                                val effectiveProxyMode = when {
                                    reframeOcclusionProxyTrackId != null -> FollowCameraProxyMode.OCCLUSION
                                    identityIsHandoff -> FollowCameraProxyMode.ID_HANDOFF
                                    else -> null
                                }
                                val followProxyIou = when {
                                    followOcclusionProxy != null && identityTrack != null ->
                                        TrackManager.computeBBoxIoU(identityTrack.bbox, followOcclusionProxy.bbox)
                                    identityIsHandoff -> reframeLastHandoffIou
                                    else -> null
                                }
                                val continuityAgeUs = reframeLastIdentityPtsUs?.let { lastIdentityPtsUs ->
                                    (ptsUs - lastIdentityPtsUs).coerceAtLeast(0L)
                                }
                                if (
                                    art.gaoge.dance.engine.diagnostics.DiagnosticsBuild.ENABLED &&
                                    (
                                        lastLoggedReframeProxyTrackId != effectiveProxyTrackId ||
                                            lastLoggedReframeProxyMode != effectiveProxyMode
                                    )
                                ) {
                                    art.gaoge.dance.engine.diagnostics.NativeDiagnostics.event(
                                        level = "INFO",
                                        component = "ExportPipeline",
                                        event = "AUTO_REFRAME_PROXY_CHANGE",
                                        fields = mapOf(
                                            "job_id" to jobId,
                                            "frame" to processedFrames,
                                            "pts_us" to ptsUs,
                                            "target_person_id" to followTargetId,
                                            "identity_track_id" to currentIdentityTrackId,
                                            "previous_proxy_id" to lastLoggedReframeProxyTrackId,
                                            "previous_proxy_mode" to lastLoggedReframeProxyMode?.name,
                                            "current_proxy_id" to effectiveProxyTrackId,
                                            "current_proxy_iou" to followProxyIou,
                                            "proxy_mode" to effectiveProxyMode?.name,
                                            "continuity_age_us" to continuityAgeUs,
                                            "identity_state" to identityTrack?.state?.name,
                                            "identity_frames_since_observation" to
                                                identityTrack?.framesSinceLastObservation,
                                            "root_target_state" to rootFollowTrack?.state?.name,
                                            "root_target_frames_since_observation" to
                                                rootFollowTrack?.framesSinceLastObservation
                                        )
                                    )
                                    lastLoggedReframeProxyTrackId = effectiveProxyTrackId
                                    lastLoggedReframeProxyMode = effectiveProxyMode
                                }
                                val visualCrop = reframeFollower.cropForFrame(
                                    target = followObservation ?: if (!reframeInitialized) followSeed else null,
                                    presentationTimeUs = ptsUs,
                                    sourceAspectRatio = trackingWidth.toFloat() / trackingHeight.toFloat(),
                                    outputAspectRatio = requireNotNull(request.follow.outputAspectRatio).toFloat(),
                                    zoom = request.follow.zoom.toFloat(),
                                    smoothFactor = request.follow.smoothFactor.toFloat()
                                )
                                val reframeMotion = reframeFollower.motionState()
                                if (!reframeInitialized) {
                                    art.gaoge.dance.engine.diagnostics.NativeDiagnostics.event(
                                        level = "INFO",
                                        component = "ExportPipeline",
                                        event = "POST_CROP_FIRST_FRAME",
                                        fields = mapOf(
                                            "target_person_id" to followTargetId,
                                            "identity_track_id" to currentIdentityTrackId,
                                            "observed_target" to (identityTrack?.observedThisFrame == true),
                                            "root_observed_target" to (rootFollowTrack?.observedThisFrame == true),
                                            "target_source" to targetSource,
                                            "crop_left" to visualCrop.left,
                                            "crop_top" to visualCrop.top,
                                            "crop_right" to visualCrop.right,
                                            "crop_bottom" to visualCrop.bottom
                                        )
                                    )
                                }
                                if (
                                    art.gaoge.dance.engine.diagnostics.DiagnosticsBuild.ENABLED &&
                                    (processedFrames == 1 || processedFrames % 15 == 0)
                                ) {
                                    art.gaoge.dance.engine.diagnostics.NativeDiagnostics.event(
                                        level = "INFO",
                                        component = "ExportPipeline",
                                        event = "AUTO_REFRAME_SAMPLE",
                                        fields = mapOf(
                                            "job_id" to jobId,
                                            "frame" to processedFrames,
                                            "pts_us" to ptsUs,
                                            "target_person_id" to followTargetId,
                                            "identity_track_id" to currentIdentityTrackId,
                                            "observed_target" to (identityTrack?.observedThisFrame == true),
                                            "root_observed_target" to (rootFollowTrack?.observedThisFrame == true),
                                            "target_source" to targetSource,
                                            "target_state" to identityTrack?.state?.name,
                                            "target_frames_since_observation" to
                                                identityTrack?.framesSinceLastObservation,
                                            "root_target_state" to rootFollowTrack?.state?.name,
                                            "root_target_frames_since_observation" to
                                                rootFollowTrack?.framesSinceLastObservation,
                                            "target_proxy_id" to effectiveProxyTrackId,
                                            "target_proxy_iou" to followProxyIou,
                                            "target_proxy_mode" to effectiveProxyMode?.name,
                                            "target_occlusion_proxy_id" to reframeOcclusionProxyTrackId,
                                            "proxy_raw_center_x" to rawOcclusionProxyObservation?.centerX,
                                            "proxy_stabilized_center_x" to
                                                stabilizedOcclusionProxyObservation?.centerX,
                                            "proxy_stabilization_delta_x" to if (
                                                rawOcclusionProxyObservation != null &&
                                                stabilizedOcclusionProxyObservation != null
                                            ) {
                                                stabilizedOcclusionProxyObservation.centerX -
                                                    rawOcclusionProxyObservation.centerX
                                            } else {
                                                null
                                            },
                                            "target_handoff_age_us" to continuityAgeUs,
                                            "target_last_handoff_age_us" to reframeLastHandoffAgeUs,
                                            "target_center_x" to followObservation?.centerX,
                                            "target_center_y" to followObservation?.centerY,
                                            "crop_center_x" to visualCrop.centerX,
                                            "crop_center_y" to visualCrop.centerY,
                                            "camera_velocity_x" to reframeMotion.velocityX,
                                            "camera_velocity_y" to reframeMotion.velocityY,
                                            "camera_velocity_x_spans_per_s" to (
                                                reframeMotion.velocityX / visualCrop.width.coerceAtLeast(1e-6f)
                                            ),
                                            "camera_target_error_x" to (
                                                reframeMotion.targetX - reframeMotion.cameraX
                                            ),
                                            "camera_target_error_x_spans" to (
                                                (reframeMotion.targetX - reframeMotion.cameraX) /
                                                    visualCrop.width.coerceAtLeast(1e-6f)
                                            ),
                                            "crop_left" to visualCrop.left,
                                            "crop_top" to visualCrop.top,
                                            "crop_right" to visualCrop.right,
                                            "crop_bottom" to visualCrop.bottom
                                        )
                                    )
                                }
                                reframeInitialized = true
                                val glCrop = art.gaoge.dance.engine.camera.ReframeGeometry
                                    .visualTopLeftToScreenGl(visualCrop)
                                val cropTextureMatrix = art.gaoge.dance.engine.camera.ReframeGeometry
                                    .textureMatrixForScreenGlCrop(glCrop)
                                val chosenScene = if (clarityAbCapture != null &&
                                    clarityAbBaselineTarget != null
                                ) {
                                    // Compare selected and unselected *observed* boxes; a
                                    // positive overlap is a review hint, not a privacy verdict.
                                    val visibleProtected = trackedList.filter {
                                        allPrivacyTargetIds.contains(it.id) && it.observedThisFrame
                                    }
                                    val visibleOther = trackedList.filter {
                                        !allPrivacyTargetIds.contains(it.id) && it.observedThisFrame
                                    }
                                    // Use the *final* dynamic portrait crop, not the full
                                    // horizontal frame. Exact source-space tracked bboxes are
                                    // projected into the identical crop used by the encoder.
                                    val cropPrivacy = CropClarityCropPrivacyGeometry.evaluate(
                                        crop = visualCrop,
                                        sourceWidth = trackingWidth,
                                        sourceHeight = trackingHeight,
                                        protectedBoxes = visibleProtected.map { it.bbox },
                                        otherBoxes = visibleOther.map { it.bbox }
                                    )
                                    val protagonistBox = identityTrack?.takeIf { it.observedThisFrame }?.bbox
                                    val currentPosition = protagonistBox?.let {
                                        (it.centerX / trackingWidth.coerceAtLeast(1).toFloat()) to
                                            (it.centerY / trackingHeight.coerceAtLeast(1).toFloat())
                                    }
                                    val frameGap = (processedFrames - previousProtagonistFrame).coerceAtLeast(1)
                                    val motion = if (currentPosition != null &&
                                        previousProtagonistPosition != null
                                    ) {
                                        val old = requireNotNull(previousProtagonistPosition)
                                        val dx = currentPosition.first - old.first
                                        val dy = currentPosition.second - old.second
                                        (sqrt(dx * dx + dy * dy) / frameGap).coerceAtMost(1f)
                                    } else 0f
                                    if (currentPosition != null) {
                                        previousProtagonistPosition = currentPosition
                                        previousProtagonistFrame = processedFrames
                                    }
                                    clarityAbCapture?.choose(
                                        processedFrames,
                                        CropClaritySceneSampler.Signals(
                                            luma = qualityLuma,
                                            lumaAgeFrames = processedFrames - qualityLumaFrame,
                                            cropProtectedCount = cropPrivacy.cropProtectedCount,
                                            cropProtectedAreaFraction = cropPrivacy.cropProtectedAreaFraction,
                                            cropOverlap = cropPrivacy.maxCropOverlap,
                                            protagonistMotion = motion,
                                            sourceProtectedCount = cropPrivacy.sourceProtectedCount
                                        )
                                    )
                                } else null
                                var baselineReady = false
                                if (chosenScene != null) {
                                    val baselineTarget = requireNotNull(clarityAbBaselineTarget)
                                    try {
                                        val previous = baselineTarget.bind()
                                        try {
                                            renderProtected(1.0)
                                            baselineReady = true
                                        } finally {
                                            baselineTarget.restore(previous)
                                        }
                                    } catch (error: Throwable) {
                                        art.gaoge.dance.engine.diagnostics.NativeDiagnostics.event(
                                            level = "WARN", component = "ExportPipeline",
                                            event = "CROP_CLARITY_AB_BASELINE_FAILED",
                                            fields = mapOf("job_id" to jobId, "frame" to processedFrames,
                                                "error" to error.javaClass.simpleName)
                                        )
                                    }
                                }
                                // Snapshot the exact *protected* output, not a raw frame or a
                                // second independently computed camera trajectory.
                                var baselineBitmap: android.graphics.Bitmap? = null
                                if (baselineReady) {
                                    try {
                                        glRenderer.renderBase(
                                            frameTexture = requireNotNull(clarityAbBaselineTarget).textureId,
                                            texMatrix = cropTextureMatrix,
                                            textureType = art.gaoge.dance.engine.render.SourceTextureType.TEXTURE_2D
                                        )
                                        baselineBitmap = glRenderer.captureRenderedFrame()
                                        if (baselineBitmap != null &&
                                            !CropClarityAbCapture.hasVisibleProtectedContent(requireNotNull(baselineBitmap))
                                        ) {
                                            baselineBitmap?.recycle()
                                            baselineBitmap = null
                                            art.gaoge.dance.engine.diagnostics.NativeDiagnostics.event(
                                                level = "INFO", component = "ExportPipeline",
                                                event = "CROP_CLARITY_AB_DARK_FRAME_SKIPPED",
                                                fields = mapOf("job_id" to jobId, "frame" to processedFrames)
                                            )
                                        }
                                    } catch (error: Throwable) {
                                        art.gaoge.dance.engine.diagnostics.NativeDiagnostics.event(
                                            level = "WARN", component = "ExportPipeline",
                                            event = "CROP_CLARITY_AB_READBACK_FAILED",
                                            fields = mapOf("job_id" to jobId, "frame" to processedFrames,
                                                "variant" to "off", "error" to error.javaClass.simpleName)
                                        )
                                    }
                                }
                                // Always leave the production enhanced output on the encoder
                                // surface, even if the optional baseline readback failed.
                                glRenderer.renderBase(
                                    frameTexture = target.textureId,
                                    texMatrix = cropTextureMatrix,
                                    textureType = art.gaoge.dance.engine.render.SourceTextureType.TEXTURE_2D
                                )
                                if (baselineBitmap != null) {
                                    val baseline = requireNotNull(baselineBitmap)
                                    val enhanced = try { glRenderer.captureRenderedFrame() } catch (_: Throwable) { null }
                                    if (enhanced != null) {
                                        clarityAbCapture?.capture(
                                            choice = requireNotNull(chosenScene),
                                            ptsUs = ptsUs,
                                            cropLeft = visualCrop.left,
                                            cropTop = visualCrop.top,
                                            cropRight = visualCrop.right,
                                            cropBottom = visualCrop.bottom,
                                            baseline = baseline,
                                            enhanced = enhanced
                                        ) ?: run { baseline.recycle(); enhanced.recycle() }
                                    } else {
                                        baseline.recycle()
                                        art.gaoge.dance.engine.diagnostics.NativeDiagnostics.event(
                                            level = "WARN", component = "ExportPipeline",
                                            event = "CROP_CLARITY_AB_READBACK_FAILED",
                                            fields = mapOf("job_id" to jobId, "frame" to processedFrames,
                                                "variant" to "on")
                                        )
                                    }
                                }
                            } else {
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
                                    faceStickerPlacements = faceOnlyFrameResult?.stickerPlacements.orEmpty(),
                                    tightMask = shouldUseTightFullBodyMaskForExport(selectedIds),
                                    sourceWidth = trackingWidth,
                                    sourceHeight = trackingHeight,
                                    initialFollowTarget = followSeed,
                                    legStretchTargetPersonId = if (request.follow.enabled) {
                                        request.follow.targetPersonId?.toInt()
                                    } else {
                                        null
                                    },
                                    cropClarityScale = request.cropClarityScale ?: 1.0,
                                    cropClarityJobId = jobId
                                )
                            }
                            renderedFrameCount++
                        }


                    // Optional live preview capture when enabled (async background IO).
                    // Runtime gating prevents capture/readback/JPEG work while the UI is closed.
                    val now = System.currentTimeMillis()
                    if (!livePreviewEnabled.get()) {
                        lastLivePreviewCaptureTime = 0L
                        lastPreviewFilePath.set(null)
                    }
                    if (livePreviewEnabled.get() && (now - lastLivePreviewCaptureTime > 350 || processedFrames == 1)) {
                        lastLivePreviewCaptureTime = now
                        if (isPreviewSaving.compareAndSet(false, true)) {
                            val capturedBmp = glRenderer.captureRenderedFrame()
                            if (capturedBmp != null) {
                                previewSequence++
                                val captureSequence = previewSequence
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
                                        val targetPreviewFile = java.io.File(livePreviewDir, "preview_${jobId}_$captureSequence.jpg")
                                        val tempPreview = java.io.File(livePreviewDir, "preview_${jobId}_tmp_$captureSequence.jpg")
                                        java.io.FileOutputStream(tempPreview).use { out ->
                                            previewBmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 75, out)
                                        }
                                        if (previewBmp !== capturedBmp) {
                                            previewBmp.recycle()
                                        }
                                        capturedBmp.recycle()
                                        if (tempPreview.exists()) {
                                            tempPreview.renameTo(targetPreviewFile)
                                            if (livePreviewEnabled.get()) {
                                                lastPreviewFilePath.set(targetPreviewFile.absolutePath)
                                            }
                                            val staleSequence = captureSequence - 3L
                                            if (staleSequence > 0L) {
                                                java.io.File(livePreviewDir, "preview_${jobId}_$staleSequence.jpg").delete()
                                            }
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
                        // Keep video on the same trim-rebased source timeline as
                        // AudioTrackCopier instead of shifting the first decoded
                        // frame to t=0 independently.
                        val relPtsNs = (ptsUs - trimStartUs).coerceAtLeast(0L) * 1000L
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
                        val trimmedDurationUs = (trimEndUs - trimStartUs).coerceAtLeast(1L)
                        val progress = if (ptsUs >= trimStartUs) {
                            ((ptsUs - trimStartUs).toDouble() / trimmedDurationUs).coerceIn(0.0, 0.99)
                        } else {
                            (processedFrames.toDouble() / totalEstFrames).coerceIn(0.0, 0.99)
                        }

                        status = status.copy(
                            state = "processing",
                            currentFrame = processedFrames.toLong(),
                            fps = currentFps,
                            progress = progress,
                            outputUri = null,
                            currentPreviewPath = if (livePreviewEnabled.get()) lastPreviewFilePath.get() else null
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
                    art.gaoge.dance.engine.diagnostics.NativeDiagnostics.recordPipelineLifecycle(
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

                // Always emit one compact, non-media Logcat record on successful
                // exports. In Release this captures *real* app/codec pipeline
                // throughput without debug shadow probes or A/B readbacks.
                // renderEffects is a CPU dispatch timer (not GPU execution time).
                try {
                    val renderStage = profiler.snapshotSummary()["renderEffects"].orEmpty()
                    val elapsedMs = (android.os.SystemClock.elapsedRealtimeNanos() - perfStartNs) / 1_000_000.0
                    val thermal = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                        (context.getSystemService(Context.POWER_SERVICE) as? android.os.PowerManager)
                            ?.currentThermalStatus
                    } else null
                    val payload = org.json.JSONObject().apply {
                        put("schema", 1)
                        put("build_mode", if (art.gaoge.dance.engine.BuildConfig.DEBUG) "debug" else "release")
                        put("git_commit", art.gaoge.dance.engine.BuildConfig.GIT_COMMIT_SHA)
                        put("device_model", android.os.Build.MODEL)
                        put("source_width", videoInfo.displayWidth)
                        put("source_height", videoInfo.displayHeight)
                        put("source_fps", videoInfo.fps)
                        put("trim_start_ms", trimStartMs)
                        put("trim_end_ms", trimEndMs)
                        put("target_width", targetWidth)
                        put("target_height", targetHeight)
                        put("target_fps", nominalOutputFps)
                        put("video_bitrate", request.videoBitrate)
                        put("profile", request.processingProfile)
                        put("follow_enabled", request.follow.enabled)
                        put("privacy_target_count", allPrivacyTargetIds.size)
                        put("clarity_scale", request.cropClarityScale ?: 1.0)
                        put("clarity_state", if ((request.cropClarityScale ?: 1.0) > 1.001) "on" else "off")
                        put("decoded_frames", decodedFrameCount)
                        put("rendered_frames", renderedFrameCount)
                        put("encoded_frames", encodedFrameCount)
                        put("yolo_accelerator", yoloEffectiveAccelerator.name)
                        put("yolo_fallback", yoloFallbackReason ?: org.json.JSONObject.NULL)
                        put("elapsed_ms", elapsedMs)
                        put("throughput_fps", renderedFrameCount * 1000.0 / elapsedMs.coerceAtLeast(1.0))
                        put("render_cpu_dispatch_count", renderStage["count"] ?: 0)
                        put("render_cpu_dispatch_p50_ms", renderStage["p50_ms"] ?: 0)
                        put("render_cpu_dispatch_p95_ms", renderStage["p95_ms"] ?: 0)
                        put("thermal_status_end", thermal ?: org.json.JSONObject.NULL)
                        put("pss_end_kb", android.os.Debug.getPss())
                        put("ab_capture_possible", art.gaoge.dance.engine.BuildConfig.DEBUG)
                        put("state", "completed")
                    }
                    android.util.Log.i("WoahExportPerf", payload.toString())
                } catch (t: Throwable) {
                    android.util.Log.w("WoahExportPerf", "benchmark record unavailable: ${t.javaClass.simpleName}")
                }

                try {
                    val p95DeltaUs = if (surfacePtsDeltaSamples.isNotEmpty()) {
                        val sorted = surfacePtsDeltaSamples.sorted()
                        sorted[(sorted.size * 0.95).toInt().coerceAtMost(sorted.size - 1)]
                    } else 0L

                    art.gaoge.dance.engine.diagnostics.NativeDiagnostics.recordPipelineSummary(
                        jobId = jobId,
                        summary = mapOf(
                            "job_id" to jobId,
                            "profile" to request.processingProfile,
                            "source_width" to videoInfo.displayWidth,
                            "source_height" to videoInfo.displayHeight,
                            "source_fps" to videoInfo.fps,
                            "target_width" to targetWidth,
                            "target_height" to targetHeight,
                            "target_fps" to nominalOutputFps,
                            "crop_clarity_scale" to (request.cropClarityScale ?: 1.0),
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
                            "face_roi_read_count" to faceRoiReadCount,
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
                            "face_deterministic_cpu_primary_preferred" to preferDebugFaceDeterministicCpuPrimary,
                            "face_deterministic_cpu_primary_inference_frames" to faceDeterministicCpuPrimaryInferenceFrames,
                            "face_deterministic_cpu_primary_fallback_frames" to faceDeterministicCpuPrimaryFallbackFrames,
                            "cross_device_adaptive_shadow_matrix_enabled" to
                                (crossDeviceTrackingDiagnostics?.isAdaptiveShadowMatrixEnabled() ?: false),
                            "cross_device_adaptive_shadow_tracker_steps" to
                                (crossDeviceTrackingDiagnostics?.getAdaptiveShadowTrackerSteps() ?: 0L),
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
                            "yolo_effective_cpu_num_threads" to yoloEffectiveCpuNumThreads,
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
                            "cpu_mt4_probe_threads" to CPU_MT_PROBE_THREADS,
                            "cpu_mt4_signature_scope" to "FULL_EXPORT",
                            "cpu_mt4_probe_inference_frames" to cpuMt4ProbeInferenceFrames,
                            "cpu_mt4_production_reuse_frames" to cpuMt4ProductionReuseFrames,
                            "cpu_mt4_production_reuse_parity_frames" to cpuMt4ProductionReuseParityFrames,
                            "cpu_mt4_production_reuse_parity_exact" to cpuMt4ProductionReuseParityExact,
                            "cpu_mt4_reference_source_policy" to if (
                                reuseProductionCpuFallbackForCpuMt4Reference
                            ) {
                                "INDEPENDENT_ARTIFACT_WINDOW_WITH_SPARSE_PARITY_THEN_PRODUCTION_CPU4T_REUSE"
                            } else {
                                "INDEPENDENT_CPU4T_FULL_EXPORT"
                            },
                            "cpu_mt4_production_reuse_parity_interval_frames" to
                                CPU_MT4_PRODUCTION_REUSE_PARITY_INTERVAL_FRAMES,
                            "cpu_mt4_shadow_adaptive_schedules" to art.gaoge.dance.engine.diagnostics.CrossDeviceTrackingDiagnostics.DEFAULT_ADAPTIVE_CONFIGS.map { it.key },
                            "cpu_mt4_probe_fallback_reason" to cpuMt4ProbeFallbackReason,
                            "state" to "completed"
                        )
                    )
                    art.gaoge.dance.engine.diagnostics.NativeDiagnostics.recordPipelineLifecycle(
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
                art.gaoge.dance.engine.diagnostics.NativeDiagnostics.recordPipelineLifecycle(
                    stage = "FAILED",
                    jobId = jobId,
                    fields = mapOf(
                        "error_type" to e.javaClass.simpleName
                    )
                )
                pipelineException = e
            } finally {
                try { previewScope?.cancel() } catch (_: Throwable) {}
                try { cpuMt4ProbeSegmenter?.close() } catch (_: Throwable) {}
                try { canonicalInferenceDecoder?.close() } catch (_: Throwable) {}
                try { decoder?.close() } catch (_: Throwable) {}
                // Run the independent CPU-readable decoder probe only after the production
                // decoder is fully closed. This keeps the diagnostic from warming, occupying,
                // or otherwise perturbing the decoder/OES path whose behavior we are measuring.
                if (
                    art.gaoge.dance.engine.BuildConfig.DEBUG &&
                    pipelineException == null &&
                    !isCancelled.get()
                ) {
                    art.gaoge.dance.engine.diagnostics.VideoYuvDiagnosticSampler.capture(
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
                try { clarityAbCapture?.close() } catch (_: Throwable) {}
                try { clarityAbBaselineTarget?.close() } catch (_: Throwable) {}
                try { privacyRenderTarget?.close() } catch (_: Throwable) {}
                try { privacyRenderer?.close() } catch (_: Throwable) {}
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
        private const val CPU_MT_PROBE_THREADS = 4
        private const val CPU_MT4_ARTIFACT_MAX_PTS_US = 450_000L
        private const val CPU_MT4_PRODUCTION_REUSE_PARITY_INTERVAL_FRAMES = 120
        internal const val FOLLOW_CAMERA_PREDICTION_GRACE_FRAMES = 6
        internal const val FOLLOW_CAMERA_ID_HANDOFF_RELAXED_WINDOW_US = 650_000L
        internal const val FOLLOW_CAMERA_ID_HANDOFF_WINDOW_US = 1_100_000L
        private const val FOLLOW_CAMERA_ID_HANDOFF_MIN_IOU = 0.40f
        private const val FOLLOW_CAMERA_ID_HANDOFF_MAX_CENTER_DISTANCE_RATIO = 0.24f
        private const val FOLLOW_CAMERA_ID_HANDOFF_MIN_WIDTH_RATIO = 0.58f
        private const val FOLLOW_CAMERA_ID_HANDOFF_MAX_WIDTH_RATIO = 1.70f
        private const val FOLLOW_CAMERA_ID_HANDOFF_MIN_HEIGHT_RATIO = 0.65f
        private const val FOLLOW_CAMERA_ID_HANDOFF_MAX_HEIGHT_RATIO = 1.55f
        private const val FOLLOW_CAMERA_ID_HANDOFF_MIN_SCORE_MARGIN = 0.08f
        private const val FOLLOW_CAMERA_ID_HANDOFF_LATE_MIN_IOU = 0.60f
        private const val FOLLOW_CAMERA_ID_HANDOFF_LATE_MAX_CENTER_DISTANCE_RATIO = 0.12f
        private const val FOLLOW_CAMERA_ID_HANDOFF_LATE_MIN_WIDTH_RATIO = 0.65f
        private const val FOLLOW_CAMERA_ID_HANDOFF_LATE_MAX_WIDTH_RATIO = 1.55f
        private const val FOLLOW_CAMERA_ID_HANDOFF_LATE_MIN_HEIGHT_RATIO = 0.75f
        private const val FOLLOW_CAMERA_ID_HANDOFF_LATE_MAX_HEIGHT_RATIO = 1.35f
        private const val FOLLOW_CAMERA_ID_HANDOFF_LATE_MIN_SCORE_MARGIN = 0.10f
        internal const val SELECTION_IDENTITY_ROOT_MIN_CONFIDENCE = 0.60

        internal fun resolveFollowCameraObservation(
            track: TrackedPerson?,
            trackingWidth: Int,
            trackingHeight: Int
        ): FloatRect? {
            if (track == null || trackingWidth <= 0 || trackingHeight <= 0) return null
            val predictedGrace =
                !track.observedThisFrame &&
                    track.framesSinceLastObservation in 1..FOLLOW_CAMERA_PREDICTION_GRACE_FRAMES &&
                    track.state != TrackState.LOST &&
                    track.state != TrackState.REMOVED
            if (!track.observedThisFrame && !predictedGrace) return null
            return FloatRect(
                left = track.bbox.left / trackingWidth.toFloat(),
                top = track.bbox.top / trackingHeight.toFloat(),
                right = track.bbox.right / trackingWidth.toFloat(),
                bottom = track.bbox.bottom / trackingHeight.toFloat()
            )
        }

        internal fun resolveFollowCameraOcclusionProxy(
            target: TrackedPerson?,
            tracks: List<TrackedPerson>,
            excludedTrackIds: Set<Int> = emptySet()
        ): TrackedPerson? {
            if (target == null || target.observedThisFrame) return null
            if (target.state != TrackState.OCCLUDED && target.state != TrackState.REACQUIRING) {
                return null
            }

            val targetBox = target.bbox
            val targetWidth = targetBox.width.coerceAtLeast(1f)
            val targetHeight = targetBox.height.coerceAtLeast(1f)
            val referenceDim = maxOf(targetWidth, targetHeight, 1f)

            return tracks.asSequence()
                .filter { candidate ->
                    candidate.id != target.id && candidate.id !in excludedTrackIds &&
                        candidate.observedThisFrame &&
                        candidate.state != TrackState.LOST &&
                        candidate.state != TrackState.REMOVED
                }
                .mapNotNull { candidate ->
                    val candidateBox = candidate.bbox
                    val iou = TrackManager.computeBBoxIoU(targetBox, candidateBox)
                    val dx = candidateBox.centerX - targetBox.centerX
                    val dy = candidateBox.centerY - targetBox.centerY
                    val centerDistanceRatio = sqrt(dx * dx + dy * dy) / referenceDim
                    val widthRatio = candidateBox.width.coerceAtLeast(1f) / targetWidth
                    val heightRatio = candidateBox.height.coerceAtLeast(1f) / targetHeight
                    val explicitOccluder = target.occludedByTrackIds.contains(candidate.id)
                    val minIou = if (explicitOccluder) 0.45f else 0.55f
                    val maxCenterDistanceRatio = if (explicitOccluder) 0.30f else 0.20f
                    val minScale = if (explicitOccluder) 0.70f else 0.78f
                    val maxScale = if (explicitOccluder) 1.43f else 1.28f
                    if (
                        iou < minIou ||
                        centerDistanceRatio > maxCenterDistanceRatio ||
                        widthRatio !in minScale..maxScale ||
                        heightRatio !in minScale..maxScale
                    ) {
                        null
                    } else {
                        candidate to iou
                    }
                }
                .maxByOrNull { it.second }
                ?.first
        }

        internal fun resolveFollowCameraLostHandoffProxy(
            anchor: FloatRect?,
            targetId: Int,
            tracks: List<TrackedPerson>,
            handoffAgeUs: Long,
            excludedTrackIds: Set<Int> = emptySet()
        ): TrackedPerson? {
            if (
                anchor == null ||
                anchor.width <= 0f ||
                anchor.height <= 0f ||
                handoffAgeUs < 0L ||
                handoffAgeUs > FOLLOW_CAMERA_ID_HANDOFF_WINDOW_US
            ) {
                return null
            }
            val lateHandoff = handoffAgeUs > FOLLOW_CAMERA_ID_HANDOFF_RELAXED_WINDOW_US
            val minIou = if (lateHandoff) {
                FOLLOW_CAMERA_ID_HANDOFF_LATE_MIN_IOU
            } else {
                FOLLOW_CAMERA_ID_HANDOFF_MIN_IOU
            }
            val maxCenterDistanceRatio = if (lateHandoff) {
                FOLLOW_CAMERA_ID_HANDOFF_LATE_MAX_CENTER_DISTANCE_RATIO
            } else {
                FOLLOW_CAMERA_ID_HANDOFF_MAX_CENTER_DISTANCE_RATIO
            }
            val minWidthRatio = if (lateHandoff) {
                FOLLOW_CAMERA_ID_HANDOFF_LATE_MIN_WIDTH_RATIO
            } else {
                FOLLOW_CAMERA_ID_HANDOFF_MIN_WIDTH_RATIO
            }
            val maxWidthRatio = if (lateHandoff) {
                FOLLOW_CAMERA_ID_HANDOFF_LATE_MAX_WIDTH_RATIO
            } else {
                FOLLOW_CAMERA_ID_HANDOFF_MAX_WIDTH_RATIO
            }
            val minHeightRatio = if (lateHandoff) {
                FOLLOW_CAMERA_ID_HANDOFF_LATE_MIN_HEIGHT_RATIO
            } else {
                FOLLOW_CAMERA_ID_HANDOFF_MIN_HEIGHT_RATIO
            }
            val maxHeightRatio = if (lateHandoff) {
                FOLLOW_CAMERA_ID_HANDOFF_LATE_MAX_HEIGHT_RATIO
            } else {
                FOLLOW_CAMERA_ID_HANDOFF_MAX_HEIGHT_RATIO
            }
            val minScoreMargin = if (lateHandoff) {
                FOLLOW_CAMERA_ID_HANDOFF_LATE_MIN_SCORE_MARGIN
            } else {
                FOLLOW_CAMERA_ID_HANDOFF_MIN_SCORE_MARGIN
            }
            val anchorWidth = anchor.width.coerceAtLeast(1f)
            val anchorHeight = anchor.height.coerceAtLeast(1f)
            val referenceDim = maxOf(anchorWidth, anchorHeight, 1f)

            data class Candidate(
                val track: TrackedPerson,
                val score: Float
            )

            val candidates = tracks.asSequence()
                .filter { candidate ->
                    candidate.id != targetId && candidate.id !in excludedTrackIds &&
                        candidate.observedThisFrame &&
                        candidate.state != TrackState.LOST &&
                        candidate.state != TrackState.REMOVED
                }
                .mapNotNull { candidate ->
                    val candidateBox = candidate.bbox
                    val iou = TrackManager.computeBBoxIoU(anchor, candidateBox)
                    val dx = candidateBox.centerX - anchor.centerX
                    val dy = candidateBox.centerY - anchor.centerY
                    val centerDistanceRatio = sqrt(dx * dx + dy * dy) / referenceDim
                    val widthRatio = candidateBox.width.coerceAtLeast(1f) / anchorWidth
                    val heightRatio = candidateBox.height.coerceAtLeast(1f) / anchorHeight
                    if (
                        iou < minIou ||
                        centerDistanceRatio > maxCenterDistanceRatio ||
                        widthRatio !in minWidthRatio..maxWidthRatio ||
                        heightRatio !in minHeightRatio..maxHeightRatio
                    ) {
                        null
                    } else {
                        val score =
                            iou -
                                centerDistanceRatio * 0.25f -
                                kotlin.math.abs(widthRatio - 1f) * 0.10f -
                                kotlin.math.abs(heightRatio - 1f) * 0.10f
                        Candidate(candidate, score)
                    }
                }
                .sortedByDescending { it.score }
                .toList()

            val best = candidates.firstOrNull() ?: return null
            val second = candidates.getOrNull(1)
            if (second != null && best.score - second.score < minScoreMargin) {
                return null
            }
            return best.track
        }

        internal fun canonicalizeFaceReferenceCoordinate(value: Float): Float =
            FaceReferenceGeometryCanonicalizer.coordinate(value)

        internal fun canonicalizeFaceReferenceBbox(bbox: FloatRect): FloatRect =
            FaceReferenceGeometryCanonicalizer.rect(bbox)

        internal fun resolveFaceOnlyIdentityProtectedIds(
            metadata: art.gaoge.dance.engine.storage.AnalysisMetadata?,
            privacyTargetIds: Set<Int>
        ): Set<Int> {
            val credibleAnalysisIds = metadata
                ?.persons
                ?.asSequence()
                ?.filter { it.confidence >= SELECTION_IDENTITY_ROOT_MIN_CONFIDENCE }
                ?.map { it.id }
                ?.toSet()
                .orEmpty()
            return credibleAnalysisIds + privacyTargetIds
        }

        internal fun resolveInitialTrackIdsFromAnalysis(
            metadata: art.gaoge.dance.engine.storage.AnalysisMetadata?,
            detections: List<art.gaoge.dance.engine.inference.PersonDetection>,
            targetWidth: Int,
            targetHeight: Int
        ): List<Int> {
            if (metadata == null || metadata.persons.isEmpty() || detections.isEmpty()) {
                return detections.indices.toList()
            }

            val cached = metadata.persons
            val costMatrix = Array(cached.size) { r ->
                val cPerson = cached[r]
                val cLeft = (cPerson.bbox.left * targetWidth).toFloat()
                val cTop = (cPerson.bbox.top * targetHeight).toFloat()
                val cRight = (cPerson.bbox.right * targetWidth).toFloat()
                val cBottom = (cPerson.bbox.bottom * targetHeight).toFloat()
                val cBox = art.gaoge.dance.engine.inference.FloatRect(cLeft, cTop, cRight, cBottom)

                FloatArray(detections.size) { c ->
                    val dBox = detections[c].bbox
                    val iou = art.gaoge.dance.engine.tracking.TrackManager.computeBBoxIoU(cBox, dBox)
                    val refDim = maxOf(cBox.width, cBox.height, 1f)
                    val dx = cBox.centerX - dBox.centerX
                    val dy = cBox.centerY - dBox.centerY
                    val dist = kotlin.math.sqrt(dx * dx + dy * dy)
                    val distScore = (1.0f - (dist / (refDim * 1.5f))).coerceIn(0f, 1f)
                    val score = 0.7f * iou + 0.3f * distScore
                    (1.0f - score).coerceIn(0f, 1f)
                }
            }

            val matchResult = art.gaoge.dance.engine.tracking.HungarianSolver.match(
                costMatrix,
                maxCostThreshold = 0.85f
            )
            val assignedIds = IntArray(detections.size) { -1 }
            val usedIds = mutableSetOf<Int>()
            for ((cachedIndex, detectionIndex) in matchResult.matches) {
                if (detectionIndex < detections.size && cachedIndex < cached.size) {
                    val id = cached[cachedIndex].id
                    assignedIds[detectionIndex] = id
                    usedIds.add(id)
                }
            }

            var nextId = 0
            for (index in assignedIds.indices) {
                if (assignedIds[index] == -1) {
                    while (usedIds.contains(nextId)) nextId++
                    assignedIds[index] = nextId
                    usedIds.add(nextId)
                    nextId++
                }
            }
            return assignedIds.toList()
        }

        internal fun shouldUseFreshFullBodyClassPrimary(
            fullBodyPersonIds: Set<Int>,
            faceOnlyPersonIds: Set<Int>
        ): Boolean = fullBodyPersonIds.isNotEmpty() && faceOnlyPersonIds.isEmpty()

        internal fun shouldUseTightFullBodyMaskForExport(
            fullBodyPersonIds: Set<Int>
        ): Boolean = fullBodyPersonIds.isNotEmpty()

        internal fun shouldPreferDebugFaceDeterministicCpuPrimary(
            isDebugBuild: Boolean,
            fullBodyPersonIds: Set<Int>,
            faceOnlyPersonIds: Set<Int>
        ): Boolean =
            isDebugBuild &&
                fullBodyPersonIds.isEmpty() &&
                faceOnlyPersonIds.isNotEmpty()

        internal fun shouldReuseProductionCpuFallbackForCpuMt4Reference(
            isDebugBuild: Boolean,
            fullBodyPersonIds: Set<Int>,
            faceOnlyPersonIds: Set<Int>,
            effectiveAccelerator: art.gaoge.dance.engine.litert.LiteRtAccelerator,
            effectiveCpuNumThreads: Int?
        ): Boolean =
            isDebugBuild &&
                fullBodyPersonIds.isNotEmpty() &&
                faceOnlyPersonIds.isEmpty() &&
                effectiveAccelerator == art.gaoge.dance.engine.litert.LiteRtAccelerator.CPU &&
                effectiveCpuNumThreads == CPU_MT_PROBE_THREADS
    }
}


