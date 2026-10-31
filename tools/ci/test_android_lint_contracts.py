"""Source-boundary regressions supplement real Gradle lint and Kotlin tests.

These do not replace API-level device/media validation.
"""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[2]
NATIVE = ROOT / "mobile/packages/dance_native/android/src"


class AndroidLintBoundaryTests(unittest.TestCase):
    def test_surface_color_overwrite_does_not_call_api29_remove_key(self):
        source = (NATIVE / "main/kotlin/art/gaoge/dance/engine/media/VideoDecoder.kt").read_text()
        self.assertNotIn(".removeKey(", source)
        self.assertIn("setInteger(MediaFormat.KEY_COLOR_FORMAT", source)
        self.assertIn("COLOR_FormatSurface", source)

    def test_debug_broadcast_keeps_privileged_sender_boundary(self):
        source = (NATIVE / "debug/kotlin/art/gaoge/dance/engine/comparison/ComparisonGlM0Activity.kt").read_text()
        self.assertIn("ContextCompat.registerReceiver(this,testReceiver,filter,", source)
        self.assertIn("android.Manifest.permission.DUMP,null,ContextCompat.RECEIVER_EXPORTED)", source)
        self.assertNotIn("else @Suppress", source)

    def test_muxer_receives_codec_flags_not_extractor_flags(self):
        source = (NATIVE / "main/kotlin/art/gaoge/dance/engine/media/AudioTrackCopier.kt").read_text()
        self.assertNotIn("bufferInfo.flags = extractor.sampleFlags", source)
        self.assertIn("muxableAudioSampleIsSync(extractor.sampleFlags)", source)
        self.assertIn("MediaCodec.BUFFER_FLAG_KEY_FRAME", source)


if __name__ == "__main__":
    unittest.main()
