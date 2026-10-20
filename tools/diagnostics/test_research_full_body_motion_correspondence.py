"""Synthetic grayscale-only research gate: no source video and no output mask."""
from __future__ import annotations

import unittest

from tools.diagnostics.research_full_body_motion_correspondence import (
    GrayPlane, screen_correspondence,
)

WIDTH = 48
HEIGHT = 40
PATTERN = tuple((19 + (i * 137 + i * i * 53 + (i % 3) * 47)) % 241 for i in range(25))
OTHER_PATTERN = tuple((137 + (i * 71 + i * i * 37 + (i % 5) * 29)) % 241 for i in range(25))


def make_plane(centers: tuple[tuple[int, int, tuple[int, ...]], ...] = (),
               width: int = WIDTH, height: int = HEIGHT) -> GrayPlane:
    values = [35] * (width * height)
    for center_x, center_y, patch in centers:
        for index, value in enumerate(patch):
            x = center_x - 2 + index % 5
            y = center_y - 2 + index // 5
            if 0 <= x < width and 0 <= y < height:
                values[y * width + x] = value
    return GrayPlane(width, height, tuple(values))


def screen(
    prev: GrayPlane,
    current: GrayPlane,
    seed=(18, 18),
    *,
    target_center=(21, 20),
    protected=frozenset(),
    **overrides,
):
    tx, ty = target_center
    unknown = frozenset(
        y * WIDTH + x
        for y in range(ty - 4, ty + 5)
        for x in range(tx - 4, tx + 5)
        if x in range(WIDTH) and y in range(HEIGHT)
    )
    args = dict(
        seed=seed,
        previous_pts_us=39_766_666,
        current_pts_us=39_800_000,
        protagonist_root_id=2,
        seed_root_id=2,
        two_prior_observations_verified=True,
        unknown_target_pixels=unknown,
        independent_protected_pixels=protected,
        protected_occupancy_complete=True,
    )
    args.update(overrides)
    return screen_correspondence(prev, current, **args)


