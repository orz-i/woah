#!/usr/bin/env python3
"""Stage 4 observational-ambiguity witness for FULL_BODY pixel ownership.

Produces a deterministic, scalar-only *synthetic counterexample* for one
specific failure mode: two worlds share precisely the same YOLO/track,
independently prompted Mask, frame/PTS and protected-person observation data,
but a missed protected person occupies different true pixels.

No video, source image, model inference, mask export, model weights, tracking
runtime or privacy compositor. Nothing can authorize clearing protected pixels.
This is a falsification of an inference shortcut, NOT a privacy acceptance test.
"""
from __future__ import annotations

from dataclasses import replace
import json
from pathlib import Path
import sys

ROOT = Path(__file__).resolve().parents[2]
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))

from tools.diagnostics.independent_instance_mask_gate import (  # noqa: E402
    IndependentEvidence, SOURCE_SPACE, evaluate,
)

WIDTH = 32
HEIGHT = 24
RISK_PTS_US = (
    39_733_333, 39_766_666, 39_800_000,
    39_833_333, 39_866_666, 39_900_000,
)
REPORT_SCOPE = "STAGE4_PAIRED_INDISTINGUISHABLE_SYNTHETIC_WORLDS_ONLY"


def _rect(left: int, top: int, right: int, bottom: int) -> bytes:
    # Ephemeral synthetic truth, never included in report/serialized.
    plane = bytearray(WIDTH * HEIGHT)
    for y in range(top, bottom):
        for x in range(left, right):
            plane[y * WIDTH + x] = 255
    return bytes(plane)


def synthetic_worlds(pts_us: int) -> tuple[IndependentEvidence, IndependentEvidence]:
    if pts_us not in RISK_PTS_US:
        raise ValueError("Only six explicitly reviewed risk PTS are supported")
    remote_protected = _rect(29, 10, 31, 20)
    base = IndependentEvidence(
        width=WIDTH,
        height=HEIGHT,
        source_pts_us=pts_us,
        candidate_pts_us=pts_us,
        coordinate_space=SOURCE_SPACE,
        independent_model_family="synthetic_independent",
        independent_model_id="same-model-observations-in-two-worlds",
        protagonist_root_id=2,
        prompt_root_id=2,
        confirmed_previous_roots=(2, 2),
        confirmed_previous_pts_us=(pts_us - 66_666, pts_us - 33_333),
        conservative_unknown_mask=_rect(5, 4, 28, 22),
        independent_protagonist_mask=_rect(7, 7, 22, 20),
        protected_selected_roots=frozenset({1}),
        observed_selected_roots=frozenset({1}),
        observed_selected_masks={1: remote_protected},
        independent_selected_masks={1: remote_protected},
        selected_bbox_by_root={1: (28, 7, 32, 23)},
        # An assertion that the observation census is complete is NOT an
        # independently established physical guarantee; the witness deliberately
        # tests the security consequence of treating the assertion as proof.
        independently_verified_selected_census=True,
        synthetic_truth_protected_mask=None,
    )
    # Counterfactual truth A agrees with the observed protected person.
    safe = replace(base, synthetic_truth_protected_mask=remote_protected)
    # Truth B: protected person actually occupies the old protagonist region,
    # but all *available* observations remain exactly the same as truth A.
    # An occluded/missed person can have an incorrect visible-position census.
    missed = replace(
        base,
        synthetic_truth_protected_mask=_rect(10, 10, 17, 17),
    )
    return safe, missed


def evaluate_ambiguity() -> dict[str, object]:
    comparisons = []
    for pts_us in RISK_PTS_US:
        safe, missed = synthetic_worlds(pts_us)
        if replace(safe, synthetic_truth_protected_mask=None) != replace(
            missed, synthetic_truth_protected_mask=None
        ):
            raise AssertionError("observable evidence changed between synthetic worlds")
        good = evaluate(safe)
        bad = evaluate(missed)
        if (
            good.hypothetical_clear_pixels <= 0
            or good.hypothetical_clear_pixels != bad.hypothetical_clear_pixels
            or good.synthetic_false_clear_pixels != 0
            or bad.synthetic_false_clear_pixels is None
            or bad.synthetic_false_clear_pixels <= 0
            or good.scalars()["pixel_carve_authorization"] != "NEVER"
            or bad.scalars()["pixel_carve_authorization"] != "NEVER"
        ):
            raise AssertionError("synthetic counterexample contract invalid")
        comparisons.append((
            good.hypothetical_clear_pixels,
            bad.synthetic_false_clear_pixels,
        ))

    return {
        "schema": 1,
        "evidence_scope": REPORT_SCOPE,
        "synthetic_source_geometry": f"{WIDTH}x{HEIGHT}",
        "paired_world_count": len(comparisons),
        "observational_equality_all_pairs": True,
        "hypothetical_clear_pixels_per_world": sorted(set(c[0] for c in comparisons)),
        "safe_world_false_clear_pixels": 0,
        "missed_protected_world_false_clear_pixels_min": min(c[1] for c in comparisons),
        "missed_protected_world_false_clear_pixels_max": max(c[1] for c in comparisons),
        "counterexample_interpretation": (
            "same observed evidence but different protected ground-truth owners; "
            "an independent prompted Mask is not a validated protected-person census"
        ),
        "model_only_identity_ownership": "NOT_IDENTIFIABLE_FROM_AVAILABLE_EVIDENCE",
        "real_video_ground_truth_available": False,
        "real_video_privacy_acceptance": "NOT_PROVEN",
        "pixel_carve_authorization": "NEVER",
        "production_runtime_changed": False,
        "required_next_evidence": (
            "INDEPENDENT_CURRENT_PROTECTED_OCCUPANCY_AND_VISIBLE_PIXEL_GROUND_TRUTH"
        ),
    }


def main() -> None:
    print(json.dumps(evaluate_ambiguity(), sort_keys=True))


if __name__ == "__main__":
    main()
