package art.gaoge.dance.engine.comparison

import java.util.concurrent.atomic.AtomicLong
import kotlin.math.abs
import kotlin.math.ceil

/**
 * Uses Media3's planned frame release time, NOT callback arrival time and NOT
 * evidence that pixels reached the physical display. External capture remains
 * mandatory before accepting a playback backend.
 */
internal class ComparisonFrameProbe {
    enum class Side { TEACHER, LEARNER }

    data class Frame(
        val sourcePtsUs: Long,
        val plannedReleaseNs: Long
    )

    data class PairSample(
        val teacherPtsUs: Long,
        val learnerPtsUs: Long,
        val signedMediaErrorUs: Long,
        val plannedReleaseSkewUs: Long
    )

    data class Summary(
        val teacherFrames: Int,
        val learnerFrames: Int,
        val pairedFrames: Int,
        val p50AbsMediaErrorUs: Long?,
        val p95AbsMediaErrorUs: Long?,
        val maxAbsMediaErrorUs: Long?,
        val meanSignedMediaErrorUs: Double?,
        val p95PlannedReleaseSkewUs: Long?,
        val examples: List<PairSample>
    )

    private val epoch = AtomicLong(0)
    private val teacher = ArrayList<Frame>()
    private val learner = ArrayList<Frame>()
    // 60fps x 10min = 36k frames per side; keep the full M0 endurance run.
    private val limit = 50_000

    @Synchronized
    fun startNewEpoch(): Long {
        teacher.clear()
        learner.clear()
        return epoch.incrementAndGet()
    }

    @Synchronized
    fun add(side: Side, generation: Long, ptsUs: Long, plannedReleaseNs: Long) {
        if (generation != epoch.get() || plannedReleaseNs <= 0 || ptsUs < 0) return
        val destination = if (side == Side.TEACHER) teacher else learner
        if (destination.size < limit) destination.add(Frame(ptsUs, plannedReleaseNs))
    }

    @Synchronized
    fun snapshot(offsetUs: Long, startupExclusionNs: Long = 1_000_000_000L): Summary {
        val ts = teacher.sortedBy { it.plannedReleaseNs }
        val ls = learner.sortedBy { it.plannedReleaseNs }
        if (ts.isEmpty() || ls.isEmpty()) {
            return Summary(ts.size, ls.size, 0, null, null, null, null, null, emptyList())
        }

        // Ignore initial seek/startup (when independent decoders may be catching up).
        val readyNs = maxOf(ts.first().plannedReleaseNs, ls.first().plannedReleaseNs) +
            startupExclusionNs
        val learnerUsable = ls.filter { it.plannedReleaseNs >= readyNs }
        val teacherUsable = ts.filter { it.plannedReleaseNs >= readyNs }
        val matches = ArrayList<PairSample>()
        var nextLearnerIndex = 0
        val maximumPairingSkewNs = 25_000_000L // not an acceptance criterion

        for (t in teacherUsable) {
            if (nextLearnerIndex >= learnerUsable.size) break
            // Match distinct frames by expected *display* time, not player position.
            while (nextLearnerIndex + 1 < learnerUsable.size &&
                abs(learnerUsable[nextLearnerIndex + 1].plannedReleaseNs -
                    t.plannedReleaseNs) <
                abs(learnerUsable[nextLearnerIndex].plannedReleaseNs -
                    t.plannedReleaseNs)
            ) {
                nextLearnerIndex++
            }
            val l = learnerUsable[nextLearnerIndex]
            val skewNs = abs(l.plannedReleaseNs - t.plannedReleaseNs)
            if (skewNs <= maximumPairingSkewNs) {
                matches.add(PairSample(
                    teacherPtsUs = t.sourcePtsUs,
                    learnerPtsUs = l.sourcePtsUs,
                    signedMediaErrorUs = l.sourcePtsUs - t.sourcePtsUs - offsetUs,
                    plannedReleaseSkewUs = skewNs / 1000L
                ))
                nextLearnerIndex++ // one-to-one
            }
        }

        if (matches.isEmpty()) {
            return Summary(ts.size, ls.size, 0, null, null, null, null, null, emptyList())
        }
        val absoluteErrors = matches.map { abs(it.signedMediaErrorUs) }.sorted()
        val skew = matches.map { it.plannedReleaseSkewUs }.sorted()
        return Summary(
            teacherFrames = ts.size,
            learnerFrames = ls.size,
            pairedFrames = matches.size,
            p50AbsMediaErrorUs = percentile(absoluteErrors, 0.5),
            p95AbsMediaErrorUs = percentile(absoluteErrors, 0.95),
            maxAbsMediaErrorUs = absoluteErrors.last(),
            meanSignedMediaErrorUs = matches.map { it.signedMediaErrorUs.toDouble() }.average(),
            p95PlannedReleaseSkewUs = percentile(skew, 0.95),
            examples = matches.takeLast(24)
        )
    }

    private fun percentile(sorted: List<Long>, probability: Double): Long =
        sorted[(ceil(probability * sorted.size).toInt() - 1).coerceIn(0, sorted.lastIndex)]
}
