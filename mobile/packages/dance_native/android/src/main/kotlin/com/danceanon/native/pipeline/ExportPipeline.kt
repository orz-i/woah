package com.danceanon.native.pipeline

import android.content.Context
import android.media.MediaFormat
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
    ) = withContext(Dispatchers.Default) {
        val startTime = System.currentTimeMillis()
        val videoInfo = VideoProbe.probe(context, sourceUri)

        val targetWidth = if (request.targetWidth > 0) request.targetWidth.toInt() else videoInfo.displayWidth.toInt()
        val targetHeight = if (request.targetHeight > 0) request.targetHeight.toInt() else videoInfo.displayHeight.toInt()
        val targetFps = if (request.targetFps > 0) request.targetFps.toFloat() else videoInfo.fps.toFloat()

        val finalOutFile = File(request.outputFilePath)
        finalOutFile.parentFile?.mkdirs()
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

        val muxer = Mp4Muxer(tempOutFile.absolutePath, expectedTracks = if (videoInfo.hasAudio) 2 else 1)
        val audioCopier = AudioTrackCopier(context, sourceUri)
        val hasAudioTrack = audioCopier.prepare()
        val audioFmt = audioCopier.audioFormat

        if (hasAudioTrack && audioFmt != null) {
            muxer.addAudioTrack(audioFmt)
        } else {
            muxer.forceStartIfSingleTrack()
        }

        val encoder = VideoEncoder(
            width = targetWidth,
            height = targetHeight,
            bitrate = request.videoBitrate.toInt().coerceAtLeast(4_000_000),
            fps = targetFps
        )

        var eglCore: EglCore? = null
        var glRenderer: GlRenderer? = null

        try {
            val inputSurface = encoder.prepare()
            eglCore = EglCore()
            val eglSurface = eglCore.createWindowSurface(inputSurface)
            eglCore.makeCurrent(eglSurface)

            glRenderer = GlRenderer()
            glRenderer.initialize(targetWidth, targetHeight)

            val decoder = VideoDecoder(context, sourceUri, outputSurface = null)
            decoder.prepare()

            val trackManager = com.danceanon.native.tracking.TrackManager()
            var processedFrames = 0
            var lastProgressEmitTime = 0L

            segmenter.initialize()

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

                    // Run tracking on detections
                    val trackedList = if (processedFrames == 1) {
                        trackManager.initialize(emptyList())
                    } else {
                        trackManager.update(emptyList(), ptsUs)
                    }

                    glRenderer.render(
                        frameTexture = 0,
                        persons = trackedList,
                        selectedPersonIds = selectedIds,
                        effects = request.effects,
                        follow = request.follow,
                        presentationTimeUs = ptsUs
                    )

                    eglCore.setPresentationTime(eglSurface, ptsUs * 1000L)
                    eglCore.swapBuffers(eglSurface)

                    encoder.drainEncoder(muxer, 0, endOfStream = false)

                    // Emit progress
                    val now = System.currentTimeMillis()
                    if (now - lastProgressEmitTime > 200 || processedFrames % 5 == 0) {
                        lastProgressEmitTime = now
                        val elapsedSec = (now - startTime) / 1000.0
                        val currentFps = if (elapsedSec > 0) processedFrames / elapsedSec else 0.0
                        val progress = (processedFrames.toDouble() / totalFrames).coerceIn(0.0, 0.99)

                        status = status.copy(
                            state = "processing",
                            currentFrame = processedFrames.toLong(),
                            fps = currentFps,
                            progress = progress
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
                return@withContext
            }

            // Drain remaining encoder output
            encoder.drainEncoder(muxer, 0, endOfStream = true)

            // Copy audio
            if (hasAudioTrack && audioCopier.audioTrackIndexInSource >= 0) {
                audioCopier.copyToMuxer(muxer, 1)
            }

            // Close pipeline
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

        } catch (e: Exception) {
            tempOutFile.delete()
            status = status.copy(
                state = "failed",
                errorCode = "EXPORT_FAILED",
                errorMessage = e.message ?: "Export failed"
            )
            emitProgress(status, onStatusChange)
        } finally {
            muxer.close()
            audioCopier.close()
            encoder.close()
            glRenderer?.close()
            eglCore?.close()
        }
    }
}
