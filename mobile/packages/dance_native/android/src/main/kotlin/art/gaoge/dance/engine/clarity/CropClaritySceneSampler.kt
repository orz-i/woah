package art.gaoge.dance.engine.clarity

import java.nio.ByteBuffer
import kotlin.math.max

/**
 * Debug-only online selector. Five temporal pairs have a separate budget;
 * the other seven pairs are reserved across thirds of the source timeline.
 * No decoded pixels are retained and all scene signals are review heuristics.
 */
class CropClaritySceneSampler(private val nominalFrames: Int) {
    data class LumaEvidence(val mean: Float, val p10: Int, val p90: Int, val brightFraction: Float) {
        val contrast: Int get() = p90 - p10
        val meaningful: Boolean get() = mean >= 12f && p90 >= 30 && brightFraction >= 0.025f
    }

    data class Signals(
        val luma: LumaEvidence?,
        val lumaAgeFrames: Int,
        val cropProtectedCount: Int,
        val cropProtectedAreaFraction: Float,
        val cropOverlap: Float,
        val protagonistMotion: Float,
        val sourceProtectedCount: Int
    )

    data class Choice(
        val frame: Int,
        val kind: String,
        val samplePhase: String,
        val burstIndex: Int?,
        val contrast: Int,
        val lumaMean: Float,
        val overlap: Float,
        val motion: Float,
        val protectedCount: Int,
        val cropProtectedCount: Int,
        val cropProtectedAreaFraction: Float
    )

    companion object {
        const val SELECTION_POLICY = "online_timeline_reservation_and_crop_visibility_v3"
        const val MAX_PAIRS = 12
        const val TEMPORAL_FRAMES = 5
        const val MAX_LUMA_AGE_FRAMES = 16
        const val LUMA_PROBE_STRIDE = 12
        private const val MIN_EVENT_GAP = 12
        private val SCENE_BUDGET_BY_PHASE = intArrayOf(2, 2, 3) // early, middle, final third

        /** Samples only aggregates from the existing inference readback, never saves RGB. */
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
    private val phaseSceneCounts = IntArray(3)
    private var lastEventFrame = -MIN_EVENT_GAP
    private var burstUntilFrame = -1
    private var burstIndex = 0
    private var burstStarted = false
    private var blackOrEmptyFrames = 0
    private var staleLumaFrames = 0
    private var eligibleCropPrivacyFrames = 0

    val screeningSummary: Map<String, Int> get() = mapOf(
        "black_or_empty_frames" to blackOrEmptyFrames,
        "stale_luma_frames" to staleLumaFrames,
        "eligible_crop_privacy_frames" to eligibleCropPrivacyFrames
    )
    val choices: List<Choice> get() = chosen.toList()
    val counts: Map<String, Int> get() = kinds.toMap()
    val phaseCounts: Map<String, Int> get() = mapOf(
        "early" to phaseSceneCounts[0],
        "middle" to phaseSceneCounts[1],
        "late" to phaseSceneCounts[2]
    )

    /** Timeline boundaries are based on the nominal *source* frame count, not samples already taken. */
    fun phaseForFrame(frame: Int): String = when (phaseIndex(frame)) {
        0 -> "early"
        1 -> "middle"
        else -> "late"
    }

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
        if (signals.cropProtectedCount > 0) eligibleCropPrivacyFrames++
        val motion = signals.protagonistMotion >= 0.012f
        val contrast = luma.contrast >= 96

        if (burstUntilFrame >= frame) {
            if (chosen.lastOrNull()?.frame == frame - 1 && burstIndex < TEMPORAL_FRAMES - 1) {
                burstIndex++
                return add(frame, "temporal_burst", burstIndex, signals, luma)
            }
            // A dropped/dark frame ends the burst. Never label non-adjacent frames contiguous.
            burstUntilFrame = -1
        }
        if (frame - lastEventFrame < MIN_EVENT_GAP) return null

        // Five independent slots: a fast protagonist or an eventual middle-third
        // content fallback may open the sequence, without spending late scene quota.
        if (!burstStarted && frame <= nominalFrames.coerceAtLeast(1) - TEMPORAL_FRAMES - 2 &&
            (motion || frame > nominalFrames.coerceAtLeast(1) / 3)
        ) {
            burstStarted = true
            burstIndex = 0
            burstUntilFrame = frame + TEMPORAL_FRAMES - 1
            lastEventFrame = frame
            return add(frame, "temporal_burst", 0, signals, luma)
        }

        val phase = phaseIndex(frame)
        val scenesHere = phaseSceneCounts[phase]
        if (scenesHere >= SCENE_BUDGET_BY_PHASE[phase]) return null
        val slotOpens = earliestSceneFrame(phase, scenesHere)
        if (frame < slotOpens) return null
        // Give final-crop privacy candidates a short first opportunity in
        // each reserved slot; otherwise fall back to visible general content.
        // This stays online and finite even when all privacy boxes are off-crop.
        val privacyWait = (nominalFrames.coerceAtLeast(1) / 40).coerceIn(2, 12)
        if (signals.cropProtectedCount == 0 && frame < slotOpens + privacyWait) return null

        val kind = when {
            signals.cropProtectedCount > 0 && signals.cropOverlap >= 0.08f -> "crop_privacy_overlap"
            signals.cropProtectedCount > 0 -> "crop_privacy_visible"
            phase == 2 -> "late_content"
            contrast -> "high_contrast"
            motion -> "fast_motion"
            else -> "content_anchor"
        }
        phaseSceneCounts[phase]++
        lastEventFrame = frame
        return add(frame, kind, null, signals, luma)
    }

    private fun earliestSceneFrame(phase: Int, slot: Int): Int {
        val length = nominalFrames.coerceAtLeast(1)
        val fraction = when (phase) {
            0 -> if (slot == 0) 0.0 else 0.15
            1 -> if (slot == 0) 1.0 / 3.0 else 0.50
            else -> when (slot) {
                0 -> 2.0 / 3.0
                1 -> 0.78
                else -> 0.88
            }
        }
        val byFraction = kotlin.math.ceil(length * fraction).toInt().coerceAtLeast(1)
        val floor = when (phase) {
            0 -> 1
            1 -> length / 3 + 1
            else -> (2L * length / 3L).toInt() + 1
        }
        return max(floor, byFraction)
    }

    private fun phaseIndex(frame: Int): Int {
        val last = nominalFrames.coerceAtLeast(1)
        return when {
            frame.toLong() * 3L <= last -> 0
            frame.toLong() * 3L <= 2L * last -> 1
            else -> 2
        }
    }

    private fun add(frame: Int, kind: String, index: Int?, signals: Signals, luma: LumaEvidence): Choice {
        val choice = Choice(
            frame = frame,
            kind = kind,
            samplePhase = phaseForFrame(frame),
            burstIndex = index,
            contrast = luma.contrast,
            lumaMean = luma.mean,
            overlap = signals.cropOverlap.coerceIn(0f, 1f),
            motion = signals.protagonistMotion.coerceAtLeast(0f),
            protectedCount = signals.sourceProtectedCount,
            cropProtectedCount = signals.cropProtectedCount,
            cropProtectedAreaFraction = signals.cropProtectedAreaFraction.coerceIn(0f, 1f)
        )
        chosen += choice
        kinds[kind] = (kinds[kind] ?: 0) + 1
        return choice
    }
}
