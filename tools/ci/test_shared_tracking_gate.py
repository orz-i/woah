import json,tempfile,unittest
from pathlib import Path
from unittest.mock import patch
from tools.release import verify_ios_phase5 as gate

class SharedTrackingGateTests(unittest.TestCase):
    def tearDown(self):gate.FAILURES.clear()
    def test_native_golden_lease_and_identity_contract_pass(self):
        gate.FAILURES.clear();gate.verify_trace_contract();self.assertEqual([],gate.FAILURES)
    def test_rewriting_ios_expected_state_cannot_hide_native_divergence(self):
        payload=json.loads(gate.TRACE.read_text(encoding='utf-8'))
        payload['cases'][0]['steps'][0]['expect'][0]['state']='LOST'
        with tempfile.TemporaryDirectory() as directory:
            path=Path(directory)/'gold.json';path.write_text(json.dumps(payload),encoding='utf-8')
            gate.FAILURES.clear()
            with patch.object(gate,'TRACE',path):gate.verify_trace_contract()
        self.assertTrue(any('native Golden mismatch state' in x for x in gate.FAILURES))
    def test_deleting_rust_call_from_swift_adapter_is_rejected(self):
        original=gate.text
        def altered(path,label):
            value=original(path,label)
            return value.replace('frame_kernel_tracking_step(','independent_tracker_step(') if path.name=='IOSSharedTrackingSession.swift' else value
        gate.FAILURES.clear()
        with patch.object(gate,'text',side_effect=altered):gate.verify_ios_replay_surface()
        self.assertTrue(any('Swift identity transport must call Rust' in x for x in gate.FAILURES))

if __name__=='__main__':unittest.main()
