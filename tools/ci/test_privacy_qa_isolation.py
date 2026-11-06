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

class PrivacyQaAcceptanceBoundary(unittest.TestCase):
    def test_export_only_cannot_be_mistaken_for_full_acceptance(self):
        import io
        args=['runner','android','--prefix','test','--media','media','--report','report',
              '--binary','qa.apk','--stage-export-only']
        with patch('sys.argv',args), patch('sys.stderr',new_callable=io.StringIO), patch.object(run_regression,'Android') as device:
            with self.assertRaises(SystemExit) as result:run_regression.main()
            self.assertEqual(result.exception.code,2)
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


class AndroidWakeLeaseTest(unittest.TestCase):
    def adapter(self):
        adapter=object.__new__(run_regression.Android)
        adapter.adb=['adb','-s','test-device']
        return adapter

    def test_restores_original_setting_even_when_export_raises(self):
        calls=[]
        def command(*args,**kwargs):
            calls.append(args)
            if args[-4:]==('settings','get','global','stay_on_while_plugged_in'):return '0'
            if args[-3:]==('dumpsys','window','policy'):return 'KeyguardServiceDelegate\n showing=false'
            return ''
        with patch.object(run_regression,'command',side_effect=command):
            with self.assertRaisesRegex(RuntimeError,'export failed'):
                with self.adapter().awake():raise RuntimeError('export failed')
        self.assertEqual(calls[-1][-5:],('settings','put','global','stay_on_while_plugged_in','0'))

    def test_locked_phone_is_rejected_before_any_matrix_work(self):
        def command(*args,**kwargs):
            if args[-4:]==('settings','get','global','stay_on_while_plugged_in'):return 'null'
            if args[-3:]==('dumpsys','window','policy'):return 'KeyguardServiceDelegate\n showing=true'
            return ''
        with patch.object(run_regression,'command',side_effect=command) as device:
            with self.assertRaisesRegex(RuntimeError,'manual unlock'):
                with self.adapter().awake():self.fail('Must not run behind a locked screen')
        self.assertEqual(device.call_args.args[-4:],('settings','delete','global','stay_on_while_plugged_in'))

if __name__=='__main__':unittest.main()
