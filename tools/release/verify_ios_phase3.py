#!/usr/bin/env python3
"""Static contract gate for Woah iOS Phase 3 Metal preview.

This verifier can run on Windows. It proves repository intent and cross-platform
contracts only. GitHub-hosted macOS/Xcode remains authoritative for Swift/Metal
compilation; real-device visual/privacy/performance acceptance remains separate.
"""

from __future__ import annotations

import sys
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
SOURCES = ROOT / "mobile/packages/dance_native/ios/dance_native/Sources/dance_native"
FAILURES: list[str] = []


def check(condition: bool, message: str) -> None:
    if not condition:
        FAILURES.append(message)


def text(name: str) -> str:
    path = SOURCES / name
    check(path.is_file(), f"Missing iOS Phase 3 source: {name}")
    return path.read_text(encoding="utf-8") if path.is_file() else ""


def verify_preview_pipeline() -> None:
    pipeline = text("IOSPreviewPipeline.swift")
    for token in (
        "analysisCache.loadVideoUri(cacheId: request.analysisCacheId)",
        "analysisCache.loadMetadata(cacheId: cacheId)",
        "AVAssetImageGenerator(asset: asset)",
        "generator.appliesPreferredTrackTransform = true",
        "generator.requestedTimeToleranceBefore = .zero",
        "generator.requestedTimeToleranceAfter = .zero",
        "preferredBackend: .xnnpack",
        "IOSPreviewIdentityMatcher.assign(",
        'code: "PREVIEW_PRIVACY_UNRESOLVED"',
        "fullBodyIds.union(faceOnlyIds)",
        ".subtracting(fullBodyIds)",
        "renderer.render(",
        "kCGImageDestinationLossyCompressionQuality: 0.85",
        '"preview_\\(cacheId)_\\(timestampMs)_\\(nonce).jpg"',
        "frameAnalysisCache[key] = entry",
        "func clearForAnalysis(cacheId: String)",
    ):
        check(token in pipeline, f"Phase 3 preview pipeline missing: {token}")

    check(
        "Keep preview inference deterministic" in pipeline,
        "Preview inference must document the XNNPACK determinism boundary",
    )


def verify_identity_contract() -> None:
    matcher = text("IOSPreviewIdentityMatcher.swift")
    for token in (
        "maxCostThreshold: Float32 = 0.70",
        "1.0 - bboxIoU",
        "candidates.sort",
        "usedIds",
        "assignedIds",
        "cached.map(\\.id).max()",
    ):
        check(token in matcher, f"Phase 3 identity matcher missing: {token}")

    android = (
        ROOT
        / "mobile/packages/dance_native/android/src/main/kotlin/com/danceanon/native/pipeline/PreviewPipeline.kt"
    ).read_text(encoding="utf-8")
    check(
        "HungarianSolver.match(costMatrix, maxCostThreshold = 0.70f)" in android,
        "Android preview matching threshold changed; revisit iOS parity",
    )


def verify_metal_renderer() -> None:
    renderer = text("IOSMetalPreviewRenderer.swift")
    for token in (
        "import Metal",
        "MTLCreateSystemDefaultDevice()",
        'makeFunction(name: "woahPreviewKernel")',
        "makeComputePipelineState",
        "dispatchThreads(",
        "commandBuffer.waitUntilCompleted()",
        "min(sourceWidth, 1280)",
        "preprocess.scale",
        "preprocess.padLeft",
        "preprocess.padTop",
        "dilate(target.detection.mask, radius: 1)",
        "erode(candidate.detection.mask, radius: 1)",
        "bboxOverlapRatio(target.detection, candidate.detection)",
        "normalizedFootDelta >= 0.10",
        "maskOverlapRatio(effective, candidate.detection.mask) > 0.02",
        "privacy * carve / 255",
        'case "blur": return 2',
        'case "gradient": return 3',
        'case "mosaic": return 5',
        "borderWidth * 3.0",
        "stickerEnabled",
        "conservativeFaceRect(",
        "woahPreviewKernel",
        "float4(1.0, 0.84, 0.0, 1.0)",
    ):
        check(token in renderer, f"Phase 3 Metal renderer missing: {token}")

    check(
        "ambiguous overlap always keeps privacy" in renderer,
        "iOS preview occlusion policy must remain privacy-first when depth is ambiguous",
    )
    check(
        "transparent asset holes" in renderer,
        "Sticker mode must document and preserve fail-closed face privacy",
    )


