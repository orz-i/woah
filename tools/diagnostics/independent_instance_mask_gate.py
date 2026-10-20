"""Synthetic-only, source-space gate for independently prompted instance masks.

No model execution, source-video decoding, image IO, serialized masks, or Android
compositor hooks. Results are *scalar risk measurements*, NEVER carve masks.
Even perfect synthetic results cannot prove real protected-person occupancy.
"""
from __future__ import annotations

from dataclasses import dataclass
from typing import Mapping, Optional, Tuple


# The detector already in Woah (YOLO11-seg) is NOT an independent second model.
MODEL_FAMILIES = frozenset(("mediapipe_interactive", "synthetic_independent"))
SOURCE_SPACE = "ORIGINAL_SOURCE_TOP_DOWN"
MIN_POSITIVE_CONFIDENCE = 240
UNKNOWN_MASK_THRESHOLD = 128
PROTECTED_MASK_THRESHOLD = 39
PROTECTED_MARGIN = 2
MAX_CURRENT_EVIDENCE_GAP_US = 100_000
EVIDENCE_SCOPE = "SYNTHETIC_MODEL_NEUTRAL_AGGREGATES_NO_FRAMES_NO_MASK_OUTPUT"


@dataclass(frozen=True)
class IndependentEvidence:
    width: int
    height: int
    source_pts_us: int
    candidate_pts_us: int
    coordinate_space: str
    independent_model_family: str
    independent_model_id: str
    protagonist_root_id: int
    prompt_root_id: int
    confirmed_previous_roots: Tuple[int, int]
    confirmed_previous_pts_us: Tuple[int, int]
    conservative_unknown_mask: bytes
    independent_protagonist_mask: bytes
    protected_selected_roots: frozenset[int]
    observed_selected_roots: frozenset[int]
    observed_selected_masks: Mapping[int, bytes]
    independent_selected_masks: Mapping[int, bytes]
    selected_bbox_by_root: Mapping[int, Tuple[int, int, int, int]]
    independently_verified_selected_census: bool
    synthetic_truth_protected_mask: Optional[bytes] = None


@dataclass(frozen=True)
class GateResult:
    status: str
    unknown_pixels: int = 0
    model_positive_pixels: int = 0
    unknown_positive_interior: int = 0
    protected_box_veto_pixels: int = 0
    selected_mask_veto_pixels: int = 0
    hypothetical_clear_pixels: int = 0
    synthetic_false_clear_pixels: Optional[int] = None
    synthetic_true_foreground_pixels: Optional[int] = None

    def scalars(self) -> dict:
        # Deliberately no pixel arrays, coordinates, images, mask hashes or
        # identity-rich metadata: this output can never be consumed as an
        # unmasking instruction or an output-video privacy certificate.
        return {
            "evidence_scope": EVIDENCE_SCOPE,
            "status": self.status,
            "unknown_selected_mask_pixels": self.unknown_pixels,
            "independent_model_positive_pixels": self.model_positive_pixels,
            "proposal_in_unknown_interior_pixels": self.unknown_positive_interior,
            "protected_bbox_veto_pixels": self.protected_box_veto_pixels,
            "selected_mask_veto_pixels": self.selected_mask_veto_pixels,
            "hypothetical_clear_pixels": self.hypothetical_clear_pixels,
            "synthetic_false_clear_pixels": self.synthetic_false_clear_pixels,
            "synthetic_true_foreground_pixels": self.synthetic_true_foreground_pixels,
            "privacy_visual_acceptance": "NOT_PROVEN",
            "pixel_carve_authorization": "NEVER",
        }


def _valid_mask(value: object, count: int) -> bool:
    return isinstance(value, bytes) and len(value) == count


def _valid_bbox(box: object, width: int, height: int) -> bool:
    return (
        isinstance(box, tuple)
        and len(box) == 4
        and all(type(v) is int for v in box)
        and 0 <= box[0] < box[2] <= width
        and 0 <= box[1] < box[3] <= height
    )


