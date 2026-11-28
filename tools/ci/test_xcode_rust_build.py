"""Rust file-list churn must not become a CocoaPods dependency lock change."""
from pathlib import Path
import os
import tempfile
import unittest
from unittest.mock import patch

from tools.rust import build_xcode as build

ROOT = Path(__file__).resolve().parents[2]


class XcodeRustBuildTests(unittest.TestCase):
    def test_podspec_always_checks_cargo_without_dynamic_source_list(self):
        text = (ROOT / "mobile/packages/dance_native/ios/dance_native.podspec").read_text()
        self.assertIn(":always_out_of_date => '1'", text)
        self.assertIn('tools/rust/build_xcode.py', text)
        self.assertNotIn("Dir.glob", text)
        self.assertIn("'$(BUILT_PRODUCTS_DIR)/FrameKernel/libframe_kernel.a'", text)

    def test_single_slice_always_checks_cargo_but_keeps_identical_output(self):
        with tempfile.TemporaryDirectory() as d:
            root = Path(d)
            source = root / "core/target/aarch64-apple-ios-sim/release/libframe_kernel.a"
            source.parent.mkdir(parents=True)
            source.write_bytes(b"archive-one")
            output = root / "products/FrameKernel/libframe_kernel.a"
            env = {"PLATFORM_NAME": "iphonesimulator", "ARCHS": "arm64",
                   "BUILT_PRODUCTS_DIR": str(root / "products")}
            with patch.object(build, "CORE", root / "core"), patch.dict(os.environ, env), \
                 patch.object(build.subprocess, "run") as cargo:
                build.main()
                self.assertEqual(output.read_bytes(), b"archive-one")
                os.utime(output, ns=(1_000_000_000, 1_000_000_000))
                before = output.stat().st_mtime_ns
                build.main()
                self.assertEqual(output.stat().st_mtime_ns, before)
                source.write_bytes(b"archive-two")
                build.main()
                self.assertEqual(output.read_bytes(), b"archive-two")
                self.assertEqual(cargo.call_count, 3)

    def test_multislice_lipo_is_atomic_and_unchanged_bytes_keep_timestamp(self):
        with tempfile.TemporaryDirectory() as d:
            root = Path(d)
            for target, data in (("aarch64-apple-ios-sim", b"arm"), ("x86_64-apple-ios", b"x86")):
                source = root / "core/target" / target / "release/libframe_kernel.a"
                source.parent.mkdir(parents=True)
                source.write_bytes(data)
            env = {"PLATFORM_NAME": "iphonesimulator", "ARCHS": "arm64 x86_64",
                   "BUILT_PRODUCTS_DIR": str(root / "products")}
            output = root / "products/FrameKernel/libframe_kernel.a"
            def run(args, **kwargs):
                if args[0] == "xcrun":
                    self.assertNotEqual(Path(args[-1]), output)
                    Path(args[-1]).write_bytes(b"".join(Path(x).read_bytes() for x in args[3:-2]))
            with patch.object(build, "CORE", root / "core"), patch.dict(os.environ, env), \
                 patch.object(build.subprocess, "run", side_effect=run):
                build.main()
                self.assertEqual(output.read_bytes(), b"armx86")
                os.utime(output, ns=(1_000_000_000, 1_000_000_000))
                before = output.stat().st_mtime_ns
                build.main()
                self.assertEqual(output.stat().st_mtime_ns, before)
                (root / "core/target/x86_64-apple-ios/release/libframe_kernel.a").write_bytes(b"new")
                build.main()
                self.assertEqual(output.read_bytes(), b"armnew")


if __name__ == "__main__":
    unittest.main()
