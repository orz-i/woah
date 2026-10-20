"""Offline synthetic-only FULL_BODY appearance-continuity feasibility gate.

NO connection to mobile rendering, source file input, image writes or mask carve.
The accepted result is at most one matched *point*, never a person silhouette.
Passing this gate cannot authorize removing privacy at any pixel.
"""
from __future__ import annotations

from dataclasses import dataclass
from math import sqrt


@dataclass(frozen=True)
class GrayPlane:
    width: int
    height: int
    values: tuple[int, ...]

    def __post_init__(self) -> None:
        if self.width <= 0 or self.height <= 0 or len(self.values) != self.width * self.height:
            raise ValueError("invalid plane geometry")
        if any(type(v) is not int or v < 0 or v > 255 for v in self.values):
            raise ValueError("grayscale input must have byte intensities")

    def patch(self, x: int, y: int, radius: int) -> tuple[int, ...] | None:
        if x - radius < 0 or y - radius < 0 or x + radius >= self.width or y + radius >= self.height:
            return None
        return tuple(
            self.values[row * self.width + col]
            for row in range(y - radius, y + radius + 1)
            for col in range(x - radius, x + radius + 1)
        )


@dataclass(frozen=True)
class Match:
    x: int
    y: int
    dx: int
    dy: int
    correlation: float
    uniqueness_gap: float
    photometric_mae: float


@dataclass(frozen=True)
class Verdict:
    reason: str
    match: Match | None = None


def _correlation(a: tuple[int, ...], b: tuple[int, ...]) -> float | None:
    if len(a) != len(b) or not a:
        return None
    mean_a = sum(a) / len(a)
    mean_b = sum(b) / len(b)
    norm_a = sum((v - mean_a) ** 2 for v in a)
    norm_b = sum((v - mean_b) ** 2 for v in b)
    if min(norm_a, norm_b) < 25 * len(a):
        return None  # textureless, no distinctive appearance evidence
    dot = sum((u - mean_a) * (v - mean_b) for u, v in zip(a, b))
    return dot / sqrt(norm_a * norm_b)


def _best_match(
    before: GrayPlane, after: GrayPlane, x: int, y: int,
    *, radius: int, search: int, uniqueness_gap: float
) -> tuple[str, Match | None]:
    source = before.patch(x, y, radius)
    if source is None:
        return "INVALID_SEED", None
    if _correlation(source, source) is None:
        return "LOW_TEXTURE", None

    candidates: list[tuple[float, int, int, float]] = []
    for dy in range(-search, search + 1):
        for dx in range(-search, search + 1):
            target = after.patch(x + dx, y + dy, radius)
            if target is None:
                continue
            corr = _correlation(source, target)
            if corr is not None:
                mae = sum(abs(a - b) for a, b in zip(source, target)) / len(source)
                candidates.append((corr, dx, dy, mae))
    if not candidates:
        return "NO_CANDIDATE", None
    candidates.sort(key=lambda entry: entry[0], reverse=True)
    best_corr, dx, dy, mae = candidates[0]
    if best_corr < 0.92 or mae > 12:
        return "PHOTOMETRIC_MISMATCH", None
    second = max((
        item[0] for item in candidates
        if max(abs(item[1] - dx), abs(item[2] - dy)) > 2
    ), default=-1.0)
    gap = best_corr - second
    if gap < uniqueness_gap:
        return "AMBIGUOUS_MATCH", None
    return "MATCHED", Match(x + dx, y + dy, dx, dy, best_corr, gap, mae)


def screen_correspondence(
    previous: GrayPlane,
    current: GrayPlane,
    *,
    seed: tuple[int, int],
    previous_pts_us: int,
    current_pts_us: int,
    protagonist_root_id: int,
    seed_root_id: int,
    two_prior_observations_verified: bool,
    unknown_target_pixels: frozenset[int],
    independent_protected_pixels: frozenset[int],
    independent_protected_boxes: tuple[tuple[int, int, int, int], ...] = (),
    protected_occupancy_complete: bool = False,
    search: int = 7,
    radius: int = 2,
) -> Verdict:
    """Return a sparse research verdict only, not unmasking instructions.

    Pixel-set arguments are synthetic ground-truth *fixtures* in tests, not
    source artifacts or current production segmentation tensors.
    """
    if previous.width != current.width or previous.height != current.height:
        return Verdict("GEOMETRY_MISMATCH")
    if current_pts_us <= previous_pts_us or current_pts_us - previous_pts_us > 100_000:
        return Verdict("INVALID_EVIDENCE_GAP")
    if protagonist_root_id != seed_root_id or not two_prior_observations_verified:
        return Verdict("UNVERIFIED_PROTAGONIST_SEED")
    if not protected_occupancy_complete:
        return Verdict("INCOMPLETE_PROTECTED_EVIDENCE")
    if any(len(box) != 4 or box[2] <= box[0] or box[3] <= box[1]
           for box in independent_protected_boxes):
        return Verdict("INCOMPLETE_PROTECTED_EVIDENCE")

    first_status, forward = _best_match(
        previous, current, seed[0], seed[1], radius=radius, search=search,
        uniqueness_gap=0.055
    )
    if forward is None:
        return Verdict(first_status)
    reverse_status, backward = _best_match(
        current, previous, forward.x, forward.y, radius=radius, search=search,
        uniqueness_gap=0.055
    )
    if backward is None or max(abs(backward.x - seed[0]), abs(backward.y - seed[1])) > 1:
        return Verdict("FORWARD_BACKWARD_INCONSISTENT")

    # The unknown target is *only* the conflicting conservative SELECTED
    # detection. No other independently protected person is ever cleared.
    index = forward.y * current.width + forward.x
    if index not in unknown_target_pixels:
        return Verdict("OUTSIDE_UNKNOWN_TARGET")
    # A current, observed protected root's box is a conservative veto even
    # when its instance Mask is absent. Geometry may reject a real protagonist
    # feature, but geometry NEVER authorizes a privacy cut.
    if any(left - 1 <= forward.x < right + 1 and
           top - 1 <= forward.y < bottom + 1
           for left, top, right, bottom in independent_protected_boxes):
        return Verdict("INDEPENDENT_PROTECTED_BOX_VETO")
    # One-pixel safety ring covers texture filtering / GL sampling uncertainty.
    for offset_y in (-1, 0, 1):
        for offset_x in (-1, 0, 1):
            check_x, check_y = forward.x + offset_x, forward.y + offset_y
            if 0 <= check_x < current.width and 0 <= check_y < current.height:
                if check_y * current.width + check_x in independent_protected_pixels:
                    return Verdict("INDEPENDENT_PROTECTED_VETO")
    return Verdict("SHADOW_POINT_MATCH_ONLY", forward)
