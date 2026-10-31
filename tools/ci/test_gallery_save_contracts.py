"""Bridge/Android-adapter wiring guards; transaction behavior has native JVM tests."""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[2]
NATIVE = ROOT / "mobile/packages/dance_native/android/src/main/kotlin/art/gaoge/dance/engine"


class GallerySaveWiringTests(unittest.TestCase):
    def test_method_handler_only_submits_io_to_worker(self):
        plugin = (NATIVE / "DanceNativePlugin.kt").read_text()
        handler = plugin.split('"saveVideoToGallery" -> {', 1)[1].split('"shareVideo" -> {', 1)[0]
        self.assertIn("worker.submit(filePath)", handler)
        for forbidden in ("copyTo(", "FileInputStream(", "contentResolver.", "saveVideoToMediaStore("):
            self.assertNotIn(forbidden, handler)
        self.assertIn("postReply = { callback -> replyHandler.post { callback() } }", plugin)
        self.assertIn("gallerySaveWorker?.close()", plugin)

    def test_adapter_publishes_pending_and_checks_exact_update(self):
        adapter = (NATIVE / "media/MediaStoreVideoSaveBackend.kt").read_text()
        self.assertIn("MediaStore.Video.Media.IS_PENDING, 1", adapter)
        self.assertIn("MediaStore.Video.Media.IS_PENDING, 0", adapter)
        self.assertIn("resolver.update(uri.toUri(), values, null, null) != 1", adapter)
        self.assertIn("Build.VERSION_CODES.Q", adapter)

    def test_rollback_only_addresses_inserted_uri_and_never_scans_source(self):
        adapter = (NATIVE / "media/MediaStoreVideoSaveBackend.kt").read_text()
        plugin = (NATIVE / "DanceNativePlugin.kt").read_text()
        self.assertIn("resolver.delete(uri.toUri(), null, null) != 1", adapter)
        self.assertNotIn("MediaScannerConnection", plugin)
        self.assertNotIn("sourcePath", adapter)
        self.assertNotIn("filePath", adapter)


if __name__ == "__main__":
    unittest.main()
