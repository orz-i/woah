"""Wiring guards supplement (not replace) native execution and synthetic fixtures."""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[2]
ANDROID = ROOT / 'mobile/packages/dance_native/android/src/main/kotlin/art/gaoge/dance/engine'
IOS = ROOT / 'mobile/packages/dance_native/ios/dance_native/Sources/dance_native'


class CacheLifecycleContracts(unittest.TestCase):
    def test_android_releases_only_from_actual_export_completion(self):
        coordinator = (ANDROID / 'export/ExportCoordinator.kt').read_text()
        cancel = coordinator.split('fun cancelJob(', 1)[1].split('fun cancelAllJobs', 1)[0]
        self.assertNotIn('cacheLeases', cancel)
        finish = coordinator.split('fun onJobFinished(', 1)[1]
        self.assertIn('cacheLeases.remove(jobId)?.close()', finish)
        self.assertIn('request.analysisCacheId.takeIf { it.isNotBlank() }', coordinator)
        api = (ANDROID / 'bridge/DanceNativeApiImpl.kt').read_text()
        release = api.split('override suspend fun releaseProject', 1)[1].split('fun close()', 1)[0]
        self.assertIn('cacheRegistry.releaseProject', release)
        self.assertNotIn('clearAnalysisCache', release)

    def test_ios_export_task_finally_owns_lease_not_cancel_status(self):
        coordinator = (IOS / 'IOSExportCoordinator.swift').read_text()
        self.assertLess(coordinator.index('defer { cacheLease?.close() }'), coordinator.index('guard let self else'))
        cancel = coordinator.split('func cancel(jobId:', 1)[1].split('func status(jobId:', 1)[0]
        self.assertNotIn('cacheLease', cancel)
        plugin = (IOS / 'DanceNativePlugin.swift').read_text()
        release = plugin.split('func releaseProject', 1)[1].split('private func emitExportStatus', 1)[0]
        self.assertIn('cacheRegistry.requestRelease', release)
        self.assertNotIn('clearAnalysisCache', release)

    def test_synthetic_ios_lifecycle_smoke_is_required_by_real_phase6_lane(self):
        dart = (ROOT / 'mobile/app/lib/ios_phase6_smoke_main.dart').read_text()
        runner = (ROOT / 'tools/ios/run_phase4_simulator_smoke.py').read_text()
        self.assertIn("'runIOSCacheLifecycleSmoke'", dart)
        self.assertIn('WOAH_CACHE_LIFECYCLE_SMOKE=PASS', dart)
        self.assertIn('"WOAH_CACHE_LIFECYCLE_SMOKE=PASS"', runner)
        actual_export = (IOS / 'IOSExportPhase4Smoke.swift').read_text()
        self.assertIn('CACHE_LEASE_RELEASED_EARLY', actual_export)
        self.assertIn('CACHE_LEASE_NOT_RELEASED', actual_export)


if __name__ == '__main__':
    unittest.main()
