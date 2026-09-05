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
    private val modelInputSize: Int = 640,
    private val startUs: Long = 0L
) : AutoCloseable {

    data class RuntimeInfo(
        val codecName: String,
        val colorStandard: Int?,
        val colorRange: Int?,
        val colorTransfer: Int?
    )

    data class DecodedRgbaFrame(
        val ptsUs: Long,
        val rgbaBuffer: ByteBuffer
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
        if (startUs > 0L) {
            extractor.seekTo(startUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
        }
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
    ): ByteBuffer = decodeRgbaFrame(
        targetPtsUs = targetPtsUs,
        mapper = mapper,
        requireExactPts = true
    ).rgbaBuffer

    /**
     * Decodes the first display frame at or after [targetPtsUs]. This is used by one-shot
     * analysis where a millisecond trim boundary is not guaranteed to be an exact source PTS.
     */
    fun decodeRgbaAtOrAfterPts(
        targetPtsUs: Long,
        mapper: ModelCoordinateMapper
    ): DecodedRgbaFrame = decodeRgbaFrame(
        targetPtsUs = targetPtsUs,
        mapper = mapper,
        requireExactPts = false
    )

    private fun decodeRgbaFrame(
        targetPtsUs: Long,
        mapper: ModelCoordinateMapper,
        requireExactPts: Boolean
    ): DecodedRgbaFrame {
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
                            if (requireExactPts && ptsUs != targetPtsUs) {
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
                            return DecodedRgbaFrame(ptsUs = ptsUs, rgbaBuffer = rgbaBuffer)
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
    private const val LETTERBOX_RGBA = -9276814 // 0xFF727272 as signed Int.

    internal class Workspace(val modelInputSize: Int) {
        internal val rgbaInts = IntArray(modelInputSize * modelInputSize)
        internal var yBytes = ByteArray(0)
        internal var uBytes = ByteArray(0)
        internal var vBytes = ByteArray(0)
        internal var planKey: PlanKey? = null
        internal var plan: SamplingPlan? = null
        internal var accessPlanKey: AccessPlanKey? = null
        internal var yAccessPlan: PlaneAccessPlan? = null
        internal var uAccessPlan: PlaneAccessPlan? = null
        internal var vAccessPlan: PlaneAccessPlan? = null
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

    internal data class PlaneAccessPlan(
        val x0: IntArray,
        val x1: IntArray,
        val xW1: IntArray,
        val y0: IntArray,
        val y1: IntArray,
        val yW1: IntArray,
        val maxOffset: Int
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

    internal data class AccessPlanKey(
        val planKey: PlanKey,
        val yRowStride: Int,
        val yPixelStride: Int,
        val uRowStride: Int,
        val uPixelStride: Int,
        val vRowStride: Int,
        val vPixelStride: Int
    )

    internal data class SamplingPlan(
        val validX: BooleanArray,
        val validY: BooleanArray,
        val validXStart: Int,
        val validXEndExclusive: Int,
        val validYStart: Int,
        val validYEndExclusive: Int,
        val lumaX: AxisSamples,
        val lumaY: AxisSamples,
        val uvX: AxisSamples,
        val uvY: AxisSamples,
        val swapAxes: Boolean,
        val lumaNearestInValidRect: Boolean
    )

    internal data class ColorTransform(
        val yTerms: IntArray,
        val rU: IntArray,
        val rV: IntArray,
        val gU: IntArray,
        val gV: IntArray,
        val bU: IntArray,
        val bV: IntArray,
        val rBias: Int,
        val gBias: Int,
        val bBias: Int
    )

    private val limitedBt601 = buildColorTransform(
        fullRange = false,
        rU = 0,
        rV = 409,
        gU = -100,
        gV = -208,
        bU = 516,
        bV = 0
    )
    private val limitedBt2020 = buildColorTransform(
        fullRange = false,
        rU = 0,
        rV = 430,
        gU = -48,
        gV = -167,
        bU = 548,
        bV = 0
    )
    private val limitedBt709 = buildColorTransform(
        fullRange = false,
        rU = 0,
        rV = 459,
        gU = -55,
        gV = -136,
        bU = 541,
        bV = 0
    )
    private val fullBt601 = buildColorTransform(
        fullRange = true,
        rU = 0,
        rV = 359,
        gU = -88,
        gV = -183,
        bU = 454,
        bV = 0
    )
    private val fullBt2020 = buildColorTransform(
        fullRange = true,
        rU = 0,
        rV = 377,
        gU = -42,
        gV = -146,
        bU = 482,
        bV = 0
    )
    private val fullBt709 = buildColorTransform(
        fullRange = true,
        rU = 0,
        rV = 403,
        gU = -48,
        gV = -120,
        bU = 475,
        bV = 0
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
        val accessPlanKey = AccessPlanKey(
            planKey = planKey,
            yRowStride = yPlane.rowStride,
            yPixelStride = yPlane.pixelStride,
            uRowStride = uPlane.rowStride,
            uPixelStride = uPlane.pixelStride,
            vRowStride = vPlane.rowStride,
            vPixelStride = vPlane.pixelStride
        )
        if (workspace.accessPlanKey != accessPlanKey) {
            workspace.yAccessPlan = buildPlaneAccessPlan(
                x = plan.lumaX,
                y = plan.lumaY,
                rowStride = yPlane.rowStride,
                pixelStride = yPlane.pixelStride
            )
            workspace.uAccessPlan = buildPlaneAccessPlan(
                x = plan.uvX,
                y = plan.uvY,
                rowStride = uPlane.rowStride,
                pixelStride = uPlane.pixelStride
            )
            workspace.vAccessPlan = buildPlaneAccessPlan(
                x = plan.uvX,
                y = plan.uvY,
                rowStride = vPlane.rowStride,
                pixelStride = vPlane.pixelStride
            )
            workspace.accessPlanKey = accessPlanKey
        }
        val yAccessPlan = workspace.yAccessPlan ?: error("Canonical Y access plan missing")
        val uAccessPlan = workspace.uAccessPlan ?: error("Canonical U access plan missing")
        val vAccessPlan = workspace.vAccessPlan ?: error("Canonical V access plan missing")
        validatePlaneAccess(yPlane, yAccessPlan)
        validatePlaneAccess(uPlane, uAccessPlan)
        validatePlaneAccess(vPlane, vAccessPlan)
        val colorTransform = colorTransform(colorStandard, colorRange)
        val rgbaInts = workspace.rgbaInts
        rgbaInts.fill(LETTERBOX_RGBA)

        val canUseNearestLumaFusedChroma =
            !plan.swapAxes &&
                plan.lumaNearestInValidRect &&
                uPlane.rowStride == vPlane.rowStride &&
                uPlane.pixelStride == vPlane.pixelStride

        if (plan.swapAxes) {
            for (modelY in plan.validYStart until plan.validYEndExclusive) {
                // glReadPixels contract is bottom-up; YoloPreprocessor flips it back to model top-down.
                val dstRow = (size - 1 - modelY) * size
                val xIndex = modelY
                for (modelX in plan.validXStart until plan.validXEndExclusive) {
                    val yIndex = modelX
                    val y8 = sampleSnapshotFast(yPlane, yAccessPlan, xIndex, yIndex)
                    val u8 = sampleSnapshotFast(uPlane, uAccessPlan, xIndex, yIndex)
                    val v8 = sampleSnapshotFast(vPlane, vAccessPlan, xIndex, yIndex)
                    rgbaInts[dstRow + modelX] = rgbaFromYuv(y8, u8, v8, colorTransform)
                }
            }
        } else if (canUseNearestLumaFusedChroma) {
            val yBytes = yPlane.bytes
            for (modelY in plan.validYStart until plan.validYEndExclusive) {
                val dstRow = (size - 1 - modelY) * size
                val yRow = yAccessPlan.y0[modelY]
                for (modelX in plan.validXStart until plan.validXEndExclusive) {
                    val y8 = yBytes[yRow + yAccessPlan.x0[modelX]].toInt() and 0xFF
                    val uv = sampleUvPairFast(
                        uPlane = uPlane,
                        vPlane = vPlane,
                        access = uAccessPlan,
                        xIndex = modelX,
                        yIndex = modelY
                    )
                    rgbaInts[dstRow + modelX] = rgbaFromYuv(
                        y = y8,
                        u = uv ushr 8,
                        v = uv and 0xFF,
                        transform = colorTransform
                    )
                }
            }
        } else {
            for (modelY in plan.validYStart until plan.validYEndExclusive) {
                val dstRow = (size - 1 - modelY) * size
                for (modelX in plan.validXStart until plan.validXEndExclusive) {
                    val y8 = sampleSnapshotFast(yPlane, yAccessPlan, modelX, modelY)
                    val u8 = sampleSnapshotFast(uPlane, uAccessPlan, modelX, modelY)
                    val v8 = sampleSnapshotFast(vPlane, vAccessPlan, modelX, modelY)
                    rgbaInts[dstRow + modelX] = rgbaFromYuv(y8, u8, v8, colorTransform)
                }
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
        val validXStart = validX.indexOfFirst { it }.let { if (it >= 0) it else size }
        val validXEndExclusive = validX.indexOfLast { it }.let { if (it >= 0) it + 1 else size }
        val validYStart = validY.indexOfFirst { it }.let { if (it >= 0) it else size }
        val validYEndExclusive = validY.indexOfLast { it }.let { if (it >= 0) it + 1 else size }
        val lumaNearestInValidRect =
            (validXStart until validXEndExclusive).all { lumaX.w1[it] == 0 } &&
                (validYStart until validYEndExclusive).all { lumaY.w1[it] == 0 }
        return SamplingPlan(
            validX = validX,
            validY = validY,
            validXStart = validXStart,
            validXEndExclusive = validXEndExclusive,
            validYStart = validYStart,
            validYEndExclusive = validYEndExclusive,
            lumaX = lumaX,
            lumaY = lumaY,
            uvX = uvX,
            uvY = uvY,
            swapAxes = swapAxes,
            lumaNearestInValidRect = lumaNearestInValidRect
        )
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

    internal fun buildPlaneAccessPlan(
        x: AxisSamples,
        y: AxisSamples,
        rowStride: Int,
        pixelStride: Int
    ): PlaneAccessPlan {
        val x0 = IntArray(x.i0.size)
        val x1 = IntArray(x.i1.size)
        for (i in x0.indices) {
            x0[i] = x.i0[i] * pixelStride
            x1[i] = x.i1[i] * pixelStride
        }
        val y0 = IntArray(y.i0.size)
        val y1 = IntArray(y.i1.size)
        for (i in y0.indices) {
            y0[i] = y.i0[i] * rowStride
            y1[i] = y.i1[i] * rowStride
        }
        val maxX = maxOf(x0.maxOrNull() ?: 0, x1.maxOrNull() ?: 0)
        val maxY = maxOf(y0.maxOrNull() ?: 0, y1.maxOrNull() ?: 0)
        return PlaneAccessPlan(
            x0 = x0,
            x1 = x1,
            xW1 = x.w1,
            y0 = y0,
            y1 = y1,
            yW1 = y.w1,
            maxOffset = maxX + maxY
        )
    }

    private fun validatePlaneAccess(plane: PlaneSnapshot, access: PlaneAccessPlan) {
        if (access.maxOffset < 0 || access.maxOffset >= plane.length) {
            error(
                "YUV plane access out of bounds: maxOffset=${access.maxOffset} length=${plane.length}"
            )
        }
    }

    internal fun sampleSnapshotFast(
        plane: PlaneSnapshot,
        access: PlaneAccessPlan,
        xIndex: Int,
        yIndex: Int
    ): Int {
        val fx = access.xW1[xIndex]
        val fy = access.yW1[yIndex]
        val row0 = access.y0[yIndex]
        val row1 = access.y1[yIndex]
        val col0 = access.x0[xIndex]
        val col1 = access.x1[xIndex]
        val bytes = plane.bytes
        val p00 = bytes[row0 + col0].toInt() and 0xFF
        if (fx == 0 && fy == 0) return p00
        val p10 = bytes[row0 + col1].toInt() and 0xFF
        val p01 = bytes[row1 + col0].toInt() and 0xFF
        val p11 = bytes[row1 + col1].toInt() and 0xFF
        val top = p00 * (FP - fx) + p10 * fx
        val bottom = p01 * (FP - fx) + p11 * fx
        return (top * (FP - fy) + bottom * fy + (FP * FP / 2)) / (FP * FP)
    }

    /**
     * Samples U and V together when both chroma planes share the same row/pixel
     * layout. The fixed-point arithmetic for each component is intentionally the
     * same as [sampleSnapshotFast]; only the shared offset/weight work is fused.
     * The high byte is U and the low byte is V.
     */
    internal fun sampleUvPairFast(
        uPlane: PlaneSnapshot,
        vPlane: PlaneSnapshot,
        access: PlaneAccessPlan,
        xIndex: Int,
        yIndex: Int
    ): Int {
        val fx = access.xW1[xIndex]
        val fy = access.yW1[yIndex]
        val row0 = access.y0[yIndex]
        val row1 = access.y1[yIndex]
        val col0 = access.x0[xIndex]
        val col1 = access.x1[xIndex]
        val i00 = row0 + col0
        val u00 = uPlane.bytes[i00].toInt() and 0xFF
        val v00 = vPlane.bytes[i00].toInt() and 0xFF
        if (fx == 0 && fy == 0) return (u00 shl 8) or v00

        val i10 = row0 + col1
        val i01 = row1 + col0
        val i11 = row1 + col1
        val u10 = uPlane.bytes[i10].toInt() and 0xFF
        val u01 = uPlane.bytes[i01].toInt() and 0xFF
        val u11 = uPlane.bytes[i11].toInt() and 0xFF
        val v10 = vPlane.bytes[i10].toInt() and 0xFF
        val v01 = vPlane.bytes[i01].toInt() and 0xFF
        val v11 = vPlane.bytes[i11].toInt() and 0xFF
        val topU = u00 * (FP - fx) + u10 * fx
        val bottomU = u01 * (FP - fx) + u11 * fx
        val topV = v00 * (FP - fx) + v10 * fx
        val bottomV = v01 * (FP - fx) + v11 * fx
        val u = (topU * (FP - fy) + bottomU * fy + (FP * FP / 2)) / (FP * FP)
        val v = (topV * (FP - fy) + bottomV * fy + (FP * FP / 2)) / (FP * FP)
        return (u shl 8) or v
    }

    private fun buildColorTransform(
        fullRange: Boolean,
        rU: Int,
        rV: Int,
        gU: Int,
        gV: Int,
        bU: Int,
        bV: Int
    ): ColorTransform {
        val yTerms = IntArray(256) { y ->
            if (fullRange) {
                y * FP
            } else {
                298 * (y - 16).coerceAtLeast(0)
            }
        }
        fun contribution(coefficient: Int): IntArray =
            IntArray(256) { value -> coefficient * (value - 128) }

        return ColorTransform(
            yTerms = yTerms,
            rU = contribution(rU),
            rV = contribution(rV),
            gU = contribution(gU),
            gV = contribution(gV),
            bU = contribution(bU),
            bV = contribution(bV),
            rBias = 128,
            // Full-range green historically subtracts a rounded chroma term:
            // y - ((x + 128) >> 8) == ((y << 8) - x + 127) >> 8.
            gBias = if (fullRange) 127 else 128,
            bBias = 128
        )
    }

    private fun colorTransform(colorStandard: Int?, colorRange: Int?): ColorTransform {
        val fullRange = colorRange == MediaFormat.COLOR_RANGE_FULL
        return when (colorStandard ?: MediaFormat.COLOR_STANDARD_BT709) {
            MediaFormat.COLOR_STANDARD_BT601_PAL,
            MediaFormat.COLOR_STANDARD_BT601_NTSC -> if (fullRange) fullBt601 else limitedBt601
            MediaFormat.COLOR_STANDARD_BT2020 -> if (fullRange) fullBt2020 else limitedBt2020
            else -> if (fullRange) fullBt709 else limitedBt709
        }
    }

    internal fun rgbaFromYuv(
        y: Int,
        u: Int,
        v: Int,
        transform: ColorTransform
    ): Int {
        val yTerm = transform.yTerms[y]
        val r = (
            (yTerm + transform.rU[u] + transform.rV[v] + transform.rBias) shr 8
            ).coerceIn(0, 255)
        val g = (
            (yTerm + transform.gU[u] + transform.gV[v] + transform.gBias) shr 8
            ).coerceIn(0, 255)
        val b = (
            (yTerm + transform.bU[u] + transform.bV[v] + transform.bBias) shr 8
            ).coerceIn(0, 255)
        return (0xFF shl 24) or (b shl 16) or (g shl 8) or r
    }

    internal fun optimizedRgbaFromYuv(
        y: Int,
        u: Int,
        v: Int,
        colorStandard: Int?,
        colorRange: Int?
    ): Int = rgbaFromYuv(y, u, v, colorTransform(colorStandard, colorRange))

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
