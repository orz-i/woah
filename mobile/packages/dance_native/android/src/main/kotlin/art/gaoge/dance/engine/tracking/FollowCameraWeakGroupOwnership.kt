package art.gaoge.dance.engine.tracking

/**
 * Narrow negative group-ownership check for a previously confirmed, unprotected
 * camera-follow root. An aged occlusion-group box can graze a different person's
 * detection and reserve it indefinitely. Such weak geometry alone is not proof
 * that the group owns the detection.
 *
 * This never changes identity association thresholds. The result is a permission
 * for the ORIGINAL root to compete in ordinary Global Hungarian, not a commit;
 * no other track may claim the released detection and an uncommitted detection
 * must still be reserved against ordinary new-track/LOST recovery.
 */
internal object FollowCameraWeakGroupOwnership {
    data class Evidence(
        val recentlyVerifiedFollowCommit: Boolean,
        val rootActiveAndObservedPreviousFrame: Boolean,
        val rootOutsideGroup: Boolean,
        val rootNotPrivacyProtected: Boolean,
        val unassignedByGroupHungarian: Boolean,
        val detectorHasMask: Boolean,
        val confidence: Float,
        val rootPredictedIoU: Float,
        val rootLastObservedIoU: Float,
        val rootMaskIoU: Float,
        val groupStrongestIoU: Float,
        val otherTrackStrongestIoU: Float,
        val otherDetectionStrongestIoU: Float
    )

    private const val MAX_WEAK_GROUP_IOU = 0.18f
    private const val MAX_OTHER_TRACK_IOU = 0.18f
    private const val MAX_OTHER_DETECTION_IOU = 0.15f

    fun canYield(e: Evidence): Boolean {
        if (!e.recentlyVerifiedFollowCommit || !e.rootActiveAndObservedPreviousFrame ||
            !e.rootOutsideGroup || !e.rootNotPrivacyProtected ||
            !e.unassignedByGroupHungarian || !e.detectorHasMask
        ) return false
        if (!e.confidence.isFinite() || e.confidence < 0f ||
            !e.rootPredictedIoU.isFinite() || e.rootPredictedIoU < 0f ||
            !e.rootLastObservedIoU.isFinite() || e.rootLastObservedIoU < 0f ||
            !e.rootMaskIoU.isFinite() || e.rootMaskIoU < 0f ||
            !e.groupStrongestIoU.isFinite() || e.groupStrongestIoU < 0f ||
            !e.otherTrackStrongestIoU.isFinite() || e.otherTrackStrongestIoU < 0f ||
            !e.otherDetectionStrongestIoU.isFinite() || e.otherDetectionStrongestIoU < 0f
        ) return false
        if (e.groupStrongestIoU >= MAX_WEAK_GROUP_IOU ||
            e.otherTrackStrongestIoU >= MAX_OTHER_TRACK_IOU ||
            e.otherDetectionStrongestIoU >= MAX_OTHER_DETECTION_IOU
        ) return false

        // A detector-confidence dip with otherwise exceptionally consistent
        // recent body and mask must not let a stale group's slight edge grazing
        // steal its ownership. No motion-only or location-only shortcut.
        val nearExactAnchor = e.confidence >= 0.55f &&
            e.rootPredictedIoU >= 0.62f &&
            e.rootLastObservedIoU >= 0.85f &&
            e.rootMaskIoU >= 0.55f &&
            e.otherTrackStrongestIoU < 0.15f

        // When the detector expands the observed body box, require a stronger
        // confidence signal alongside a still-recognizable body and mask.
        val resizedBody = e.confidence >= 0.70f &&
            e.rootPredictedIoU >= 0.48f &&
            e.rootLastObservedIoU >= 0.72f &&
            e.rootMaskIoU >= 0.50f
        return nearExactAnchor || resizedBody
    }
}
