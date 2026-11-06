package art.gaoge.dance.engine.tracking

import art.gaoge.dance.engine.inference.FloatRect
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

/** Frozen prediction-first native scoring, independent of the Rust implementation. */
internal object ReferenceAssociationScore {
    fun evaluate(v: FloatArray, age: Long, flags: Int, c: FloatArray): Float {
        val p = FloatRect(v[0], v[1], v[2], v[3]); val o = FloatRect(v[4], v[5], v[6], v[7])
        val d = FloatRect(v[8], v[9], v[10], v[11]); val bbox = TrackManager.computeBBoxIoU(p, d)
        val dx = d.centerX - p.centerX; val dy = d.centerY - p.centerY
        val distance = sqrt(dx * dx + dy * dy)
        val flexible = (flags and 3) >= 2 || (flags and 4) != 0
        val limit = if (flexible) max(p.width * 2.5f, p.height * 2f) else max(p.width * 1.5f, p.height * .8f)
        if (bbox <= 0 && distance > limit) return 0f
        val motion = max((1f - distance / limit).coerceIn(0f, 1f), (1f - v[13] / (c[4] * 2f)).coerceIn(0f, 1f))
        if ((flags and 3) == 2) {
            val mag = v[16] * v[16] + v[17] * v[17]
            val reliable = max(10f, max(o.width, o.height) * .08f)
            if (age <= 500_000 && mag >= reliable * reliable) {
                val progress = ((d.centerX - o.centerX) * v[16] + (d.centerY - o.centerY) * v[17]) / mag
                if (progress < .15f) return 0f
            }
        }
        fun direction(velocity: Float, delta: Float): Boolean? =
            if (abs(velocity) > 30f && abs(delta) > 10f) velocity * delta > 0 else null
        val x = direction(v[14], d.centerX - o.centerX); val y = direction(v[15], d.centerY - o.centerY)
        val direction = if (x == false || y == false) 0f else if (x == true || y == true) 1f else .5f
        val raw = c[0] * bbox + c[1] * v[12] + c[2] * motion + c[3] * direction
        val penalty = if ((flags and 24) == 24 && v[12] < .05f) .4f else 1f
        return (raw * penalty).coerceIn(0f, 1f)
    }
}
