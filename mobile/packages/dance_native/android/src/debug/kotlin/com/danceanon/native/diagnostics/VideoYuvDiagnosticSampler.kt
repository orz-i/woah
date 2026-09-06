package com.danceanon.native.diagnostics

import android.content.Context
import android.media.Image
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import java.security.MessageDigest
import java.util.Locale

/**
 * Debug-only CPU-readable decoder probe used to locate cross-device divergence before OES RGB.
 *
 * A short independent decoder run samples canonical visible Y/U/V coordinates from YUV_420_888
 * images after the production export resources have been released. It never feeds production
 * inference or rendering, so it cannot become an identity or privacy input or prewarm the decoder
 * path being measured.
 */
object VideoYuvDiagnosticSampler {
    private const val MAX_PTS_US = 450_000L
    private const val Y_GRID = 64
    private const val UV_GRID = 32

    fun capture(context: Context, sourceUri: String, jobId: String) {
        if (!com.danceanon.dance_native.BuildConfig.DEBUG) return

        val extractor = MediaExtractor()
        var decoder: MediaCodec? = null
        try {
            if (sourceUri.startsWith("content://")) {
                extractor.setDataSource(context, Uri.parse(sourceUri), null)
            } else {
                extractor.setDataSource(sourceUri.removePrefix("file://"))
            }

            var trackIndex = -1
            var inputFormat: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME).orEmpty()
                if (mime.startsWith("video/")) {
                    trackIndex = i
                    inputFormat = format
                    break
                }
            }
            if (trackIndex < 0 || inputFormat == null) {
                recordFailure(jobId, "NO_VIDEO_TRACK")
                return
            }

            extractor.selectTrack(trackIndex)
            val mime = inputFormat.getString(MediaFormat.KEY_MIME) ?: run {
                recordFailure(jobId, "NO_VIDEO_MIME")
                return
            }
            inputFormat.setInteger(
                MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible
            )

            val codec = MediaCodec.createDecoderByType(mime)
            decoder = codec
            val codecName = codec.name
            codec.configure(inputFormat, null, null, 0)
            codec.start()

            val bufferInfo = MediaCodec.BufferInfo()
            var inputEos = false
            var emptyOutputCount = 0
            var captureOrdinal = 0
            var outputFormat: MediaFormat? = null

