"""Synthetic adversarial risk matrix for a separate, prompted segmentation model.

No original video, private screenshots, decoded frames or model weights.
Every mask below is constructed from integer rectangles in ephemeral memory.
"""
from __future__ import annotations

from dataclasses import replace
import json
from pathlib import Path
import unittest

from tools.diagnostics.independent_instance_mask_gate import (
    EVIDENCE_SCOPE, IndependentEvidence, SOURCE_SPACE, evaluate,
)

WIDTH, HEIGHT = 32, 24
PIXELS = WIDTH * HEIGHT
PTS = 39_800_000


def rect(
    left: int, top: int, right: int, bottom: int,
    *, value: int = 255, width: int = WIDTH, height: int = HEIGHT
) -> bytes:
    result = bytearray(width * height)
    for y in range(max(0, top), min(height, bottom)):
        for x in range(max(0, left), min(width, right)):
            result[y * width + x] = value
    return bytes(result)


def evidence(**changes) -> IndependentEvidence:
    selected = rect(23, 11, 29, 20)
    ground_truth = rect(23, 11, 29, 20)
    default = dict(
        width=WIDTH,
        height=HEIGHT,
        source_pts_us=PTS,
        candidate_pts_us=PTS,
        coordinate_space=SOURCE_SPACE,
        independent_model_family="synthetic_independent",
        independent_model_id="fixture-programmed-separate-object",
        protagonist_root_id=2,
        prompt_root_id=2,
        confirmed_previous_roots=(2, 2),
        confirmed_previous_pts_us=(PTS - 66_666, PTS - 33_333),
        conservative_unknown_mask=rect(5, 4, 28, 22),
        independent_protagonist_mask=rect(7, 7, 22, 20),
        protected_selected_roots=frozenset({1}),
        observed_selected_roots=frozenset({1}),
        observed_selected_masks={1: selected},
        independent_selected_masks={1: selected},
        selected_bbox_by_root={1: (21, 9, 30, 22)},
        independently_verified_selected_census=True,
        synthetic_truth_protected_mask=ground_truth,
    )
    default.update(changes)
    return IndependentEvidence(**default)


