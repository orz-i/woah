"""Privacy pixel-ownership contract from the 2026-10-18 PLK110 audit.

The five source-space records below are anonymized geometry/area metadata,
not reconstructed video pixels. Kotlin tests hold the synthetic pixel cases;
these Python tests verify the trace math and regression contract on Macs without
an Android JDK/SDK. A device export and pixel review remain mandatory.
"""
from __future__ import annotations

from dataclasses import dataclass
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1]
NATIVE = ROOT / "mobile/packages/dance_native/android/src/main/kotlin/art/gaoge/dance/engine"
RESOLVER = (NATIVE / "privacy/PrivacyOcclusionResolver.kt").read_text(encoding="utf-8")
EXPORT = (NATIVE / "pipeline/ExportPipeline.kt").read_text(encoding="utf-8")


@dataclass(frozen=True)
class CoverageEvent:
    pts_us: int
    selected_index: int
    unselected_index: int
    raw_area: int
    effective_area: int
    selected_bbox: tuple[float, float, float, float]
    unselected_bbox: tuple[float, float, float, float]
    # Quantized source-space production ID 2 bbox, in 1/16 source pixels.
    observed_id2_bbox_q16: tuple[int, int, int, int]


# Both exports in the same import session have identical source-space frames.
# 810x1440: job_1792334883122; 1280x720: job_1792335324731.
RISK_FRAMES = (
    CoverageEvent(30833333, 2, 3, 79, 58,
                  (559.5117, 272.42188, 607.9883, 448.82812),
                  (566.25, 251.09375, 721.25, 588.90625),
                  (9060, 4018, 11540, 9423)),
    CoverageEvent(34900000, 3, 2, 134, 92,
                  (585.0, 250.15625, 682.5, 496.09375),
                  (528.75, 282.96875, 658.75, 615.78125),
                  (8460, 4528, 10540, 9853)),
    CoverageEvent(39733333, 2, 3, 82, 59,
                  (504.64844, 280.39062, 589.10156, 500.85938),
                  (518.8281, 293.59375, 691.7969, 623.90625),
                  (8301, 4698, 11069, 9983)),
    CoverageEvent(39766666, 2, 3, 142, 100,
                  (503.125, 280.625, 590.625, 503.125),
                  (523.59375, 303.59375, 692.03125, 623.90625),
                  (8378, 4858, 11073, 9983)),
    CoverageEvent(39800000, 2, 3, 161, 129,
                  (503.39844, 281.40625, 591.60156, 504.84375),
                  (524.53125, 343.90625, 692.96875, 624.84375),
                  (8393, 5503, 11088, 9998)),
)


def iou(a, b):
    left, top = max(a[0], b[0]), max(a[1], b[1])
    right, bottom = min(a[2], b[2]), min(a[3], b[3])
    intersection = max(0.0, right - left) * max(0.0, bottom - top)
    union = ((a[2] - a[0]) * (a[3] - a[1]) +
             (b[2] - b[0]) * (b[3] - b[1]) - intersection)
    return intersection / union if union > 0 else 0.0


def unknown_pixel_may_be_carved(*, fresh_owner, corroborating_observation,
                                selected_byte, unselected_byte, in_eroded_core,
                                same_source_mapping=True):
    """Numerical replay of Kotlin corroboratedUnknownForegroundCore's gates."""
    return (
        fresh_owner and corroborating_observation and in_eroded_core and
        same_source_mapping and (unselected_byte - selected_byte) / 255 >= 0.10
    )


