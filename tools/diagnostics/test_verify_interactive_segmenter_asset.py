"""Offline tests for pinned Google MagicTouch .task archive integrity.

Synthetic TFLite headers only; no real model/file committed or decoded video.
"""
from __future__ import annotations

import contextlib
import hashlib
import io
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
import zipfile

from tools.diagnostics import verify_interactive_segmenter_asset as subject


def make_task(path: Path, *, model_bytes: bytes = b"\x00\x00\x00\x00TFL3\x10\x20",
              name: str = "interactive_segmentation.tflite",
              extra_names: tuple[str, ...] = ()) -> None:
    with zipfile.ZipFile(path, "w", compression=zipfile.ZIP_STORED) as output:
        output.writestr(name, model_bytes)
        for name in extra_names:
            output.writestr(name, b"small fixture")


def lock_for(path: Path) -> dict:
    return dict(
        schema=1,
        model_id=subject.MODEL_NAME,
        model_uri=subject.PINNED_URL,
        sha256=hashlib.sha256(path.read_bytes()).hexdigest(),
        bytes=path.stat().st_size,
        upstream_gcs_etag_md5=hashlib.md5(path.read_bytes()).hexdigest(),
        android_dependency=subject.SOURCE_LIBRARY,
        expected_input_shape=[768, 768, 3],
        model_license_scope_review="REQUIRES_VERSION_SPECIFIC_REVIEW",
        release_status="RESEARCH_ONLY_NO_MODEL_BUNDLING",
    )


