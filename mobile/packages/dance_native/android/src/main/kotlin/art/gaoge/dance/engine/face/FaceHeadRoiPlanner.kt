package art.gaoge.dance.engine.face

import art.gaoge.dance.engine.inference.FloatRect

data class FaceHeadRoiPlan(
    val sourceRect: FloatRect,
    val anchorX: Float,
    val anchorY: Float,
    val outputSize: Int = 256
)

/**
 * Plans the source-resolution square crop used by the face locator.
 *
 * The constants are intentionally derived from the real-device "upper" ROI
 * benchmark, which preserved enough source detail for all four distant people
 * in the current fixture. The crop is shifted rather than truncated at frame
 * edges so the downstream 256x256 detector input remains square.
 */
object FaceHeadRoiPlanner {
    const val OUTPUT_SIZE = 256

    fun plan(personBbox: FloatRect, frameWidth: Int, frameHeight: Int): FaceHeadRoiPlan? {
        val value=art.gaoge.dance.engine.core.FrameKernel.faceHeadPlanNative(
            floatArrayOf(personBbox.left,personBbox.top,personBbox.right,personBbox.bottom),frameWidth,frameHeight
        ) ?: return null
        return FaceHeadRoiPlan(FloatRect(value[0],value[1],value[2],value[3]),value[4],value[5],OUTPUT_SIZE)
    }
}