            while (emptyOutputCount < 200) {
                if (!inputEos) {
                    val inputIndex = codec.dequeueInputBuffer(0L)
                    if (inputIndex >= 0) {
                        val inputBuffer = codec.getInputBuffer(inputIndex)
                        if (inputBuffer != null) {
                            inputBuffer.clear()
                            val sampleSize = extractor.readSampleData(inputBuffer, 0)
                            if (sampleSize < 0) {
                                codec.queueInputBuffer(
                                    inputIndex,
                                    0,
                                    0,
                                    0L,
                                    MediaCodec.BUFFER_FLAG_END_OF_STREAM
                                )
                                inputEos = true
                            } else {
                                codec.queueInputBuffer(inputIndex, 0, sampleSize, extractor.sampleTime, 0)
                                extractor.advance()
                            }
                        }
                    }
                }

                val outputIndex = codec.dequeueOutputBuffer(bufferInfo, 10_000L)
                when {
                    outputIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                        emptyOutputCount++
                    }
                    outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        outputFormat = codec.outputFormat
                        emptyOutputCount = 0
                    }
                    outputIndex >= 0 -> {
                        emptyOutputCount = 0
                        val isEos = (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0
                        val ptsUs = bufferInfo.presentationTimeUs
                        try {
                            if (!isEos && ptsUs <= MAX_PTS_US) {
                                val image = codec.getOutputImage(outputIndex)
                                if (image != null) {
                                    image.use {
                                        val artifact = sampleImage(it)
                                        if (artifact != null) {
                                            captureOrdinal++
                                            recordCapture(
                                                jobId = jobId,
                                                ordinal = captureOrdinal,
                                                ptsUs = ptsUs,
                                                codecName = codecName,
                                                outputFormat = outputFormat ?: codec.outputFormat,
                                                image = it,
                                                bytes = artifact
                                            )
                                        } else {
                                            recordFailure(jobId, "YUV_SAMPLE_LAYOUT_INVALID", ptsUs)
                                        }
                                    }
                                } else {
                                    recordFailure(jobId, "OUTPUT_IMAGE_NULL", ptsUs)
                                }
                            }
                        } finally {
                            codec.releaseOutputBuffer(outputIndex, false)
                        }

                        if (isEos || ptsUs > MAX_PTS_US) break
                    }
                }
            }
        } catch (t: Throwable) {
            recordFailure(jobId, "${t.javaClass.simpleName}:${t.message ?: "unknown"}")
        } finally {
            try { decoder?.stop() } catch (_: Throwable) {}
            try { decoder?.release() } catch (_: Throwable) {}
            try { extractor.release() } catch (_: Throwable) {}
        }
    }

    private fun sampleImage(image: Image): ByteArray? {
        val planes = image.planes
        if (planes.size < 3) return null
        val crop = image.cropRect
        if (crop.width() <= 0 || crop.height() <= 0) return null

        val y = samplePlane(
            plane = planes[0],
            originX = crop.left,
            originY = crop.top,
            width = crop.width(),
            height = crop.height(),
            grid = Y_GRID
        ) ?: return null
        val u = samplePlane(
            plane = planes[1],
            originX = crop.left / 2,
            originY = crop.top / 2,
            width = (crop.width() + 1) / 2,
            height = (crop.height() + 1) / 2,
            grid = UV_GRID
        ) ?: return null
        val v = samplePlane(
            plane = planes[2],
            originX = crop.left / 2,
            originY = crop.top / 2,
            width = (crop.width() + 1) / 2,
            height = (crop.height() + 1) / 2,
            grid = UV_GRID
        ) ?: return null

        return ByteArray(y.size + u.size + v.size).also { out ->
            y.copyInto(out, 0)
            u.copyInto(out, y.size)
            v.copyInto(out, y.size + u.size)
        }
    }

    private fun samplePlane(
        plane: Image.Plane,
        originX: Int,
        originY: Int,
        width: Int,
        height: Int,
        grid: Int
    ): ByteArray? {
        val buffer = plane.buffer.duplicate()
        val base = buffer.position()
        val limit = buffer.limit()
        val out = ByteArray(grid * grid)
        var outIndex = 0
        for (gy in 0 until grid) {
            val y = originY + (((gy * 2L + 1L) * height) / (grid * 2L)).toInt().coerceIn(0, height - 1)
            for (gx in 0 until grid) {
                val x = originX + (((gx * 2L + 1L) * width) / (grid * 2L)).toInt().coerceIn(0, width - 1)
                val index = base + y * plane.rowStride + x * plane.pixelStride
                if (index < base || index >= limit) return null
                out[outIndex++] = buffer.get(index)
            }
        }
        return out
    }

    private fun recordCapture(
        jobId: String,
        ordinal: Int,
        ptsUs: Long,
        codecName: String,
        outputFormat: MediaFormat,
        image: Image,
        bytes: ByteArray
    ) {
        val safeJobId = jobId.replace(Regex("[^A-Za-z0-9._-]"), "_")
        val fileName = "decoder_yuv_${safeJobId}_${ordinal}_${ptsUs}_${Y_GRID}x${Y_GRID}_${UV_GRID}x${UV_GRID}.yuvs"
        NativeDiagnostics.writeArtifactAsync(fileName, bytes)
        val crop = image.cropRect
        val planes = image.planes

        NativeDiagnostics.event(
            level = "INFO",
            component = "VideoYuvDiagnosticSampler",
            event = "DECODER_YUV_CAPTURED",
            fields = buildMap {
                put("job_id", jobId)
                put("capture_ordinal", ordinal)
                put("pts_us", ptsUs)
                put("codec_name", codecName)
                put("image_format", image.format)
                put("image_width", image.width)
                put("image_height", image.height)
                put("crop_rect", listOf(crop.left, crop.top, crop.right, crop.bottom))
                put("y_row_stride", planes[0].rowStride)
                put("y_pixel_stride", planes[0].pixelStride)
                put("u_row_stride", planes[1].rowStride)
                put("u_pixel_stride", planes[1].pixelStride)
                put("v_row_stride", planes[2].rowStride)
                put("v_pixel_stride", planes[2].pixelStride)
                put("artifact", fileName)
                put("sample_sha256", sha256(bytes))
                if (outputFormat.containsKey(MediaFormat.KEY_COLOR_STANDARD)) {
                    put("color_standard", outputFormat.getInteger(MediaFormat.KEY_COLOR_STANDARD))
                }
                if (outputFormat.containsKey(MediaFormat.KEY_COLOR_RANGE)) {
                    put("color_range", outputFormat.getInteger(MediaFormat.KEY_COLOR_RANGE))
                }
                if (outputFormat.containsKey(MediaFormat.KEY_COLOR_TRANSFER)) {
                    put("color_transfer", outputFormat.getInteger(MediaFormat.KEY_COLOR_TRANSFER))
                }
            }
        )
    }

    private fun recordFailure(jobId: String, reason: String, ptsUs: Long? = null) {
        NativeDiagnostics.event(
            level = "WARN",
            component = "VideoYuvDiagnosticSampler",
            event = "DECODER_YUV_CAPTURE_FAILED",
            fields = buildMap {
                put("job_id", jobId)
                put("reason", reason)
                if (ptsUs != null) put("pts_us", ptsUs)
            }
        )
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { "%02x".format(Locale.US, it.toInt() and 0xff) }
}
