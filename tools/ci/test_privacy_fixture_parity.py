"""Mutation tests for Android-iOS 22-case regression-intent equivalence.

Passing these only proves case arguments match the Android acceptance reports,
never that real iOS export succeeded or that privacy is safe to release.
"""
from pathlib import Path
import unittest

from tools.ci.verify_android_ios_privacy_matrix import verify,parse_dart_cases

ROOT=Path(__file__).resolve().parents[2]
IOS=ROOT/'mobile/app/lib'
ANDROID=ROOT/'reports/privacy-regression-2026-11-04'


class PrivacyFixtureParity(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.fixture=(IOS/'ios_privacy_regression_20261104_main.dart').read_text()

    def test_active_qa_matrix_is_22_for_22_equivalent(self):
        for src in (self.fixture,):
            x=verify(src)
            self.assertEqual((x['total_cases'],x['matched'],x['failed']),(22,22,0))
            self.assertTrue(x['all_22_equivalent'])

    def test_android_full_length_original_is_face_only(self):
        import json
        x=json.loads((ANDROID/'group_full_length_follow.json').read_text())
        self.assertIs(x['face_only'],True)
        self.assertEqual((x['frames'],x['hero'],x['selected_ids']),(581,6,[0,1,2,3,4,5]))

    def test_full_body_under_same_case_name_is_rejected(self):
        old="""'case': 'group_full_length_follow',
    'input': 'group-hd-8956295.mp4',
    'fill': 'solid',
    'hero': 6,
    'faceOnly': true,"""
        self.assertIn(old,self.fixture)
        wrong=self.fixture.replace(old,old.replace("'faceOnly': true","'faceOnly': false"),1)
        a=verify(wrong)
        self.assertEqual(a['matched'],21)
        self.assertEqual(a['failed'],1)
        changed=[r for r in a['rows'] if r['material_differences']]
        self.assertEqual(changed[0]['case'],'group_full_length_follow')
        self.assertEqual(changed[0]['material_differences'][0]['field'],'face_only')

    def test_hero_identity_swap_is_rejected(self):
        old="""'case': 'group_full_length_follow',
    'input': 'group-hd-8956295.mp4',
    'fill': 'solid',
    'hero': 6,"""
        self.assertIn(old,self.fixture)
        wrong=self.fixture.replace(old,old.replace("'hero': 6","'hero': 5"),1)
        self.assertEqual(verify(wrong)['failed'],1)

    def test_video_source_and_trim_drift_are_detected(self):
        a=self.fixture.replace("'case': 'group_full_length_follow',\n    'input': 'group-hd-8956295.mp4'",
                                "'case': 'group_full_length_follow',\n    'input': 'duo-dance-6940536.mp4'",1)
        self.assertEqual(verify(a)['failed'],1)
        a=self.fixture.replace("'case': 'group_full_length_follow',\n    'input': 'group-hd-8956295.mp4'",
                                "'case': 'group_full_length_follow',\n    'startMs': 1000,\n    'input': 'group-hd-8956295.mp4'",1)
        self.assertEqual(verify(a)['failed'],1)

    def test_reassigning_face_only_mode_in_other_case_is_rejected(self):
        old="""'case': 'group_face_solid_follow',
    'input': 'group-hd-8956295.mp4',
    'fill': 'solid',
    'hero': 6,"""
        self.assertIn(old,self.fixture)
        wrong=self.fixture.replace(old,old+"\n    'faceOnly': false,",1)
        self.assertEqual(verify(wrong)['failed'],1)

    def test_first_only_selection_intent_is_strict(self):
        old="""'case': 'group_first_solid',
    'input': 'group-hd-8956295.mp4',
    'fill': 'solid',
    'faceOnly': false,
    'select': 'first',"""
        self.assertIn(old,self.fixture)
        wrong=self.fixture.replace(old,old.replace("    'select': 'first',",''),1)
        self.assertEqual(verify(wrong)['failed'],1)

    def test_phase5_ci_gate_reads_corrected_android_parity(self):
        from tools.release import verify_ios_phase5 as gate
        gate.FAILURES.clear()
        try:
            gate.verify_regression_fixture_mode_parity()
            self.assertEqual(gate.FAILURES,[])
        finally:
            gate.FAILURES.clear()

    def test_phase5_ci_gate_rejects_legacy_full_body_named_face_only(self):
        from unittest.mock import patch
        from tools.release import verify_ios_phase5 as gate
        original=gate.text
        def altered(path,label):
            contents=original(path,label)
            if path.name=='ios_privacy_regression_20261104_main.dart':
                target="""'case': 'group_full_length_follow',
    'input': 'group-hd-8956295.mp4',
    'fill': 'solid',
    'hero': 6,
    'faceOnly': true,"""
                assert target in contents
                return contents.replace(target,target.replace("'faceOnly': true","'faceOnly': false"),1)
            return contents
        gate.FAILURES.clear()
        try:
            with patch.object(gate,'text',side_effect=altered):
                gate.verify_regression_fixture_mode_parity()
            self.assertTrue(any('Android/iOS fixture mismatch' in x for x in gate.FAILURES))
        finally:
            gate.FAILURES.clear()

    def test_duplicate_or_unsupported_dart_fixture_rejected(self):
        with self.assertRaises(ValueError):parse_dart_cases(self.fixture.replace("'case': 'group_full_length_follow'","'case': 'group_full_solid'",1))
        with self.assertRaises(ValueError):parse_dart_cases(self.fixture.replace("'hero': 6,\n    'faceOnly': true,", "'hero': math.random(),\n    'faceOnly': true,",1))

if __name__=='__main__':unittest.main()
