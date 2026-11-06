package art.gaoge.dance.engine.privacy

import art.gaoge.dance.engine.core.FrameKernel
import art.gaoge.dance.engine.inference.FloatRect

data class PersonBboxTranslation(val dx: Float, val dy: Float)

/** Adapter to the shared edge-agreement translation estimator. */
object PersonBboxMotionEstimator {
    fun estimate(previous: FloatRect, current: FloatRect): PersonBboxTranslation {
        val output = FrameKernel.anchorTranslation(previous, current)
        return PersonBboxTranslation(output[0], output[1])
    }
}
