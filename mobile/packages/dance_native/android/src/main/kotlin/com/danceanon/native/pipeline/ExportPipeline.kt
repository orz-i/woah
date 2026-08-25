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
import com.danceanon.native.inference.YoloLiteRtSegmenter
import com.danceanon.native.media.AudioTrackCopier
import com.danceanon.native.media.Mp4Muxer
import com.danceanon.native.media.VideoDecoder
import com.danceanon.native.media.VideoEncoder
import com.danceanon.native.media.VideoProbe
import com.danceanon.native.render.EglCore
import com.danceanon.native.render.GlRenderer
import com.danceanon.native.tracking.TrackState
import com.danceanon.native.tracking.TrackedPerson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean

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

        // Dedicated GL thread with active Looper to guarantee EGLContext thread-affinity and instant frame callbacks
        val glThread = HandlerThread("ExportGlPipeline").apply { start() }
        val glHandler = Handler(glThread.looper)

        val pipelineLatch = CountDownLatch(1)
        var pipelineException: Throwable? = null

        glHandler.post {
            var eglCore: EglCore? = null
            var glRenderer: GlRenderer? = null
            var surfaceTexture: SurfaceTexture? = null
            var decoderSurface: Surface? = null
            var decoder: VideoDecoder? = null
            var encoder: VideoEncoder? = null
            var muxer: Mp4Muxer? = null
            var audioCopier: AudioTrackCopier? = null
            var oesTextureId = 0

            try {
                muxer = Mp4Muxer(tempOutFile.absolutePath, expectedTracks = if (videoInfo.hasAudio) 2 else 1)
                audioCopier = AudioTrackCopier(context, sourceUri)
                val hasAudioTrack = audioCopier.prepare()
                val audioFmt = audioCopier.audioFormat

                if (hasAudioTrack && audioFmt != null) {
                    muxer.addAudioTrack(audioFmt)
                } else {
                    muxer.forceStartIfSingleTrack()
                }

                encoder = VideoEncoder(
                    width = targetWidth,
                    height = targetHeight,
                    bitrate = request.videoBitrate.toInt().coerceAtLeast(4_000_000),
                    fps = targetFps
                )

                val inputSurface = encoder.prepare()
                eglCore = EglCore()
                val eglSurface = eglCore.createWindowSurface(inputSurface)
                eglCore.makeCurrent(eglSurface)

                glRenderer = GlRenderer()
                glRenderer.initialize(targetWidth, targetHeight)

                // Setup OES texture for VideoDecoder hardware rendering
                val oesTextures = IntArray(1)
                GLES20.glGenTextures(1, oesTextures, 0)
                oesTextureId = oesTextures[0]
                GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTextureId)
                GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
                GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
                GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
                GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)

                val frameSync = Object()
                var frameAvailable = false

                surfaceTexture = SurfaceTexture(oesTextureId).apply {
                    setDefaultBufferSize(targetWidth, targetHeight)
                    setOnFrameAvailableListener({
                        synchronized(frameSync) {
                            frameAvailable = true
                            frameSync.notifyAll()
                        }
                    }, glHandler)
                }
                decoderSurface = Surface(surfaceTexture)

                decoder = VideoDecoder(context, sourceUri, outputSurface = decoderSurface)
                decoder.prepare()

                val trackManager = com.danceanon.native.tracking.TrackManager()
                var processedFrames = 0
                var lastProgressEmitTime = 0L
                var lastPreviewEmitTime = 0L
                val previewFile = File(context.cacheDir, "export_preview_${jobId}.jpg")
                val stMatrix = floatArrayOf(
                    1f, 0f, 0f, 0f,
                    0f, 1f, 0f, 0f,
                    0f, 0f, 1f, 0f,
                    0f, 0f, 0f, 1f
                )

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

                        // Await frame arrival from decoder hardware rendering
                        synchronized(frameSync) {
                            var waited = 0
                            while (!frameAvailable && waited < 100) {
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

                        // Run AI segmentation & tracking on the decoded video frame
                        val inferenceInterval = 1
                        val detections = if (processedFrames == 1 || processedFrames % inferenceInterval == 0) {
                            val inferenceBmp = glRenderer.captureFrameForInference(oesTextureId, stMatrix)
                            if (inferenceBmp != null) {
                                val seg = segmenter.segmentBitmapSync(inferenceBmp, ptsUs)
                                inferenceBmp.recycle()
                                seg.persons
                            } else {
                                emptyList()
                            }
                        } else {
                            emptyList()
                        }

                        // Run tracking on detections
                        val trackedList = if (processedFrames == 1) {
                            trackManager.initialize(detections)
                        } else if (detections.isNotEmpty()) {
                            trackManager.update(detections, ptsUs)
                        } else {
                            trackManager.predict(ptsUs)
                        }

                        glRenderer.render(
                            frameTexture = oesTextureId,
                            texMatrix = stMatrix,
                            persons = trackedList,
                            selectedPersonIds = selectedIds,
                            effects = request.effects,
                            follow = request.follow,
                            presentationTimeUs = ptsUs
                        )

                        eglCore.setPresentationTime(eglSurface, ptsUs * 1000L)
                        eglCore.swapBuffers(eglSurface)

                        encoder.drainEncoder(muxer, endOfStream = false)

                        // Emit progress & real-time rendered frame snapshot
                        val now = System.currentTimeMillis()
                        if (now - lastProgressEmitTime > 200 || processedFrames % 5 == 0) {
                            lastProgressEmitTime = now
                            val elapsedSec = (now - startTime) / 1000.0
                            val currentFps = if (elapsedSec > 0) processedFrames / elapsedSec else 0.0
                            val progress = (processedFrames.toDouble() / totalFrames).coerceIn(0.0, 0.99)

                            if (now - lastPreviewEmitTime > 300) {
                                lastPreviewEmitTime = now
                                try {
                                    val previewBmp = glRenderer.captureRenderedFrame()
                                    if (previewBmp != null) {
                                        java.io.FileOutputStream(previewFile).use { out ->
                                            previewBmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 75, out)
                                        }
                                        previewBmp.recycle()
                                    }
                                } catch (_: Throwable) {}
                            }

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

                    if (reachedEOS || (!fed && processedFrames >= totalFrames)) {
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
                try { decoderSurface?.release() } catch (_: Throwable) {}
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
        glThread.quitSafely()
    }
}
