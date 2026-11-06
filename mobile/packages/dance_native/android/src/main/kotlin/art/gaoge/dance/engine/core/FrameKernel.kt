package art.gaoge.dance.engine.core

import art.gaoge.dance.engine.geometry.ModelCoordinateMapper
import art.gaoge.dance.engine.inference.FloatRect
import java.nio.ByteBuffer

/** Transport adapter only; the production and JVM paths load the same Rust core. */
internal object FrameKernel {
    init {
        System.loadLibrary("frame_kernel_jni")
        check(abiVersion() == 1) { "Unsupported FrameKernel ABI" }
    }

    private external fun abiVersion(): Int
    private external fun needsFaceProtectionNative(body: Boolean, face: Boolean): Boolean
    private external fun faceProtectionIdsNative(body: LongArray, face: LongArray): LongArray
    private external fun detailViewsNative(width: Int, height: Int): FloatArray
    private external fun detailCompleteNative(box: FloatArray, view: FloatArray, width: Int, height: Int): Boolean
    private external fun iouNative(a: FloatArray, b: FloatArray): Float
    private external fun reprojectMaskNative(mask: ByteBuffer, output: ByteBuffer, local: FloatArray, scene: FloatArray, view: FloatArray)

    fun needsFaceProtection(body: Boolean, face: Boolean): Boolean = needsFaceProtectionNative(body, face)

    fun faceProtectionIds(body: Set<Int>, face: Set<Int>): Set<Int> =
        faceProtectionIdsNative(body.map { it.toLong() }.toLongArray(), face.map { it.toLong() }.toLongArray())
            .map { it.toInt() }.toSet()

    fun detailViews(width: Int, height: Int): List<FloatRect> =
        detailViewsNative(width, height).asList().chunked(4).map { FloatRect(it[0], it[1], it[2], it[3]) }

    fun detailComplete(box: FloatRect, view: FloatRect, width: Int, height: Int): Boolean =
        detailCompleteNative(box.values(), view.values(), width, height)

    fun iou(a: FloatRect, b: FloatRect): Float = iouNative(a.values(), b.values())

    fun reprojectMask(mask: ByteBuffer, output: ByteBuffer, local: ModelCoordinateMapper, scene: ModelCoordinateMapper, view: FloatRect) =
        reprojectMaskNative(mask, output, local.values(), scene.values(), view.values())

    private fun FloatRect.values() = floatArrayOf(left, top, right, bottom)
    private fun ModelCoordinateMapper.values() = floatArrayOf(scale, padLeft, padTop, srcWidth.toFloat(), srcHeight.toFloat(), modelInputSize.toFloat(), protoSize.toFloat())
}
