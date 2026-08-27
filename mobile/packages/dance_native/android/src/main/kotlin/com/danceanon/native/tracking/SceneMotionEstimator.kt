package com.danceanon.native.tracking

import com.danceanon.native.diagnostics.NativeDiagnostics
import com.danceanon.native.inference.PersonDetection
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

data class SceneMotion(
    val dx: Float,
    val dy: Float,
    val inlierCount: Int,
    val confidence: Float
)

object SceneMotionEstimator {

    fun estimateSceneMotion(
        tracks: List<InternalTrack>,
        detections: List<PersonDetection>,
        config: TrackingConfig
    ): SceneMotion {
        if (tracks.size < config.minSceneMotionInliers || detections.size < config.minSceneMotionInliers) {
            return SceneMotion(0f, 0f, 0, 0f)
        }

        data class ResidualPair(
            val trackId: Int,
            val detIndex: Int,
            val dx: Float,
            val dy: Float
        )

        val pairs = mutableListOf<ResidualPair>()

        for (t in tracks) {
            val pBox = t.currentPredictedBbox
            val pW = max(1f, pBox.width)
            val pH = max(1f, pBox.height)
            val pAspect = pW / pH

            for ((dIdx, d) in detections.withIndex()) {
                val dBox = d.bbox
                val dW = max(1f, dBox.width)
                val dH = max(1f, dBox.height)
                val dAspect = dW / dH

                // Reject morphologically incompatible pairs
                val heightRatio = min(pH, dH) / max(pH, dH)
                val aspectDiff = abs(pAspect - dAspect)
                if (heightRatio < 0.5f || aspectDiff > 0.4f) continue

                val dx = dBox.centerX - pBox.centerX
                val dy = dBox.centerY - pBox.centerY

                if (abs(dx) <= config.maxGlobalShift && abs(dy) <= config.maxGlobalShift) {
                    pairs.add(ResidualPair(t.id, dIdx, dx, dy))
                }
            }
        }

        if (pairs.isEmpty()) {
            return SceneMotion(0f, 0f, 0, 0f)
        }

        // RANSAC clustering on residual vectors with directional coherence
        var bestInliers = emptyList<ResidualPair>()
        var bestTrackCount = 0

        val tol = config.sceneMotionTolerance

        for (hypothesis in pairs) {
            val inliers = mutableListOf<ResidualPair>()
            val seenTracks = mutableSetOf<Int>()
            val seenDets = mutableSetOf<Int>()

            for (p in pairs) {
                if (seenTracks.contains(p.trackId) || seenDets.contains(p.detIndex)) continue

                // Directional sign consistency check for non-trivial shifts
                if (abs(hypothesis.dx) > 10f && p.dx * hypothesis.dx < 0f) continue
                if (abs(hypothesis.dy) > 10f && p.dy * hypothesis.dy < 0f) continue

                val dist = sqrt((p.dx - hypothesis.dx) * (p.dx - hypothesis.dx) + (p.dy - hypothesis.dy) * (p.dy - hypothesis.dy))
                if (dist <= tol) {
                    inliers.add(p)
                    seenTracks.add(p.trackId)
                    seenDets.add(p.detIndex)
                }
            }

            if (seenTracks.size > bestTrackCount) {
                bestTrackCount = seenTracks.size
                bestInliers = inliers
            }
        }

        if (bestTrackCount >= config.minSceneMotionInliers) {
            val medianDx = median(bestInliers.map { it.dx })
            val medianDy = median(bestInliers.map { it.dy })

            // Filter out negligible sub-pixel jitter
            if (abs(medianDx) < 5f && abs(medianDy) < 5f) {
                return SceneMotion(0f, 0f, 0, 0f)
            }

            val confidence = bestTrackCount.toFloat() / tracks.size.toFloat()

            NativeDiagnostics.event(
                level = "INFO",
                component = "SceneMotionEstimator",
                event = "SCENE_MOTION_ESTIMATED",
                fields = mapOf(
                    "dx" to medianDx,
                    "dy" to medianDy,
                    "inliers" to bestTrackCount,
                    "confidence" to confidence,
                    "track_count" to tracks.size,
                    "det_count" to detections.size
                )
            )

            return SceneMotion(medianDx, medianDy, bestTrackCount, confidence)
        }

        if (pairs.isNotEmpty() && bestTrackCount < config.minSceneMotionInliers) {
            NativeDiagnostics.event(
                level = "DEBUG",
                component = "SceneMotionEstimator",
                event = "SCENE_MOTION_REJECTED",
                fields = mapOf(
                    "reason" to "INSUFFICIENT_INLIERS",
                    "best_inliers" to bestTrackCount,
                    "required" to config.minSceneMotionInliers
                )
            )
        }

        return SceneMotion(0f, 0f, 0, 0f)
    }

    private fun median(values: List<Float>): Float {
        if (values.isEmpty()) return 0f
        val sorted = values.sorted()
        val mid = sorted.size / 2
        return if (sorted.size % 2 == 1) {
            sorted[mid]
        } else {
            (sorted[mid - 1] + sorted[mid]) / 2f
        }
    }
}
