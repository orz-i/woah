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
    private val conversionWorkspace = CanonicalYuvToRgba.Workspace(modelInputSize)

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
                                colorRange = colorInt(outputFormat, MediaFormat.KEY_COLOR_RANGE),
                                workspace = conversionWorkspace
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

    internal class Workspace(val modelInputSize: Int) {
        internal val rgbaInts = IntArray(modelInputSize * modelInputSize)
        internal var yBytes = ByteArray(0)
        internal var uBytes = ByteArray(0)
        internal var vBytes = ByteArray(0)
        internal var planKey: PlanKey? = null
        internal var plan: SamplingPlan? = null
    }

    internal data class PlaneSnapshot(
        val bytes: ByteArray,
        val length: Int,
        val rowStride: Int,
        val pixelStride: Int
    )

    internal data class AxisSamples(
        val i0: IntArray,
        val i1: IntArray,
        val w1: IntArray
    )

    internal data class PlanKey(
        val imageWidth: Int,
        val imageHeight: Int,
        val cropLeft: Int,
        val cropTop: Int,
        val cropRight: Int,
        val cropBottom: Int,
        val modelInputSize: Int,
        val scaledWBits: Int,
        val scaledHBits: Int,
        val padLeftBits: Int,
        val padTopBits: Int,
        val rotation: Int
    )

    internal data class SamplingPlan(
        val validX: BooleanArray,
        val validY: BooleanArray,
        val lumaX: AxisSamples,
        val lumaY: AxisSamples,
        val uvX: AxisSamples,
        val uvY: AxisSamples,
        val swapAxes: Boolean
    )

    fun convert(
        image: Image,
        output: ByteBuffer,
        mapper: ModelCoordinateMapper,
        rotationDegrees: Int,
        colorStandard: Int?,
        colorRange: Int?,
        workspace: Workspace = Workspace(mapper.modelInputSize)
    ) {
        require(image.planes.size >= 3) { "Expected YUV_420_888 image" }
        require(output.capacity() >= mapper.modelInputSize * mapper.modelInputSize * 4)
        require(workspace.modelInputSize == mapper.modelInputSize)

        val crop = image.cropRect
        val rotation = ((rotationDegrees % 360) + 360) % 360
        val size = mapper.modelInputSize
        val planKey = PlanKey(
            imageWidth = image.width,
            imageHeight = image.height,
            cropLeft = crop.left,
            cropTop = crop.top,
            cropRight = crop.right,
            cropBottom = crop.bottom,
            modelInputSize = size,
            scaledWBits = mapper.scaledW.toBits(),
            scaledHBits = mapper.scaledH.toBits(),
            padLeftBits = mapper.padLeft.toBits(),
            padTopBits = mapper.padTop.toBits(),
            rotation = rotation
        )
        val plan = if (workspace.planKey == planKey) {
            workspace.plan ?: error("Canonical YUV sampling plan missing")
        } else {
            buildSamplingPlan(image.width, image.height, crop, mapper, rotation).also {
                workspace.planKey = planKey
                workspace.plan = it
            }
        }

        val yPlane = snapshotPlane(image.planes[0], workspace, 0)
        val uPlane = snapshotPlane(image.planes[1], workspace, 1)
        val vPlane = snapshotPlane(image.planes[2], workspace, 2)
        val rgbaInts = workspace.rgbaInts

        for (bufferY in 0 until size) {
            // glReadPixels contract is bottom-up; YoloPreprocessor flips it back to model top-down.
            val modelY = size - 1 - bufferY
            val dstRow = bufferY * size
            for (modelX in 0 until size) {
                val dstIndex = dstRow + modelX
                if (!plan.validX[modelX] || !plan.validY[modelY]) {
                    rgbaInts[dstIndex] = rgbaLittleEndianInt(0x727272)
                    continue
                }
                val xIndex = if (plan.swapAxes) modelY else modelX
                val yIndex = if (plan.swapAxes) modelX else modelY
                val y8 = sampleSnapshot(yPlane, plan.lumaX, xIndex, plan.lumaY, yIndex)
                val u8 = sampleSnapshot(uPlane, plan.uvX, xIndex, plan.uvY, yIndex)
                val v8 = sampleSnapshot(vPlane, plan.uvX, xIndex, plan.uvY, yIndex)
                rgbaInts[dstIndex] = rgbaLittleEndianInt(
                    yuvToRgb(y8, u8, v8, colorStandard, colorRange)
                )
            }
        }

        output.clear()
        output.duplicate().apply {
            position(0)
            order(ByteOrder.LITTLE_ENDIAN)
        }.asIntBuffer().put(rgbaInts, 0, rgbaInts.size)
        output.rewind()
    }

    private fun buildSamplingPlan(
        imageWidth: Int,
        imageHeight: Int,
        crop: android.graphics.Rect,
        mapper: ModelCoordinateMapper,
        rotation: Int
    ): SamplingPlan {
        val size = mapper.modelInputSize
        val cropW = crop.width()
        val cropH = crop.height()
        val swapAxes = rotation == 90 || rotation == 270
        val displayW = if (swapAxes) cropH else cropW
        val displayH = if (swapAxes) cropW else cropH
        val left = mapper.padLeft.toDouble()
        val top = mapper.padTop.toDouble()
        val right = (mapper.padLeft + mapper.scaledW).toDouble()
        val bottom = (mapper.padTop + mapper.scaledH).toDouble()
        val validX = BooleanArray(size)
        val validY = BooleanArray(size)
        val displayX = DoubleArray(size)
        val displayY = DoubleArray(size)

        for (i in 0 until size) {
            val c = i + 0.5
            validX[i] = c >= left && c < right
            validY[i] = c >= top && c < bottom
            displayX[i] = (((c - left) / mapper.scaledW.toDouble()).coerceIn(0.0, 1.0) * displayW) - 0.5
            displayY[i] = (((c - top) / mapper.scaledH.toDouble()).coerceIn(0.0, 1.0) * displayH) - 0.5
        }

        val sourceX = DoubleArray(size)
        val sourceY = DoubleArray(size)
        for (i in 0 until size) {
            sourceX[i] = when (rotation) {
                90 -> displayY[i] + crop.left
                180 -> crop.right - 1.0 - displayX[i]
                270 -> crop.right - 1.0 - displayY[i]
                else -> displayX[i] + crop.left
            }.coerceIn(crop.left.toDouble(), (crop.right - 1).toDouble())
            sourceY[i] = when (rotation) {
                90 -> crop.bottom - 1.0 - displayX[i]
                180 -> crop.bottom - 1.0 - displayY[i]
                270 -> displayX[i] + crop.top
                else -> displayY[i] + crop.top
            }.coerceIn(crop.top.toDouble(), (crop.bottom - 1).toDouble())
        }

        val lumaX = axisSamples(sourceX, imageWidth)
        val lumaY = axisSamples(sourceY, imageHeight)
        val uvWidth = (imageWidth + 1) / 2
        val uvHeight = (imageHeight + 1) / 2
        val uvX = axisSamples(DoubleArray(size) { sourceX[it] * 0.5 }, uvWidth)
        val uvY = axisSamples(DoubleArray(size) { sourceY[it] * 0.5 }, uvHeight)
        return SamplingPlan(validX, validY, lumaX, lumaY, uvX, uvY, swapAxes)
    }

    private fun axisSamples(coords: DoubleArray, dimension: Int): AxisSamples {
        val i0 = IntArray(coords.size)
        val i1 = IntArray(coords.size)
        val w1 = IntArray(coords.size)
        val maxIndex = (dimension - 1).coerceAtLeast(0)
        for (i in coords.indices) {
            val c = coords[i].coerceIn(0.0, maxIndex.toDouble())
            val base = floor(c).toInt()
            i0[i] = base
            i1[i] = (base + 1).coerceAtMost(maxIndex)
            w1[i] = ((c - base) * FP).roundToInt().coerceIn(0, FP)
        }
        return AxisSamples(i0, i1, w1)
    }

    private fun snapshotPlane(plane: Image.Plane, workspace: Workspace, slot: Int): PlaneSnapshot {
        val duplicate = plane.buffer.duplicate()
        val length = duplicate.remaining()
        val target = when (slot) {
            0 -> ensureCapacity(workspace.yBytes, length).also { workspace.yBytes = it }
            1 -> ensureCapacity(workspace.uBytes, length).also { workspace.uBytes = it }
            else -> ensureCapacity(workspace.vBytes, length).also { workspace.vBytes = it }
        }
        duplicate.get(target, 0, length)
        return PlaneSnapshot(target, length, plane.rowStride, plane.pixelStride)
    }

    private fun ensureCapacity(bytes: ByteArray, required: Int): ByteArray =
        if (bytes.size >= required) bytes else ByteArray(required)

    internal fun sampleSnapshot(
        plane: PlaneSnapshot,
        x: AxisSamples,
        xIndex: Int,
        y: AxisSamples,
        yIndex: Int
    ): Int {
        val fx = x.w1[xIndex]
        val fy = y.w1[yIndex]
        val row0 = y.i0[yIndex] * plane.rowStride
        val row1 = y.i1[yIndex] * plane.rowStride
        val col0 = x.i0[xIndex] * plane.pixelStride
        val col1 = x.i1[xIndex] * plane.pixelStride
        val p00 = planeByte(plane, row0 + col0)
        if (fx == 0 && fy == 0) return p00
        val p10 = planeByte(plane, row0 + col1)
        val p01 = planeByte(plane, row1 + col0)
        val p11 = planeByte(plane, row1 + col1)
        val top = p00 * (FP - fx) + p10 * fx
        val bottom = p01 * (FP - fx) + p11 * fx
        return (top * (FP - fy) + bottom * fy + (FP * FP / 2)) / (FP * FP)
    }

    private fun planeByte(plane: PlaneSnapshot, index: Int): Int {
        if (index < 0 || index >= plane.length) {
            error("YUV plane index out of bounds: index=$index length=${plane.length}")
        }
        return plane.bytes[index].toInt() and 0xFF
    }

    internal fun rgbaLittleEndianInt(rgb: Int): Int {
        val r = (rgb ushr 16) and 0xFF
        val g = (rgb ushr 8) and 0xFF
        val b = rgb and 0xFF
        return (0xFF shl 24) or (b shl 16) or (g shl 8) or r
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

}
