#!/usr/bin/env python3
"""Static contract gate for Woah iOS preview Metal preview.

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
    check(path.is_file(), f"Missing iOS preview source: {name}")
    return path.read_text(encoding="utf-8") if path.is_file() else ""


def verify_preview_pipeline() -> None:
    pipeline = text("IOSPreviewPipeline.swift")
    for token in (
        "analysisCache.loadVideoUri(cacheId: request.analysisCacheId)",
        "analysisCache.loadMetadata(cacheId: request.analysisCacheId)",
        "AVAssetImageGenerator(asset: asset)",
        "generator.appliesPreferredTrackTransform = true",
        "generator.requestedTimeToleranceBefore = .zero",
        "generator.requestedTimeToleranceAfter = .zero",
        "preferredBackend: .xnnpack",
        "IOSPreviewIdentityMatcher.assign(",
        'code: "PREVIEW_PRIVACY_UNRESOLVED"',
        "IOSFrameKernel.sceneScope(",
        "IOSFrameKernel.sceneTargets(",
        "protectUnassignedFaces: scope != 0",
        "IOSPrivacySelectionScope.currentPersons(",
        "IOSFrameKernel.faceProtectionIds(body: renderBodyIds, face: renderFaceIds)",
        "IOSPrivacySelectionScope.compositorFaceIds(selected: renderFaceIds,",
        ".subtracting(fullBodyIds)",
        "renderer.render(",
        "kCGImageDestinationLossyCompressionQuality: 0.85",
        '"preview_\\(cacheId)_\\(timestampMs)_\\(nonce).jpg"',
        "frameAnalysisCache[key] = entry",
        "func clearForAnalysis(cacheId: String)",
    ):
        check(token in pipeline, f"preview preview pipeline missing: {token}")

    check(
        "Keep preview inference deterministic" in pipeline,
        "Preview inference must document the XNNPACK determinism boundary",
    )
    adapter = text("IOSFrameKernel.swift")
    check(
        "static func faceProtectionIds(body: Set<Int>, face: Set<Int>)" in adapter
        and "frame_kernel_face_targets(" in adapter,
        "Preview face targets must cross the shared Rust C ABI",
    )
    core_path = ROOT / "crates/frame-kernel/src/protection.rs"
    core = core_path.read_text(encoding="utf-8") if core_path.is_file() else ""
    check(
        "pub fn face_targets(" in core and ".chain(face_ids)" in core
        and "BODY | FACE" in core,
        "Shared core must protect the union of body/face targets and supplement body faces",
    )


def verify_identity_contract() -> None:
    matcher = text("IOSPreviewIdentityMatcher.swift")
    for token in (
        "maxCostThreshold: Float32 = 0.70",
        "IOSFrameKernel.matchRects(",
        "usedIds",
        "assignedIds",
        "cached.map(\\.id).max()",
    ):
        check(token in matcher, f"preview identity matcher missing: {token}")

    android = (
        ROOT
        / "mobile/packages/dance_native/android/src/main/kotlin/art/gaoge/dance/engine/pipeline/PreviewPipeline.kt"
    ).read_text(encoding="utf-8")
    android_replay=(ROOT/"mobile/packages/dance_native/android/src/main/kotlin/art/gaoge/dance/engine/pipeline/PreviewIdentityReplay.kt").read_text(encoding="utf-8")
    swift_replay=text("IOSPreviewIdentityReplay.swift")
    check("FrameKernel.matchRects(boxes,detections.map{it.bbox},0.70f)" in android_replay,
          "Android analysis-anchor matching threshold changed")
    check("IOSSharedTrackingSession.replayPlan(" in swift_replay and "metadata?.anchorTimestampUs" in text("IOSPreviewPipeline.swift")
          and "FrameKernel.replayPlan(" in android_replay and "metadata?.anchorTimestampUs" in android_replay,
          "Named identity preview must replay from its actual shared analysis anchor")
    adapter = text("IOSFrameKernel.swift")
    core_path = ROOT / "crates/frame-kernel/src/association.rs"
    core = core_path.read_text(encoding="utf-8") if core_path.is_file() else ""
    check("frame_kernel_match_rects(" in adapter and "pub fn match_rects(" in core
          and "pub fn match_costs(" in core and "total_cmp" in core,
          "Preview identity association must use the deterministic shared core")


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
        check(token in renderer, f"preview Metal renderer missing: {token}")

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
        "preview requires YOLO preprocess mapping in IOSYoloInferenceResult",
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
        "preview getPreviewFrame must no longer be a platform stub",
    )
    check((SOURCES / "IOSExportPipeline.swift").is_file(), "Missing production IOSExportPipeline.swift")
    check(
        "exportCoordinator.start(request: request)" in export_block
        and 'code: "PLATFORM_NOT_SUPPORTED"' not in export_block,
        " startExport must route to the real iOS export coordinator",
    )
    registry = text("IOSRegenerableCacheRegistry.swift")
    check(
        "cacheRegistry.requestRelease(cacheId: projectId, owner: cacheOwner)" in release_block
        and "cacheRegistry.setOwnerCleanup(instance.cacheOwner)" in plugin
        and "preview?.clearForAnalysis(cacheId: id)" in plugin
        and "$0.released && $0.leases == 0" in registry
        and "listeners.forEach { $0(id) }" in registry,
        "releaseProject must defer in-memory/on-disk preview cleanup until actual cache leases drain",
    )


def verify_metal_validation_lanes() -> None:
    extractor = ROOT / "tools/ios/extract_preview_metal.py"
    compiler = ROOT / "tools/ios/compile_preview_metal.py"
    simulator = ROOT / "tools/ios/run_simulator_regression.py"
    macos_gate = ROOT / "tools/ios/run_native_regression.py"
    smoke_main = ROOT / "mobile/app/lib/ios_native_regression_main.dart"
    smoke_swift = SOURCES / "IOSMetalSmoke.swift"
    workflow = ROOT / ".github/workflows/ios-cloud.yml"
    phase1_verifier = ROOT / "tools/release/verify_ios_inference.py"
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
        check(path.is_file(), f"Missing preview Metal validation asset: {path.relative_to(ROOT)}")
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
    plugin = text("DanceNativePlugin.swift")

    check("private static let kernelSource" in extractor_text,
          "Offline Metal extractor must target the production renderer kernel source")
    check('"metal"' in compiler_text and '"metallib"' in compiler_text,
          "Offline Metal compiler must invoke both Apple metal and metallib tools")
    check("WOAH_METAL_SMOKE=PASS" in smoke_main_text,
          "Simulator smoke entrypoint must emit a deterministic PASS marker")
    check("runIOSMetalSmoke" in plugin,
          "Native plugin must expose the isolated preview Metal smoke method")
    for token in (
        "MTLCreateSystemDefaultDevice()",
        "IOSMetalPreviewRenderer()",
        "renderer.render(",
        'fillMode: "solid"',
        'code: "METAL_SMOKE_PIXEL_MISMATCH"',
    ):
        check(token in smoke_swift_text, f"preview GPU smoke missing: {token}")
    check("xcrun" in simulator_text and "simctl" in simulator_text and "--console" in simulator_text,
          "Simulator smoke runner must boot/install/launch through simctl")
    check("python tools/release/verify_ios_inference.py" in workflow_text,
          "iOS Cloud CI must retain the inference static verifier")
    check(
        "run: python tools/ios/run_native_regression.py" in workflow_text,
        "iOS Cloud CI must explicitly invoke the privacy-class gate preserving preview",
    )
    check(
        "compile_preview_metal.py" in macos_gate_text
        and '"iphoneos"' in macos_gate_text
        and '"iphonesimulator"' in macos_gate_text,
        "preview macOS gate must offline-compile the exact Metal source for iPhoneOS and iOS Simulator",
    )
    for token in (
        '"--simulator"',
        "ios_native_regression_main.dart",
        "run_simulator_regression.py",
    ):
        check(token in macos_gate_text, f"preview macOS gate missing validation step: {token}")


def main() -> int:
    verify_preview_pipeline()
    verify_identity_contract()
    verify_metal_renderer()
    verify_runtime_wiring_and_gates()
    verify_metal_validation_lanes()
    if FAILURES:
        print("iOS preview verification FAILED:")
        for failure in FAILURES:
            print(f" - {failure}")
        return 1
    print("iOS preview static verification passed")
    return 0


if __name__ == "__main__":
    sys.exit(main())
