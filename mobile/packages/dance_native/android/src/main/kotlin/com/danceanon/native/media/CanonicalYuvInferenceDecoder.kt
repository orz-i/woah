package com.danceanon.native.media

import android.content.Context
import android.media.Image
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import com.danceanon.native.geometry.ModelCoordinateMapper
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.floor
import kotlin.math.roundToInt

/**
 * Experimental inference-only decoder used to remove SurfaceTexture/OES color conversion from
 * the YOLO input path. Rendering keeps using the existing Surface decoder.
 *
 * The decoder exposes YUV_420_888 Images and converts them with CPU integer color math into the
 * same 640x640, bottom-up RGBA contract consumed by segmentGlReadbackRgbaSync(). No decoded frame
 * from this class is used for rendering or identity decisions other than as YOLO pixel input.
 */
class CanonicalYuvInferenceDecoder(
    private val context: Context,
    private val sourceUri: String,
    private val rotationDegrees: Int,
    private val modelInputSize: Int = 640
) : AutoCloseable {

    data class RuntimeInfo(
        val codecName: String,
        val colorStandard: Int?,
        val colorRange: Int?,
        val colorTransfer: Int?
    )

    private val extractor = MediaExtractor()
    private var codec: MediaCodec? = null
    private var inputEos = false
    private var outputEos = false
    private var outputFormat: MediaFormat? = null
    private val bufferInfo = MediaCodec.BufferInfo()
    private val rgbaBuffer = ByteBuffer.allocateDirect(modelInputSize * modelInputSize * 4).apply {
        order(ByteOrder.nativeOrder())
    }

    var runtimeInfo: RuntimeInfo? = null
        private set

    fun prepare() {
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
        require(trackIndex >= 0 && inputFormat != null) { "No video track for canonical inference decode" }

        extractor.selectTrack(trackIndex)
        val mime = inputFormat.getString(MediaFormat.KEY_MIME)
            ?: error("Missing video MIME for canonical inference decode")
        inputFormat.setInteger(
            MediaFormat.KEY_COLOR_FORMAT,
            MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible
        )

        val decoder = MediaCodec.createDecoderByType(mime)
        decoder.configure(inputFormat, null, null, 0)
        decoder.start()
        codec = decoder
        outputFormat = null
        updateRuntimeInfo(decoder.name, inputFormat)
    }

    /**
     * Sequentially decodes until [targetPtsUs] and returns a reused bottom-up RGBA buffer.
     * Older decoded frames are discarded when the production YOLO stride skips them.
     */
    fun decodeRgbaAtPts(
        targetPtsUs: Long,
        mapper: ModelCoordinateMapper
    ): ByteBuffer {
        val decoder = codec ?: error("Canonical inference decoder not prepared")
        var emptyOutputCount = 0

        while (!outputEos && emptyOutputCount < 300) {
            feedAvailableInput(decoder)

            val outputIndex = decoder.dequeueOutputBuffer(bufferInfo, 10_000L)
            when {
                outputIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                    emptyOutputCount++
                }
                outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    outputFormat = decoder.outputFormat
                    updateRuntimeInfo(decoder.name, outputFormat)
                    emptyOutputCount = 0
                }
                outputIndex >= 0 -> {
                    emptyOutputCount = 0
                    val isEos = (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0
                    val ptsUs = bufferInfo.presentationTimeUs
                    if (isEos) outputEos = true

                    var image: Image? = null
                    try {
                        if (!isEos && ptsUs >= targetPtsUs) {
                            if (ptsUs != targetPtsUs) {
                                error("Canonical decoder PTS mismatch: requested=$targetPtsUs decoded=$ptsUs")
                            }
                            image = decoder.getOutputImage(outputIndex)
                                ?: error("Canonical decoder returned null YUV image at pts=$ptsUs")
                            CanonicalYuvToRgba.convert(
                                image = image,
                                output = rgbaBuffer,
                                mapper = mapper,
                                rotationDegrees = rotationDegrees,
                                colorStandard = colorInt(outputFormat, MediaFormat.KEY_COLOR_STANDARD),
                                colorRange = colorInt(outputFormat, MediaFormat.KEY_COLOR_RANGE)
                            )
                            return rgbaBuffer
                        }
                    } finally {
                        try { image?.close() } catch (_: Throwable) {}
                        decoder.releaseOutputBuffer(outputIndex, false)
                    }
                }
            }
        }

        error("Canonical decoder timed out before pts=$targetPtsUs")
    }

    private fun feedAvailableInput(decoder: MediaCodec) {
        if (inputEos) return
        while (true) {
            val inputIndex = decoder.dequeueInputBuffer(0L)
            if (inputIndex < 0) return
            val inputBuffer = decoder.getInputBuffer(inputIndex) ?: return
            inputBuffer.clear()
            val sampleSize = extractor.readSampleData(inputBuffer, 0)
            if (sampleSize < 0) {
                decoder.queueInputBuffer(
                    inputIndex,
                    0,
                    0,
                    0L,
                    MediaCodec.BUFFER_FLAG_END_OF_STREAM
                )
                inputEos = true
                return
            }
            decoder.queueInputBuffer(inputIndex, 0, sampleSize, extractor.sampleTime, 0)
            extractor.advance()
        }
    }

    private fun updateRuntimeInfo(codecName: String, format: MediaFormat?) {
        runtimeInfo = RuntimeInfo(
            codecName = codecName,
            colorStandard = colorInt(format, MediaFormat.KEY_COLOR_STANDARD),
            colorRange = colorInt(format, MediaFormat.KEY_COLOR_RANGE),
            colorTransfer = colorInt(format, MediaFormat.KEY_COLOR_TRANSFER)
        )
    }

    private fun colorInt(format: MediaFormat?, key: String): Int? =
        if (format != null && format.containsKey(key)) format.getInteger(key) else null

    override fun close() {
        try { codec?.stop() } catch (_: Throwable) {}
        try { codec?.release() } catch (_: Throwable) {}
        try { extractor.release() } catch (_: Throwable) {}
        codec = null
    }
}

