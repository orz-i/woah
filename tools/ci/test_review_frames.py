import unittest
from tools.privacy.review_frames import sequential_selected

class NoSeekDecoder:
    def __init__(self):self.decoded=0
    def read(self):
        frame=self.decoded;self.decoded+=1
        return (frame<8,frame)
    def set(self,*args):raise AssertionError('Random seeking is unreliable')

class ReviewFrameTests(unittest.TestCase):
    def test_late_and_reordered_requests_select_actual_decoded_frames(self):
        decoder=NoSeekDecoder()
        self.assertEqual([(2,2),(6,6)],list(sequential_selected(decoder,[6,2,6])))
        self.assertEqual(7,decoder.decoded)
    def test_missing_tail_frame_rejects_incomplete_review(self):
        with self.assertRaisesRegex(ValueError,'unavailable'):
            list(sequential_selected(NoSeekDecoder(),[2,8]))
    def test_empty_review_does_not_decode(self):
        decoder=NoSeekDecoder();self.assertEqual([],list(sequential_selected(decoder,[])));self.assertEqual(0,decoder.decoded)

if __name__=='__main__':unittest.main()