class FullBodySparseMotionFeasibilityTest(unittest.TestCase):
    def setUp(self):
        self.previous = make_plane(((18, 18, PATTERN),))
        self.moved = make_plane(((21, 20, PATTERN),))

    def test_unique_textured_motion_is_detected_but_only_as_a_shadow_point(self):
        result = screen(self.previous, self.moved)
        self.assertEqual(result.reason, "SHADOW_POINT_MATCH_ONLY")
        self.assertIsNotNone(result.match)
        self.assertEqual((result.match.dx, result.match.dy), (3, 2))
        self.assertGreater(result.match.correlation, 0.99)

    def test_static_distinctive_appearance_is_not_erased_by_geometry_assumptions(self):
        unchanged = make_plane(((18, 18, PATTERN),))
        result = screen(self.previous, unchanged, target_center=(18, 18))
        self.assertEqual(result.reason, "SHADOW_POINT_MATCH_ONLY")
        self.assertEqual((result.match.dx, result.match.dy), (0, 0))

    def test_flat_white_shirt_is_rejected_due_to_missing_texture(self):
        flat = make_plane()
        result = screen(flat, flat, target_center=(18, 18))
        self.assertEqual(result.reason, "LOW_TEXTURE")

    def test_two_identically_textured_people_yield_ambiguous_correspondence(self):
        previous = make_plane(((23, 18, PATTERN),))
        current = make_plane(((17, 18, PATTERN), (29, 18, PATTERN)))
        result = screen(previous, current, seed=(23, 18), target_center=(17, 18))
        self.assertEqual(result.reason, "AMBIGUOUS_MATCH")

    def test_newly_occluding_protected_person_vetoes_a_good_match(self):
        center_index = 20 * WIDTH + 21
        result = screen(self.previous, self.moved, protected=frozenset({center_index}))
        self.assertEqual(result.reason, "INDEPENDENT_PROTECTED_VETO")
        self.assertIsNone(result.match)

    def test_protected_selected_pixel_one_step_from_match_also_vetoes(self):
        protected_neighbour = 20 * WIDTH + 22
        result = screen(self.previous, self.moved, protected=frozenset({protected_neighbour}))
        self.assertEqual(result.reason, "INDEPENDENT_PROTECTED_VETO")

    def test_incomplete_protected_occupancy_is_fail_closed(self):
        result = screen(
            self.previous, self.moved, protected_occupancy_complete=False
        )
        self.assertEqual(result.reason, "INCOMPLETE_PROTECTED_EVIDENCE")
        self.assertIsNone(result.match)

    def test_missing_selected_mask_but_current_selected_bbox_still_vetoes(self):
        result = screen(
            self.previous, self.moved,
            independent_protected_boxes=((20, 19, 28, 29),)
        )
        self.assertEqual(result.reason, "INDEPENDENT_PROTECTED_BOX_VETO")
        self.assertIsNone(result.match)

    def test_adjacent_protected_bbox_uncertainty_margin_vetoes(self):
        result = screen(
            self.previous, self.moved,
            independent_protected_boxes=((22, 21, 29, 30),)
        )
        self.assertEqual(result.reason, "INDEPENDENT_PROTECTED_BOX_VETO")

    def test_bad_protected_box_geometry_is_incomplete_not_ignored(self):
        result = screen(
            self.previous, self.moved,
            independent_protected_boxes=((22, 21, 10, 30),)
        )
        self.assertEqual(result.reason, "INCOMPLETE_PROTECTED_EVIDENCE")

    def test_false_claim_of_complete_protection_can_still_match_an_intruder(self):
        # Identical stranger at a previous protagonist feature site. If the
        # external protected census is INCORRECTLY claimed complete, flow and
        # appearance alone still accept. Thus do NOT deploy this algorithm.
        current_other_person = make_plane(((21, 20, PATTERN),))
        result = screen(self.previous, current_other_person)
        self.assertEqual(result.reason, "SHADOW_POINT_MATCH_ONLY")
        self.assertIsNotNone(result.match)

    def test_protagonist_point_outside_unknown_target_is_not_relevant(self):
        result = screen(self.previous, self.moved, target_center=(40, 32))
        self.assertEqual(result.reason, "OUTSIDE_UNKNOWN_TARGET")

    def test_disocclusion_leaves_no_valid_correspondence(self):
        result = screen(self.previous, make_plane())
        self.assertIn(result.reason, ("PHOTOMETRIC_MISMATCH", "NO_CANDIDATE"))

    def test_selected_identity_and_two_prior_observations_are_required(self):
        self.assertEqual(screen(self.previous, self.moved, seed_root_id=1).reason,
                         "UNVERIFIED_PROTAGONIST_SEED")
        self.assertEqual(
            screen(self.previous, self.moved, two_prior_observations_verified=False).reason,
            "UNVERIFIED_PROTAGONIST_SEED"
        )

    def test_pts_gap_and_reverse_clock_are_rejected(self):
        self.assertEqual(screen(
            self.previous, self.moved, current_pts_us=40_000_000
        ).reason, "INVALID_EVIDENCE_GAP")
        self.assertEqual(screen(
            self.previous, self.moved, current_pts_us=39_766_666
        ).reason, "INVALID_EVIDENCE_GAP")

    def test_geometry_contract_is_exact(self):
        cropped = make_plane(width=32, height=32)
        self.assertEqual(screen(self.previous, cropped).reason, "GEOMETRY_MISMATCH")

    def test_repetitive_appearance_and_protected_crossing_do_not_make_safe_mask(self):
        # Even where a point would be accepted, this algorithm never returns a
        # binary foreground Mask or a region to carve from the selected target.
        center = 20 * WIDTH + 21
        protected = frozenset({center})
        result = screen(self.previous, self.moved, protected=protected)
        self.assertIsNone(result.match)
        self.assertEqual(result.reason, "INDEPENDENT_PROTECTED_VETO")


if __name__ == "__main__":
    unittest.main()
