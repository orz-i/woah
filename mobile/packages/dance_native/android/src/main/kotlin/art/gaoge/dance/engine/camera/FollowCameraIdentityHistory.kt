package art.gaoge.dance.engine.camera

import art.gaoge.dance.engine.tracking.TrackedPerson

/**
 * CAMERA-ONLY negative identity evidence for a single export.
 *
 * A track observed in the same frame as the selected protagonist cannot later
 * be assumed to be the same protagonist merely because their boxes overlap.
 * Even if the two boxes came from a duplicate detection, identity is ambiguous,
 * so the safer camera behavior is HOLD. Track IDs are session-scoped; this
 * history is never used by TrackManager or the privacy compositor.
 */
class FollowCameraIdentityHistory {
    private val coObservedTrackIds = mutableSetOf<Int>()

    fun recordSelectedObservation(
        selectedId: Int,
        selectedObserved: Boolean,
        tracks: List<TrackedPerson>
    ) {
        if (!selectedObserved) return
        tracks.forEach { track ->
            if (track.id != selectedId && track.observedThisFrame) {
                coObservedTrackIds += track.id
            }
        }
    }

    fun excludedIds(): Set<Int> = coObservedTrackIds.toSet()
}
