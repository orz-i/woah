"""Exact source-frame correspondence regressions; no media files or FFmpeg needed."""
import unittest
from fractions import Fraction

from verify_comparison_export import frame_times, source_correspondence


class ExportCorrespondenceTest(unittest.TestCase):
    def test_fractional_reference_maps_to_exact_target_frame_boundary(self):
        # The real iOS output exposed this boundary. Microsecond rounding would
        # move the lookup just before frame 80 and incorrectly demand frame 79.
        reference = [Fraction(9, 5), Fraction(11, 6), Fraction(28, 15)]
        target = [Fraction(79, 30), Fraction(8, 3), Fraction(27, 10)]
        self.assertEqual(source_correspondence(reference, target, Fraction(11, 6), .8, 1_200_000),
                         (Fraction(11, 6), Fraction(8, 3)))
        rounded_reference = Fraction(round(Fraction(11, 6) * 1_000_000), 1_000_000)
        self.assertEqual(source_correspondence(reference, target, rounded_reference, .8, 1_200_000)[1],
                         Fraction(79, 30))

    def test_vfr_source_holds_until_its_actual_next_pts(self):
        reference = [Fraction(0), Fraction(1, 30), Fraction(1, 10)]
        target = [Fraction(0), Fraction(1, 15), Fraction(1, 10)]
        self.assertEqual(source_correspondence(reference, target, Fraction(1, 12), 1, 0),
                         (Fraction(1, 30), Fraction(1, 15)))

    def test_probe_integer_pts_preserves_time_base(self):
        info = {'streams': [{'codec_type': 'video', 'time_base': '1/15360'}],
                'frames': [{'media_type': 'video', 'best_effort_timestamp': 28160,
                            'best_effort_timestamp_time': '1.833333'}]}
        self.assertEqual(frame_times(info), [Fraction(11, 6)])


if __name__ == '__main__':
    unittest.main()
