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


class AndroidForegroundReadinessTest(unittest.TestCase):
    def adapter(self):
        adapter=object.__new__(run_regression.Android)
        adapter.adb=['adb','-s','test-device']
        return adapter

    def test_ready_phone_never_requires_global_settings_write(self):
        with patch.object(run_regression,'command',return_value='showing=false') as device:
            with self.adapter().awake():pass
        self.assertFalse(any('settings' in call.args for call in device.call_args_list))

    def test_locked_phone_is_rejected_before_any_matrix_work(self):
        with patch.object(run_regression,'command',return_value='KeyguardServiceDelegate\n showing=true'):
            with self.assertRaisesRegex(RuntimeError,'manual unlock'):
                with self.adapter().awake():self.fail('Must not run behind a locked screen')

    def test_transient_adb_read_disconnect_retries_without_restarting_app(self):
        import subprocess
        replies=[subprocess.CompletedProcess([],1,'',"adb: device 'test-device' not found"),subprocess.CompletedProcess([],0,'{"status":"finished"}','')]
        with patch.object(run_regression.subprocess,'run',side_effect=replies) as process, patch.object(run_regression.time,'sleep'):
            value=run_regression.command('adb','-s','test-device','shell','cat','results.json')
        self.assertEqual(value,'{"status":"finished"}')
        self.assertEqual(process.call_args_list[0].args,process.call_args_list[1].args)

    def test_failed_mutation_is_not_automatically_repeated(self):
        import subprocess
        with patch.object(run_regression.subprocess,'run',return_value=subprocess.CompletedProcess([],1,'',"adb: device 'test-device' not found")) as process:
            with self.assertRaises(RuntimeError):run_regression.command('adb','-s','test-device','shell','am','start','test')
        self.assertEqual(process.call_count,1)

if __name__=='__main__':unittest.main()
