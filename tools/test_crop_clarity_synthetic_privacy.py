"""Synthetic multi-person frame/privacy-first-post-crop coordinate regression.

A pure-pixel oracle using no real media, segmentation model or private images.
This tests geometry and stage order only; it cannot prove GPU compositor,
face detector, occlusion tracker, or real video privacy correctness.
"""
from __future__ import annotations

import unittest

SOURCE_WIDTH = 160
SOURCE_HEIGHT = 90
OUTPUT_WIDTH = 40
OUTPUT_HEIGHT = 64


def contains(rect: tuple[float, float, float, float], x: float, y: float) -> bool:
    return rect[0] <= x < rect[2] and rect[1] <= y < rect[3]


def source_point(
    crop: tuple[float, float, float, float], x: int, y: int
) -> tuple[float, float]:
    # Identical top-left crop convention to SmoothFollower's visualCrop.
    sx = SOURCE_WIDTH * (crop[0] + (x + .5) / OUTPUT_WIDTH * (crop[2] - crop[0]))
    sy = SOURCE_HEIGHT * (crop[1] + (y + .5) / OUTPUT_HEIGHT * (crop[3] - crop[1]))
    return sx, sy


def render_protected_then_crop(
    crop: tuple[float, float, float, float],
    protected: tuple[tuple[float, float, float, float], ...],
) -> list[list[bool]]:
    return [
        [any(contains(box, *source_point(crop, x, y)) for box in protected)
         for x in range(OUTPUT_WIDTH)]
        for y in range(OUTPUT_HEIGHT)
    ]


def crop_first_without_remapping_masks(
    protected: tuple[tuple[float, float, float, float], ...]
) -> list[list[bool]]:
    return [
        [any(contains(box, (x + .5) / OUTPUT_WIDTH * SOURCE_WIDTH,
                      (y + .5) / OUTPUT_HEIGHT * SOURCE_HEIGHT) for box in protected)
         for x in range(OUTPUT_WIDTH)]
        for y in range(OUTPUT_HEIGHT)
    ]


class SyntheticCropPrivacyRegressionTest(unittest.TestCase):
    def test_crossings_motion_and_source_to_portrait_mapping(self):
        # Two protected people cross with an unprotected protagonist. All
        # positions are scripted rather than learned from video or detection.
        protected_boxes = [
            ((14, 10, 36, 76), (80, 15, 102, 75)),
            ((25, 10, 47, 76), (70, 15, 92, 75)),
            ((35, 10, 57, 76), (58, 15, 80, 75)),
            ((49, 10, 71, 76), (48, 15, 70, 75)),
            ((63, 10, 85, 76), (38, 15, 60, 75)),
            ((79, 10, 101, 76), (27, 15, 49, 75)),
        ]
        crops = [
            (.02, 0., .33, 1.), (.10, 0., .41, 1.), (.17, 0., .48, 1.),
            (.24, 0., .55, 1.), (.36, 0., .67, 1.), (.46, 0., .77, 1.),
        ]
        differences_for_wrong_order = 0
        visible_cases = 0
        for protected, crop in zip(protected_boxes, crops):
            actual = render_protected_then_crop(crop, protected)
            wrong = crop_first_without_remapping_masks(protected)
            count = sum(pixel for row in actual for pixel in row)
            self.assertGreater(count, 0)
            visible_cases += 1
            differences_for_wrong_order += sum(
                actual[y][x] != wrong[y][x]
                for y in range(OUTPUT_HEIGHT) for x in range(OUTPUT_WIDTH)
            )
        self.assertEqual(visible_cases, 6)
        self.assertGreater(differences_for_wrong_order, 500)

    def test_off_crop_selected_target_must_not_be_promoted_into_portrait(self):
        selected = ((8., 12., 27., 77.),)
        crop = (.68, 0., .99, 1.)
        composed = render_protected_then_crop(crop, selected)
        self.assertEqual(sum(sum(row) for row in composed), 0)

    def test_lost_and_reidentified_trajectories_are_not_privacy_assertions(self):
        # A missing detection or identity reassociation is outside this oracle.
        # Model those moments explicitly and do not fabricate a 'safe' result.
        frames = [
            {"protected": ((27., 14., 49., 74.),), "identity_state": "OBSERVED"},
            {"protected": (), "identity_state": "LOST"},
            {"protected": (), "identity_state": "AMBIGUOUS_REID"},
            {"protected": ((75., 14., 97., 74.),), "identity_state": "OBSERVED"},
        ]
        crops = [(0.0, 0., .31, 1.), (.15, 0., .46, 1.),
                 (.32, 0., .63, 1.), (.39, 0., .70, 1.)]
        independently_verified = 0
        intentionally_unverified = 0
        for frame, crop in zip(frames, crops):
            mask = render_protected_then_crop(crop, frame["protected"])
            if frame["identity_state"] != "OBSERVED":
                intentionally_unverified += 1
                self.assertEqual(sum(sum(row) for row in mask), 0)
            else:
                independently_verified += 1
                self.assertGreater(sum(sum(row) for row in mask), 0)
        self.assertEqual(independently_verified, 2)
        self.assertEqual(intentionally_unverified, 2)


if __name__ == "__main__":
    unittest.main()
