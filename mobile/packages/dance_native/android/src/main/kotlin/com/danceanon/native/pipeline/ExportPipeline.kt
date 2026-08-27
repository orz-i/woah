package com.danceanon.native.pipeline

import android.content.Context
import android.graphics.SurfaceTexture
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface
import com.danceanon.native.bridge.DanceProcessingEvents
import com.danceanon.native.bridge.ExportRequestDto
import com.danceanon.native.bridge.JobStatusDto
import com.danceanon.native.inference.RgbaColOrder
import com.danceanon.native.inference.RgbaRowOrder
import com.danceanon.native.inference.YoloOnnxSegmenter
import com.danceanon.native.jobs.JobManager
import com.danceanon.native.media.AudioTrackCopier
import com.danceanon.native.media.Mp4Muxer
import com.danceanon.native.media.VideoDecoder
import com.danceanon.native.media.VideoEncoder
import com.danceanon.native.media.VideoProbe
import com.danceanon.native.render.EglCore
import com.danceanon.native.render.GlRenderer
import com.danceanon.native.storage.CacheManager
import com.danceanon.native.tracking.HungarianSolver
import com.danceanon.native.tracking.TrackManager
import com.danceanon.native.tracking.TrackState
import com.danceanon.native.tracking.TrackedPerson
import kotlinx.coroutines.Dispatchers
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
    private val segmenter: YoloOnnxSegmenter,
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
        onStatusChange(status)
        kotlinx.coroutines.CoroutineScope(Dispatchers.Main).launch {
            try { eventEmitter?.onProgressUpdate(status) } catch (_: Throwable) {}
        }

        segmenter.initialize()

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
            var encoder: VideoEncoder? = null
            var muxer: Mp4Muxer? = null
            var audioCopier: AudioTrackCopier? = null
            var frameReader: com.danceanon.native.render.InferenceFrameReader? = null
            var oesTextureId = 0
            var fallbackTexture2DId = 0
            var livePreviewFile: java.io.File? = null
            var decoderSurface: android.view.Surface? = null
            var fallbackRetriever: android.media.MediaMetadataRetriever? = null
            var use2DFallbackMode = false

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

                // Allocate 2D fallback texture
                val tex2D = IntArray(1)
                android.opengl.GLES20.glGenTextures(1, tex2D, 0)
                fallbackTexture2DId = tex2D[0]
                android.opengl.GLES20.glBindTexture(android.opengl.GLES20.GL_TEXTURE_2D, fallbackTexture2DId)
                android.opengl.GLES20.glTexParameteri(android.opengl.GLES20.GL_TEXTURE_2D, android.opengl.GLES20.GL_TEXTURE_MIN_FILTER, android.opengl.GLES20.GL_LINEAR)
                android.opengl.GLES20.glTexParameteri(android.opengl.GLES20.GL_TEXTURE_2D, android.opengl.GLES20.GL_TEXTURE_MAG_FILTER, android.opengl.GLES20.GL_LINEAR)
                android.opengl.GLES20.glTexParameteri(android.opengl.GLES20.GL_TEXTURE_2D, android.opengl.GLES20.GL_TEXTURE_WRAP_S, android.opengl.GLES20.GL_CLAMP_TO_EDGE)
                android.opengl.GLES20.glTexParameteri(android.opengl.GLES20.GL_TEXTURE_2D, android.opengl.GLES20.GL_TEXTURE_WRAP_T, android.opengl.GLES20.GL_CLAMP_TO_EDGE)

                val inferenceFbo = com.danceanon.native.render.InferenceFbo(640)
                val inferenceRenderer = com.danceanon.native.render.InferenceRenderer()
                val mapper = com.danceanon.native.geometry.ModelCoordinateMapper(targetWidth, targetHeight, 640)
                val profiler = com.danceanon.native.profiler.PipelineProfiler()

                val frameSync = Object()
                var frameAvailable = false

                surfaceTexture = SurfaceTexture(oesTextureId).apply {
                    val bufW = if (videoInfo.codedWidth > 0) videoInfo.codedWidth.toInt() else targetWidth
                    val bufH = if (videoInfo.codedHeight > 0) videoInfo.codedHeight.toInt() else targetHeight
                    setDefaultBufferSize(bufW, bufH)
                    setOnFrameAvailableListener({
                        synchronized(frameSync) {
                            frameAvailable = true
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
                val totalEstFrames = ((videoInfo.durationMs / 1000.0) * targetFps).toLong().coerceAtLeast(1L)
                val stMatrix = FloatArray(16).apply {
                    android.opengl.Matrix.setIdentityM(this, 0)
                }
                var lastPresentationNs = -1L
                val trackManager = TrackManager()
                val profile = ProcessingProfile.fromName(request.processingProfile)
                val frameStride = profile.inferenceStride
                var lastProgressEmitTime = 0L
                val isSam2Mode = profile.useSam2
                var sam2Fbo: com.danceanon.native.sam2.Sam2InputFbo? = null
                var sam2Renderer: com.danceanon.native.sam2.Sam2InputRenderer? = null
                var sam2Tracker: com.danceanon.native.sam2.ISam2VideoTracker? = null

                android.util.Log.i(
                    "ExportPipeline",
                    "Pipeline Config: isSam2Mode=$isSam2Mode, profileName=${profile.name}, stride=$frameStride, inputSize=${profile.inputSize}, target=${targetWidth}x${targetHeight}"
                )

                if (isSam2Mode) {
                    sam2Fbo = com.danceanon.native.sam2.Sam2InputFbo(com.danceanon.native.sam2.Sam2TensorContract.IMAGE_SIZE)
                    sam2Renderer = com.danceanon.native.sam2.Sam2InputRenderer()
                    val bundle = com.danceanon.native.sam2.Sam2OnnxModelLoader.loadFromAssets(context)
                    sam2Tracker = com.danceanon.native.sam2.Sam2OnnxVideoTracker(bundle, encoderStride = frameStride)
                }

                var lastLivePreviewCaptureTime = 0L
                val livePreviewDir = java.io.File(context.cacheDir, "export_live_preview").apply { mkdirs() }
                livePreviewFile = java.io.File(livePreviewDir, "preview_${jobId}.jpg")



                while (!isCancelled.get()) {
                    val fed = decoder.feedInputBuffer()
                    var reachedEOS = false

                    decoder.drainOutputBuffer { ptsUs, isEOS ->
                        if (isEOS || isCancelled.get()) {
                            reachedEOS = true
                            return@drainOutputBuffer
                        }

                        processedFrames++

                        val selectedIds = request.selectedPersonIds.map { it.toInt() }.toSet()

                        // Await frame arrival from decoder hardware rendering (immediate wakeup via frameHandler)
                        synchronized(frameSync) {
                            var waited = 0
                            while (!frameAvailable && waited < 500) {
                                try {
                                    frameSync.wait(20)
                                    waited += 20
                                } catch (_: InterruptedException) {}
                            }
                            frameAvailable = false
                        }

                        // Ensure OES texture is active and bound before latching frame
                        android.opengl.GLES20.glActiveTexture(android.opengl.GLES20.GL_TEXTURE0)
                        android.opengl.GLES20.glBindTexture(android.opengl.GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTextureId)

                        // Latch and update OES texture with decoded video frame
                        try {
                            surfaceTexture?.updateTexImage()
                            surfaceTexture?.getTransformMatrix(stMatrix)
                        } catch (e: Throwable) {
                            android.util.Log.w("ExportPipeline", "updateTexImage warning: ${e.message}")
                        }

                        val rotation = videoInfo.rotation.toInt()
                        val finalTexMatrix = GlRenderer.computeTransformMatrix(stMatrix, rotation)



                        // 2. Perform Inference / Temporal Mask Tracking
                        val trackedList: List<com.danceanon.native.tracking.TrackedPerson> = if (isSam2Mode && sam2Fbo != null && sam2Renderer != null && sam2Tracker != null) {
                            if (processedFrames == 1) {
                                // Frame 1: YOLO anchor detection to register prompt boxes
                                val initialPersons = profiler.recordStage("yoloAnchor") {
                                    inferenceRenderer.renderToFbo(oesTextureId, finalTexMatrix, mapper, inferenceFbo)
                                    val yoloRgbaBuffer = inferenceFbo.readRgbaPixels()
                                    val seg = segmenter.segmentGlReadbackRgbaSync(yoloRgbaBuffer, mapper, ptsUs, colOrder = RgbaColOrder.LEFT_TO_RIGHT)
                                    seg.persons.sortedBy { it.bbox.centerX }
                                }

                                val sam2RgbaBuffer = profiler.recordStage("sam2Readback") {
                                    sam2Renderer.renderToFbo(oesTextureId, finalTexMatrix, sam2Fbo)
                                    sam2Fbo.readRgbaPixels()
                                }

                                profiler.recordStage("sam2Init") {
                                    initialPersons.mapIndexed { idx, det ->
                                        sam2Tracker.initializeWithRgba(
                                            rgbaBuffer = sam2RgbaBuffer,
                                            width = targetWidth,
                                            height = targetHeight,
                                            objectId = idx,
                                            bbox = det.bbox
                                        )

                                        val visualMask = det.mask?.copy(
                                            samplingRect = com.danceanon.native.inference.FloatRect(0f, 0f, 1f, 1f)
                                        ) ?: det.mask

                                        com.danceanon.native.tracking.TrackedPerson(
                                            id = idx,
                                            bbox = det.bbox,
                                            mask = visualMask,
                                            confidence = det.confidence,
                                            state = com.danceanon.native.tracking.TrackState.ACTIVE
                                        )
                                    }
                                }
                            } else {
                                // Frame 2+: SAM2 persistent temporal propagation with direct FBO RGBA and Stride Caching
                                val sam2RgbaBuffer = profiler.recordStage("sam2Readback") {
                                    sam2Renderer.renderToFbo(oesTextureId, finalTexMatrix, sam2Fbo)
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
                            val detections = if (shouldInfer) {
                                profiler.recordStage("gpuLetterbox") {
                                    inferenceRenderer.renderToFbo(oesTextureId, finalTexMatrix, mapper, inferenceFbo)
                                }
                                val debugSize = inferenceFbo.size
                                val rgbaBuffer = profiler.recordStage("readback640") {
                                    inferenceFbo.readRgbaPixels()
                                }
                                val seg = profiler.recordStage("inference") {
                                    segmenter.segmentGlReadbackRgbaSync(rgbaBuffer, mapper, ptsUs, colOrder = RgbaColOrder.LEFT_TO_RIGHT)
                                }
                                profiler.recordStage("privacySafety") {
                                    com.danceanon.native.privacy.PrivacySegmentationProcessor.DEFAULT.applyPrivacySafety(seg.persons)
                                }
                            } else {
                                emptyList()
                            }

                            // Tracking on detections
                            profiler.recordStage("tracking") {
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
                        }


                        // 4. Render final anonymized frame to EGL surface (encoder input)
                        var renderTexId = oesTextureId
                        var renderTexType = com.danceanon.native.render.SourceTextureType.OES
                        var renderTexMatrix: FloatArray? = finalTexMatrix

                        // Self-healing black screen check on frame 1
                        if (processedFrames == 1 && !use2DFallbackMode) {
                            profiler.recordStage("renderEffects") {
                                glRenderer.render(
                                    frameTexture = oesTextureId,
                                    texMatrix = finalTexMatrix,
                                    persons = trackedList,
                                    selectedPersonIds = selectedIds,
                                    effects = request.effects,
                                    follow = request.follow,
                                    presentationTimeUs = ptsUs,
                                    textureType = com.danceanon.native.render.SourceTextureType.OES
                                )
                            }

                            val sampleBmp = glRenderer.captureRenderedFrame()
                            val isBlack = isFrameBlack(sampleBmp)
                            sampleBmp?.recycle()

                            if (isBlack) {
                                android.util.Log.w("ExportPipeline", "OES Hardware pipe rendered black screen on Android 16/Device. Auto-activating 2D Frame Provider fallback!")
                                use2DFallbackMode = true
                                fallbackRetriever = android.media.MediaMetadataRetriever().apply {
                                    if (sourceUri.startsWith("content://")) {
                                        setDataSource(context, android.net.Uri.parse(sourceUri))
                                    } else {
                                        setDataSource(sourceUri.removePrefix("file://"))
                                    }
                                }
                            }
                        }

                        if (use2DFallbackMode) {
                            val retriever = fallbackRetriever
                            var fallbackBmp: android.graphics.Bitmap? = null
                            if (retriever != null) {
                                try {
                                    fallbackBmp = retriever.getFrameAtTime(ptsUs, android.media.MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                                        ?: retriever.getFrameAtTime(ptsUs, android.media.MediaMetadataRetriever.OPTION_CLOSEST)
                                } catch (_: Throwable) {}
                            }

                            if (fallbackBmp != null) {
                                val softwareBmp = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O && fallbackBmp.config == android.graphics.Bitmap.Config.HARDWARE) {
                                    fallbackBmp.copy(android.graphics.Bitmap.Config.ARGB_8888, false).also {
                                        if (fallbackBmp != it) fallbackBmp.recycle()
                                    }
                                } else {
                                    fallbackBmp
                                }

                                val rot = videoInfo.rotation.toInt()
                                val rotatedBmp = if (rot != 0) {
                                    val mat = android.graphics.Matrix().apply { postRotate(rot.toFloat()) }
                                    val rb = android.graphics.Bitmap.createBitmap(softwareBmp, 0, 0, softwareBmp.width, softwareBmp.height, mat, true)
                                    if (softwareBmp != rb) softwareBmp.recycle()
                                    rb
                                } else {
                                    softwareBmp
                                }

                                android.opengl.GLES20.glActiveTexture(android.opengl.GLES20.GL_TEXTURE0)
                                android.opengl.GLES20.glBindTexture(android.opengl.GLES20.GL_TEXTURE_2D, fallbackTexture2DId)
                                android.opengl.GLUtils.texImage2D(android.opengl.GLES20.GL_TEXTURE_2D, 0, rotatedBmp, 0)
                                rotatedBmp.recycle()

                                renderTexId = fallbackTexture2DId
                                renderTexType = com.danceanon.native.render.SourceTextureType.TEXTURE_2D
                                renderTexMatrix = null
                            }
                        }

                        profiler.recordStage("renderEffects") {
                            glRenderer.render(
                                frameTexture = renderTexId,
                                texMatrix = renderTexMatrix,
                                persons = trackedList,
                                selectedPersonIds = selectedIds,
                                effects = request.effects,
                                follow = request.follow,
                                presentationTimeUs = ptsUs,
                                textureType = renderTexType
                            )
                        }

                        // Optional live preview capture when enabled
                        val now = System.currentTimeMillis()
                        var currentLivePreviewPath = status.currentPreviewPath
                        if (request.enableLivePreview && (now - lastLivePreviewCaptureTime > 400 || processedFrames == 1)) {
                            lastLivePreviewCaptureTime = now
                            try {
                                val visualBmp = glRenderer.captureRenderedFrame()
                                if (visualBmp != null) {
                                    val scale = minOf(1.0f, 480f / maxOf(visualBmp.width, visualBmp.height))
                                    val previewBmp = if (scale < 1.0f) {
                                        android.graphics.Bitmap.createScaledBitmap(
                                            visualBmp,
                                            (visualBmp.width * scale).toInt().coerceAtLeast(1),
                                            (visualBmp.height * scale).toInt().coerceAtLeast(1),
                                            true
                                        )
                                    } else {
                                        visualBmp
                                    }
                                    val tempPreview = java.io.File(livePreviewDir, "preview_${jobId}_tmp.jpg")
                                    java.io.FileOutputStream(tempPreview).use { out ->
                                        previewBmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 75, out)
                                    }
                                    if (previewBmp !== visualBmp) {
                                        previewBmp.recycle()
                                    }
                                    visualBmp.recycle()
                                    if (tempPreview.exists()) {
                                        if (livePreviewFile.exists()) livePreviewFile.delete()
                                        tempPreview.renameTo(livePreviewFile)
                                        currentLivePreviewPath = livePreviewFile.absolutePath
                                    }
                                }
                            } catch (e: Throwable) {
                                android.util.Log.w("ExportPipeline", "Live preview capture warning: ${e.message}")
                            }
                        }

                        // 2. Swap buffers to push rendered frame to hardware encoder
                        if (eglSurface != null) {
                            val rawPtsNs = ptsUs.coerceAtLeast(0L) * 1000L
                            val presentationNs = if (rawPtsNs > lastPresentationNs) {
                                lastPresentationNs = rawPtsNs
                                rawPtsNs
                            } else {
                                lastPresentationNs += 1_000_000L // Ensure at least 1ms progression
                                lastPresentationNs
                            }
                            eglCore.setPresentationTime(eglSurface, presentationNs)
                            eglCore.swapBuffers(eglSurface)
                        }

                        // 3. Drain encoder output to MP4 muxer
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
                                currentPreviewPath = currentLivePreviewPath
                            )
                            emitProgress(status, onStatusChange)
                        }
                    }

                    if (reachedEOS || isCancelled.get() || (!fed && processedFrames >= totalEstFrames)) {
                        break
                    }
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
                pipelineException = e
            } finally {
                try { decoder?.close() } catch (_: Throwable) {}
                try { decoderSurface?.release() } catch (_: Throwable) {}
                try { fallbackRetriever?.release() } catch (_: Throwable) {}
                try { frameReader?.close() } catch (_: Throwable) {}
                try { surfaceTexture?.release() } catch (_: Throwable) {}
                if (oesTextureId != 0) {
                    try { GLES20.glDeleteTextures(1, intArrayOf(oesTextureId), 0) } catch (_: Throwable) {}
                }
                if (fallbackTexture2DId != 0) {
                    try { GLES20.glDeleteTextures(1, intArrayOf(fallbackTexture2DId), 0) } catch (_: Throwable) {}
                }
                try { muxer?.close() } catch (_: Throwable) {}
                try { audioCopier?.close() } catch (_: Throwable) {}
                try { encoder?.close() } catch (_: Throwable) {}
                try { glRenderer?.close() } catch (_: Throwable) {}
                try { eglCore?.close() } catch (_: Throwable) {}
                try { if (livePreviewFile?.exists() == true) livePreviewFile?.delete() } catch (_: Throwable) {}
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
        val samplePoints = listOf(
            Pair(w / 4, h / 4),
            Pair(w / 2, h / 4),
            Pair(3 * w / 4, h / 4),
            Pair(w / 4, h / 2),
            Pair(w / 2, h / 2),
            Pair(3 * w / 4, h / 2),
            Pair(w / 4, 3 * h / 4),
            Pair(w / 2, 3 * h / 4),
            Pair(3 * w / 4, 3 * h / 4)
        )
        for (pt in samplePoints) {
            val pixel = bitmap.getPixel(pt.first.coerceIn(0, w - 1), pt.second.coerceIn(0, h - 1))
            val r = (pixel shr 16) and 0xFF
            val g = (pixel shr 8) and 0xFF
            val b = pixel and 0xFF
            if (r > 5 || g > 5 || b > 5) {
                return false
            }
        }
        return true
    }
}

