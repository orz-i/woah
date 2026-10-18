"""Fail-closed source-path regression for camera follow LOST recovery.

The companion Kotlin JVM tests execute the three-frame gate. On the Mac without
Android JDK, this test verifies that production actually wires the gate into
the reserved-detection path without relaxing protected privacy association.
"""
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
NATIVE = ROOT / "mobile/packages/dance_native/android/src/main/kotlin/art/gaoge/dance/engine"
TRACKER = (NATIVE / "tracking/TrackManager.kt").read_text()
GATE = (NATIVE / "tracking/FollowReservedLostRecovery.kt").read_text()
EXPORT = (NATIVE / "pipeline/ExportPipeline.kt").read_text()


class FollowReservedLostRecoveryContract(unittest.TestCase):
    def test_export_only_enables_follow_target(self):
        self.assertIn("trackManager.setFollowCameraTrackId(", EXPORT)
        self.assertIn("if (request.follow.enabled) request.follow.targetPersonId?.toInt() else null", EXPORT)

    def test_guard_excludes_all_identity_and_privacy_selected_tracks(self):
        section = TRACKER[TRACKER.index("val followLostTrack ="):TRACKER.index("val reservedFollowEvidence =")]
        for expected in (
            "FollowReservedLostRecovery.isEligibleTarget(",
            "state = track.state",
            "framesSinceLastObservation = track.framesSinceLastObservation",
            "hasObservedMask = track.lastObservedMask != null",
            "identityProtected = id in protectedTrackIds",
            "privacySelected = id in privacySelectedTrackIds",
            "facePrivacySelected = id in facePrivacySelectedTrackIds",
            "belongsToAnyGroup = occlusionGroups.any",
        ):
            self.assertIn(expected, section)
        gate = GATE[GATE.index("fun isEligibleTarget("):GATE.index("fun isStrictEvidence(")]
        for expected in (
            "state == TrackState.LOST",
            "framesSinceLastObservation in 1..MAX_OBSERVATION_AGE_FRAMES",
            "hasObservedMask",
            "!identityProtected && !privacySelected && !facePrivacySelected",
            "!belongsToAnyGroup",
        ):
            self.assertIn(expected, gate)

    def test_no_release_to_global_and_only_original_track_can_claim(self):
        section = TRACKER[TRACKER.index("// 6. Ordinary LOST recovery."):TRACKER.index("// 7. Filter out REMOVED tracks")]
        self.assertIn("val owners = reservedGroupOwnerTrackIdsByDetectionIndex[dIdx].orEmpty()", section)
        self.assertIn("owners.isNotEmpty() && target.id !in owners", section)
        self.assertIn(".singleOrNull()", section)
        self.assertIn("FollowReservedLostRecovery.isStrictEvidence(it)", section)
        self.assertIn("confirmedFollowDetectionIndex to detections[confirmedFollowDetectionIndex]", section)
        self.assertIn("detIndex == confirmedFollowDetectionIndex && candTrack.id != followCameraTrackId", section)
        self.assertIn("candTrack.id == followCameraTrackId && detIndex != confirmedFollowDetectionIndex", section)
        self.assertIn("if (detIndex == confirmedFollowDetectionIndex) continue", section)
        self.assertNotIn("reservedGroupDetectionIndices.remove(", section)
        self.assertNotIn("setIdentityProtectedTrackIds(", section)
        self.assertNotIn("setPrivacySelectedTrackIds(", section)

    def test_gate_requires_geometry_mask_uniqueness_and_multiple_real_frames(self):
        for expected in (
            "MAX_OBSERVATION_AGE_FRAMES = 6",
            "MIN_VOTES = 3",
            "MIN_OBSERVATION_SPAN_US = 50_000L",
            "MIN_PREDICTED_IOU = 0.72f",
            "MIN_LAST_OBSERVED_IOU = 0.70f",
            "MIN_MASK_IOU = 0.20f",
            "MAX_OTHER_IOU = 0.20f",
            "MAX_VOTE_GAP_US = 100_000L",
            "competingEvidenceBelow(evidence, MAX_OTHER_IOU)",
            "competingEvidenceBelow(evidence, ANCHORED_MAX_OTHER_IOU)",
            "competingEvidenceBelow(evidence, CONTINUE_MAX_OTHER_IOU)",
            "ptsUs - lastPtsUs <= MAX_VOTE_GAP_US",
        ):
            self.assertIn(expected, GATE)

    def test_drifting_prediction_needs_independent_anchor_and_three_votes(self):
        for expected in (
            "ANCHORED_MIN_PREDICTED_IOU = 0.60f",
            "ANCHORED_MIN_LAST_OBSERVED_IOU = 0.85f",
            "ANCHORED_MIN_MASK_IOU = 0.55f",
            "ANCHORED_MIN_CONFIDENCE = 0.80f",
            "ANCHORED_MAX_OTHER_IOU = 0.15f",
            "MIN_CONTINUITY_IOU = 0.70f",
            "evidence.predictedIoU >= ANCHORED_MIN_PREDICTED_IOU",
            "evidence.lastObservedIoU >= ANCHORED_MIN_LAST_OBSERVED_IOU",
            "evidence.maskIoU >= ANCHORED_MIN_MASK_IOU",
            "return ordinary || anchored",
        ):
            self.assertIn(expected, GATE)
        self.assertIn("followReservedLostRecovery.observe(reservedFollowEvidence, timestampUs)", TRACKER)

    def test_single_frame_continuation_only_after_actual_verified_commit(self):
        for expected in (
            "lastFollowReservedCommitPtsUs = timestampUs",
            "observedOnPreviousFrame = followLostTrack.observedOnPreviousFrame",
            "FollowReservedLostRecovery.isRecentVerifiedContinuation(",
            "FollowReservedLostRecovery.isContinuationEvidence(it)",
            "continuingFollowEvidence?.detectionIndex ?: confirmedFromVotes",
            "FOLLOW_RESERVED_CONTINUATION_COMMIT",
        ):
            self.assertIn(expected, TRACKER)
        for expected in (
            "lastConfirmedPtsUs >= 0L",
            "ptsUs > lastConfirmedPtsUs",
            "ptsUs - lastConfirmedPtsUs <= MAX_VOTE_GAP_US",
            "observedOnPreviousFrame",
            "CONTINUE_MIN_CONFIDENCE = 0.60f",
            "CONTINUE_MIN_LAST_OBSERVED_IOU = 0.78f",
            "CONTINUE_MIN_MASK_IOU = 0.40f",
            "CONTINUE_MAX_OTHER_IOU = 0.15f",
        ):
            self.assertIn(expected, GATE)
        # The continuation proof must be revoked by a prediction-only frame,
        # by changing targets, or by resetting the tracker.
        self.assertGreaterEqual(TRACKER.count("lastFollowReservedCommitPtsUs = -1L"), 4)

    def test_remains_independent_from_camera_handoff(self):
        self.assertIn("reframeIdentityHistory.recordSelectedObservation(", EXPORT)
        self.assertIn("reframeTemporalRecovery.observe(", EXPORT)
        self.assertIn("excludedTrackIds = excludedCameraProxyIds", EXPORT)


if __name__ == "__main__":
    unittest.main()
