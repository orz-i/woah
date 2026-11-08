import unittest
import numpy as np
from tools.privacy.audit_known_faces import projection,probe

class KnownFaceProbeTests(unittest.TestCase):
    def test_source_points_follow_the_recorded_crop(self):
        for actual,expected in zip(projection((600,400),(1000,800),(.4,.25,.8,.75),(200,200)),(100.0,100.0,0.5)):
            self.assertAlmostEqual(expected,actual)
        self.assertIsNone(projection((200,400),(1000,800),(.4,.25,.8,.75),(200,200)))
    def test_actual_opaque_pixels_distinguish_a_leak_from_a_planned_region(self):
        clear=np.full((80,80,3),190,dtype=np.uint8)
        self.assertFalse(probe(clear,(40,40,1),12,.9)['passed'])
        clear[28:52,28:52]=(0,0,255)
        self.assertEqual(1.0,probe(clear,(40,40,1),12,.9)['fraction'])
    def test_clipped_patch_cannot_pass_on_a_smaller_area(self):
        with self.assertRaisesRegex(ValueError,'clipped'):
            probe(np.zeros((80,80,3),dtype=np.uint8),(2,40,1),12,.9)

if __name__=='__main__':unittest.main()