class IndependentSegmenterAssetVerifierTest(unittest.TestCase):
    def test_complete_synthetic_zip_integrity_never_authorizes_render(self):
        with tempfile.TemporaryDirectory() as dirname:
            path = Path(dirname) / "fixture.task"
            make_task(path)
            spec = lock_for(path)
            with patch.object(subject, "MODEL_BYTES", path.stat().st_size), patch.object(
                subject, "UPSTREAM_GCS_ETAG_MD5", spec["upstream_gcs_etag_md5"]
            ):
                verified = subject.verify(path, spec)
            self.assertEqual(verified["artifact_state"], "PINNED_STRUCTURAL_INTEGRITY_ONLY")
            self.assertEqual(verified["tflite_entries"], 1)
            self.assertTrue(verified["sha256_match"])
            self.assertTrue(verified["upstream_gcs_etag_match"])
            self.assertEqual(verified["android_api_abi"], "NOT_VERIFIED_BY_ARCHIVE")
            self.assertEqual(verified["privacy_visual_acceptance"], "NOT_PROVEN")
            self.assertEqual(verified["pixel_carve_authorization"], "NEVER")
            rendered = json.dumps(verified)
            self.assertNotIn(str(path), rendered)
            self.assertNotIn("0x", rendered)

    def test_tampered_model_hash_is_rejected(self):
        with tempfile.TemporaryDirectory() as dirname:
            path = Path(dirname) / "synthetic.task"
            make_task(path)
            spec = lock_for(path)
            with patch.object(subject, "MODEL_BYTES", path.stat().st_size), patch.object(
                subject, "UPSTREAM_GCS_ETAG_MD5", spec["upstream_gcs_etag_md5"]
            ):
                spec["sha256"] = "a" * 64
                with self.assertRaisesRegex(ValueError, "SHA-256 mismatch"):
                    subject.verify(path, spec)

    def test_incomplete_artifact_byte_count_is_rejected(self):
        with tempfile.TemporaryDirectory() as dirname:
            path = Path(dirname) / "synthetic.task"
            make_task(path)
            spec = lock_for(path)
            spec["bytes"] += 1
            with patch.object(subject, "MODEL_BYTES", spec["bytes"]), patch.object(
                subject, "UPSTREAM_GCS_ETAG_MD5", spec["upstream_gcs_etag_md5"]
            ):
                with self.assertRaisesRegex(ValueError, "byte size mismatch"):
                    subject.verify(path, spec)

    def test_repository_lock_and_actual_android_dependency_remain_pinned(self):
        path = (subject.ROOT / "tools/diagnostics/baselines/mediapipe_magictouch_int8_1.lock.json")
        lock = json.loads(path.read_text(encoding="utf-8"))
        subject.validate_lock(lock)
        self.assertEqual(lock["sha256"],
                         "38431bc66b883404e8397f74c3579404315b9b52b04a46c6346fe906a7309b03")
        self.assertEqual(lock["upstream_gcs_etag_md5"],
                         "eff9593362906a47112886311c6d34c6")
        gradle = (subject.ROOT / "mobile/packages/dance_native/android/build.gradle.kts").read_text(
            encoding="utf-8")
        subject.validate_android_dependency_source(gradle)
        with self.assertRaisesRegex(ValueError, "Gradle dependency"):
            subject.validate_android_dependency_source(
                gradle.replace(subject.SOURCE_LIBRARY, "com.google.mediapipe:tasks-vision:latest.release")
            )

    def test_md5_gcs_etag_mismatch_is_rejected_even_when_sha_matches(self):
        with tempfile.TemporaryDirectory() as dirname:
            path = Path(dirname) / "synthetic.task"
            make_task(path)
            spec = lock_for(path)
            spec["upstream_gcs_etag_md5"] = "0" * 32
            with patch.object(subject, "MODEL_BYTES", path.stat().st_size), patch.object(
                subject, "UPSTREAM_GCS_ETAG_MD5", "0" * 32
            ):
                with self.assertRaisesRegex(ValueError, "recorded MD5 ETag"):
                    subject.verify(path, spec)

    def test_model_versions_are_never_silently_updated(self):
        with tempfile.TemporaryDirectory() as dirname:
            path = Path(dirname) / "synthetic.task"
            make_task(path)
            original = lock_for(path)
            for field, invalid in (
                ("model_uri", "https://example.org/another-model.task"),
                ("upstream_gcs_etag_md5", "wrong-etag"),
                ("model_id", "some-other-person-segmentation"),
                ("android_dependency", "com.google.mediapipe:tasks-vision:latest.release"),
                ("expected_input_shape", [512, 512, 4]),
                ("model_license_scope_review", "APPROVED"),
                ("release_status", "RELEASE"),
                ("sha256", "abc"),
            ):
                candidate = dict(original, **{field: invalid})
                with patch.object(subject, "MODEL_BYTES", path.stat().st_size), patch.object(
                    subject, "UPSTREAM_GCS_ETAG_MD5", original["upstream_gcs_etag_md5"]
                ):
                    with self.assertRaisesRegex(ValueError, "model lock"):
                        subject.validate_lock(candidate)

    def test_model_inside_repository_tree_is_rejected(self):
        with tempfile.TemporaryDirectory() as dirname:
            root = Path(dirname)
            sub = root / "fake-git-repo"
            sub.mkdir()
            path = sub / "unapproved.task"
            make_task(path)
            spec = lock_for(path)
            with patch.object(subject, "MODEL_BYTES", path.stat().st_size), patch.object(
                subject, "UPSTREAM_GCS_ETAG_MD5", spec["upstream_gcs_etag_md5"]
            ):
                with self.assertRaisesRegex(ValueError, "Git tree"):
                    subject.verify(path, spec, repo_root=sub)

    def test_model_archive_never_trusts_path_traversal(self):
        with tempfile.TemporaryDirectory() as dirname:
            path = Path(dirname) / "malicious.task"
            make_task(path, extra_names=("../escape-model.bin",))
            with self.assertRaisesRegex(ValueError, "unsafe or duplicated"):
                subject.inspect_task_archive(path)

    def test_model_tflite_magic_header_is_required(self):
        with tempfile.TemporaryDirectory() as dirname:
            path = Path(dirname) / "invalid.task"
            make_task(path, model_bytes=b"\x00\x00\x00\x00FAIL")
            with self.assertRaisesRegex(ValueError, "TFL3 identifier"):
                subject.inspect_task_archive(path)

    def test_archive_with_no_real_model_is_rejected(self):
        with tempfile.TemporaryDirectory() as dirname:
            path = Path(dirname) / "empty.task"
            make_task(path, name="not-a-model.txt")
            with self.assertRaisesRegex(ValueError, "no compatible TFLite"):
                subject.inspect_task_archive(path)

    def test_nonzip_artifact_is_rejected(self):
        with tempfile.TemporaryDirectory() as dirname:
            path = Path(dirname) / "bad.task"
            path.write_bytes(b"some bytes")
            with self.assertRaisesRegex(ValueError, "not a readable ZIP"):
                subject.inspect_task_archive(path)

    def test_reject_unexpected_fields_before_hashing(self):
        with tempfile.TemporaryDirectory() as dirname:
            path = Path(dirname) / "synthetic.task"
            make_task(path)
            spec = lock_for(path)
            spec["enable_privacy_carve"] = True
            with patch.object(subject, "MODEL_BYTES", path.stat().st_size), patch.object(
                subject, "UPSTREAM_GCS_ETAG_MD5", spec["upstream_gcs_etag_md5"]
            ):
                with self.assertRaisesRegex(ValueError, "lock fields"):
                    subject.verify(path, spec)

    def test_script_has_no_model_downloader_or_image_decoder(self):
        body = Path(subject.__file__).read_text(encoding="utf-8")
        for forbidden in (
            "import requests", "import urllib", "import cv2", "from PIL",
            "import torch", "GlRenderer", "MaskPrivacyProcessor",
            "imageio", "video_capture", "ffmpeg",
        ):
            self.assertNotIn(forbidden, body)


if __name__ == "__main__":
    unittest.main()
