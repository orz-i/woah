import base64
import os
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

from tools.release import write_cutout_preview_marker as marker


def encoded(*values):
    return ",".join(base64.b64encode(x.encode()).decode() for x in values)


class CutoutPreviewMarkerTest(unittest.TestCase):
    def test_explicit_opt_in_only(self):
        self.assertFalse(marker.enabled(""))
        self.assertFalse(marker.enabled(encoded("WOAH_ENABLE_CUTOUT_PREVIEW=false")))
        self.assertFalse(marker.enabled(encoded("OTHER=WOAH_ENABLE_CUTOUT_PREVIEW=true")))
        self.assertTrue(marker.enabled(encoded("OTHER=hidden", "WOAH_ENABLE_CUTOUT_PREVIEW=true")))

    def test_duplicate_rejected(self):
        with self.assertRaises(ValueError):
            marker.enabled(encoded("WOAH_ENABLE_CUTOUT_PREVIEW=true", "WOAH_ENABLE_CUTOUT_PREVIEW=false"))

    def test_incremental_disabled_build_clears_marker(self):
        with tempfile.TemporaryDirectory() as path:
            env = {"TARGET_BUILD_DIR": path, "UNLOCALIZED_RESOURCES_FOLDER_PATH": "Runner.app"}
            flag = Path(path) / "Runner.app" / "WoahCutoutPreview.flag"
            with patch.dict(os.environ, dict(env, DART_DEFINES=encoded("WOAH_ENABLE_CUTOUT_PREVIEW=true")), clear=True):
                marker.main()
                self.assertEqual(flag.read_text(), "true\n")
            with patch.dict(os.environ, env, clear=True):
                marker.main()
                self.assertEqual(flag.read_text(), "false\n")


if __name__ == "__main__":
    unittest.main()
