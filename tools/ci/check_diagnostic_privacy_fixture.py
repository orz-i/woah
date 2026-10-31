"""Compare synthetic before/after diagnostic bundles with actual quality consumers.

This is compatibility evidence, not a hardware/golden promotion acceptance.
"""
from pathlib import Path
import hashlib
import sys
import zipfile


def verify(before: Path, after: Path) -> None:
    sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "diagnostics"))
    import analyze_cross_device_batch as analyzer
    import face_iteration_gate as face
    import full_body_iteration_gate as full

    a, b = map(analyzer.read_bundle, (before, after))
    for backend in ("production", "cpu_probe", "cpu_mt4_probe"):
        assert a["detections"][backend], f"empty fixture: {backend}"
        assert a["detections"][backend] == b["detections"][backend], backend
    for key in (
        "analysis_selection", "face_roi_detector",
        "shadow_adaptive_sources", "shadow_adaptive_reasons", "shadow_adaptive_metrics",
    ):
        assert a[key] == b[key], key
    for module in (face, full):
        x = module.extract_bundle(before, include_fingerprints=True)
        y = module.extract_bundle(after, include_fingerprints=True)
        for key in ("device", "commit", "quality", "runtime",
                    "performance", "observability", "fingerprints"):
            assert x[key] == y[key], (module.__name__, key, x[key], y[key])

    def binary_hashes(path: Path) -> dict[str, str]:
        with zipfile.ZipFile(path) as archive:
            return {
                name: hashlib.sha256(archive.read(name)).hexdigest()
                for name in archive.namelist()
                if name.endswith((".rgba", ".yuvs", ".f32s"))
            }
    assert binary_hashes(before), "empty binary fixture"
    assert binary_hashes(before) == binary_hashes(after)
    print("PASS: synthetic diagnostic consumer compatibility; NOT hardware validation")


if __name__ == "__main__":
    if len(sys.argv) != 3:
        raise SystemExit("usage: check_diagnostic_privacy_fixture.py BEFORE.zip AFTER.zip")
    verify(Path(sys.argv[1]), Path(sys.argv[2]))
