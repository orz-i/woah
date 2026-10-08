package art.gaoge.dance.engine.clarity

import java.nio.ByteBuffer
import kotlin.math.max

/**
 * Debug-only, bounded, online scene sampling. No image is retained in memory:
 * content statistics come from the already-read 640x640 inference RGBA buffer;
 * motion/occlusion evidence comes from existing production tracking. All values
 * are heuristics for *choosing images to review*, never privacy decisions.
 */
class CropClaritySceneSampler(private val nominalFrames: Int) {
    data class LumaEvidence(val mean: Float, val p10: Int, val p90: Int, val brightFraction: Float) {
        val contrast: Int get() = p90 - p10
        val meaningful: Boolean get() = mean >= 12f && p90 >= 30 && brightFraction >= 0.025f
    }

    data class Signals(
        val luma: LumaEvidence?,
        val lumaAgeFrames: Int,
        val protectedOverlap: Float,
        val protectedVisible: Boolean,
        val protagonistMotion: Float,
        val protectedCount: Int
    )

    data class Choice(
        val frame: Int,
        val kind: String,
        val burstIndex: Int?,
        val contrast: Int,
        val lumaMean: Float,
        val overlap: Float,
        val motion: Float,
        val protectedCount: Int
    )

    companion object {
        const val MAX_PAIRS = 12
        const val TEMPORAL_FRAMES = 5
        const val MAX_LUMA_AGE_FRAMES = 16
        const val LUMA_PROBE_STRIDE = 12
        private const val MIN_EVENT_GAP = 12

        /** Samples only aggregates from the preexisting inference buffer; no raw data persisted. */
        fun sampleLuma(rgba: ByteBuffer, width: Int = 640, height: Int = 640): LumaEvidence? {
            if (width <= 0 || height <= 0 || rgba.limit().toLong() < width.toLong() * height * 4L) return null
            val levels = IntArray(16)
            var count = 0
            var total = 0L
            var bright = 0
            val strideY = max(1, height / 32)
            val strideX = max(1, width / 32)
            for (y in strideY / 2 until height step strideY) {
                for (x in strideX / 2 until width step strideX) {
                    val base = (y * width + x) * 4
                    val r = rgba.get(base).toInt() and 0xff
                    val g = rgba.get(base + 1).toInt() and 0xff
                    val b = rgba.get(base + 2).toInt() and 0xff
                    val lum = (54 * r + 183 * g + 19 * b + 128) shr 8
                    levels[(lum / 16).coerceIn(0, 15)]++
                    total += lum
                    if (lum >= 32) bright++
                    count++
                }
            }
            if (count == 0) return null
            fun percentile(percent: Int): Int {
                val rank = (count * percent / 100).coerceAtLeast(1)
                var running = 0
                for (i in levels.indices) {
                    running += levels[i]
                    if (running >= rank) return i * 16 + 8
                }
                return 248
            }
            return LumaEvidence(total.toFloat() / count, percentile(10), percentile(90), bright.toFloat() / count)
        }
    }

    private val chosen = mutableListOf<Choice>()
    private val kinds = mutableMapOf<String, Int>()
    private var lastEventFrame = -MIN_EVENT_GAP
    private var burstUntilFrame = -1
    private var burstIndex = 0
    private var burstStarted = false
    private var blackOrEmptyFrames = 0
    private var staleLumaFrames = 0

    val screeningSummary: Map<String, Int> get() = mapOf(
        "black_or_empty_frames" to blackOrEmptyFrames,
        "stale_luma_frames" to staleLumaFrames
    )
    val choices: List<Choice> get() = chosen.toList()
    val counts: Map<String, Int> get() = kinds.toMap()

    fun consider(frame: Int, signals: Signals): Choice? {
        if (frame < 1 || chosen.size >= MAX_PAIRS) return null
        val luma = signals.luma
        if (luma == null || signals.lumaAgeFrames !in 0..MAX_LUMA_AGE_FRAMES) {
            staleLumaFrames++
            return null
        }
        if (!luma.meaningful) {
            blackOrEmptyFrames++
            return null
        }
        val overlap = signals.protectedVisible && signals.protectedOverlap >= 0.08f
        val motion = signals.protagonistMotion >= 0.012f
        val contrast = luma.contrast >= 96

        if (burstUntilFrame >= frame) {
            if (chosen.lastOrNull()?.frame == frame - 1) {
                burstIndex++
                return add(frame, "temporal_burst", burstIndex, signals, luma)
            }
            // A dropped/non-content frame must not create a falsely contiguous run.
            burstUntilFrame = -1
        }
        if (frame - lastEventFrame < MIN_EVENT_GAP) return null

        val category = when {
            overlap && (kinds["privacy_overlap"] ?: 0) < 2 -> "privacy_overlap"
            motion && !burstStarted && frame < nominalFrames - TEMPORAL_FRAMES -> "temporal_burst"
            contrast && (kinds["high_contrast"] ?: 0) < 2 -> "high_contrast"
            motion && (kinds["fast_motion"] ?: 0) < 2 -> "fast_motion"
            !burstStarted && frame >= nominalFrames.coerceAtLeast(1) / 3 &&
                frame < nominalFrames - TEMPORAL_FRAMES -> "temporal_burst"
            (kinds["content_anchor"] ?: 0) == 0 -> "content_anchor"
            else -> return null
        }
        if (category == "temporal_burst") {
            burstStarted = true
            burstIndex = 0
            burstUntilFrame = frame + TEMPORAL_FRAMES - 1
        }
        lastEventFrame = frame
        return add(frame, category, if (category == "temporal_burst") 0 else null, signals, luma)
    }

    private fun add(frame: Int, kind: String, index: Int?, signals: Signals, luma: LumaEvidence): Choice {
        val choice = Choice(
            frame, kind, index, luma.contrast, luma.mean,
            signals.protectedOverlap.coerceIn(0f, 1f),
            signals.protagonistMotion.coerceAtLeast(0f), signals.protectedCount
        )
        chosen += choice
        kinds[kind] = (kinds[kind] ?: 0) + 1
        return choice
    }
}
