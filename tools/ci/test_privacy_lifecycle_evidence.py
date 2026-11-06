from pathlib import Path
import json,tempfile,unittest
from tools.privacy.lifecycle_evidence import verify_lifecycle,retry_case

class LifecycleEvidenceTests(unittest.TestCase):
    def prepare(self,root):
        def put(name,value):(root/name).write_text(json.dumps(value),encoding='utf-8')
        put('run-metadata.json',{'config':{'lifecycle':True}})
        put('results.json',{'process_id':17})
        life={'process_id':17,'status':'passed_api_chain','cancel_passed':True,'retry_passed':True,
              'after_cancel':{'state':'cancelled'},'cancelled_output_exists':False,'cancelled_partial_files':[],
              'custom_sticker_exists':True,'retry_bytes':2048,'retry_status':{'state':'completed','current_frame':48},
              'input':'solo.mp4','fill':'sticker','retry_start_ms':1000,'retry_end_ms':2920,
              'face_only':True,'target_width':674,'target_height':1280,'selected_ids':[0]}
        put('qa_lifecycle_results.json',life)
        put('qa_negative_results.json',{'process_id':17,'status':'passed_api_checks','flower_no_person':True,
                                       'corrupt_rejected':True,'valid_reimport':True})
        (root/'outputs').mkdir();(root/'outputs/retry_custom_sticker.mp4').write_bytes(b'x'*2048)
        return life,put

    def test_flow_proof_retains_the_actual_retry_hash_and_explicit_scope(self):
        with tempfile.TemporaryDirectory() as d:
            root=Path(d);self.prepare(root)
            proof=verify_lifecycle(root)
            self.assertEqual(proof['retry_bytes'],2048);self.assertEqual(len(proof['retry_sha256']),64)
            self.assertIn('Independent media',proof['scope'])
            self.assertEqual(retry_case(root)['audit_origin'],'lifecycle_chain')

    def test_success_flags_cannot_hide_a_changed_retry_file(self):
        with tempfile.TemporaryDirectory() as d:
            root=Path(d);self.prepare(root);(root/'outputs/retry_custom_sticker.mp4').write_bytes(b'y'*2047)
            with self.assertRaisesRegex(RuntimeError,'byte count'):verify_lifecycle(root)

    def test_stale_process_or_remaining_temporary_file_is_rejected(self):
        for change in [{'process_id':18},{'cancelled_partial_files':['owned.tmp.mp4']}]:
            with self.subTest(change=change),tempfile.TemporaryDirectory() as d:
                root=Path(d);life,put=self.prepare(root);life.update(change);put('qa_lifecycle_results.json',life)
                with self.assertRaises(RuntimeError):verify_lifecycle(root)
