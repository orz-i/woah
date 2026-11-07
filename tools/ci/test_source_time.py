import unittest
from tools.privacy.source_time import bind_microsecond_frame
class SourceTimeTests(unittest.TestCase):
    def test_mediaextractor_floor_matches_same_rational_frame_scan(self):
        frame={'frame':1,'pts':1001/30000}
        self.assertEqual((frame,33367),bind_microsecond_frame({0:{'frame':0},33367:frame},33366))
    def test_exact_frame_wins_and_larger_gap_never_falls_back_to_zero(self):
        frames={0:'zero',100:'exact',101:'next'}
        self.assertEqual(('exact',100),bind_microsecond_frame(frames,100))
        with self.assertRaises(ValueError):bind_microsecond_frame(frames,103)
    def test_ambiguous_neighbors_are_rejected(self):
        with self.assertRaises(ValueError):bind_microsecond_frame({99:'prior',101:'next'},100)
if __name__=='__main__':unittest.main()
