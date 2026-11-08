import hashlib
import json
from pathlib import Path
import tempfile
import unittest
from tools.privacy.observation_replay import approve, contract, sha

class ObservationReplayTests(unittest.TestCase):
    def test_contract_separates_detector_changes_and_environment(self):
        manifest={"platform":"android","source_files":{
            "crates/frame-kernel/src/instances.rs":"a"*64,
            "crates/frame-kernel/src/face_geometry.rs":"b"*64,
            "mobile/x/inference/Yolo.kt":"c"*64}}
        environment={"platform":"android","device":"synthetic","os":"test","runtime":"cpu"}
        first=contract(manifest,environment)[0]
        manifest["source_files"]["crates/frame-kernel/src/face_geometry.rs"]="d"*64
        self.assertEqual(first,contract(manifest,environment)[0])
        manifest["source_files"]["crates/frame-kernel/src/instances.rs"]="e"*64
        self.assertNotEqual(first,contract(manifest,environment)[0])
        environment["device"]="another synthetic device"
        self.assertNotEqual(first,contract(manifest,environment)[0])

    def baseline(self, root):
        key="a"*64; payload=root/(key+".bin");payload.write_bytes(b"synthetic detector observation")
        digest=sha(payload)
        events=root/"events.tsv"
        events.write_text(f"1000\t42\t{key}\tinference\t{digest}\n")
        results=root/"results.json"
        results.write_text(json.dumps({"status":"finished","process_id":42,
          "compiled_source_commit":"f"*40,"started_at_utc":"1970-01-01T00:00:00Z",
          "finished_at_utc":"1970-01-01T00:00:02Z","cases":[
           {"case":"synthetic","state":"completed","result":"output_created","pid":42}]}))
        receipt=root/"review.json"
        receipt.write_text(json.dumps({"decision":"approved_observation_baseline",
          "baseline_results_sha256":sha(results),"compiled_source_commit":"f"*40,
          "reviewed_case_ids":["synthetic"],"events_sha256":sha(events)}))
        return key,results,receipt

    def test_approval_requires_completed_reviewed_exact_baseline(self):
        with tempfile.TemporaryDirectory(prefix="qa-replay-synthetic-") as temporary:
            root=Path(temporary);key,results,receipt=self.baseline(root)
            result=approve(root,results,receipt)
            self.assertEqual(1,result["approved"])
            self.assertEqual(sha(root/(key+".bin")),(root/(key+".approved")).read_text().strip())
            review=json.loads(receipt.read_bytes());review["reviewed_case_ids"]=[]
            receipt.write_text(json.dumps(review))
            with self.assertRaises(AssertionError): approve(root,results,receipt)

    def test_corrupt_payload_and_changed_event_ledger_rejected(self):
        with tempfile.TemporaryDirectory(prefix="qa-replay-synthetic-") as temporary:
            root=Path(temporary);key,results,receipt=self.baseline(root)
            (root/(key+".bin")).write_bytes(b"corrupt")
            with self.assertRaises(AssertionError): approve(root,results,receipt)
            (root/"events.tsv").write_text("unbound ledger")
            with self.assertRaises(AssertionError): approve(root,results,receipt)

    def test_other_process_and_outside_baseline_observations_not_qualified(self):
        with tempfile.TemporaryDirectory(prefix="qa-replay-synthetic-") as temporary:
            root=Path(temporary);key,results,receipt=self.baseline(root)
            digest=sha(root/(key+".bin"))
            (root/"events.tsv").write_text(f"1000\t43\t{key}\tinference\t{digest}\n3000\t42\t{key}\tinference\t{digest}\n")
            review=json.loads(receipt.read_bytes());review["events_sha256"]=sha(root/"events.tsv")
            receipt.write_text(json.dumps(review))
            with self.assertRaises(AssertionError): approve(root,results,receipt)
            self.assertFalse((root/(key+".approved")).exists())

if __name__ == "__main__": unittest.main()

