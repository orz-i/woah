package art.gaoge.dance.engine.core

import art.gaoge.dance.engine.geometry.ModelCoordinateMapper
import art.gaoge.dance.engine.inference.FloatRect
import java.nio.ByteBuffer

/** Transport adapter only; the production and JVM paths load the same Rust core. */
internal object PrivacyCore {
    init {
        System.loadLibrary("woah_privacy_core_jni")
        check(abiVersion() == 1) { "Unsupported Woah privacy core ABI" }
    }

    private external fun abiVersion(): Int
    private external fun detailViewsNative(width: Int, height: Int): FloatArray
    private external fun detailCompleteNative(box: FloatArray, view: FloatArray, width: Int, height: Int): Boolean
    private external fun iouNative(a: FloatArray, b: FloatArray): Float
    private external fun reprojectMaskNative(mask: ByteBuffer, output: ByteBuffer, local: FloatArray, scene: FloatArray, view: FloatArray)

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
