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

                frameReader = com.danceanon.native.render.InferenceFrameReader(targetWidth, targetHeight, 640)

                val frameSync = Object()
                var frameAvailable = false

                surfaceTexture = SurfaceTexture(oesTextureId).apply {
                    setDefaultBufferSize(targetWidth, targetHeight)
                    setOnFrameAvailableListener({
                        synchronized(frameSync) {
                            frameAvailable = true
                            frameSync.notifyAll()
                        }
                    }, frameHandler)
                }

                decoder = VideoDecoder(
                    context = context,
                    sourceUri = sourceUri,
                    outputSurface = android.view.Surface(surfaceTexture)
                )
                decoder.prepare()

                var processedFrames = 0
                val totalEstFrames = ((videoInfo.durationMs / 1000.0) * targetFps).toLong().coerceAtLeast(1L)
                val stMatrix = FloatArray(16)
                val trackManager = TrackManager()
                val frameStride = 1
                var lastProgressEmitTime = 0L
                var lastPreviewEmitTime = 0L
                val previewFile = File(context.cacheDir, "export_preview_${jobId}.jpg")

                while (!isCancelled.get()) {
                    val fed = decoder.feedInputBuffer()
                    var reachedEOS = false

                    decoder.drainOutputBuffer { ptsUs, isEOS ->
                        if (isEOS) {
                            reachedEOS = true
                            return@drainOutputBuffer
                        }

                        processedFrames++
                        val selectedIds = request.selectedPersonIds.map { it.toInt() }.toSet()

                        // Await frame arrival from decoder hardware rendering (immediate wakeup via frameHandler)
                        synchronized(frameSync) {
                            var waited = 0
                            while (!frameAvailable && waited < 80) {
                                try {
                                    frameSync.wait(10)
                                    waited += 10
                                } catch (_: InterruptedException) {}
                            }
                            frameAvailable = false
                        }

                        // Latch and update OES texture with decoded video frame
                        try {
                            surfaceTexture?.updateTexImage()
                            surfaceTexture?.getTransformMatrix(stMatrix)
                        } catch (e: Throwable) {
                            android.util.Log.w("ExportPipeline", "updateTexImage warning: ${e.message}")
                        }

                        val rotation = videoInfo.rotation.toInt()
                        val finalTexMatrix = GlRenderer.computeTransformMatrix(stMatrix, rotation)

                        // 1. Draw base video frame to EGL window surface
                        glRenderer.renderBase(oesTextureId, finalTexMatrix)

                        // 2. Capture frame for AI segmentation using reusable InferenceFrameReader
                        val shouldInfer = (processedFrames == 1) || (processedFrames % frameStride == 0)
                        val detections = if (shouldInfer && frameReader != null) {
                            val frameBmp = frameReader.captureFrame()
                            val seg = segmenter.segmentBitmapSync(
                                bitmap = frameBmp,
                                timestampUs = ptsUs,
                                origWidth = frameReader.width,
                                origHeight = frameReader.height
                            )
                            seg.persons
                        } else {
                            emptyList()
                        }

                        // 3. Run tracking on detections with stable ID mapping from analysis cache
                        val trackedList = if (processedFrames == 1) {
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
                                        val interX1 = maxOf(cBox.left, dBox.left)
                                        val interY1 = maxOf(cBox.top, dBox.top)
                                        val interX2 = minOf(cBox.right, dBox.right)
                                        val interY2 = minOf(cBox.bottom, dBox.bottom)
                                        val interW = maxOf(0f, interX2 - interX1)
                                        val interH = maxOf(0f, interY2 - interY1)
                                        val interArea = interW * interH
                                        val unionArea = cBox.width * cBox.height + dBox.width * dBox.height - interArea
                                        val iou = if (unionArea <= 0f) 0f else interArea / unionArea

                                        val dx = (cBox.centerX - dBox.centerX) / targetWidth.toFloat()
                                        val dy = (cBox.centerY - dBox.centerY) / targetHeight.toFloat()
                                        val dist = kotlin.math.sqrt(dx * dx + dy * dy).coerceIn(0f, 1f)
                                        (0.6f * (1.0f - iou) + 0.4f * dist).coerceIn(0f, 1f)
                                    }
                                }
                                val matchResult = com.danceanon.native.tracking.HungarianSolver.match(costMatrix, maxCostThreshold = 0.70f)
                                val assignedIds = IntArray(detections.size) { -1 }
                                val usedIds = mutableSetOf<Int>()
                                for (m in matchResult.matches) {
                                    val cachedId = cached[m.first].id
                                    assignedIds[m.second] = cachedId
                                    usedIds.add(cachedId)
                                }
                                var nextId = (cached.maxOfOrNull { it.id } ?: -1) + 1
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
                        } else if (detections.isNotEmpty()) {
                            trackManager.update(detections, ptsUs)
                        } else {
                            trackManager.predict(ptsUs)
                        }

                        // 4. Render final anonymized frame to EGL surface (encoder input)
                        glRenderer.render(
                            frameTexture = oesTextureId,
                            texMatrix = finalTexMatrix,
                            persons = trackedList,
                            selectedPersonIds = selectedIds,
                            effects = request.effects,
                            follow = request.follow,
                            presentationTimeUs = ptsUs
                        )

                        // 2. Capture live preview snapshot BEFORE eglSwapBuffers (while backbuffer contains the rendered frame)
                        val now = System.currentTimeMillis()
                        if (now - lastPreviewEmitTime > 250) {
                            lastPreviewEmitTime = now
                            try {
                                val previewBmp = glRenderer.captureRenderedFrame()
                                if (previewBmp != null) {
                                    java.io.FileOutputStream(previewFile).use { out ->
                                        previewBmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 70, out)
                                    }
                                    previewBmp.recycle()
                                }
                            } catch (t: Throwable) {
                                android.util.Log.w("ExportPipeline", "Preview capture failed: ${t.message}")
                            }
                        }

                        // 3. Swap buffers to push rendered frame to hardware encoder
                        if (eglSurface != null) {
                            eglCore.setPresentationTime(eglSurface, ptsUs * 1000L)
                            eglCore.swapBuffers(eglSurface)
                        }

                        // 4. Drain encoder output to MP4 muxer
                        encoder.drainEncoder(muxer, endOfStream = false)

                        // 5. Emit progress
                        if (now - lastProgressEmitTime > 200 || processedFrames % 5 == 0) {
                            lastProgressEmitTime = now
                            val elapsedSec = (now - startTime) / 1000.0
                            val currentFps = if (elapsedSec > 0) processedFrames / elapsedSec else 0.0
                            val progress = (processedFrames.toDouble() / totalEstFrames).coerceIn(0.0, 0.99)

                            status = status.copy(
                                state = "processing",
                                currentFrame = processedFrames.toLong(),
                                fps = currentFps,
                                progress = progress,
                                outputUri = if (previewFile.exists()) previewFile.absolutePath else null
                            )
                            emitProgress(status, onStatusChange)
                        }
                    }

                    if (reachedEOS || (!fed && processedFrames >= totalEstFrames)) {
                        break
                    }
                }

                if (isCancelled.get()) {
                    tempOutFile.delete()
                    status = status.copy(state = "cancelled")
                    emitProgress(status, onStatusChange)
                    return@post
                }

                // Drain remaining encoder output
                encoder.drainEncoder(muxer, endOfStream = true)

                // Copy audio
                if (hasAudioTrack && audioCopier.audioTrackIndexInSource >= 0) {
                    audioCopier.copyToMuxer(muxer)
                }

                // Close pipeline resources
                muxer.close()
                decoder.close()
                audioCopier.close()
                encoder.close()
                eglCore.releaseSurface(eglSurface)
                eglCore.close()

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
                try { frameReader?.close() } catch (_: Throwable) {}
                try { surfaceTexture?.release() } catch (_: Throwable) {}
                if (oesTextureId != 0) {
                    try { GLES20.glDeleteTextures(1, intArrayOf(oesTextureId), 0) } catch (_: Throwable) {}
                }
                try { muxer?.close() } catch (_: Throwable) {}
                try { audioCopier?.close() } catch (_: Throwable) {}
                try { encoder?.close() } catch (_: Throwable) {}
                try { glRenderer?.close() } catch (_: Throwable) {}
                try { eglCore?.close() } catch (_: Throwable) {}
                pipelineLatch.countDown()
            }
        }

        pipelineLatch.await()
        frameThread.quitSafely()
        glThread.quitSafely()
    }
}
