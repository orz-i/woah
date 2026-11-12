"""Exact source-frame correspondence regressions; no media files or FFmpeg needed."""
import unittest
import numpy as np
from fractions import Fraction

from verify_comparison_export import frame_times, source_correspondence, check_audio_segment


class ExportCorrespondenceTest(unittest.TestCase):
    def test_silent_selection_is_valid_inside_an_audio_track(self):
        result=check_audio_segment(np.zeros(48000,dtype=np.float32),np.zeros(48000,dtype=np.float32),48000)
        self.assertTrue(result['referenceSilent'])
        with self.assertRaises(AssertionError):
            check_audio_segment(np.ones(48000,dtype=np.float32)*.1,np.zeros(48000,dtype=np.float32),48000)

    def test_audible_music_cannot_be_dropped_or_shifted_five_milliseconds(self):
        reference=np.random.default_rng(7).standard_normal(48000).astype(np.float32)*.1
        self.assertGreater(check_audio_segment(reference,reference,48000)['correlation'],.999)
        for bad in [np.zeros_like(reference),np.concatenate([np.zeros(240,dtype=np.float32),reference[:-240]])]:
            with self.assertRaises(AssertionError):
                check_audio_segment(bad,reference,48000)
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
