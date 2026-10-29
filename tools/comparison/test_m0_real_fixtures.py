import unittest
import tempfile
from pathlib import Path
from unittest import mock
import prepare_m0_real_fixtures as real
from prepare_m0_real_fixtures import annotate_frame, frame_code_spec, FOOTER_HEIGHT

class M0RealFixtureTest(unittest.TestCase):
    def test_original_picture_bytes_and_frame_boundary_are_preserved(self):
        for w,h in [(592,1280),(1280,720)]:
            raw=b"\x12\x7f\x33"*(w*h)
            result=annotate_frame(raw,w,h,31,30,'T')
            self.assertEqual(len(result),w*(h+FOOTER_HEIGHT)*3)
            self.assertEqual(result[:len(raw)],raw)

    def test_codes_round_trip_on_both_real_aspect_ratios(self):
        for w,h in [(592,1280),(1280,720)]:
            spec=frame_code_spec(w,h)
            for n in [0,31,1193]:
                image=annotate_frame(bytes(w*h*3),w,h,n,60,'L')
                code=0
                for bit in range(20):
                    x=spec['x']+bit*spec['cellWidth']+spec['cellWidth']//2
                    y=spec['y']+spec['height']//2
                    value=image[(y*w+x)*3:(y*w+x)*3+3]
                    self.assertIn(bytes(value),[bytes(3),bytes([255])*3])
                    code=code*2+(value[0]>127)
                self.assertEqual(code,n)

    def test_invalid_frames_and_code_overflow_fail_before_encoding(self):
        with self.assertRaises(ValueError):annotate_frame(b'bad',592,1280,0,30,'T')
        with self.assertRaises(ValueError):annotate_frame(bytes(592*1280*3),592,1280,2**20,30,'T')
        with self.assertRaises(ValueError):frame_code_spec(191,1280)

    def test_failed_pair_generation_does_not_publish_a_dataset(self):
        with tempfile.TemporaryDirectory() as directory:
            base=Path(directory)
            teacher=base/'teacher.media';teacher.write_bytes(b'teacher')
            learner=base/'learner.media';learner.write_bytes(b'learner')
            output=base/'prepared'
            args=['prepare_m0_real_fixtures.py','--teacher',str(teacher),'--learner',str(learner),'--out',str(output)]
            with mock.patch('sys.argv',args),mock.patch.object(real,'probe',return_value={}),mock.patch.object(real,'render',side_effect=[{'frames':1},RuntimeError('Encoder failed')]):
                with self.assertRaises(RuntimeError):real.main()
            self.assertFalse(output.exists())
            self.assertEqual(teacher.read_bytes(),b'teacher')
            self.assertEqual(learner.read_bytes(),b'learner')

if __name__=='__main__':unittest.main()
