package com.danceanon.native.privacy

import com.danceanon.native.geometry.ModelCoordinateMapper
import com.danceanon.native.inference.FloatRect
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.floor
import kotlin.math.roundToInt

/**
 * Deterministically samples a top-down square FACE ROI from the canonical 640x640
 * bottom-up RGBA buffer used by the CPU YOLO reference path.
 *
 * The canonical buffer follows the glReadPixels bottom-up contract. The returned
 * ROI is top-down because FaceLocator and FacePixelMotionTracker consume top-down
 * RGBA. Fixed-point bilinear weights keep the resampling identical across devices.
 */
internal object CanonicalFaceRoiSampler {
    private const val FP = 256

    internal class Workspace(val outputSize: Int) {
        internal val x0 = IntArray(outputSize)
        internal val x1 = IntArray(outputSize)
        internal val wx0 = IntArray(outputSize)
        internal val wx1 = IntArray(outputSize)
        internal val row0 = IntArray(outputSize)
        internal val row1 = IntArray(outputSize)
        internal val wy0 = IntArray(outputSize)
        internal val wy1 = IntArray(outputSize)
        internal val outputInts = IntArray(outputSize * outputSize)
        internal var canonicalInput = IntArray(0)
    }

    fun prepareCanonicalInput(
        canonicalRgbaBottomUp: ByteBuffer,
        modelSize: Int,
        workspace: Workspace
    ): IntArray {
        require(modelSize > 0)
        val pixelCount = modelSize * modelSize
        val requiredInputBytes = pixelCount * 4
        require(canonicalRgbaBottomUp.capacity() >= requiredInputBytes)
        if (workspace.canonicalInput.size != pixelCount) {
            workspace.canonicalInput = IntArray(pixelCount)
        }
        val inputInts = canonicalRgbaBottomUp.duplicate().apply {
            position(0)
            limit(requiredInputBytes)
            order(ByteOrder.LITTLE_ENDIAN)
        }.asIntBuffer()
        inputInts.get(workspace.canonicalInput, 0, pixelCount)
        return workspace.canonicalInput
    }