class PrivacyCoverageContractTest(unittest.TestCase):
    def test_five_warnings_are_source_space_small_unknown_targets_over_id2(self):
        self.assertEqual([round(e.pts_us / 1e6, 3) for e in RISK_FRAMES],
                         [30.833, 34.9, 39.733, 39.767, 39.8])
        for event in RISK_FRAMES:
            self.assertLess(event.effective_area / event.raw_area, 0.85)
            self.assertGreaterEqual(event.effective_area / event.raw_area, 0.65)
            observed = tuple(coord / 16 for coord in event.observed_id2_bbox_q16)
            self.assertGreater(iou(observed, event.unselected_bbox), 0.995)

    def test_unknown_mask_privacy_wins_without_all_three_pixel_signals(self):
        for event in RISK_FRAMES:
            self.assertFalse(unknown_pixel_may_be_carved(
                fresh_owner=True, corroborating_observation=True,
                selected_byte=220, unselected_byte=215,
                in_eroded_core=True
            ), event.pts_us)
            self.assertFalse(unknown_pixel_may_be_carved(
                fresh_owner=False, corroborating_observation=True,
                selected_byte=160, unselected_byte=255,
                in_eroded_core=True
            ), event.pts_us)
            self.assertFalse(unknown_pixel_may_be_carved(
                fresh_owner=True, corroborating_observation=False,
                selected_byte=160, unselected_byte=255,
                in_eroded_core=True
            ), event.pts_us)
            self.assertFalse(unknown_pixel_may_be_carved(
                fresh_owner=True, corroborating_observation=True,
                selected_byte=160, unselected_byte=255,
                in_eroded_core=True, same_source_mapping=False
            ), event.pts_us)
            self.assertTrue(unknown_pixel_may_be_carved(
                fresh_owner=True, corroborating_observation=True,
                selected_byte=160, unselected_byte=255,
                in_eroded_core=True
            ), event.pts_us)

    def test_kotlin_proof_uses_real_provenance_not_identity_promotion(self):
        self.assertIn("conservativeUnknownTarget", RESOLVER)
        self.assertIn("corroboratedUnknownForegroundCore(", RESOLVER)
        self.assertIn("observed.observedThisFrame && observed.mask != null", RESOLVER)
        self.assertIn("!selectedPersonIds.contains(observed.id)", RESOLVER)
        self.assertIn("UNKNOWN_FOREGROUND_OBSERVED_BBOX_IOU = 0.90f", RESOLVER)
        self.assertIn("TrackManager.computeBBoxIoU(observed.bbox, candBbox)", RESOLVER)
        self.assertIn("candidateFreshEvidence?.selectionClass == PrivacySelectionClass.UNSELECTED", RESOLVER)
        self.assertIn("(unselected - selected) / 255f >= MIN_STALE_RAW_PROBABILITY_ADVANTAGE", RESOLVER)
        self.assertIn("mask.samplingRect != selectedMask.samplingRect", RESOLVER)
        self.assertIn("unknownForegroundCore ?: continue", RESOLVER)
        self.assertNotIn("FollowReservedLostRecovery", RESOLVER)

    def test_real_coverage_warnings_are_not_hidden_or_weakened(self):
        self.assertIn("if (coverageRatio < 0.65f)", RESOLVER)
        self.assertIn('"PRIVACY_SEVERE_UNDERCOVERAGE"', RESOLVER)
        self.assertIn("else if (coverageRatio < 0.85f)", RESOLVER)
        self.assertIn('"PRIVACY_COVERAGE_DROP"', RESOLVER)
        self.assertLess(0.60, 0.65)
        self.assertLess(0.80, 0.85)

    def test_postfix_device_observed_roots_have_selected_fresh_geometry(self):
        # Quantized 1/16px bboxes from the 3255595 Android 17 logs.
        # 39.733s: verified protected ID 1, and independent protagonist ID 2.
        protected = (8074, 4486, 9426, 8014)
        fresh_selected = (8074, 4486, 9426, 8014)
        fresh_unselected = (8301, 4698, 11069, 9983)
        self.assertGreaterEqual(iou(protected, fresh_selected), 0.90)
        self.assertLess(iou(protected, fresh_unselected), 0.90)
        # 45.933s: observed protected ID 0 is still selected while leaving
        # the bottom of the 720px source frame. On 45.967s it is unobserved
        # and guarded by the existing fallback instead of a class handoff.
        exiting_root = (2559, 9186, 4601, 11514)
        self.assertEqual(iou(exiting_root, exiting_root), 1.0)
        self.assertGreater(exiting_root[3] / 16, 719)

    def test_observed_selected_fresh_class_contradiction_aborts_export(self):
        self.assertIn("observedSelectedWithoutFreshClassCoverage(", RESOLVER)
        self.assertIn("OBSERVED_SELECTED_FRESH_BBOX_IOU = 0.90f", RESOLVER)
        self.assertIn("HungarianSolver.match(", RESOLVER)
        self.assertIn("assignment.unmatchedTracks.map", RESOLVER)
        self.assertIn("it.selectionClass == PrivacySelectionClass.SELECTED", RESOLVER)
        self.assertIn("mask.mapper != selectedMask.mapper", RESOLVER)
        self.assertIn("if (preferFreshPrivacyClassPrimary)", EXPORT)
        gate = EXPORT.index('event = "FULL_BODY_FRESH_CLASS_CONTRADICTION_FAIL_CLOSED"')
        render = EXPORT.index('profiler.recordStage("renderEffects")')
        self.assertLess(gate, render)
        self.assertIn("DanceNativeException.EXPORT_FAILED", EXPORT[gate:render])
        self.assertIn("uncoveredObservedIds", EXPORT[gate:render])

    def test_missing_observed_full_body_mask_fails_export_before_source_render(self):
        self.assertIn("observedSelectedWithoutMasks(", RESOLVER)
        gate = EXPORT.index('event = "FULL_BODY_PRIVACY_MASK_MISSING_FAIL_CLOSED"')
        render = EXPORT.index('profiler.recordStage("renderEffects")')
        self.assertLess(gate, render)
        self.assertIn("DanceNativeException.EXPORT_FAILED", EXPORT[gate:render])
        self.assertIn("follow = request.follow.copy(enabled = false)", EXPORT)
        self.assertLess(
            EXPORT.index("renderProtected(request.cropClarityScale ?: 1.0)"),
            EXPORT.index("val visualCrop = reframeFollower.cropForFrame(")
        )


if __name__ == "__main__":
    unittest.main()
