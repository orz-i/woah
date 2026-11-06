"""A misconfigured QA APK must never reach an ADB install."""
from pathlib import Path
from types import SimpleNamespace
import unittest
from unittest.mock import patch
from tools.privacy import run_regression

class PrivacyQaIsolation(unittest.TestCase):
    def test_normal_package_is_rejected_before_any_device_command(self):
        args=SimpleNamespace(binary=Path('normal-app.apk'),serial='test-device',install=True)
        with patch.object(run_regression,'android_package_id',return_value='art.gaoge.dance'), patch.object(run_regression,'command') as device:
            with self.assertRaisesRegex(RuntimeError,'Refuse to install'):
                run_regression.Android(args)
            device.assert_not_called()

class PrivacyQaLaunch(unittest.TestCase):
    def test_launch_tolerates_a_missing_pid_before_process_creation(self):
        with patch.object(run_regression, 'command', side_effect=[None, '', '123']), patch.object(run_regression.time, 'sleep') as sleep:
            self.assertEqual(run_regression.wait_for_android_process(['adb']), '123')
            self.assertEqual(sleep.call_count, 2)

    def test_launch_fails_when_process_never_starts(self):
        with patch.object(run_regression, 'command', return_value=None):
            with self.assertRaisesRegex(TimeoutError, 'did not start'):
                run_regression.wait_for_android_process(['adb'], timeout=0)

if __name__=='__main__':unittest.main()
