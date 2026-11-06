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
    private external fun sceneScopeNative(roots: LongArray, body: LongArray, face: LongArray, hasHero: Boolean, hero: Long): Int
    private external fun compositorTargetsNative(selected: LongArray, covered: LongArray, explicit: LongArray): LongArray
    private external fun currentHeroIndexNative(boxes: FloatArray, observed: FloatArray?): Int
    private external fun boxFilterStepNative(values: FloatArray, clock: LongArray, operation: Int, bounds: FloatArray?, pts: Long, factor: Float): FloatArray
    private external fun invertMatrix4Native(values: FloatArray): FloatArray?
    private external fun letterboxNative(width: Int, height: Int, outputWidth: Int, outputHeight: Int): FloatArray
    private external fun normalizePixelsNative(input: ByteBuffer, width: Int, height: Int, rowStride: Int,
        channels: Int, red: Int, green: Int, blue: Int, flipRows: Boolean, flipColumns: Boolean,
        interleaved: Boolean, scale: Float, bias: Float, output: FloatArray)
    private external fun resizeRgbaNative(input: ByteBuffer, width: Int, height: Int, stride: Int,
        output: ByteBuffer, outputWidth: Int, outputHeight: Int, fill: Int)
    private external fun yuv420Native(y: ByteBuffer, u: ByteBuffer, v: ByteBuffer, metadata: IntArray, output: ByteBuffer)
    private external fun matchCostsNative(costs: FloatArray, rows: Int, columns: Int, maxCost: Float): IntArray
    private external fun matchRectsNative(rows: FloatArray, columns: FloatArray, maxCost: Float): IntArray
    private external fun followResetNative(values: FloatArray, clock: LongArray)
    private external fun followStepNative(values: FloatArray, clock: LongArray, target: FloatArray?, pts: Long,
        sourceAspect: Float, outputAspect: Float, zoom: Float, smooth: Float): FloatArray
    private external fun fitEvenRatioNative(width: Int, height: Int, maxHeight: Int, ratioWidth: Int, ratioHeight: Int): IntArray?
    private external fun maskBytesNative(input: FloatArray): ByteArray
    private external fun decodeInstancesNative(head: FloatArray, proto: FloatArray, spec: IntArray, transform: FloatArray,
        thresholds: FloatArray, detections: FloatArray, masks: ByteBuffer, metrics: LongArray): Int
    private external fun needsFaceProtectionNative(body: Boolean, face: Boolean): Boolean
    private external fun faceProtectionIdsNative(body: LongArray, face: LongArray): LongArray
    private external fun detailViewsNative(width: Int, height: Int): FloatArray
    private external fun detailCompleteNative(box: FloatArray, view: FloatArray, width: Int, height: Int): Boolean
    private external fun iouNative(a: FloatArray, b: FloatArray): Float
    private external fun reprojectMaskNative(mask: ByteBuffer, output: ByteBuffer, local: FloatArray, scene: FloatArray, view: FloatArray)

    fun needsFaceProtection(body: Boolean, face: Boolean): Boolean = needsFaceProtectionNative(body, face)

    fun matchCosts(costs: FloatArray, rows: Int, columns: Int, maxCost: Float): List<Pair<Int, Int>> =
        matchCostsNative(costs, rows, columns, maxCost).asList().chunked(2).map { it[0] to it[1] }
    fun matchRects(rows: List<FloatRect>, columns: List<FloatRect>, maxCost: Float): List<Pair<Int, Int>> {
        fun flatten(rects: List<FloatRect>): FloatArray = FloatArray(rects.size * 4).also { result ->
            rects.forEachIndexed { i, r -> r.values().copyInto(result, i * 4) }
        }
        return matchRectsNative(flatten(rows), flatten(columns), maxCost).asList().chunked(2).map { it[0] to it[1] }
    }

    fun followReset(values: FloatArray, clock: LongArray) = followResetNative(values, clock)
    fun followStep(values: FloatArray, clock: LongArray, target: FloatRect?, pts: Long,
        sourceAspect: Float, outputAspect: Float, zoom: Float, smooth: Float): FloatRect {
        val result = followStepNative(values, clock, target?.values(), pts, sourceAspect, outputAspect, zoom, smooth)
        return FloatRect(result[0], result[1], result[2], result[3])
    }
    fun fitEvenRatio(width: Int, height: Int, maxHeight: Int, ratioWidth: Int, ratioHeight: Int): Pair<Int, Int>? =
        fitEvenRatioNative(width, height, maxHeight, ratioWidth, ratioHeight)?.let { it[0] to it[1] }

    fun maskBytes(input: FloatArray): ByteArray = maskBytesNative(input)

    fun decodeInstances(head: FloatArray, proto: FloatArray, spec: IntArray, transform: FloatArray,
        thresholds: FloatArray, detections: FloatArray, masks: ByteBuffer, metrics: LongArray): Int =
        decodeInstancesNative(head, proto, spec, transform, thresholds, detections, masks, metrics)

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

    fun letterbox(width: Int, height: Int, outputWidth: Int, outputHeight: Int) = letterboxNative(width, height, outputWidth, outputHeight)
    const val SCENE_BODY = 1
    const val SCENE_FACE = 2
    const val SCENE_OTHER_FACES = 4
    fun sceneScope(roots: Set<Int>, body: Set<Int>, face: Set<Int>, hero: Int?): Int =
        sceneScopeNative(roots.ids(), body.ids(), face.ids(), hero != null, hero?.toLong() ?: 0)
    fun compositorTargets(selected: Set<Int>, covered: Set<Int>, explicit: Set<Int>): Set<Int> =
        compositorTargetsNative(selected.ids(), covered.ids(), explicit.ids()).map { it.toInt() }.toSet()
    fun currentHeroIndex(boxes: List<FloatRect>, observed: FloatRect?): Int =
        currentHeroIndexNative(boxes.flatMap { it.values().asList() }.toFloatArray(), observed?.values())
    private fun Set<Int>.ids() = map { it.toLong() }.toLongArray()
    fun boxFilterStep(values: FloatArray, clock: LongArray, operation: Int, bounds: FloatRect? = null, pts: Long = -1, factor: Float = 1f) =
        boxFilterStepNative(values, clock, operation, bounds?.values(), pts, factor)
    fun invertMatrix4(values: FloatArray) = invertMatrix4Native(values)
    fun yuv420(y: ByteBuffer, u: ByteBuffer, v: ByteBuffer, metadata: IntArray, output: ByteBuffer) = yuv420Native(y, u, v, metadata, output)

    fun normalizePixels(input: ByteBuffer, width: Int, height: Int, rowStride: Int, output: FloatArray,
        channels: Int = 4, red: Int = 0, green: Int = 1, blue: Int = 2,
        flipRows: Boolean = false, flipColumns: Boolean = false, interleaved: Boolean = false,
        scale: Float = 1f / 255f, bias: Float = 0f) =
        normalizePixelsNative(input, width, height, rowStride, channels, red, green, blue,
            flipRows, flipColumns, interleaved, scale, bias, output)

    fun resizeRgba(input: ByteBuffer, width: Int, height: Int, stride: Int, output: ByteBuffer,
        outputWidth: Int, outputHeight: Int, fill: Int = 114) =
        resizeRgbaNative(input, width, height, stride, output, outputWidth, outputHeight, fill)

    private fun FloatRect.values() = floatArrayOf(left, top, right, bottom)
    private fun ModelCoordinateMapper.values() = floatArrayOf(scale, padLeft, padTop, srcWidth.toFloat(), srcHeight.toFloat(), modelInputSize.toFloat(), protoSize.toFloat())
}
