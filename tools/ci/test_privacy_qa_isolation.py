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

if __name__=='__main__':unittest.main()