/** CPU-only deterministic YUV_420_888 -> letterboxed RGBA conversion. */
internal object CanonicalYuvToRgba {
    private const val FP = 256

    fun convert(
        image: Image,
        output: ByteBuffer,
        mapper: ModelCoordinateMapper,
        rotationDegrees: Int,
        colorStandard: Int?,
        colorRange: Int?
    ) {
        require(image.planes.size >= 3) { "Expected YUV_420_888 image" }
        require(output.capacity() >= mapper.modelInputSize * mapper.modelInputSize * 4)

        val crop = image.cropRect
        val cropW = crop.width()
        val cropH = crop.height()
        val rotation = ((rotationDegrees % 360) + 360) % 360
        val displayW = if (rotation == 90 || rotation == 270) cropH else cropW
        val displayH = if (rotation == 90 || rotation == 270) cropW else cropH
        val size = mapper.modelInputSize
        val left = mapper.padLeft.toDouble()
        val top = mapper.padTop.toDouble()
        val right = (mapper.padLeft + mapper.scaledW).toDouble()
        val bottom = (mapper.padTop + mapper.scaledH).toDouble()

        output.clear()
        for (bufferY in 0 until size) {
            // glReadPixels contract is bottom-up; YoloPreprocessor flips it back to model top-down.
            val modelY = size - 1 - bufferY
            val cy = modelY + 0.5
            for (modelX in 0 until size) {
                val cx = modelX + 0.5
                if (cx < left || cx >= right || cy < top || cy >= bottom) {
                    putRgba(output, 114, 114, 114)
                    continue
                }

                val u = ((cx - left) / mapper.scaledW.toDouble()).coerceIn(0.0, 1.0)
                val v = ((cy - top) / mapper.scaledH.toDouble()).coerceIn(0.0, 1.0)
                val dx = u * displayW - 0.5
                val dy = v * displayH - 0.5
                val sourceX: Double
                val sourceY: Double
                when (rotation) {
                    90 -> {
                        sourceX = dy
                        sourceY = cropH - 1.0 - dx
                    }
                    180 -> {
                        sourceX = cropW - 1.0 - dx
                        sourceY = cropH - 1.0 - dy
                    }
                    270 -> {
                        sourceX = cropW - 1.0 - dy
                        sourceY = dx
                    }
                    else -> {
                        sourceX = dx
                        sourceY = dy
                    }
                }
                val sx = (sourceX + crop.left).coerceIn(crop.left.toDouble(), (crop.right - 1).toDouble())
                val sy = (sourceY + crop.top).coerceIn(crop.top.toDouble(), (crop.bottom - 1).toDouble())

                val y = samplePlane(image.planes[0], sx, sy, image.width, image.height)
                val uvX = sx * 0.5
                val uvY = sy * 0.5
                val uvWidth = (image.width + 1) / 2
                val uvHeight = (image.height + 1) / 2
                val u8 = samplePlane(image.planes[1], uvX, uvY, uvWidth, uvHeight)
                val v8 = samplePlane(image.planes[2], uvX, uvY, uvWidth, uvHeight)
                putPackedRgb(output, yuvToRgb(y, u8, v8, colorStandard, colorRange))
            }
        }
        output.rewind()
    }

    internal fun inverseRotate(
        dx: Double,
        dy: Double,
        sourceW: Int,
        sourceH: Int,
        rotation: Int
    ): Pair<Double, Double> = when (rotation) {
        90 -> dy to (sourceH - 1.0 - dx)
        180 -> (sourceW - 1.0 - dx) to (sourceH - 1.0 - dy)
        270 -> (sourceW - 1.0 - dy) to dx
        else -> dx to dy
    }