    fun sampleTopDown(
        canonicalRgbaBottomUp: ByteBuffer,
        mapper: ModelCoordinateMapper,
        sourceRect: FloatRect,
        outputSize: Int,
        output: ByteBuffer,
        workspace: Workspace = Workspace(outputSize),
        preparedCanonicalInput: IntArray? = null,
        preparedCanonicalInputIsOpaque: Boolean = false,
        preparedCanonicalOutputUsesHeapStaging: Boolean = false
    ): ByteBuffer {
        require(outputSize > 0)
        require(workspace.outputSize == outputSize)
        val modelSize = mapper.modelInputSize
        val requiredInputBytes = modelSize * modelSize * 4
        val requiredOutputBytes = outputSize * outputSize * 4
        require(canonicalRgbaBottomUp.capacity() >= requiredInputBytes)
        require(output.capacity() >= requiredOutputBytes)
        require(preparedCanonicalInput == null || preparedCanonicalInput.size >= modelSize * modelSize)
        require(!preparedCanonicalInputIsOpaque || preparedCanonicalInput != null)
        require(!preparedCanonicalOutputUsesHeapStaging || preparedCanonicalInput != null)

        val inputInts = if (preparedCanonicalInput == null) {
            canonicalRgbaBottomUp.duplicate().apply {
                position(0)
                limit(requiredInputBytes)
                order(ByteOrder.LITTLE_ENDIAN)
            }.asIntBuffer()
        } else {
            null
        }
        val dstInts = output.duplicate().apply {
            clear()
            limit(requiredOutputBytes)
            order(ByteOrder.LITTLE_ENDIAN)
        }.asIntBuffer()

        val rectWidth = sourceRect.width.coerceAtLeast(1f)
        val rectHeight = sourceRect.height.coerceAtLeast(1f)

        // Precompute all axis sampling before the hot pixel loop. The previous
        // implementation repeated source/model transforms, floor and weight
        // rounding for every output pixel. Keeping exactly the same formulas and
        // fixed-point weights here preserves byte-for-byte output while reducing
        // the inner loop to packed integer loads and channel interpolation.
        for (outX in 0 until outputSize) {
            val srcX = sourceRect.left + ((outX + 0.5f) / outputSize) * rectWidth
            val modelCenterX = mapper.sourceToModelX(srcX)
            val modelPixelX = (modelCenterX - 0.5f).coerceIn(0f, (modelSize - 1).toFloat())
            val x0 = floor(modelPixelX.toDouble()).toInt()
            workspace.x0[outX] = x0
            workspace.x1[outX] = (x0 + 1).coerceAtMost(modelSize - 1)
            val wx1 = ((modelPixelX - x0) * FP).roundToInt().coerceIn(0, FP)
            workspace.wx0[outX] = FP - wx1
            workspace.wx1[outX] = wx1
        }
        for (outY in 0 until outputSize) {
            val srcY = sourceRect.top + ((outY + 0.5f) / outputSize) * rectHeight
            val modelCenterY = mapper.sourceToModelY(srcY)
            val modelPixelY = (modelCenterY - 0.5f).coerceIn(0f, (modelSize - 1).toFloat())
            val y0 = floor(modelPixelY.toDouble()).toInt()
            val y1 = (y0 + 1).coerceAtMost(modelSize - 1)
            val bufferY0 = modelSize - 1 - y0
            val bufferY1 = modelSize - 1 - y1
            workspace.row0[outY] = bufferY0 * modelSize
            workspace.row1[outY] = bufferY1 * modelSize
            val wy1 = ((modelPixelY - y0) * FP).roundToInt().coerceIn(0, FP)
            workspace.wy0[outY] = FP - wy1
            workspace.wy1[outY] = wy1
        }

        var dstIndex = 0
        if (preparedCanonicalInput != null) {
            if (preparedCanonicalOutputUsesHeapStaging) {
                for (outY in 0 until outputSize) {
                    val row0 = workspace.row0[outY]
                    val row1 = workspace.row1[outY]
                    val wy0 = workspace.wy0[outY]
                    val wy1 = workspace.wy1[outY]
                    for (outX in 0 until outputSize) {
                        val x0 = workspace.x0[outX]
                        val x1 = workspace.x1[outX]
                        val wx0 = workspace.wx0[outX]
                        val wx1 = workspace.wx1[outX]
                        val p00 = preparedCanonicalInput[row0 + x0]
                        val p10 = preparedCanonicalInput[row0 + x1]
                        val p01 = preparedCanonicalInput[row1 + x0]
                        val p11 = preparedCanonicalInput[row1 + x1]
                        val w00 = wx0 * wy0
                        val w10 = wx1 * wy0
                        val w01 = wx0 * wy1
                        val w11 = wx1 * wy1
                        val r = bilerpChannelExpanded(p00, p10, p01, p11, 0, w00, w10, w01, w11)
                        val g = bilerpChannelExpanded(p00, p10, p01, p11, 8, w00, w10, w01, w11)
                        val b = bilerpChannelExpanded(p00, p10, p01, p11, 16, w00, w10, w01, w11)
                        val a = if (preparedCanonicalInputIsOpaque) {
                            0xff
                        } else {
                            bilerpChannelExpanded(p00, p10, p01, p11, 24, w00, w10, w01, w11)
                        }
                        workspace.outputInts[dstIndex++] = r or (g shl 8) or (b shl 16) or (a shl 24)
                    }
                }
                dstInts.put(workspace.outputInts, 0, outputSize * outputSize)
            } else {
                for (outY in 0 until outputSize) {
                    val row0 = workspace.row0[outY]
                    val row1 = workspace.row1[outY]
                    val wy0 = workspace.wy0[outY]
                    val wy1 = workspace.wy1[outY]
                    for (outX in 0 until outputSize) {
                        val x0 = workspace.x0[outX]
                        val x1 = workspace.x1[outX]
                        val wx0 = workspace.wx0[outX]
                        val wx1 = workspace.wx1[outX]
                        val p00 = preparedCanonicalInput[row0 + x0]
                        val p10 = preparedCanonicalInput[row0 + x1]
                        val p01 = preparedCanonicalInput[row1 + x0]
                        val p11 = preparedCanonicalInput[row1 + x1]
                        val w00 = wx0 * wy0
                        val w10 = wx1 * wy0
                        val w01 = wx0 * wy1
                        val w11 = wx1 * wy1
                        val r = bilerpChannelExpanded(p00, p10, p01, p11, 0, w00, w10, w01, w11)
                        val g = bilerpChannelExpanded(p00, p10, p01, p11, 8, w00, w10, w01, w11)
                        val b = bilerpChannelExpanded(p00, p10, p01, p11, 16, w00, w10, w01, w11)
                        val a = if (preparedCanonicalInputIsOpaque) {
                            0xff
                        } else {
                            bilerpChannelExpanded(p00, p10, p01, p11, 24, w00, w10, w01, w11)
                        }
                        dstInts.put(dstIndex++, r or (g shl 8) or (b shl 16) or (a shl 24))
                    }
                }
            }
        } else {
            val directInput = requireNotNull(inputInts)
            for (outY in 0 until outputSize) {
                val row0 = workspace.row0[outY]
                val row1 = workspace.row1[outY]
                val wy1 = workspace.wy1[outY]
                val wy0 = FP - wy1
                for (outX in 0 until outputSize) {
                    val x0 = workspace.x0[outX]
                    val x1 = workspace.x1[outX]
                    val wx1 = workspace.wx1[outX]
                    val wx0 = FP - wx1
                    val p00 = directInput.get(row0 + x0)
                    val p10 = directInput.get(row0 + x1)
                    val p01 = directInput.get(row1 + x0)
                    val p11 = directInput.get(row1 + x1)
                    val r = bilerpChannel(p00, p10, p01, p11, 0, wx0, wx1, wy0, wy1)
                    val g = bilerpChannel(p00, p10, p01, p11, 8, wx0, wx1, wy0, wy1)
                    val b = bilerpChannel(p00, p10, p01, p11, 16, wx0, wx1, wy0, wy1)
                    val a = bilerpChannel(p00, p10, p01, p11, 24, wx0, wx1, wy0, wy1)
                    dstInts.put(dstIndex++, r or (g shl 8) or (b shl 16) or (a shl 24))
                }
            }
        }
        output.position(0)
        output.limit(requiredOutputBytes)
        return output
    }

