"""Tiny synthetic material fixtures; the real xcarchive is checked in macOS CI."""
from pathlib import Path
import plistlib
import tempfile
import unittest
from tools.ci.check_ios_privacy_archive import ROOT, OWNED, EXPECTED_APIS, source_manifests, verify_archive


class PrivacyArchiveTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix='woah-privacy-fixture-')
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.archive = self.root / 'Fixture.xcarchive'
        self.app = self.archive / 'Products/Applications/Runner.app'
        self.app.mkdir(parents=True)
        self.write(self.archive / 'Info.plist', {'ApplicationProperties': {'ApplicationPath': 'Applications/Runner.app'}})
        self.info = {'CFBundleSupportedPlatforms': ['iPhoneOS'], 'CFBundleExecutable': 'Runner',
                     'CFBundleIdentifier': 'art.gaoge.dance'}
        self.write(self.app / 'Info.plist', self.info)
        # Just enough bytes to exercise material checks; never claimed as a real binary/build.
        (self.app / 'Runner').write_bytes(b'\xcf\xfa\xed\xfe' + b'synthetic-fixture')
        self.manifest = {'NSPrivacyAccessedAPITypes': EXPECTED_APIS, 'NSPrivacyTracking': False,
                         'NSPrivacyTrackingDomains': [], 'NSPrivacyCollectedDataTypes': []}
        for target, source in OWNED.items():
            self.write(self.root / source, self.manifest)
            self.write(self.app / target, self.manifest)
        self.write(self.app / 'ThirdParty.bundle/PrivacyInfo.xcprivacy', {'NSPrivacyAccessedAPITypes': []})

    def write(self, path, value):
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(plistlib.dumps(value))

    def verify(self):
        return verify_archive(self.archive, self.root)

    def test_actual_source_declarations(self):
        self.assertEqual(len(source_manifests()), 2)

    def test_complete_fixture_and_inventory(self):
        report = self.verify()
        self.assertEqual(len(report['owned_manifests']), 2)
        self.assertEqual(len(report['manifest_inventory']), 3)
        self.assertIn('unsigned archive materials only', report['validation_scope'])

    def test_missing_root_manifest_fails(self):
        (self.app / 'PrivacyInfo.xcprivacy').unlink()
        with self.assertRaises(FileNotFoundError):
            self.verify()

    def test_third_party_cannot_replace_native_manifest(self):
        (self.app / 'dance_native_privacy.bundle/PrivacyInfo.xcprivacy').unlink()
        with self.assertRaises(FileNotFoundError):
            self.verify()

    def test_wrong_reason_fails(self):
        altered = dict(self.manifest, NSPrivacyAccessedAPITypes=[{
            'NSPrivacyAccessedAPIType': 'NSPrivacyAccessedAPICategoryFileTimestamp',
            'NSPrivacyAccessedAPITypeReasons': ['DDA9.1']}])
        self.write(self.app / 'PrivacyInfo.xcprivacy', altered)
        with self.assertRaisesRegex(ValueError, 'C617.1'):
            self.verify()

    def test_unexpected_owned_manifest_key_fails(self):
        self.write(self.app / 'PrivacyInfo.xcprivacy', dict(self.manifest, UnexpectedKey=True))
        with self.assertRaisesRegex(ValueError, 'Unexpected owned privacy keys'):
            self.verify()

    def test_invalid_plist_fails(self):
        (self.app / 'PrivacyInfo.xcprivacy').write_text('not a plist')
        with self.assertRaises(plistlib.InvalidFileException):
            self.verify()

    def test_missing_archive_metadata_fails(self):
        (self.archive / 'Info.plist').unlink()
        with self.assertRaises(FileNotFoundError):
            self.verify()

    def test_simulator_is_not_iphoneos_archive(self):
        self.write(self.app / 'Info.plist', dict(self.info, CFBundleSupportedPlatforms=['iPhoneSimulator']))
        with self.assertRaisesRegex(ValueError, 'iPhoneOS'):
            self.verify()

    def test_wrong_application_product_fails(self):
        self.write(self.app / 'Info.plist', dict(self.info, CFBundleIdentifier='other.product'))
        with self.assertRaisesRegex(ValueError, 'Woah application'):
            self.verify()

    def test_missing_executable_fails(self):
        (self.app / 'Runner').unlink()
        with self.assertRaises(FileNotFoundError):
            self.verify()

    def test_unsafe_application_path_fails(self):
        self.write(self.archive / 'Info.plist', {'ApplicationProperties': {'ApplicationPath': '../outside.app'}})
        with self.assertRaisesRegex(ValueError, 'safe ApplicationPath'):
            self.verify()

    def test_real_archive_lane_is_required(self):
        workflow = (ROOT / '.github/workflows/ios-cloud.yml').read_text()
        self.assertIn('flutter build ipa --release --no-codesign --no-pub --target lib/main.dart', workflow)
        self.assertIn('check_ios_privacy_archive.py --archive', workflow)
        self.assertIn('build/ios/archive/Runner.xcarchive', workflow)
        self.assertIn('artifacts/Woah-ios-privacy-report.json', workflow)
        self.assertIn('flutter build ios --debug --no-codesign --target lib/main.dart', workflow)
        self.assertLess(workflow.index('Archive no-codesign app'), workflow.index('Build unsigned Release archive'))


if __name__ == '__main__':
    unittest.main()
