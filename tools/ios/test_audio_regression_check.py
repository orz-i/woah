import unittest
import numpy as np
from audio_regression_check import compare_audio_samples

class AudioAlignmentTests(unittest.TestCase):
    def setUp(self):
        self.signal=np.random.default_rng(37).normal(0,.1,32000)
    def test_aligned(self):
        r=compare_audio_samples(self.signal,self.signal.copy())
        self.assertAlmostEqual(r['correlation'],1,places=8);self.assertEqual(r['lag_ms'],0)
    def test_delayed(self):
        r=compare_audio_samples(self.signal,np.pad(self.signal,(320,0)))
        self.assertAlmostEqual(r['correlation'],1,places=3);self.assertEqual(r['lag_ms'],20)
    def test_advanced(self):
        r=compare_audio_samples(self.signal,self.signal[160:])
        self.assertAlmostEqual(r['correlation'],1,places=3);self.assertEqual(r['lag_ms'],-10)
    def test_silent_or_unrelated(self):
        self.assertLess(compare_audio_samples(self.signal,np.zeros(32000))['correlation'],.1)
        self.assertLess(compare_audio_samples(self.signal,self.signal[::-1])['correlation'],.1)

if __name__=='__main__':unittest.main()
