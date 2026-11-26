"""Focused host-only probe tests (run in the optional model-probe environment)."""
from types import SimpleNamespace
import unittest
from importlib.util import find_spec

# The regular project environment does not require the host LiteRT probe.
if find_spec("ai_edge_litert") is None or find_spec("torch") is None:
    raise unittest.SkipTest("Optional segmentation-probe environment is not installed")

import numpy as np
import torch

from probe_instance_models import oracle
from probe_selected_refinement import choose, crop_bounds, refine
from replay_instance_models import TorchRunner


def mask(x0, y0, x1, y1):
    out = np.zeros((100,100),np.uint8)
    out[y0:y1,x0:x1] = 255
    return out


class SelectedRefinementTest(unittest.TestCase):
    def test_crop_aligns_source_and_alpha_grids(self):
        alpha = mask(23,31,50,63)
        bounds = crop_bounds(alpha,250,150)
        self.assertTrue(all(x % 2 == 0 for x in bounds))
        self.assertLess(bounds[0],23)
        self.assertLess(bounds[1],31)
        self.assertGreater(bounds[2],50)
        self.assertGreater(bounds[3],63)
        self.assertIsNone(crop_bounds(np.zeros_like(alpha),250,150))

    def test_accepted_and_rejected_candidates(self):
        old = mask(20,20,60,60)
        same = old.copy()
        bounds = (10,10,80,80)
        self.assertEqual(choose(old,[same],bounds)[:2],(0,"accepted"))
        self.assertEqual(choose(old,[],bounds)[:2],(None,"no_candidate"))
        other = mask(65,65,75,75)
        self.assertEqual(choose(old,[other],bounds)[:2],(0,"insufficient_agreement"))
        self.assertEqual(choose(old,[same,same.copy()],bounds)[:2],(0,"ambiguous_candidate"))
        addition = same.copy()
        addition[20:60,60:65] = 255
        self.assertEqual(choose(old,[addition,mask(60,20,65,60)],bounds)[:2],
                         (0,"competing_addition"))

    def test_artificial_crop_border_rejected(self):
        old = mask(20,20,60,60)
        candidate = old.copy()
        candidate[19,25] = 255
        self.assertEqual(choose(old,[candidate],(10,19,80,80))[:2],
                         (0,"artificial_crop_edge"))

    def test_missing_mask_does_not_invoke_model(self):
        raw, result, info = refine(None,None,None,None)
        self.assertIsNone(raw)
        self.assertIsNone(result)
        self.assertEqual(info["reason"],"missing_baseline")


class InstanceModelProbeTest(unittest.TestCase):
    def test_oracle_vetoes_distractors_and_separates_confidence(self):
        selected = mask(15,15,35,35)
        wrong = mask(15,15,90,90)
        annotation = {"selected":[.25,.25],"distractor":[.8,.8],
                      "additionalDistractors":[{"point":[.5,.5],"description":"other"}],
                      "visibleContour":[[15,15],[34,15],[34,34],[15,34]]}
        result = oracle([SimpleNamespace(confidence=.22),SimpleNamespace(confidence=.9)],
                        [selected,wrong],annotation,(100,100))
        self.assertEqual(result["weak"]["eligibleCount"],1)
        self.assertEqual(result["strong"]["eligibleCount"],0)
        self.assertEqual(result["weak"]["bestContour"]["iou"],1.)
        self.assertEqual(result["strong"]["bestContour"]["iou"],0.)

    def test_torch_adapter_keeps_explicit_pixel_coordinate_contract(self):
        runner = TorchRunner(lambda x:(x+1,x+2))
        tensor = np.zeros((1,3,2,2),np.float32)
        runner.set_tensor(0,tensor)
        runner.invoke()
        self.assertEqual(runner.coordinate_units,0)
        self.assertEqual(runner.get_input_details(),[{"index":0}])
        self.assertTrue(np.array_equal(runner.get_tensor(1),tensor+1))
        self.assertTrue(np.array_equal(runner.get_tensor(2),tensor+2))
        self.assertEqual(runner.get_tensor(2).dtype,np.float32)
        with self.assertRaises(ValueError):
            runner.get_tensor(3)


if __name__ == "__main__":
    unittest.main()