def verify_runtime_wiring_and_gates() -> None:
    plugin = text("DanceNativePlugin.swift")
    runner = text("IOSYoloRunner.swift")
    check(
        "let preprocess: IOSYoloPreprocessResult" in runner,
        "Phase 3 requires YOLO preprocess mapping in IOSYoloInferenceResult",
    )
    preview_start = plugin.find("func getPreviewFrame")
    export_start = plugin.find("func startExport", preview_start)
    cancel_start = plugin.find("func cancelJob", export_start)
    release_start = plugin.find("func releaseProject")
    build_info_start = plugin.find("private static func buildInfo", release_start)
    preview_block = plugin[preview_start:export_start]
    export_block = plugin[export_start:cancel_start]
    release_block = plugin[release_start:build_info_start]
    check(
        "return try await previewPipeline.render(request: request)" in preview_block,
        "DanceNativePlugin.getPreviewFrame must route through IOSPreviewPipeline",
    )
    check(
        'code: "PLATFORM_NOT_SUPPORTED"' not in preview_block,
        "Phase 3 getPreviewFrame must no longer be a platform stub",
    )
    phase4_pipeline = SOURCES / "IOSExportPipeline.swift"
    if phase4_pipeline.is_file():
        check(
            "exportCoordinator.start(request: request)" in export_block
            and 'code: "PLATFORM_NOT_SUPPORTED"' not in export_block,
            "Once Phase 4 is present, startExport must route to the real iOS export coordinator",
        )
    else:
        check(
            'code: "PLATFORM_NOT_SUPPORTED"' in export_block,
            "Phase 3 must keep startExport gated until Phase 4",
        )
    check(
        "previewPipeline.clearForAnalysis(cacheId: projectId)" in release_block,
        "releaseProject must clear in-memory/on-disk preview state",
    )


def verify_metal_validation_lanes() -> None:
    extractor = ROOT / "tools/ios/extract_phase3_metal.py"
    compiler = ROOT / "tools/ios/compile_phase3_metal.py"
    simulator = ROOT / "tools/ios/run_phase3_simulator_smoke.py"
    macos_gate = ROOT / "tools/ios/run_phase3_macos_gate.py"
    smoke_main = ROOT / "mobile/app/lib/ios_metal_smoke_main.dart"
    smoke_swift = SOURCES / "IOSMetalPhase3Smoke.swift"
    workflow = ROOT / ".github/workflows/ios-cloud.yml"
    phase1_verifier = ROOT / "tools/release/verify_ios_phase1.py"
    for path in (
        extractor,
        compiler,
        simulator,
        macos_gate,
        smoke_main,
        smoke_swift,
        workflow,
        phase1_verifier,
    ):
        check(path.is_file(), f"Missing Phase 3 Metal validation asset: {path.relative_to(ROOT)}")
    if not all(path.is_file() for path in (
        extractor,
        compiler,
        simulator,
        macos_gate,
        smoke_main,
        smoke_swift,
        workflow,
        phase1_verifier,
    )):
        return

    extractor_text = extractor.read_text(encoding="utf-8")
    compiler_text = compiler.read_text(encoding="utf-8")
    simulator_text = simulator.read_text(encoding="utf-8")
    macos_gate_text = macos_gate.read_text(encoding="utf-8")
    smoke_main_text = smoke_main.read_text(encoding="utf-8")
    smoke_swift_text = smoke_swift.read_text(encoding="utf-8")
    workflow_text = workflow.read_text(encoding="utf-8")
    phase1_text = phase1_verifier.read_text(encoding="utf-8")
    plugin = text("DanceNativePlugin.swift")

    check("private static let kernelSource" in extractor_text,
          "Offline Metal extractor must target the production renderer kernel source")
    check('"metal"' in compiler_text and '"metallib"' in compiler_text,
          "Offline Metal compiler must invoke both Apple metal and metallib tools")
    check("WOAH_METAL_PHASE3_SMOKE=PASS" in smoke_main_text,
          "Simulator smoke entrypoint must emit a deterministic PASS marker")
    check("runIOSMetalPhase3Smoke" in plugin,
          "Native plugin must expose the isolated Phase 3 Metal smoke method")
    for token in (
        "MTLCreateSystemDefaultDevice()",
        "IOSMetalPreviewRenderer()",
        "renderer.render(",
        'fillMode: "solid"',
        'code: "METAL_SMOKE_PIXEL_MISMATCH"',
    ):
        check(token in smoke_swift_text, f"Phase 3 GPU smoke missing: {token}")
    check("xcrun" in simulator_text and "simctl" in simulator_text and "--console" in simulator_text,
          "Simulator smoke runner must boot/install/launch through simctl")
    check("python tools/release/verify_ios_phase1.py" in workflow_text,
          "iOS Cloud CI must retain the Phase 1 verifier hook used by the Phase 3 macOS gate")
    check(
        ("run_phase3_macos_gate.py" in phase1_text or "run_phase4_macos_gate.py" in phase1_text)
        and 'GITHUB_ACTIONS' in phase1_text,
        "The GitHub macOS Phase 1 hook must invoke an Apple-only gate that preserves Phase 3",
    )
    check(
        "compile_phase3_metal.py" in macos_gate_text
        and '"iphoneos"' in macos_gate_text
        and '"iphonesimulator"' in macos_gate_text,
        "Phase 3 macOS gate must offline-compile the exact Metal source for iPhoneOS and iOS Simulator",
    )
    for token in (
        '"--simulator"',
        "ios_metal_smoke_main.dart",
        "run_phase3_simulator_smoke.py",
    ):
        check(token in macos_gate_text, f"Phase 3 macOS gate missing validation step: {token}")


def main() -> int:
    verify_preview_pipeline()
    verify_identity_contract()
    verify_metal_renderer()
    verify_runtime_wiring_and_gates()
    verify_metal_validation_lanes()
    if FAILURES:
        print("iOS Phase 3 verification FAILED:")
        for failure in FAILURES:
            print(f" - {failure}")
        return 1
    print("iOS Phase 3 static verification passed")
    return 0


if __name__ == "__main__":
    sys.exit(main())
