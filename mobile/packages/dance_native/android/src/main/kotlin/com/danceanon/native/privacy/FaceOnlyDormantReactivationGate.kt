package com.danceanon.native.privacy

/**
 * Requires repeated current-frame motion evidence before a previously dormant
 * FACE_ONLY track can resume rendering.
 *
 * Each sample has already passed TrackManager's independent motion-only gate.
 * This class deliberately does not add another identity threshold or retain a
 * segmentation mask. It only requires a second independently-qualified sample
 * for the same protected track inside the existing short body-motion bridge
 * window. Exact YOLO observation clears the pending probe immediately.
 */
internal class FaceOnlyDormantReactivationGate(
    private val maxConfirmationGapUs: Long = FaceOnlyDormancyPolicy.MAX_DIRECT_UNOBSERVED_AGE_US
) {
    data class Decision(
        val confirmedTrackIds: Set<Int>,
        val pendingTrackIds: Set<Int>
    )

    private val firstEvidencePtsUsByTrackId = mutableMapOf<Int, Long>()

    fun update(
        activeTrackIds: Set<Int>,
        dormantTrackIds: Set<Int>,
        observedTrackIds: Set<Int>,
        freshMotionTrackIds: Set<Int>,
        ptsUs: Long
    ): Decision {
        firstEvidencePtsUsByTrackId.keys.retainAll(activeTrackIds)
        observedTrackIds.forEach(firstEvidencePtsUsByTrackId::remove)
        firstEvidencePtsUsByTrackId.keys.removeAll { !dormantTrackIds.contains(it) }

        val confirmed = linkedSetOf<Int>()
        dormantTrackIds.forEach { trackId ->
            if (!activeTrackIds.contains(trackId) || observedTrackIds.contains(trackId)) {
                firstEvidencePtsUsByTrackId.remove(trackId)
                return@forEach
            }

            val firstPtsUs = firstEvidencePtsUsByTrackId[trackId]
            if (!freshMotionTrackIds.contains(trackId)) {
                if (
                    firstPtsUs != null &&
                    (ptsUs < firstPtsUs || ptsUs - firstPtsUs > maxConfirmationGapUs)
                ) {
                    firstEvidencePtsUsByTrackId.remove(trackId)
                }
                return@forEach
            }

            if (
                firstPtsUs != null &&
                ptsUs > firstPtsUs &&
                ptsUs - firstPtsUs <= maxConfirmationGapUs
            ) {
                confirmed += trackId
                firstEvidencePtsUsByTrackId.remove(trackId)
            } else {
                firstEvidencePtsUsByTrackId[trackId] = ptsUs
            }
        }

        return Decision(
            confirmedTrackIds = confirmed,
            pendingTrackIds = firstEvidencePtsUsByTrackId.keys.toCollection(linkedSetOf())
        )
    }
}