    private fun bilerpChannel(
        p00: Int,
        p10: Int,
        p01: Int,
        p11: Int,
        shift: Int,
        wx0: Int,
        wx1: Int,
        wy0: Int,
        wy1: Int
    ): Int {
        val v00 = (p00 ushr shift) and 0xff
        val v10 = (p10 ushr shift) and 0xff
        val v01 = (p01 ushr shift) and 0xff
        val v11 = (p11 ushr shift) and 0xff
        val top = v00 * wx0 + v10 * wx1
        val bottom = v01 * wx0 + v11 * wx1
        return ((top * wy0 + bottom * wy1 + (FP * FP / 2)) / (FP * FP))
            .coerceIn(0, 255)
    }

    /**
     * Exact algebraic expansion of [bilerpChannel]. The four fixed-point
     * weights sum to FP*FP, so every intermediate remains well inside Int
     * range and the final rounding/division contract is unchanged.
     */
    private fun bilerpChannelExpanded(
        p00: Int,
        p10: Int,
        p01: Int,
        p11: Int,
        shift: Int,
        w00: Int,
        w10: Int,
        w01: Int,
        w11: Int
    ): Int {
        val v00 = (p00 ushr shift) and 0xff
        val v10 = (p10 ushr shift) and 0xff
        val v01 = (p01 ushr shift) and 0xff
        val v11 = (p11 ushr shift) and 0xff
        return ((
            v00 * w00 +
                v10 * w10 +
                v01 * w01 +
                v11 * w11 +
                (FP * FP / 2)
            ) / (FP * FP)).coerceIn(0, 255)
    }
}