def _in_box_with_margin(
    x: int, y: int, bbox: Tuple[int, int, int, int], margin: int
) -> bool:
    return (
        bbox[0] - margin <= x < bbox[2] + margin
        and bbox[1] - margin <= y < bbox[3] + margin
    )


def _has_supported_neighborhood(
    mask: bytes, width: int, height: int, x: int, y: int
) -> bool:
    # A one-cell erosion of the prompted protagonist foreground prevents
    # uncertain boundaries from creating apparent "recovery".
    if x < 1 or y < 1 or x >= width - 1 or y >= height - 1:
        return False
    return all(
        mask[(y + dy) * width + x + dx] >= MIN_POSITIVE_CONFIDENCE
        for dy in (-1, 0, 1)
        for dx in (-1, 0, 1)
    )


def _masked_near(
    mask: bytes, width: int, height: int, x: int, y: int, margin: int
) -> bool:
    for dy in range(-margin, margin + 1):
        ny = y + dy
        if ny < 0 or ny >= height:
            continue
        for dx in range(-margin, margin + 1):
            nx = x + dx
            if 0 <= nx < width and mask[ny * width + nx] >= PROTECTED_MASK_THRESHOLD:
                return True
    return False


def evaluate(evidence: IndependentEvidence) -> GateResult:
    """Measure hypothetical safety in memory; return aggregate counts ONLY.

    The caller's claim of a complete selected-person census is NOT proof that
    no missed protected person exists. Tests deliberately expose that gap.
    Thus there is no production-authorizable return status.
    """
    if (
        type(evidence.width) is not int or type(evidence.height) is not int
        or not 2 <= evidence.width <= 8192
        or not 2 <= evidence.height <= 8192
        or evidence.width * evidence.height > 8_388_608
        or evidence.coordinate_space != SOURCE_SPACE
    ):
        return GateResult("INVALID_SOURCE_GEOMETRY")
    pixels = evidence.width * evidence.height
    if not _valid_mask(evidence.conservative_unknown_mask, pixels) or not _valid_mask(
        evidence.independent_protagonist_mask, pixels
    ):
        return GateResult("MISSING_OR_INCOMPATIBLE_PROPOSAL_MASK")
    if (
        type(evidence.source_pts_us) is not int
        or type(evidence.candidate_pts_us) is not int
        or evidence.source_pts_us <= 0
        or evidence.candidate_pts_us != evidence.source_pts_us
    ):
        return GateResult("SOURCE_PTS_MISMATCH")
    if (
        type(evidence.independent_model_family) is not str
        or evidence.independent_model_family not in MODEL_FAMILIES
        or type(evidence.independent_model_id) is not str
        or not evidence.independent_model_id.strip()
    ):
        return GateResult("INDEPENDENT_MODEL_NOT_VERIFIED")
    if (
        type(evidence.protagonist_root_id) is not int
        or evidence.protagonist_root_id < 0
        or type(evidence.prompt_root_id) is not int
        or evidence.prompt_root_id != evidence.protagonist_root_id
        or not isinstance(evidence.confirmed_previous_roots, tuple)
        or evidence.confirmed_previous_roots
        != (evidence.protagonist_root_id, evidence.protagonist_root_id)
        or not isinstance(evidence.confirmed_previous_pts_us, tuple)
        or len(evidence.confirmed_previous_pts_us) != 2
    ):
        return GateResult("PROTAGONIST_PROVENANCE_NOT_VERIFIED")
    prev0, prev1 = evidence.confirmed_previous_pts_us
    if (
        any(type(p) is not int for p in (prev0, prev1))
        or prev0 < 0
        or not prev0 < prev1 < evidence.source_pts_us
        or evidence.source_pts_us - prev1 > MAX_CURRENT_EVIDENCE_GAP_US
        or prev1 - prev0 > MAX_CURRENT_EVIDENCE_GAP_US
    ):
        return GateResult("PROTAGONIST_EVIDENCE_EXPIRED")
    selected = evidence.protected_selected_roots
    if (
        not isinstance(selected, frozenset)
        or not selected
        or evidence.protagonist_root_id in selected
        or any(type(root) is not int or root < 0 for root in selected)
        or evidence.independently_verified_selected_census is not True
        or not isinstance(evidence.observed_selected_roots, frozenset)
        or evidence.observed_selected_roots != selected
        or not isinstance(evidence.observed_selected_masks, Mapping)
        or not isinstance(evidence.independent_selected_masks, Mapping)
        or not isinstance(evidence.selected_bbox_by_root, Mapping)
        or set(evidence.observed_selected_masks) != selected
        or set(evidence.independent_selected_masks) != selected
        or set(evidence.selected_bbox_by_root) != selected
    ):
        return GateResult("INCOMPLETE_SELECTED_OCCUPANCY_EVIDENCE")
    for root in selected:
        if (
            not _valid_mask(evidence.observed_selected_masks[root], pixels)
            or not _valid_mask(evidence.independent_selected_masks[root], pixels)
            or not _valid_bbox(
                evidence.selected_bbox_by_root[root], evidence.width, evidence.height
            )
        ):
            return GateResult("INVALID_PROTECTED_GEOMETRY_OR_MASK")
    if (
        evidence.synthetic_truth_protected_mask is not None
        and not _valid_mask(evidence.synthetic_truth_protected_mask, pixels)
    ):
        return GateResult("INVALID_SYNTHETIC_TRUTH")

    unknown_pixels = sum(v >= UNKNOWN_MASK_THRESHOLD for v in evidence.conservative_unknown_mask)
    positive_pixels = sum(v >= MIN_POSITIVE_CONFIDENCE for v in evidence.independent_protagonist_mask)
    inside = box_veto = mask_veto = hypothetical_clear = false_clears = 0
    for y in range(evidence.height):
        for x in range(evidence.width):
            index = y * evidence.width + x
            if evidence.conservative_unknown_mask[index] < UNKNOWN_MASK_THRESHOLD:
                continue
            if not _has_supported_neighborhood(
                evidence.independent_protagonist_mask, evidence.width, evidence.height, x, y
            ):
                continue
            inside += 1
            if any(
                _in_box_with_margin(
                    x, y, evidence.selected_bbox_by_root[root], PROTECTED_MARGIN
                )
                for root in selected
            ):
                box_veto += 1
                continue
            if any(
                _masked_near(mask, evidence.width, evidence.height, x, y, PROTECTED_MARGIN)
                for root in selected
                for mask in (
                    evidence.observed_selected_masks[root],
                    evidence.independent_selected_masks[root],
                )
            ):
                mask_veto += 1
                continue
            hypothetical_clear += 1
            if (
                evidence.synthetic_truth_protected_mask is not None
                and evidence.synthetic_truth_protected_mask[index] >= PROTECTED_MASK_THRESHOLD
            ):
                false_clears += 1

    if hypothetical_clear == 0:
        status = "NO_INDEPENDENT_SAFE_CANDIDATE_KEEP_PRIVACY"
    elif evidence.synthetic_truth_protected_mask is None:
        status = "UNLABELED_HYPOTHESIS_NO_PRIVACY_ACCEPTANCE"
    elif false_clears:
        status = "SYNTHETIC_FALSE_CLEAR_BLOCKS_MODEL"
    else:
        status = "SYNTHETIC_NO_FALSE_CLEAR_NOT_VIDEO_ACCEPTED"
    return GateResult(
        status=status,
        unknown_pixels=unknown_pixels,
        model_positive_pixels=positive_pixels,
        unknown_positive_interior=inside,
        protected_box_veto_pixels=box_veto,
        selected_mask_veto_pixels=mask_veto,
        hypothetical_clear_pixels=hypothetical_clear,
        synthetic_false_clear_pixels=(
            false_clears if evidence.synthetic_truth_protected_mask is not None else None
        ),
        synthetic_true_foreground_pixels=(
            hypothetical_clear - false_clears
            if evidence.synthetic_truth_protected_mask is not None else None
        ),
    )
