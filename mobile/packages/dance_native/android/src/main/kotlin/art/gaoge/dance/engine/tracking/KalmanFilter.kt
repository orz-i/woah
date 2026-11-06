package art.gaoge.dance.engine.tracking

import art.gaoge.dance.engine.core.FrameKernel
import art.gaoge.dance.engine.inference.FloatRect

/** DTO compatibility only. Prediction, covariance and gating execute in Rust. */
class KalmanFilter {
    val state = FloatArray(8)
    val covariance = Array(8) { FloatArray(8) }
    private val values = FloatArray(72)
    private val clock = longArrayOf(-1L)
    private fun step(operation: Int, bounds: FloatRect? = null, timestampUs: Long = -1L, factor: Float = 1f): FloatArray {
        state.copyInto(values)
        covariance.forEachIndexed { i, row -> row.copyInto(values, 8 + i * 8) }
        val result = FrameKernel.boxFilterStep(values, clock, operation, bounds, timestampUs, factor)
        values.copyInto(state, 0, 0, 8)
        covariance.forEachIndexed { i, row -> values.copyInto(row, 0, 8 + i * 8, 16 + i * 8) }
        return result
    }
    fun init(bbox: FloatRect, timestampUs: Long = -1L) { step(0, bbox, timestampUs) }
    fun predict(timestampUs: Long = -1L): FloatRect = step(1, timestampUs = timestampUs).rect()
    fun update(bbox: FloatRect, timestampUs: Long = -1L) { step(2, bbox, timestampUs) }
    fun dampenVelocity(factor: Float = 0.70f) { step(3, factor = factor) }
    fun gatingDistance(bbox: FloatRect): Float = step(4, bbox)[4]
    fun toBBox(): FloatRect = step(5).rect()
    private fun FloatArray.rect() = FloatRect(this[0], this[1], this[2], this[3])
    companion object {
        fun invert4x4(matrix: Array<FloatArray>): Array<FloatArray>? {
            require(matrix.size == 4 && matrix.all { it.size == 4 })
            val input = FloatArray(16)
            matrix.forEachIndexed { i, row -> row.copyInto(input, i * 4) }
            val output = FrameKernel.invertMatrix4(input) ?: return null
            return Array(4) { i -> output.copyOfRange(i * 4, i * 4 + 4) }
        }
    }
}