class IndependentInstanceGateTest(unittest.TestCase):
    def test_clear_independent_person_candidate_still_has_no_export_authority(self):
        result = evaluate(evidence())
        self.assertEqual(result.status, "SYNTHETIC_NO_FALSE_CLEAR_NOT_VIDEO_ACCEPTED")
        self.assertGreater(result.hypothetical_clear_pixels, 0)
        self.assertGreater(result.protected_box_veto_pixels, 0)
        self.assertEqual(result.synthetic_false_clear_pixels, 0)
        summary = result.scalars()
        self.assertEqual(summary["privacy_visual_acceptance"], "NOT_PROVEN")
        self.assertEqual(summary["pixel_carve_authorization"], "NEVER")
        self.assertEqual(summary["evidence_scope"], EVIDENCE_SCOPE)

    def test_ambiguous_selected_bbox_vetoes_when_selected_masks_are_empty(self):
        # Undersegmented selected person still has independently observed bbox.
        blank = bytes(PIXELS)
        result = evaluate(evidence(
            observed_selected_masks={1: blank},
            independent_selected_masks={1: blank},
        ))
        self.assertGreater(result.protected_box_veto_pixels, 0)
        self.assertEqual(result.synthetic_false_clear_pixels, 0)

    def test_protected_source_mask_veto_extends_beyond_its_detector_bbox(self):
        protected_extension = rect(12, 10, 16, 15)
        result = evaluate(evidence(
            observed_selected_masks={1: protected_extension},
            independent_selected_masks={1: bytes(PIXELS)},
            selected_bbox_by_root={1: (25, 15, 31, 23)},
            synthetic_truth_protected_mask=protected_extension,
        ))
        self.assertGreater(result.selected_mask_veto_pixels, 0)
        self.assertEqual(result.synthetic_false_clear_pixels, 0)

    def test_newly_selected_person_reappears_where_prompted_protagonist_was(self):
        # Critical falsifier: a user-supplied "complete" census could still be
        # wrong. A model's perfect high confidence on the wrong person does NOT
        # prove pixel ownership. The evaluator must expose the false-clear risk.
        overlooked_protected_person = rect(10, 10, 17, 17)
        result = evaluate(evidence(
            observed_selected_masks={1: rect(27, 10, 30, 20)},
            independent_selected_masks={1: rect(27, 10, 30, 20)},
            selected_bbox_by_root={1: (27, 9, 31, 22)},
            synthetic_truth_protected_mask=overlooked_protected_person,
        ))
        self.assertEqual(result.status, "SYNTHETIC_FALSE_CLEAR_BLOCKS_MODEL")
        self.assertGreater(result.synthetic_false_clear_pixels, 0)
        self.assertEqual(result.scalars()["pixel_carve_authorization"], "NEVER")

    def test_identical_clothing_swap_is_not_disambiguated_by_point_prompt(self):
        # Pixel-perfect same-color/texture impostor: a prompted object mask
        # offers no intrinsic, trustworthy root-ID certificate.
        impostor_at_center = rect(11, 11, 19, 19)
        result = evaluate(evidence(
            observed_selected_masks={1: rect(29, 10, 31, 19)},
            independent_selected_masks={1: rect(29, 10, 31, 19)},
            selected_bbox_by_root={1: (28, 8, 32, 22)},
            synthetic_truth_protected_mask=impostor_at_center,
        ))
        self.assertEqual(result.status, "SYNTHETIC_FALSE_CLEAR_BLOCKS_MODEL")
        self.assertGreater(result.synthetic_false_clear_pixels, 0)

    def test_96_automatic_adversarial_crossings_expose_false_census_claims(self):
        # Sweep a deterministic matrix of protected-person intrusions into the
        # foreground, while a stale or wrong selected bbox sits elsewhere.
        # This exercises 96 independent synthetic layouts with zero media IO.
        false_alerts = 0
        for variant in range(96):
            # 96 distinct positions on a 10-by-10 offset grid (not merely
            # different test iterations over repeated coordinates).
            x = 8 + variant % 10
            y = 8 + variant // 10
            selected_truth = rect(x, y, x + 3, y + 4)
            result = evaluate(evidence(
                observed_selected_masks={1: rect(29, 10, 31, 20)},
                independent_selected_masks={1: rect(29, 10, 31, 20)},
                selected_bbox_by_root={1: (28, 7, 32, 23)},
                synthetic_truth_protected_mask=selected_truth,
            ))
            self.assertEqual(result.scalars()["pixel_carve_authorization"], "NEVER")
            self.assertNotEqual(result.scalars()["privacy_visual_acceptance"], "PASS")
            if result.synthetic_false_clear_pixels:
                self.assertEqual(result.status, "SYNTHETIC_FALSE_CLEAR_BLOCKS_MODEL")
                false_alerts += 1
        self.assertGreaterEqual(false_alerts, 80)

    def test_truly_current_selected_bbox_vetoes_crossing_person(self):
        # If the independent protected census does observe the crossing,
        # conservative bbox rejection blocks that region rather than carving.
        selected_here = rect(11, 11, 19, 19)
        result = evaluate(evidence(
            observed_selected_masks={1: selected_here},
            independent_selected_masks={1: selected_here},
            selected_bbox_by_root={1: (10, 9, 21, 21)},
            synthetic_truth_protected_mask=selected_here,
        ))
        self.assertGreater(result.protected_box_veto_pixels, 0)
        self.assertEqual(result.synthetic_false_clear_pixels, 0)

    def test_low_confidence_or_fully_disoccluded_model_keeps_privacy(self):
        for candidate in (bytes(PIXELS), rect(7, 7, 22, 20, value=225)):
            result = evaluate(evidence(independent_protagonist_mask=candidate))
            self.assertEqual(result.status, "NO_INDEPENDENT_SAFE_CANDIDATE_KEEP_PRIVACY")
            self.assertEqual(result.hypothetical_clear_pixels, 0)

    def test_hairlike_one_pixel_fine_support_is_not_eroded_into_fake_proof(self):
        thin = rect(8, 8, 9, 20)
        result = evaluate(evidence(independent_protagonist_mask=thin))
        self.assertEqual(result.hypothetical_clear_pixels, 0)

    def test_untrusted_selected_census_payload_does_not_crash(self):
        for bad in (
            {"protected_selected_roots": None},
            {"protected_selected_roots": [1]},
            {"observed_selected_roots": None},
            {"observed_selected_masks": None},
            {"independent_selected_masks": None},
            {"selected_bbox_by_root": None},
            {"independently_verified_selected_census": 1},
        ):
            self.assertEqual(
                evaluate(evidence(**bad)).status,
                "INCOMPLETE_SELECTED_OCCUPANCY_EVIDENCE",
            )

    def test_missing_current_selected_census_must_fail_closed(self):
        invalid = evidence(independently_verified_selected_census=False)
        result = evaluate(invalid)
        self.assertEqual(result.status, "INCOMPLETE_SELECTED_OCCUPANCY_EVIDENCE")
        self.assertEqual(result.hypothetical_clear_pixels, 0)

    def test_selected_identity_must_have_current_observed_negative_evidence(self):
        result = evaluate(evidence(observed_selected_roots=frozenset()))
        self.assertEqual(result.status, "INCOMPLETE_SELECTED_OCCUPANCY_EVIDENCE")

    def test_missing_independent_negative_mask_rejects_even_with_bbox(self):
        result = evaluate(evidence(independent_selected_masks={}))
        self.assertEqual(result.status, "INCOMPLETE_SELECTED_OCCUPANCY_EVIDENCE")

    def test_missing_observed_selected_mask_rejects(self):
        result = evaluate(evidence(observed_selected_masks={}))
        self.assertEqual(result.status, "INCOMPLETE_SELECTED_OCCUPANCY_EVIDENCE")

    def test_missing_selected_bbox_rejects(self):
        result = evaluate(evidence(selected_bbox_by_root={}))
        self.assertEqual(result.status, "INCOMPLETE_SELECTED_OCCUPANCY_EVIDENCE")

    def test_unmodeled_extra_selected_person_rejects(self):
        result = evaluate(evidence(protected_selected_roots=frozenset({1, 3})))
        self.assertEqual(result.status, "INCOMPLETE_SELECTED_OCCUPANCY_EVIDENCE")

    def test_invalid_selected_mask_lengths_and_bbox_are_rejected(self):
        for bad in (
            {"observed_selected_masks": {1: bytes(1)}},
            {"independent_selected_masks": {1: bytes(PIXELS + 1)}},
            {"selected_bbox_by_root": {1: (20, 9, 19, 21)}},
            {"selected_bbox_by_root": {1: (-1, 0, 20, 21)}},
            {"selected_bbox_by_root": {1: (12, 8, 33, 23)}},
        ):
            self.assertEqual(
                evaluate(evidence(**bad)).status, "INVALID_PROTECTED_GEOMETRY_OR_MASK"
            )

    def test_yolo_mask_alone_is_not_an_independent_model(self):
        for family in ("yolo11_seg", "mediapipe_image_segmenter", "none"):
            self.assertEqual(
                evaluate(evidence(independent_model_family=family)).status,
                "INDEPENDENT_MODEL_NOT_VERIFIED",
            )

    def test_candidate_model_identity_cannot_be_missing_or_malformed(self):
        for identity in ("   ", None, 42):
            self.assertEqual(
                evaluate(evidence(independent_model_id=identity)).status,
                "INDEPENDENT_MODEL_NOT_VERIFIED",
            )
        self.assertEqual(
            evaluate(evidence(independent_model_family=None)).status,
            "INDEPENDENT_MODEL_NOT_VERIFIED",
        )

    def test_protagonist_prompt_must_match_observed_root_not_nearest_person(self):
        for changes in (
            {"prompt_root_id": 1},
            {"confirmed_previous_roots": (2, 1)},
            {"protagonist_root_id": 1},
            {"confirmed_previous_roots": (2, 2, 2)},
        ):
            self.assertEqual(
                evaluate(evidence(**changes)).status,
                "PROTAGONIST_PROVENANCE_NOT_VERIFIED",
            )

    def test_source_pts_are_required_not_portrait_export_clock(self):
        for changes in (
            {"source_pts_us": 0},
            {"candidate_pts_us": PTS + 33_333},
            {"candidate_pts_us": PTS - 33_333},
        ):
            self.assertEqual(evaluate(evidence(**changes)).status, "SOURCE_PTS_MISMATCH")

    def test_stale_or_reversed_root_observations_fail_closed(self):
        for changes in (
            {"confirmed_previous_pts_us": (PTS - 2_000_000, PTS - 1_000_000)},
            {"confirmed_previous_pts_us": (PTS - 33_333, PTS - 66_666)},
            {"confirmed_previous_pts_us": (PTS, PTS)},
        ):
            self.assertEqual(evaluate(evidence(**changes)).status,
                             "PROTAGONIST_EVIDENCE_EXPIRED")

    def test_crop_or_coordinate_space_mismatch_rejects(self):
        for changes in (
            {"coordinate_space": "POST_CROP_PORTRAIT"},
            {"width": WIDTH + 1},
            {"height": HEIGHT - 1},
        ):
            outcome = evaluate(evidence(**changes))
            self.assertIn(outcome.status, (
                "INVALID_SOURCE_GEOMETRY",
                "MISSING_OR_INCOMPATIBLE_PROPOSAL_MASK",
            ))

    def test_invalid_truth_mask_is_not_misreported_as_safe(self):
        self.assertEqual(
            evaluate(evidence(synthetic_truth_protected_mask=bytes(1))).status,
            "INVALID_SYNTHETIC_TRUTH",
        )

    def test_no_synthetic_truth_remains_unlabeled_not_accepted(self):
        outcome = evaluate(evidence(synthetic_truth_protected_mask=None))
        self.assertEqual(outcome.status, "UNLABELED_HYPOTHESIS_NO_PRIVACY_ACCEPTANCE")
        self.assertIsNone(outcome.synthetic_false_clear_pixels)
        self.assertEqual(outcome.scalars()["privacy_visual_acceptance"], "NOT_PROVEN")

    def test_output_is_scalar_only_with_no_mask_indices_or_urls(self):
        outcome = evaluate(evidence())
        serialized = json.dumps(outcome.scalars())
        for disallowed in ("bbox_model", "pixels_xy", "rgba", "raw_mask",
                           "image_url", "selected_root_id", "private"):
            self.assertNotIn(disallowed, serialized)
        self.assertNotIn('"true"', serialized)
        self.assertNotIn('"privacy_visual_acceptance": "PASS"', serialized)

    def test_no_model_execution_files_or_mobile_renderer_link(self):
        text = (Path(__file__).parent / "independent_instance_mask_gate.py").read_text()
        for disallowed in (
            "import cv2", "import numpy", "from PIL", "torch", "Path(",
            "open(", "read_bytes(", "write_bytes(", "MediaCodec",
            "GlRenderer", "PrivacyOcclusionResolver",
        ):
            self.assertNotIn(disallowed, text)

    def test_46_second_stale_mask_fix_remains_separate(self):
        # Research may never reinterpret the 178-unobserved-frame root1 as a
        # selected person to re-cover the 46s ceiling: existing observation
        # expiry stays authoritative on the Android privacy path.
        root = Path(__file__).resolve().parents[2]
        resolver = (root / "mobile/packages/dance_native/android/src/main/kotlin"
                    "/art/gaoge/dance/engine/privacy/PrivacyOcclusionResolver.kt").read_text()
        self.assertIn(
            "person.framesSinceLastObservation <= maxFallbackObservationAgeFrames",
            resolver,
        )


if __name__ == "__main__":
    unittest.main()
