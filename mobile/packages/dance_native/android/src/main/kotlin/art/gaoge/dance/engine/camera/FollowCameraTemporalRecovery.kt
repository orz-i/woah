package art.gaoge.dance.engine.camera

import art.gaoge.dance.engine.inference.FloatRect
import art.gaoge.dance.engine.tracking.TrackState
import art.gaoge.dance.engine.tracking.TrackedPerson
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Conservative, CAMERA-ONLY recovery when a selected follow target's track was
 * removed. The privacy TrackManager, selected masks and identity-protected roots
 * are deliberately untouched. A single overlapping detection can never handoff.
 *
 * The source-space bbox of the last observed target is kept as a fixed anchor;
 * after the original strict handoff window, at least three *real observations*
 * of one uniquely matching track across time are required. Never acquire a
 * known protected/privacy-selected person or someone observed separately from
 * the target just before it vanished.
 */
class FollowCameraTemporalRecovery {
    data class Handoff(
        val track: TrackedPerson,
        val ageUs: Long,
        val observations: Int,
        val observationSpanUs: Long,
        val anchorIou: Float
    )

    companion object {
        const val MIN_AGE_US = 1_100_000L
        const val MAX_AGE_US = 2_600_000L
        const val MAX_VOTE_GAP_US = 850_000L
        const val MIN_OBSERVATION_SPAN_US = 250_000L
        const val MIN_OBSERVATIONS = 3
        const val DISTINCT_COOCCURRENCE_WINDOW_US = 800_000L
        private const val MIN_IOU = 0.32f
        private const val MAX_CENTER_DISTANCE_RATIO = 0.30f
        private const val MIN_WIDTH_RATIO = 0.55f
        private const val MAX_WIDTH_RATIO = 1.85f
        private const val MIN_HEIGHT_RATIO = 0.58f
        private const val MAX_HEIGHT_RATIO = 1.95f
        private const val MIN_SCORE_MARGIN = 0.14f
        private const val MIN_TRACK_CONFIDENCE = 0.45f
    }

    private data class Candidate(
        val track: TrackedPerson,
        val score: Float,
        val iou: Float
    )

    private var voteTrackId: Int? = null
    private var voteCount = 0
    private var firstVotePtsUs = -1L
    private var lastVotePtsUs = -1L
    private var lastVoteBox: FloatRect? = null

    var pendingTrackId: Int? = null
        private set
    var pendingObservations: Int = 0
        private set

    fun reset() {
        voteTrackId = null
        voteCount = 0
        firstVotePtsUs = -1L
        lastVotePtsUs = -1L
        lastVoteBox = null
        pendingTrackId = null
        pendingObservations = 0
    }

    fun observe(
        anchor: FloatRect?,
        rootTrackId: Int,
        lastTargetPtsUs: Long?,
        ptsUs: Long,
        tracks: List<TrackedPerson>,
        excludedTrackIds: Set<Int>
    ): Handoff? {
        if (anchor == null || lastTargetPtsUs == null || !valid(anchor)) {
            reset()
            return null
        }
        val ageUs = ptsUs - lastTargetPtsUs
        if (ageUs !in MIN_AGE_US..MAX_AGE_US) {
            reset()
            return null
        }

        val allCandidates = tracks.asSequence()
            .filter { track ->
                track.id != rootTrackId && track.id !in excludedTrackIds &&
                    track.observedThisFrame && track.state == TrackState.ACTIVE &&
                    track.confidence.isFinite() && track.confidence >= MIN_TRACK_CONFIDENCE &&
                    valid(track.bbox)
            }
            .mapNotNull { track -> candidate(anchor, track) }
            .sortedByDescending { it.score }
            .toList()
        val best = allCandidates.firstOrNull()
        val next = allCandidates.getOrNull(1)
        // A crossed or ambiguous person is never a camera identity handoff.
        if (best == null || (next != null && best.score - next.score < MIN_SCORE_MARGIN)) {
            // Keep previous evidence only across a short interval with no observed
            // candidate; a different/multiple plausible observed candidate resets it.
            if (next != null || ptsUs - lastVotePtsUs > MAX_VOTE_GAP_US) reset()
            return null
        }
        if (voteTrackId != best.track.id ||
            firstVotePtsUs < 0L || lastVotePtsUs < 0L ||
            ptsUs <= lastVotePtsUs || ptsUs - lastVotePtsUs > MAX_VOTE_GAP_US ||
            !continuousMotion(best.track.bbox, ptsUs)
        ) {
            voteTrackId = best.track.id
            voteCount = 1
            firstVotePtsUs = ptsUs
        } else {
            voteCount++
        }
        lastVotePtsUs = ptsUs
        lastVoteBox = best.track.bbox
        pendingTrackId = best.track.id
        pendingObservations = voteCount
        val span = ptsUs - firstVotePtsUs
        if (voteCount < MIN_OBSERVATIONS || span < MIN_OBSERVATION_SPAN_US) return null
        val result = Handoff(best.track, ageUs, voteCount, span, best.iou)
        reset()
        return result
    }

    private fun candidate(anchor: FloatRect, track: TrackedPerson): Candidate? {
        val box = track.bbox
        val ax = maxOf(anchor.width, 1f)
        val ay = maxOf(anchor.height, 1f)
        val referenceDim = maxOf(ax, ay)
        val dx = box.centerX - anchor.centerX
        val dy = box.centerY - anchor.centerY
        val distanceRatio = sqrt(dx * dx + dy * dy) / referenceDim
        val widthRatio = box.width / ax
        val heightRatio = box.height / ay
        val iou = intersectOverUnion(anchor, box)
        if (iou < MIN_IOU || distanceRatio > MAX_CENTER_DISTANCE_RATIO ||
            widthRatio !in MIN_WIDTH_RATIO..MAX_WIDTH_RATIO ||
            heightRatio !in MIN_HEIGHT_RATIO..MAX_HEIGHT_RATIO
        ) return null
        val score = iou - distanceRatio * 0.30f -
            abs(widthRatio - 1f) * 0.08f - abs(heightRatio - 1f) * 0.06f
        return Candidate(track, score, iou)
    }

    private fun continuousMotion(box: FloatRect, ptsUs: Long): Boolean {
        val old = lastVoteBox ?: return true
        val dtSeconds = (ptsUs - lastVotePtsUs) / 1_000_000f
        if (dtSeconds <= 0f) return false
        val dx = box.centerX - old.centerX
        val dy = box.centerY - old.centerY
        // A camera candidate cannot teleport farther than ~one prior person
        // height / 0.33s. This is only a continuity *gate*, not a motion model.
        val maxDistance = maxOf(old.height, box.height, 1f) * (0.22f + 2.4f * dtSeconds)
        return sqrt(dx * dx + dy * dy) <= maxDistance
    }

    private fun intersectOverUnion(a: FloatRect, b: FloatRect): Float {
        val dx = minOf(a.right, b.right) - maxOf(a.left, b.left)
        val dy = minOf(a.bottom, b.bottom) - maxOf(a.top, b.top)
        if (dx <= 0f || dy <= 0f) return 0f
        val inter = dx * dy
        return inter / (a.width * a.height + b.width * b.height - inter).coerceAtLeast(1e-6f)
    }

    private fun valid(box: FloatRect): Boolean =
        box.left.isFinite() && box.top.isFinite() && box.right.isFinite() &&
            box.bottom.isFinite() && box.width > 0f && box.height > 0f
}
