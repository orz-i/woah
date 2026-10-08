"""Source-order regression guard for the Android privacy-then-crop export path.

This supplements (and does not replace) synthetic Kotlin geometry tests or
actual Android OpenGL/device tests. No private media is required.
"""
from __future__ import annotations

from pathlib import Path
import unittest

PIPELINE = (
    Path(__file__).resolve().parents[1]
    / "mobile/packages/dance_native/android/src/main/kotlin/art/gaoge/dance/engine/pipeline/ExportPipeline.kt"
)


def check_post_crop_contract(source: str) -> None:
    try:
        start = source.index('profiler.recordStage("renderEffects")')
        end = source.index('// Optional live preview capture', start)
    except ValueError as exc:
        raise ValueError("missing export render region") from exc
    render = source[start:end]
    checkpoints = (
        'val compositor = requireNotNull(privacyRenderer)',
        'val renderProtected = { scale: Double ->',
        'follow = request.follow.copy(enabled = false)',
        'val previousFramebuffer = target.bind()',
        'renderProtected(request.cropClarityScale ?: 1.0)',
        'val visualCrop = reframeFollower.cropForFrame(',
        '.textureMatrixForScreenGlCrop(glCrop)',
        'CropClarityCropPrivacyGeometry.evaluate(',
        'crop = visualCrop,',
        'clarityAbCapture?.choose(',
        'frameTexture = target.textureId,',
    )
    positions = []
    for marker in checkpoints:
        position = render.find(marker)
        if position < 0:
            raise ValueError(f"privacy-first crop contract missing: {marker}")
        positions.append(position)
    if positions != sorted(positions):
        raise ValueError("privacy composition, crop calculation or final sampling was reordered")
    if render.find('texMatrix = cropTextureMatrix,', positions[-1]) < positions[-1]:
        raise ValueError("final post-crop renderer no longer uses the computed crop matrix")
    if 'protectedBoxes = visibleProtected.map { it.bbox }' not in render:
        raise ValueError("crop privacy evidence is not based on observed protected boxes")
    if 'otherBoxes = visibleOther.map { it.bbox }' not in render:
        raise ValueError("crop privacy overlap lost other tracks")
    if 'baselineReady' not in render or 'renderProtected(1.0)' not in render:
        raise ValueError("same-input debug OFF/ON capture contract removed")
    # Guard against comparing source-frame visibility instead of *final* crop evidence.
    if 'cropProtectedCount = cropPrivacy.cropProtectedCount' not in render:
        raise ValueError("review sampler no longer receives final crop privacy visibility")


class CropClarityPostCropContractTest(unittest.TestCase):
    def test_production_android_keeps_privacy_before_portrait_crop(self):
        check_post_crop_contract(PIPELINE.read_text(encoding="utf-8"))

    def test_accidentally_reordering_protection_and_crop_fails(self):
        source = PIPELINE.read_text(encoding="utf-8")
        altered = source.replace(
            'renderProtected(request.cropClarityScale ?: 1.0)',
            'renderProtectedMoved(request.cropClarityScale ?: 1.0)',
            1,
        )
        with self.assertRaisesRegex(ValueError, "missing"):
            check_post_crop_contract(altered)

    def test_using_uncropped_track_counts_fails(self):
        source = PIPELINE.read_text(encoding="utf-8")
        altered = source.replace(
            'cropProtectedCount = cropPrivacy.cropProtectedCount',
            'cropProtectedCount = visibleProtected.size',
            1,
        )
        with self.assertRaisesRegex(ValueError, "final crop"):
            check_post_crop_contract(altered)


if __name__ == "__main__":
    unittest.main()