    private fun samplePlane(
        plane: Image.Plane,
        x: Double,
        y: Double,
        width: Int,
        height: Int
    ): Int {
        val clampedX = x.coerceIn(0.0, (width - 1).coerceAtLeast(0).toDouble())
        val clampedY = y.coerceIn(0.0, (height - 1).coerceAtLeast(0).toDouble())
        val x0 = floor(clampedX).toInt()
        val y0 = floor(clampedY).toInt()
        val x1 = (x0 + 1).coerceAtMost(width - 1)
        val y1 = (y0 + 1).coerceAtMost(height - 1)
        val fx = ((clampedX - x0) * FP).roundToInt().coerceIn(0, FP)
        val fy = ((clampedY - y0) * FP).roundToInt().coerceIn(0, FP)

        if (fx == 0 && fy == 0) return planeByte(plane, x0, y0)
        val p00 = planeByte(plane, x0, y0)
        val p10 = planeByte(plane, x1, y0)
        val p01 = planeByte(plane, x0, y1)
        val p11 = planeByte(plane, x1, y1)
        val top = p00 * (FP - fx) + p10 * fx
        val bottom = p01 * (FP - fx) + p11 * fx
        return (top * (FP - fy) + bottom * fy + (FP * FP / 2)) / (FP * FP)
    }

    private fun planeByte(plane: Image.Plane, x: Int, y: Int): Int {
        val buffer = plane.buffer
        val index = buffer.position() + y * plane.rowStride + x * plane.pixelStride
        if (index < buffer.position() || index >= buffer.limit()) {
            error("YUV plane index out of bounds: x=$x y=$y index=$index limit=${buffer.limit()}")
        }
        return buffer.get(index).toInt() and 0xFF
    }

    internal fun yuvToRgb(
        y: Int,
        u: Int,
        v: Int,
        colorStandard: Int?,
        colorRange: Int?
    ): Int {
        val fullRange = colorRange == MediaFormat.COLOR_RANGE_FULL
        val standard = colorStandard ?: MediaFormat.COLOR_STANDARD_BT709
        val d = u - 128
        val e = v - 128

        val r: Int
        val g: Int
        val b: Int
        if (fullRange) {
            when (standard) {
                MediaFormat.COLOR_STANDARD_BT601_PAL,
                MediaFormat.COLOR_STANDARD_BT601_NTSC -> {
                    r = y + ((359 * e + 128) shr 8)
                    g = y - ((88 * d + 183 * e + 128) shr 8)
                    b = y + ((454 * d + 128) shr 8)
                }
                MediaFormat.COLOR_STANDARD_BT2020 -> {
                    r = y + ((377 * e + 128) shr 8)
                    g = y - ((42 * d + 146 * e + 128) shr 8)
                    b = y + ((482 * d + 128) shr 8)
                }
                else -> {
                    r = y + ((403 * e + 128) shr 8)
                    g = y - ((48 * d + 120 * e + 128) shr 8)
                    b = y + ((475 * d + 128) shr 8)
                }
            }
        } else {
            val c = (y - 16).coerceAtLeast(0)
            when (standard) {
                MediaFormat.COLOR_STANDARD_BT601_PAL,
                MediaFormat.COLOR_STANDARD_BT601_NTSC -> {
                    r = (298 * c + 409 * e + 128) shr 8
                    g = (298 * c - 100 * d - 208 * e + 128) shr 8
                    b = (298 * c + 516 * d + 128) shr 8
                }
                MediaFormat.COLOR_STANDARD_BT2020 -> {
                    r = (298 * c + 430 * e + 128) shr 8
                    g = (298 * c - 48 * d - 167 * e + 128) shr 8
                    b = (298 * c + 548 * d + 128) shr 8
                }
                else -> {
                    r = (298 * c + 459 * e + 128) shr 8
                    g = (298 * c - 55 * d - 136 * e + 128) shr 8
                    b = (298 * c + 541 * d + 128) shr 8
                }
            }
        }
        return (r.coerceIn(0, 255) shl 16) or
            (g.coerceIn(0, 255) shl 8) or
            b.coerceIn(0, 255)
    }

    private fun putPackedRgb(output: ByteBuffer, rgb: Int) {
        output.put(((rgb ushr 16) and 0xFF).toByte())
        output.put(((rgb ushr 8) and 0xFF).toByte())
        output.put((rgb and 0xFF).toByte())
        output.put(0xFF.toByte())
    }

    private fun putRgba(output: ByteBuffer, r: Int, g: Int, b: Int) {
        output.put(r.toByte())
        output.put(g.toByte())
        output.put(b.toByte())
        output.put(0xFF.toByte())
    }
}
